/*
 * Copyright 2019 Maksim Zheravin
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package exchange.core2.core.orderbook;

import exchange.core2.core.art.LongAdaptiveRadixTreeMap;
import exchange.core2.core.common.*;
import exchange.core2.core.common.cmd.CommandResultCode;
import exchange.core2.core.common.cmd.OrderCommand;
import exchange.core2.core.processors.ObjectsPool;
import lombok.*;
import lombok.extern.slf4j.Slf4j;
import net.openhft.chronicle.bytes.BytesIn;
import net.openhft.chronicle.bytes.BytesOut;
import net.openhft.chronicle.bytes.WriteBytesMarshallable;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.collections.MutableInteger;
import org.agrona.collections.MutableLong;
import org.apache.commons.lang3.builder.EqualsBuilder;
import org.eclipse.collections.impl.map.mutable.primitive.LongObjectHashMap;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Slf4j
public final class OrderBookDirectImpl implements IOrderBook {

    // buckets
    private final LongAdaptiveRadixTreeMap<Bucket> askPriceBuckets;
    private final LongAdaptiveRadixTreeMap<Bucket> bidPriceBuckets;

    // symbol specification
    private final CoreSymbolSpecification symbolSpec;

    // index: orderId -> order
    private final LongAdaptiveRadixTreeMap<DirectOrder> orderIdIndex;
    //private final Long2ObjectHashMap<DirectOrder> orderIdIndex = new Long2ObjectHashMap<>();
    //private final LongObjectHashMap<DirectOrder> orderIdIndex = new LongObjectHashMap<>();

    // heads (nullable)
    private DirectOrder bestAskOrder = null;
    private DirectOrder bestBidOrder = null;

    // Object pools
    private final ObjectsPool objectsPool;

    private final OrderBookEventsHelper eventsHelper;

    public OrderBookDirectImpl(final CoreSymbolSpecification symbolSpec, final ObjectsPool objectsPool) {
        this.symbolSpec = symbolSpec;
        this.objectsPool = objectsPool;
        this.askPriceBuckets = new LongAdaptiveRadixTreeMap<>(objectsPool);
        this.bidPriceBuckets = new LongAdaptiveRadixTreeMap<>(objectsPool);
        this.eventsHelper = new OrderBookEventsHelper(() -> objectsPool.getSharedPool().getChain());
        this.orderIdIndex = new LongAdaptiveRadixTreeMap<>(objectsPool);
    }

    public OrderBookDirectImpl(final BytesIn bytes, final ObjectsPool objectsPool) {
        this.symbolSpec = new CoreSymbolSpecification(bytes);
        this.objectsPool = objectsPool;
        this.askPriceBuckets = new LongAdaptiveRadixTreeMap<>(objectsPool);
        this.bidPriceBuckets = new LongAdaptiveRadixTreeMap<>(objectsPool);
        this.eventsHelper = new OrderBookEventsHelper(() -> objectsPool.getSharedPool().getChain());
        this.orderIdIndex = new LongAdaptiveRadixTreeMap<>(objectsPool);

        final int size = bytes.readInt();
        for (int i = 0; i < size; i++) {
            DirectOrder order = new DirectOrder(bytes);
            insertOrder(order, null);
            orderIdIndex.put(order.orderId, order);
        }
    }

    @Override
    public CommandResultCode newOrder(OrderCommand cmd) {
        final OrderType orderType = cmd.orderType;
        final long size = cmd.size;

        // check if order is marketable there are matching orders
        final long filledSize = tryMatchInstantly(cmd, cmd);
        if (filledSize == size) {
            // completed before being placed - can just return
            return CommandResultCode.SUCCESS;
        }

        if (orderType == OrderType.IOC) {
            // send reject for not-completed ImmediateOrCancel order
            eventsHelper.attachRejectEvent(cmd, size - filledSize);
            return CommandResultCode.SUCCESS;
        }

        final long orderId = cmd.orderId;
        // TODO eliminate double hashtable lookup?
        if (orderIdIndex.get(orderId) != null) { // containsKey for hashtable
            // duplicate order id - can match, but can not place
            eventsHelper.attachRejectEvent(cmd, size - filledSize);
            return CommandResultCode.MATCHING_DUPLICATE_ORDER_ID;
        }

        final long price = cmd.price;

        // normally placing regular GTC order
        final DirectOrder orderRecord = objectsPool.get(ObjectsPool.DIRECT_ORDER, (Supplier<DirectOrder>) DirectOrder::new);

        orderRecord.orderId = orderId;
        orderRecord.price = price;
        orderRecord.size = size;
        orderRecord.reserveBidPrice = cmd.reserveBidPrice;
        orderRecord.action = cmd.action;
        orderRecord.uid = cmd.uid;
        orderRecord.timestamp = cmd.timestamp;
        orderRecord.filled = filledSize;

        orderIdIndex.put(orderId, orderRecord);
        insertOrder(orderRecord, null);

        return CommandResultCode.SUCCESS;
    }

    private long tryMatchInstantly(final IOrder takerOrder,
                                   final OrderCommand triggerCmd) {

        final long limitPrice = takerOrder.getPrice();

        DirectOrder makerOrder;
        boolean isBidAction = takerOrder.getAction() == OrderAction.BID;
        if (isBidAction) {
            makerOrder = bestAskOrder;
            if (makerOrder == null || makerOrder.price > limitPrice) {
                return takerOrder.getFilled();
            }
        } else {
            makerOrder = bestBidOrder;
            if (makerOrder == null || makerOrder.price < limitPrice) {
                return takerOrder.getFilled();
            }
        }

        long remainingSize = takerOrder.getSize() - takerOrder.getFilled();

        if (remainingSize == 0) {
            return takerOrder.getFilled();
        }

        DirectOrder priceBucketTail = makerOrder.parent.tail;

        final long takerReserveBidPrice = takerOrder.getReserveBidPrice();
//        final long takerOrderTimestamp = takerOrder.getTimestamp();

        // stack of own orders
        DirectOrder skipOwnOrders = null;

//        log.debug("MATCHING taker: {} remainingSize={}", takerOrder, remainingSize);

        // iterate through all orders
        do {

//            log.debug("  matching from maker order: {}", makerOrder);

            if (makerOrder.uid != takerOrder.getUid()) {
                // calculate exact volume can fill for this order
                final long tradeSize = Math.min(remainingSize, makerOrder.size - makerOrder.filled);
//                log.debug("  tradeSize: {} MIN(remainingSize={}, makerOrder={})", tradeSize, remainingSize, makerOrder.size - makerOrder.filled);

                makerOrder.filled += tradeSize;
                makerOrder.parent.volume -= tradeSize;
                remainingSize -= tradeSize;

                // remove from order book filled orders
                final boolean makerCompleted = makerOrder.size == makerOrder.filled;
                if (makerCompleted) {
                    makerOrder.parent.numOrders--;
                }

                final MatcherTradeEvent tradeEvent = eventsHelper.sendTradeEvent(makerOrder, makerCompleted, remainingSize == 0, tradeSize,
                        isBidAction ? takerReserveBidPrice : makerOrder.reserveBidPrice);

                tradeEvent.nextEvent = triggerCmd.matcherEvent;
                triggerCmd.matcherEvent = tradeEvent;

                if (!makerCompleted) {
                    // maker not completed -> no unmatched volume left, can exit matching loop
//                    log.debug("  not completed, exit");
                    break;
                }

                // if completed can remove maker order
                orderIdIndex.remove(makerOrder.orderId);
                objectsPool.put(ObjectsPool.DIRECT_ORDER, makerOrder);

            } else {
//                log.debug("  self UID");
                // attach own orders to separate chain for later processing
                // for now just pretend order is gone
                makerOrder.next = skipOwnOrders;
                skipOwnOrders = makerOrder;
                // for consistency remove size from the bucket
                makerOrder.parent.volume -= makerOrder.size - makerOrder.filled;
                makerOrder.parent.numOrders--;
            }

            if (makerOrder == priceBucketTail) {
                // reached current price tail -> remove bucket reference
                final LongAdaptiveRadixTreeMap<Bucket> buckets = isBidAction ? askPriceBuckets : bidPriceBuckets;
                buckets.remove(makerOrder.price);
                objectsPool.put(ObjectsPool.DIRECT_BUCKET, makerOrder.parent);
//                log.debug("  removed price bucket for {}", makerOrder.price);

                // set next price tail (if there is next price)
                if (makerOrder.prev != null) {
                    priceBucketTail = makerOrder.prev.parent.tail;
                }
            }

            // switch to next order
            makerOrder = makerOrder.prev; // can be null

        } while (makerOrder != null
                && remainingSize > 0
                && (isBidAction ? makerOrder.price <= limitPrice : makerOrder.price >= limitPrice));

        // break chain after last order
        if (makerOrder != null) {
            makerOrder.next = null;
        }

//        log.debug("makerOrder = {}", makerOrder);
//        log.debug("makerOrder.parent = {}", makerOrder != null ? makerOrder.parent : null);

        // update best orders reference
        if (isBidAction) {
            bestAskOrder = makerOrder;
        } else {
            bestBidOrder = makerOrder;
        }

        // process skipped own orders
        // the insertion order is naturally reversed (as expected)
        while (skipOwnOrders != null) {
            final DirectOrder toInsert = skipOwnOrders;
            skipOwnOrders = skipOwnOrders.next;
            if (isBidAction) {
                insertOwnAskOrderIntoFront(toInsert);
            } else {
                insertOwnBidOrderIntoFront(toInsert);
            }
            //log.debug("self uid insert back: {}", skipOwnOrders);
        }

        // return filled amount
        return takerOrder.getSize() - remainingSize;
    }

    private void insertOwnAskOrderIntoFront(DirectOrder selfOrder) {
        //                log.debug("+ insert  self uid order {}", selfOrder);
        selfOrder.next = null;
        selfOrder.prev = bestAskOrder;
        if (bestAskOrder != null) {
            bestAskOrder.next = selfOrder;
        }
        bestAskOrder = selfOrder;

        // update bucket/parent accordingly (check if bucket exists)
        // TODO can remember last bucket/price to avoid discovering each time
        Bucket bucket = askPriceBuckets.get(selfOrder.price);
        if (bucket == null) {
            bucket = objectsPool.get(ObjectsPool.DIRECT_BUCKET, Bucket::new);
            bucket.tail = selfOrder;
            bucket.volume = 0;
            bucket.numOrders = 0;
            askPriceBuckets.put(selfOrder.price, bucket);
        }
        bucket.numOrders++;
        bucket.volume += selfOrder.size - selfOrder.filled;
        selfOrder.parent = bucket;
    }

    private void insertOwnBidOrderIntoFront(DirectOrder selfOrder) {
        //                log.debug("+ insert  self uid order {}", selfOrder);
        selfOrder.next = null;
        selfOrder.prev = bestBidOrder;
        if (bestBidOrder != null) {
            bestBidOrder.next = selfOrder;
        }
        bestBidOrder = selfOrder;

        // update bucket/parent accordingly (check if bucket exists)
        // TODO can remember last bucket/price to avoid discovering each time
        Bucket bucket = bidPriceBuckets.get(selfOrder.price);
        if (bucket == null) {
            bucket = objectsPool.get(ObjectsPool.DIRECT_BUCKET, Bucket::new);
            bucket.tail = selfOrder;
            bucket.volume = 0;
            bucket.numOrders = 0;
            bidPriceBuckets.put(selfOrder.price, bucket);
        }
        bucket.numOrders++;
        bucket.volume += selfOrder.size - selfOrder.filled;
        selfOrder.parent = bucket;
    }

    @Override
    public CommandResultCode cancelOrder(OrderCommand cmd) {

        // TODO avoid double lookup ?
        final DirectOrder order = orderIdIndex.get(cmd.orderId);
        if (order == null || order.uid != cmd.uid) {
            return CommandResultCode.MATCHING_UNKNOWN_ORDER_ID;
        }
        orderIdIndex.remove(cmd.orderId);
        objectsPool.put(ObjectsPool.DIRECT_ORDER, order);

        final Bucket freeBucket = removeOrder(order);
        if (freeBucket != null) {
            objectsPool.put(ObjectsPool.DIRECT_BUCKET, freeBucket);
        }

        // fill action fields (for events handling)
        cmd.action = order.getAction();

        eventsHelper.sendCancelEvent(cmd, order);

        return CommandResultCode.SUCCESS;
    }

    @Override
    public CommandResultCode moveOrder(OrderCommand cmd) {

        // order lookup
        final DirectOrder orderToMove = orderIdIndex.get(cmd.orderId);
        if (orderToMove == null || orderToMove.uid != cmd.uid) {
            return CommandResultCode.MATCHING_UNKNOWN_ORDER_ID;
        }

        // risk check for exchange bids
        if (symbolSpec.type == SymbolType.CURRENCY_EXCHANGE_PAIR && orderToMove.action == OrderAction.BID && cmd.price > orderToMove.reserveBidPrice) {
            return CommandResultCode.MATCHING_MOVE_FAILED_PRICE_OVER_RISK_LIMIT;
        }

        // remove order
        final Bucket freeBucket = removeOrder(orderToMove);

        // update price
        orderToMove.price = cmd.price;

        // fill action fields (for events handling)
        cmd.action = orderToMove.getAction();

        // try match with new price as a taker order
        final long filled = tryMatchInstantly(orderToMove, cmd);
        if (filled == orderToMove.size) {
            // order was fully matched - removing
            orderIdIndex.remove(cmd.orderId);
            // returning free object back to the pool
            objectsPool.put(ObjectsPool.DIRECT_ORDER, orderToMove);
            return CommandResultCode.SUCCESS;
        }

        // not filled completely, inserting into new position
        orderToMove.filled = filled;

        // insert into a new place
        insertOrder(orderToMove, freeBucket);

        return CommandResultCode.SUCCESS;
    }


    private Bucket removeOrder(final DirectOrder order) {

        final Bucket bucket = order.parent;
        bucket.volume -= order.size - order.filled;
        bucket.numOrders--;
        Bucket bucketRemoved = null;

        if (bucket.tail == order) {
            // if we removing tail order -> change bucket tail reference
            if (order.next == null || order.next.parent != bucket) {
                // if no next or next order has different parent -> then it was the last bucket -> remove record
                final LongAdaptiveRadixTreeMap<Bucket> buckets = order.action == OrderAction.ASK ? askPriceBuckets : bidPriceBuckets;
                buckets.remove(order.price);
                bucketRemoved = bucket;
            } else {
                // otherwise at least one order always having the same parent left -> update tail reference to it
                bucket.tail = order.next; // always not null
            }
        }

        // update neighbor orders
        if (order.next != null) {
            order.next.prev = order.prev; // can be null
        }
        if (order.prev != null) {
            order.prev.next = order.next; // can be null
        }

        // check if best ask/bid were referring to the order we just removed
        if (order == bestAskOrder) {
            bestAskOrder = order.prev; // can be null
        } else if (order == bestBidOrder) {
            bestBidOrder = order.prev; // can be null
        }

        return bucketRemoved;
    }


    private void insertOrder(final DirectOrder order, final Bucket freeBucket) {

//        log.debug("   + insert order: {}", order);

        final boolean isAsk = order.action == OrderAction.ASK;
        final LongAdaptiveRadixTreeMap<Bucket> buckets = isAsk ? askPriceBuckets : bidPriceBuckets;
        final Bucket toBucket = buckets.get(order.price);

        if (toBucket != null) {
            // update tail if bucket already exists
//            log.debug(">>>> increment bucket {} from {} to {}", toBucket.tail.price, toBucket.volume, toBucket.volume +  order.size - order.filled);

            // can put bucket back to the pool (because target bucket already exists)
            if (freeBucket != null) {
                objectsPool.put(ObjectsPool.DIRECT_BUCKET, freeBucket);
            }

            toBucket.volume += order.size - order.filled;
            toBucket.numOrders++;
            final DirectOrder oldTail = toBucket.tail; // always exists, not null
            final DirectOrder prevOrder = oldTail.prev; // can be null
            // update neighbors
            toBucket.tail = order;
            oldTail.prev = order;
            if (prevOrder != null) {
                prevOrder.next = order;
            }
            // update self
            order.next = oldTail;
            order.prev = prevOrder;
            order.parent = toBucket;

        } else {

            // insert a new bucket (reuse existing)
            final Bucket newBucket = freeBucket != null
                    ? freeBucket
                    : objectsPool.get(ObjectsPool.DIRECT_BUCKET, Bucket::new);

            newBucket.tail = order;
            newBucket.volume = order.size - order.filled;
            newBucket.numOrders = 1;
            order.parent = newBucket;
            buckets.put(order.price, newBucket);
            final Bucket lowerBucket = isAsk ? buckets.getLowerValue(order.price) : buckets.getHigherValue(order.price);
            if (lowerBucket != null) {
                // attache new bucket and event to the lower entry
                DirectOrder lowerTail = lowerBucket.tail;
                final DirectOrder prevOrder = lowerTail.prev; // can be null
                // update neighbors
                lowerTail.prev = order;
                if (prevOrder != null) {
                    prevOrder.next = order;
                }
                // update self
                order.next = lowerTail;
                order.prev = prevOrder;
            } else {

                // if no floor entry, then update best order
                final DirectOrder oldBestOrder = isAsk ? bestAskOrder : bestBidOrder; // can be null

                if (oldBestOrder != null) {
                    oldBestOrder.next = order;
                }

                if (isAsk) {
                    bestAskOrder = order;
                } else {
                    bestBidOrder = order;
                }

                // update self
                order.next = null;
                order.prev = oldBestOrder;
            }
        }
    }

    @Override
    public int getOrdersNum(OrderAction action) {
        final LongAdaptiveRadixTreeMap<Bucket> buckets = action == OrderAction.ASK ? askPriceBuckets : bidPriceBuckets;
        final MutableInteger accum = new MutableInteger();
        buckets.forEach((p, b) -> accum.value += b.numOrders, Integer.MAX_VALUE);
        return accum.value;
    }

    @Override
    public long getTotalOrdersVolume(OrderAction action) {
        final LongAdaptiveRadixTreeMap<Bucket> buckets = action == OrderAction.ASK ? askPriceBuckets : bidPriceBuckets;
        final MutableLong accum = new MutableLong();
        buckets.forEach((p, b) -> accum.value += b.volume, Integer.MAX_VALUE);
        return accum.value;
    }

    @Override
    public IOrder getOrderById(final long orderId) {
        return orderIdIndex.get(orderId);
    }

    @Override
    public void validateInternalState() {
        final Long2ObjectHashMap<DirectOrder> ordersInChain = new Long2ObjectHashMap<>(orderIdIndex.size(Integer.MAX_VALUE), 0.8f);
        validateChain(true, ordersInChain);
        validateChain(false, ordersInChain);
//        log.debug("ordersInChain={}", ordersInChain);
//        log.debug("orderIdIndex={}", orderIdIndex);

//        log.debug("orderIdIndex.keySet()={}", orderIdIndex.keySet().toSortedArray());
//        log.debug("ordersInChain=        {}", ordersInChain.toSortedArray());
        orderIdIndex.forEach((k, v) -> {
            if (ordersInChain.remove(k) != v) {
                thrw("chained orders does not contain orderId=" + k);
            }
        }, Integer.MAX_VALUE);

        if (ordersInChain.size() != 0) {
            thrw("orderIdIndex does not contain each order from chains");
        }
    }

    private void validateChain(boolean asksChain, Long2ObjectHashMap<DirectOrder> ordersInChain) {

        // buckets index
        final LongAdaptiveRadixTreeMap<Bucket> buckets = asksChain ? askPriceBuckets : bidPriceBuckets;
        final LongObjectHashMap<Bucket> bucketsFoundInChain = new LongObjectHashMap<>();
        buckets.validateInternalState();

        DirectOrder order = asksChain ? bestAskOrder : bestBidOrder;

        if (order != null && order.next != null) {
            thrw("best order has not-null next reference");
        }

//        log.debug("----------- validating {} --------- ", asksChain ? OrderAction.ASK : OrderAction.BID);

        long lastPrice = -1;
        long expectedBucketVolume = 0;
        int expectedBucketOrders = 0;
        DirectOrder lastOrder = null;

        while (order != null) {

            if (ordersInChain.containsKey(order.orderId)) {
                thrw("duplicate orderid in the chain");
            }
            ordersInChain.put(order.orderId, order);

            //log.debug("id:{} p={} +{}", order.orderId, order.price, order.size - order.filled);
            expectedBucketVolume += order.size - order.filled;
            expectedBucketOrders++;

            if (lastOrder != null && order.next != lastOrder) {
                thrw("incorrect next reference");
            }
            if (order.parent.tail.price != order.price) {
                thrw("price of parent.tail differs");
            }
            if (lastPrice != -1 && order.price != lastPrice) {
                if (asksChain ^ order.price > lastPrice) {
                    thrw("unexpected price change direction");
                }
                if (order.next.parent == order.parent) {
                    thrw("unexpected price change within same bucket");
                }
            }

            if (order.parent.tail == order) {
                if (order.parent.volume != expectedBucketVolume) {
                    thrw("bucket volume does not match orders chain sizes");
                }
                if (order.parent.numOrders != expectedBucketOrders) {
                    thrw("bucket numOrders does not match orders chain length");
                }
                if (order.prev != null && order.prev.price == order.price) {
                    thrw("previous bucket has the same price");
                }
                expectedBucketVolume = 0;
                expectedBucketOrders = 0;
            }

            final Bucket knownBucket = bucketsFoundInChain.get(order.price);
            if (knownBucket == null) {
                bucketsFoundInChain.put(order.price, order.parent);
            } else if (knownBucket != order.parent) {
                thrw("found two different buckets having same price");
            }

            if (asksChain ^ order.action == OrderAction.ASK) {
                thrw("not expected order action");
            }

            lastPrice = order.price;
            lastOrder = order;
            order = order.prev;
        }

        // validate last order
        if (lastOrder != null && lastOrder.parent.tail != lastOrder) {
            thrw("last order is not a tail");
        }

//        log.debug("-------- validateChain ----- asksChain={} ", asksChain);
        buckets.forEach((price, bucket) -> {
//            log.debug("Remove {} ", price);
            if (bucketsFoundInChain.remove(price) != bucket) thrw("bucket in the price-tree not found in the chain");
        }, Integer.MAX_VALUE);

        if (!bucketsFoundInChain.isEmpty()) {
            thrw("found buckets in the chain that not discoverable from the price-tree");
        }
    }

//    private void dumpNearOrders(final DirectOrder order, int maxNeighbors) {
//        if (order == null) {
//            log.debug("no orders");
//            return;
//        }
//        DirectOrder p = order;
//        for (int i = 0; i < maxNeighbors && p.prev != null; i++) {
//            p = p.prev;
//        }
//        for (int i = 0; i < maxNeighbors * 2 && p != null; i++) {
//            log.debug(((p == order) ? "*" : " ") + "  {}\t -> \t{}", p, p.parent);
//            p = p.next;
//        }
//    }

    public void thrw(final String msg) {
        throw new IllegalStateException(msg);
    }

    @Override
    public OrderBookImplType getImplementationType() {
        return OrderBookImplType.DIRECT;
    }

    @Override
    public List<Order> findUserOrders(long uid) {
        final List<Order> list = new ArrayList<>();
        orderIdIndex.forEach((orderId, order) -> {
            if (order.uid == uid) {
                list.add(Order.builder()
                        .orderId(orderId)
                        .price(order.price)
                        .size(order.size)
                        .filled(order.filled)
                        .reserveBidPrice(order.reserveBidPrice)
                        .action(order.action)
                        .uid(order.uid)
                        .timestamp(order.timestamp)
                        .build());
            }
        }, Integer.MAX_VALUE);

        return list;
    }

    @Override
    public CoreSymbolSpecification getSymbolSpec() {
        return symbolSpec;
    }

    @Override
    public Stream<DirectOrder> askOrdersStream(boolean sortedIgnore) {
        return StreamSupport.stream(new OrdersSpliterator(bestAskOrder), false);
    }

    @Override
    public Stream<DirectOrder> bidOrdersStream(boolean sortedIgnore) {
        return StreamSupport.stream(new OrdersSpliterator(bestBidOrder), false);
    }

    @Override
    public void fillAsks(final int size, L2MarketData data) {
        data.askSize = 0;
        askPriceBuckets.forEach((p, bucket) -> {
            final int i = data.askSize++;
            data.askPrices[i] = bucket.tail.price;
            data.askVolumes[i] = bucket.volume;
            data.askOrders[i] = bucket.numOrders;
        }, size);
    }

    @Override
    public void fillBids(final int size, L2MarketData data) {
        data.bidSize = 0;
        bidPriceBuckets.forEachDesc((p, bucket) -> {
            final int i = data.bidSize++;
            data.bidPrices[i] = bucket.tail.price;
            data.bidVolumes[i] = bucket.volume;
            data.bidOrders[i] = bucket.numOrders;
        }, size);
    }

    @Override
    public int getTotalAskBuckets(final int limit) {
        return askPriceBuckets.size(limit);
    }

    @Override
    public int getTotalBidBuckets(final int limit) {
        return bidPriceBuckets.size(limit);
    }

    @Override
    public void writeMarshallable(BytesOut bytes) {
        bytes.writeByte(getImplementationType().getCode());
        symbolSpec.writeMarshallable(bytes);
        bytes.writeInt(orderIdIndex.size(Integer.MAX_VALUE));
        askOrdersStream(true).forEach(order -> order.writeMarshallable(bytes));
        bidOrdersStream(true).forEach(order -> order.writeMarshallable(bytes));
    }


    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static final class DirectOrder implements WriteBytesMarshallable, IOrder {

        @Getter
        public long orderId;

        @Getter
        public long price;

        @Getter
        public long size;

        @Getter
        public long filled;

        // new orders - reserved price for fast moves of GTC bid orders in exchange mode
        @Getter
        public long reserveBidPrice;

        // required for PLACE_ORDER only;
        @Getter
        public OrderAction action;

        @Getter
        public long uid;

        @Getter
        public long timestamp;

        // fast orders structure

        Bucket parent;

        // next order (towards the matching direction, price grows for asks)
        DirectOrder next;

        // previous order (to the tail of the queue, lower priority and worst price, towards the matching direction)
        DirectOrder prev;


        // public int userCookie;

        public DirectOrder(BytesIn bytes) {


            this.orderId = bytes.readLong(); // orderId
            this.price = bytes.readLong();  // price
            this.size = bytes.readLong(); // size
            this.filled = bytes.readLong(); // filled
            this.reserveBidPrice = bytes.readLong(); // price2
            this.action = OrderAction.of(bytes.readByte());
            this.uid = bytes.readLong(); // uid
            this.timestamp = bytes.readLong(); // timestamp
            // this.userCookie = bytes.readInt();  // userCookie

            // TODO
        }

        @Override
        public void writeMarshallable(BytesOut bytes) {
            bytes.writeLong(orderId);
            bytes.writeLong(price);
            bytes.writeLong(size);
            bytes.writeLong(filled);
            bytes.writeLong(reserveBidPrice);
            bytes.writeByte(action.getCode());
            bytes.writeLong(uid);
            bytes.writeLong(timestamp);
            // bytes.writeInt(userCookie);
            // TODO
        }

        @Override
        public String toString() {
            return "[" + orderId + " " + (action == OrderAction.ASK ? 'A' : 'B')
                    + price + ":" + size + "F" + filled
                    // + " C" + userCookie
                    + " U" + uid + "]";
        }

        @Override
        public int hashCode() {
            return Objects.hash(orderId, action, price, size, reserveBidPrice, filled,
                    //userCookie,
                    uid);
        }


        /**
         * timestamp is not included into hashCode() and equals() for repeatable results
         */
        @Override
        public boolean equals(Object o) {
            if (o == this) return true;
            if (o == null) return false;
            if (!(o instanceof DirectOrder)) return false;

            DirectOrder other = (DirectOrder) o;
            return new EqualsBuilder()
                    .append(orderId, other.orderId)
                    .append(action, other.action)
                    .append(price, other.price)
                    .append(size, other.size)
                    .append(reserveBidPrice, other.reserveBidPrice)
                    .append(filled, other.filled)
//                .append(userCookie, other.userCookie)
                    .append(uid, other.uid)
                    //.append(timestamp, other.timestamp)
                    .isEquals();
        }

        @Override
        public int stateHash() {
            return Objects.hash(orderId, action, price, size, reserveBidPrice, filled,
                    //userCookie,
                    uid);
        }
    }

    @ToString
    private static class Bucket {
        long volume;
        int numOrders;
        DirectOrder tail;
    }
}
