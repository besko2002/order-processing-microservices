package com.example.orders.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One message sent (or deliberately skipped) to a customer about one order event. */
@Entity
@Table(name = "notifications")
public class Notification {

    public enum Type { ORDER_CONFIRMED, ORDER_CANCELLED, ORDER_REJECTED }

    public enum Channel { EMAIL }

    public enum Status { SENT, SKIPPED }

    @Id
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(name = "customer_id", nullable = false)
    private UUID customerId;

    @Column(name = "order_id", nullable = false)
    private UUID orderId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Channel channel;

    @Column(nullable = false)
    private String recipient;

    @Column(nullable = false)
    private String subject;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Notification() {
    }

    public Notification(UUID eventId, UUID customerId, UUID orderId, Type type, String recipient,
                        String subject, String body, Status status) {
        this.id = UUID.randomUUID();
        this.eventId = eventId;
        this.customerId = customerId;
        this.orderId = orderId;
        this.type = type;
        this.channel = Channel.EMAIL;
        this.recipient = recipient;
        this.subject = subject;
        this.body = body;
        this.status = status;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public UUID getCustomerId() { return customerId; }
    public UUID getOrderId() { return orderId; }
    public Type getType() { return type; }
    public Channel getChannel() { return channel; }
    public String getRecipient() { return recipient; }
    public String getSubject() { return subject; }
    public String getBody() { return body; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}
