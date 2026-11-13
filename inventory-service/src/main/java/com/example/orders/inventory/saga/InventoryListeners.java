package com.example.orders.inventory.saga;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.common.events.Topics;
import com.example.orders.support.messaging.EventConsumerAutoConfiguration;
import com.example.orders.support.messaging.EventEnvelopeReader;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The two things inventory-service listens to: new orders (reserve) and the orchestrator's commands
 * (commit, release).
 *
 * <p>{@code order.events} carries more than {@code OrderCreated} — {@code OrderConfirmed},
 * {@code OrderCancelled}, {@code OrderRejected} travel on it too. Those are known message types that
 * this consumer simply has nothing to do with, so they are ignored at DEBUG. Only a record that is
 * not a message of this system at all is a poison record, and the envelope reader raises that before
 * this code runs.
 */
@Component
class InventoryListeners {

    private static final Logger log = LoggerFactory.getLogger(InventoryListeners.class);

    private final EventEnvelopeReader reader;
    private final InventorySaga saga;

    InventoryListeners(EventEnvelopeReader reader, InventorySaga saga) {
        this.reader = reader;
        this.saga = saga;
    }

    /**
     * @param record a record from {@code order.events}
     */
    @KafkaListener(
            id = "inventory-order-events",
            topics = Topics.ORDER_EVENTS,
            containerFactory = EventConsumerAutoConfiguration.CONTAINER_FACTORY)
    void onOrderEvent(ConsumerRecord<String, String> record) {
        EventEnvelope<Object> envelope = reader.read(record);
        if (envelope.payload() instanceof OrderCreated created) {
            saga.orderCreated(envelope.eventId(), created);
        }
        else {
            log.debug("Ignoring {} on {}: inventory only reacts to OrderCreated",
                    envelope.eventType(), record.topic());
        }
    }

    /**
     * @param record a record from {@code inventory.commands}
     */
    @KafkaListener(
            id = "inventory-commands",
            topics = Topics.INVENTORY_COMMANDS,
            containerFactory = EventConsumerAutoConfiguration.CONTAINER_FACTORY)
    void onCommand(ConsumerRecord<String, String> record) {
        EventEnvelope<Object> envelope = reader.read(record);
        switch (envelope.payload()) {
            case CommitStock commit -> saga.commitStock(envelope.eventId(), commit);
            case ReleaseStock release -> saga.releaseStock(envelope.eventId(), release);
            default -> log.debug("Ignoring {} on {}: not an inventory command",
                    envelope.eventType(), record.topic());
        }
    }
}
