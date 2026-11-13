package com.example.orders.common.events;

import java.util.Map;
import java.util.Optional;

/**
 * The whole message catalogue of the system, keyed by the {@code eventType} value that travels in
 * the envelope and in the Kafka header.
 *
 * <p>Consumers deserialise the envelope's payload into the type named here instead of trusting a
 * class name inside the message. That is a deliberate difference to Jackson's default polymorphic
 * handling: a producer (or anybody able to write to a topic) must not be able to choose which class
 * a consumer instantiates.
 *
 * <p>A name that is not in this map is not a message of this system — a consumer treats it as a
 * poison record and dead-letters it without retrying. A name that <em>is</em> in the map but is not
 * interesting to a particular consumer is simply ignored, which is why the registry is global
 * rather than per service.
 */
public final class EventTypes {

    private static final Map<String, Class<?>> PAYLOAD_TYPES = Map.ofEntries(
            Map.entry(OrderCreated.class.getSimpleName(), OrderCreated.class),
            Map.entry(OrderConfirmed.class.getSimpleName(), OrderConfirmed.class),
            Map.entry(OrderCancelled.class.getSimpleName(), OrderCancelled.class),
            Map.entry(OrderRejected.class.getSimpleName(), OrderRejected.class),
            Map.entry(StockReserved.class.getSimpleName(), StockReserved.class),
            Map.entry(StockRejected.class.getSimpleName(), StockRejected.class),
            Map.entry(ReleaseStock.class.getSimpleName(), ReleaseStock.class),
            Map.entry(CommitStock.class.getSimpleName(), CommitStock.class),
            Map.entry(ProcessPayment.class.getSimpleName(), ProcessPayment.class),
            Map.entry(RefundPayment.class.getSimpleName(), RefundPayment.class),
            Map.entry(PaymentCompleted.class.getSimpleName(), PaymentCompleted.class),
            Map.entry(PaymentFailed.class.getSimpleName(), PaymentFailed.class),
            Map.entry(PaymentRefunded.class.getSimpleName(), PaymentRefunded.class));

    private EventTypes() {
    }

    /**
     * @param eventType value of the {@code eventType} header/field, may be {@code null}
     * @return the payload record class, or empty when the name is not part of this system
     */
    public static Optional<Class<?>> payloadType(String eventType) {
        return eventType == null ? Optional.empty() : Optional.ofNullable(PAYLOAD_TYPES.get(eventType));
    }

    /** @return every known event type name mapped to its payload class */
    public static Map<String, Class<?>> all() {
        return PAYLOAD_TYPES;
    }
}
