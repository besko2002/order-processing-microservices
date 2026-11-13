package com.example.orders.common.events;

/**
 * Kafka topic names shared by every service.
 *
 * <p>Events are facts ("this happened"), commands are requests aimed at one owner service
 * ("please do this"). Compensating steps of the order saga are modelled as commands.
 */
public final class Topics {

    /**
     * Published by order-service: {@link OrderCreated}, {@link OrderConfirmed},
     * {@link OrderCancelled}, {@link OrderRejected}. Consumed by inventory-service, which reacts to
     * {@code OrderCreated} — the saga's first step.
     */
    public static final String ORDER_EVENTS = "order.events";

    /** Published by inventory-service: {@link StockReserved}, {@link StockRejected}. */
    public static final String INVENTORY_EVENTS = "inventory.events";

    /**
     * Published by payment-service: {@link PaymentCompleted}, {@link PaymentFailed},
     * {@link PaymentRefunded}.
     */
    public static final String PAYMENT_EVENTS = "payment.events";

    /**
     * Commands consumed by inventory-service: {@link CommitStock} once the order is paid, and
     * {@link ReleaseStock} as compensation.
     */
    public static final String INVENTORY_COMMANDS = "inventory.commands";

    /**
     * Commands consumed by payment-service: {@link ProcessPayment} to charge, and
     * {@link RefundPayment} as compensation.
     */
    public static final String PAYMENT_COMMANDS = "payment.commands";

    /** Suffix appended to a topic name to build its dead letter topic. */
    public static final String DLT_SUFFIX = ".DLT";

    private Topics() {
    }

    /** Dead letter topic for the given topic, e.g. {@code order.events.DLT}. */
    public static String deadLetterTopic(String topic) {
        return topic + DLT_SUFFIX;
    }
}
