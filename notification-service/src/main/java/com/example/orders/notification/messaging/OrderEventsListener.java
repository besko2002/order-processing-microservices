package com.example.orders.notification.messaging;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.OrderCancelled;
import com.example.orders.common.events.OrderConfirmed;
import com.example.orders.common.events.OrderRejected;
import com.example.orders.common.events.Topics;
import com.example.orders.notification.service.NotificationService;
import com.example.orders.support.messaging.EventConsumerAutoConfiguration;
import com.example.orders.support.messaging.EventEnvelopeReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Listens to the final order outcomes; every other event on the topic is ignored. */
@Component
class OrderEventsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsListener.class);

    private final EventEnvelopeReader reader;
    private final NotificationService notifications;

    OrderEventsListener(EventEnvelopeReader reader, NotificationService notifications) {
        this.reader = reader;
        this.notifications = notifications;
    }

    @KafkaListener(
            id = "notification-order-events",
            topics = Topics.ORDER_EVENTS,
            containerFactory = EventConsumerAutoConfiguration.CONTAINER_FACTORY)
    void onOrderEvent(ConsumerRecord<String, String> record) {
        EventEnvelope<Object> envelope = reader.read(record);
        switch (envelope.payload()) {
            case OrderConfirmed confirmed -> notifications.onConfirmed(envelope.eventId(), confirmed);
            case OrderCancelled cancelled -> notifications.onCancelled(envelope.eventId(), cancelled);
            case OrderRejected rejected -> notifications.onRejected(envelope.eventId(), rejected);
            default -> log.debug("Ignoring {}: no notification for it", envelope.eventType());
        }
    }
}
