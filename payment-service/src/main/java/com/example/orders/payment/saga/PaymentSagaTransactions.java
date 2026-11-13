package com.example.orders.payment.saga;

import com.example.orders.common.events.PaymentCompleted;
import com.example.orders.common.events.PaymentFailed;
import com.example.orders.common.events.PaymentRefunded;
import com.example.orders.common.events.ProcessPayment;
import com.example.orders.common.events.RefundPayment;
import com.example.orders.common.events.Topics;
import com.example.orders.payment.domain.Payment;
import com.example.orders.payment.domain.PaymentRepository;
import com.example.orders.payment.gateway.GatewayDecision;
import com.example.orders.payment.gateway.SimulatedPaymentGateway;
import com.example.orders.support.idempotency.ProcessedEvents;
import com.example.orders.support.outbox.OutboxWriter;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * payment-service's side of the saga: charging and refunding, one transaction per command.
 *
 * <p>Charging exactly once is guarded twice over, because this is the one step where a duplicate
 * costs real money:
 * <ol>
 *   <li>the {@code processed_events} note stops a redelivery of the <em>same</em> command before the
 *       gateway is called;</li>
 *   <li>the lookup by order id stops a <em>different</em> command for an order that already has a
 *       payment — it re-announces the stored outcome instead of charging again;</li>
 *   <li>and {@code UNIQUE(order_id)} in the database is there for the case where two deliveries
 *       somehow raced past both.</li>
 * </ol>
 *
 * <p>The charge and the event announcing it are one commit, so payment-service can never hold money
 * the orchestrator has not heard about, nor announce a payment it did not record.
 */
@Component
public class PaymentSagaTransactions {

    private static final Logger log = LoggerFactory.getLogger(PaymentSagaTransactions.class);

    /** Consumer name recorded in {@code processed_events} for the command topic. */
    public static final String COMMAND_CONSUMER = "payment.commands";

    private final PaymentRepository payments;
    private final SimulatedPaymentGateway gateway;
    private final OutboxWriter outbox;
    private final ProcessedEvents processedEvents;

    PaymentSagaTransactions(
            PaymentRepository payments,
            SimulatedPaymentGateway gateway,
            OutboxWriter outbox,
            ProcessedEvents processedEvents) {
        this.payments = payments;
        this.gateway = gateway;
        this.outbox = outbox;
        this.processedEvents = processedEvents;
    }

    /**
     * Saga step 3: charge the customer.
     *
     * @param eventId id of the {@code ProcessPayment} command
     * @param command its payload
     */
    @Transactional
    public void onProcessPayment(UUID eventId, ProcessPayment command) {
        if (!processedEvents.markProcessed(eventId, COMMAND_CONSUMER)) {
            return;
        }
        if (command.orderId() == null) {
            log.warn("Ignoring ProcessPayment without an order id");
            return;
        }
        Optional<Payment> existing = payments.findByOrderId(command.orderId());
        if (existing.isPresent()) {
            Payment payment = existing.get();
            // Never charge twice: report what the first attempt decided. The orchestrator's guards
            // make a repeated answer harmless, and staying silent here could strand the saga.
            log.info("Order {} already has payment {} in state {}, re-announcing that outcome",
                    command.orderId(), payment.getId(), payment.getStatus());
            announce(payment);
            return;
        }

        GatewayDecision decision = gateway.charge(command.orderId(), command.amount(), command.currency());
        Payment payment = decision.approved()
                ? Payment.authorized(command.orderId(), command.customerId(), command.amount(),
                        command.currency(), decision.providerRef())
                : Payment.failed(command.orderId(), command.customerId(), command.amount(),
                        command.currency(), decision.reason());
        // Flush inside this attempt so UNIQUE(order_id) surfaces here and not at commit time.
        payments.saveAndFlush(payment);
        announce(payment);
        log.info("Payment {} for order {} is {} ({} {})",
                payment.getId(), payment.getOrderId(), payment.getStatus(), payment.getAmount(),
                payment.getCurrency());
    }

    /**
     * Compensation: give an authorised charge back.
     *
     * @param eventId id of the {@code RefundPayment} command
     * @param command its payload
     */
    @Transactional
    public void onRefundPayment(UUID eventId, RefundPayment command) {
        if (!processedEvents.markProcessed(eventId, COMMAND_CONSUMER)) {
            return;
        }
        if (command.orderId() == null) {
            log.warn("Ignoring RefundPayment without an order id");
            return;
        }
        Optional<Payment> found = payments.findByOrderId(command.orderId());
        if (found.isEmpty()) {
            // Nothing was ever charged for this order — the orchestrator compensates without knowing
            // how far the saga got, so this is a normal outcome, not an error.
            log.info("RefundPayment for order {} has nothing to refund", command.orderId());
            return;
        }
        Payment payment = found.get();
        String reason = command.reason() == null ? "Refunded after cancellation" : command.reason();
        if (payment.refund(reason)) {
            payments.saveAndFlush(payment);
            log.info("Refunded payment {} of order {}: {}", payment.getId(), payment.getOrderId(), reason);
            outbox.record(payment.getOrderId(), PaymentRefunded.class.getSimpleName(), Topics.PAYMENT_EVENTS,
                    new PaymentRefunded(payment.getOrderId(), payment.getId(), payment.getAmount(),
                            payment.getCurrency(), reason));
            return;
        }
        switch (payment.getStatus()) {
            case REFUNDED -> {
                // Already refunded. Announce it again rather than staying silent: the orchestrator
                // may still be waiting for the confirmation, and it ignores a repeat.
                log.info("Payment {} of order {} was already refunded, re-announcing it",
                        payment.getId(), payment.getOrderId());
                outbox.record(payment.getOrderId(), PaymentRefunded.class.getSimpleName(),
                        Topics.PAYMENT_EVENTS,
                        new PaymentRefunded(payment.getOrderId(), payment.getId(), payment.getAmount(),
                                payment.getCurrency(), payment.getFailureReason()));
            }
            // A failed charge has no money to give back, and announcing a refund would tell the
            // orchestrator that something happened when nothing did.
            default -> log.info("Payment {} of order {} is {}, nothing to refund",
                    payment.getId(), payment.getOrderId(), payment.getStatus());
        }
    }

    private void announce(Payment payment) {
        switch (payment.getStatus()) {
            case AUTHORIZED -> outbox.record(payment.getOrderId(), PaymentCompleted.class.getSimpleName(),
                    Topics.PAYMENT_EVENTS,
                    new PaymentCompleted(payment.getOrderId(), payment.getId(), payment.getAmount(),
                            payment.getCurrency()));
            case FAILED -> outbox.record(payment.getOrderId(), PaymentFailed.class.getSimpleName(),
                    Topics.PAYMENT_EVENTS,
                    new PaymentFailed(payment.getOrderId(), payment.getId(), payment.getAmount(),
                            payment.getCurrency(), payment.getFailureReason()));
            // A refunded payment is not an answer to "please charge": the order that asked has long
            // been cancelled, and PaymentRefunded was published when the refund happened.
            case REFUNDED -> log.info("Payment {} of order {} is already refunded, not re-announcing a charge",
                    payment.getId(), payment.getOrderId());
        }
    }
}
