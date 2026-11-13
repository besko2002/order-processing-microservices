package com.example.orders.order.saga;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.PaymentFailed;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.StockRejected;
import com.example.orders.common.events.StockReserved;
import com.example.orders.common.events.Topics;
import com.example.orders.support.messaging.EventConsumerAutoConfiguration;
import com.example.orders.support.messaging.EventEnvelopeReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Where the saga hears back from inventory-service and payment-service.
 *
 * <p>The listeners are deliberately thin: read the envelope, dispatch on the payload type, and let
 * {@link OrderSaga} do the work. Anything that throws from here is retried by the container and
 * finally dead-lettered — which is why the listeners themselves contain no logic that could fail in
 * an interesting way.
 *
 * <p>A payload type that is known to the system but not handled by this listener (an
 * {@code OrderConfirmed} echoed back onto a topic we read, for instance) is ignored at DEBUG. Only a
 * record that is not a message of this system at all becomes a poison record, and
 * {@link EventEnvelopeReader} raises that before this code is reached.
 */
@Component
class OrderSagaListeners {

    private static final Logger log = LoggerFactory.getLogger(OrderSagaListeners.class);

    private final EventEnvelopeReader reader;
    private final OrderSaga saga;

    OrderSagaListeners(EventEnvelopeReader reader, OrderSaga saga) {
        this.reader = reader;
        this.saga = saga;
    }

    /**
     * @param record a record from {@code inventory.events}
     */
    @KafkaListener(
            id = "order-inventory-events",
            topics = Topics.INVENTORY_EVENTS,
            containerFactory = EventConsumerAutoConfiguration.CONTAINER_FACTORY)
    void onInventoryEvent(ConsumerRecord<String, String> record) {
        EventEnvelope<Object> envelope = reader.read(record);
        switch (envelope.payload()) {
            case StockReserved reserved -> saga.stockReserved(envelope.eventId(), reserved);
            case StockRejected rejected -> saga.stockRejected(envelope.eventId(), rejected);
            default -> log.debug("Ignoring {} on {}: not part of the order saga",
                    envelope.eventType(), record.topic());
        }
    }

    /**
     * @param record a record from {@code payment.events}
     */
    @KafkaListener(
            id = "order-payment-events",
            topics = Topics.PAYMENT_EVENTS,
            containerFactory = EventConsumerAutoConfiguration.CONTAINER_FACTORY)
    void onPaymentEvent(ConsumerRecord<String, String> record) {
        EventEnvelope<Object> envelope = reader.read(record);
        switch (envelope.payload()) {
            case PaymentCompleted completed -> saga.paymentCompleted(envelope.eventId(), completed);
            case PaymentFailed failed -> saga.paymentFailed(envelope.eventId(), failed);
            case PaymentRefunded refunded -> saga.paymentRefunded(envelope.eventId(), refunded);
            default -> log.debug("Ignoring {} on {}: not part of the order saga",
                    envelope.eventType(), record.topic());
        }
    }
}
