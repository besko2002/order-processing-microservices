package com.example.orders.notification.service;

import com.example.orders.common.events.OrderCancelled;
import com.example.orders.common.events.OrderConfirmed;
import com.example.orders.common.events.OrderRejected;
import com.example.orders.notification.domain.Notification;
import com.example.orders.notification.domain.NotificationRepository;
import com.example.orders.support.idempotency.ProcessedEvents;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns final order events into customer emails.
 *
 * <p>Idempotent: the event id is recorded in {@code processed_events} in the same transaction as the
 * notification row, so a Kafka redelivery sends nothing twice. The mail is sent inside that
 * transaction; if SMTP fails the exception rolls everything back and the consumer's retry/DLT policy
 * takes over.
 */
@Service
public class NotificationService {

    static final String CONSUMER = "notification-order-events";

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final ProcessedEvents processedEvents;
    private final JavaMailSender mailSender;
    private final NotificationProperties properties;

    public NotificationService(NotificationRepository repository, ProcessedEvents processedEvents,
                               JavaMailSender mailSender, NotificationProperties properties) {
        this.repository = repository;
        this.processedEvents = processedEvents;
        this.mailSender = mailSender;
        this.properties = properties;
    }

    @Transactional
    public void onConfirmed(UUID eventId, OrderConfirmed event) {
        notify(eventId, event.customerId(), event.orderId(), Notification.Type.ORDER_CONFIRMED,
                "Your order " + event.orderNumber() + " is confirmed",
                "Thank you! Order " + event.orderNumber() + " is confirmed. Total: "
                        + event.totalAmount().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                        + " " + event.currency() + ".");
    }

    @Transactional
    public void onCancelled(UUID eventId, OrderCancelled event) {
        notify(eventId, event.customerId(), event.orderId(), Notification.Type.ORDER_CANCELLED,
                "Your order " + event.orderNumber() + " was cancelled",
                "Order " + event.orderNumber() + " was cancelled. Reason: " + event.reason() + ".");
    }

    @Transactional
    public void onRejected(UUID eventId, OrderRejected event) {
        notify(eventId, event.customerId(), event.orderId(), Notification.Type.ORDER_REJECTED,
                "We could not accept order " + event.orderNumber(),
                "Order " + event.orderNumber() + " could not be accepted. Reason: " + event.reason() + ".");
    }

    private void notify(UUID eventId, UUID customerId, UUID orderId, Notification.Type type,
                        String subject, String body) {
        if (!processedEvents.markProcessed(eventId, CONSUMER)) {
            log.debug("Event {} already handled, no second notification", eventId);
            return;
        }
        String recipient = customerId + "@" + properties.recipientDomain();
        Notification.Status status = Notification.Status.SKIPPED;
        if (properties.emailEnabled()) {
            SimpleMailMessage mail = new SimpleMailMessage();
            mail.setFrom(properties.from());
            mail.setTo(recipient);
            mail.setSubject(subject);
            mail.setText(body);
            mailSender.send(mail);
            status = Notification.Status.SENT;
        }
        repository.save(new Notification(eventId, customerId, orderId, type, recipient, subject, body, status));
        log.info("{} notification for order {} -> {} ({})", type, orderId, recipient, status);
    }
}
