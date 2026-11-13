package com.example.orders.payment;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.orders.common.events.EventEnvelope;
import com.example.orders.common.events.ProcessPayment;
import com.example.orders.common.events.RefundPayment;
import com.example.orders.payment.domain.Payment;
import com.example.orders.payment.domain.PaymentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * payment-service inside the saga: commands in on Kafka, events out on Kafka, and the
 * {@code payments} row as the evidence that the money moved exactly once.
 */
class PaymentSagaTest extends PaymentIntegrationTest {

    @Test
    void anAmountUnderTheLimitIsAuthorizedAndAnnouncedAsPaymentCompleted() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();

        publishCommand(orderId, EventEnvelope.of("corr-pay-ok",
                new ProcessPayment(orderId, customerId, new BigDecimal("249.99"), "EUR")));

        JsonNode event = awaitPaymentEvent(orderId, "PaymentCompleted");
        Payment payment = payments.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
        assertThat(payment.getCustomerId()).isEqualTo(customerId);
        assertThat(payment.getAmount()).isEqualByComparingTo("249.99");
        assertThat(payment.getProviderRef()).startsWith("SIM-");
        assertThat(payment.getFailureReason()).isNull();

        assertThat(event.get("payload").get("paymentId").asText()).isEqualTo(payment.getId().toString());
        assertThat(event.get("payload").get("amount").asDouble()).isEqualTo(249.99);
        assertThat(event.get("payload").get("currency").asText()).isEqualTo("EUR");
        // The correlation id of the command travels into the event it produced.
        assertThat(event.get("correlationId").asText()).isEqualTo("corr-pay-ok");
    }

    @Test
    void anAmountAboveTheLimitIsDeclinedAndAnnouncedAsPaymentFailed() {
        UUID orderId = UUID.randomUUID();

        publishCommand(orderId, EventEnvelope.of("corr-pay-declined",
                new ProcessPayment(orderId, UUID.randomUUID(), new BigDecimal("1500.00"), "EUR")));

        JsonNode event = awaitPaymentEvent(orderId, "PaymentFailed");
        Payment payment = payments.findByOrderId(orderId).orElseThrow();
        // The failed attempt is stored, not forgotten: a redelivery has to report the same outcome.
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(payment.getProviderRef()).isNull();
        assertThat(payment.getFailureReason())
                .contains("exceeds the limit of " + DECLINE_ABOVE);
        assertThat(event.get("payload").get("reason").asText()).contains("exceeds the limit");
    }

    @Test
    void aRedeliveredProcessPaymentChargesOnlyOnce() {
        UUID orderId = UUID.randomUUID();
        // The same event id twice: what the outbox relay produces when a broker acknowledgement is
        // lost after the record was already on the topic.
        EventEnvelope<ProcessPayment> command = EventEnvelope.of("corr-pay-dup",
                new ProcessPayment(orderId, UUID.randomUUID(), new BigDecimal("50.00"), "EUR"));

        publishCommand(orderId, command);
        awaitUntil(Duration.ofSeconds(45), () -> payments.findByOrderId(orderId).isPresent());
        UUID paymentId = payments.findByOrderId(orderId).orElseThrow().getId();
        publishCommand(orderId, command);

        assertHoldsFor(Duration.ofSeconds(3), () -> {
            assertThat(payments.count()).isEqualTo(1);
            assertThat(payments.findByOrderId(orderId).orElseThrow().getId()).isEqualTo(paymentId);
        });
        // One note per (event, consumer): the second delivery found it and did nothing at all.
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    void aSecondChargeCommandForTheSameOrderReportsTheStoredOutcomeInsteadOfCharging() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        publishCommand(orderId, EventEnvelope.of("corr-pay-first",
                new ProcessPayment(orderId, customerId, new BigDecimal("75.00"), "EUR")));
        awaitUntil(Duration.ofSeconds(45), () -> payments.findByOrderId(orderId).isPresent());
        UUID paymentId = payments.findByOrderId(orderId).orElseThrow().getId();
        outbox.deleteAllInBatch();

        // A *different* command for the same order: a new event id, so the de-duplication log does
        // not stop it. The unique payment per order does.
        publishCommand(orderId, EventEnvelope.of("corr-pay-second",
                new ProcessPayment(orderId, customerId, new BigDecimal("75.00"), "EUR")));

        awaitUntil(Duration.ofSeconds(45), () -> !outbox.findAll().isEmpty());
        assertHoldsFor(Duration.ofSeconds(2), () -> {
            assertThat(payments.count()).isEqualTo(1);
            assertThat(payments.findByOrderId(orderId).orElseThrow().getId()).isEqualTo(paymentId);
        });
        assertThat(outbox.findAll()).allSatisfy(row ->
                assertThat(row.getEventType()).isEqualTo("PaymentCompleted"));
    }

    @Test
    void refundingAnAuthorizedPaymentAnnouncesPaymentRefunded() {
        UUID orderId = UUID.randomUUID();
        publishCommand(orderId, EventEnvelope.of("corr-refund-flow",
                new ProcessPayment(orderId, UUID.randomUUID(), new BigDecimal("120.00"), "EUR")));
        awaitUntil(Duration.ofSeconds(45), () -> payments.findByOrderId(orderId)
                .filter(Payment::isAuthorized).isPresent());
        UUID paymentId = payments.findByOrderId(orderId).orElseThrow().getId();

        publishCommand(orderId, EventEnvelope.of("corr-refund-flow", new RefundPayment(
                orderId, paymentId, new BigDecimal("120.00"), "EUR", "Cancelled by the customer")));

        JsonNode event = awaitPaymentEvent(orderId, "PaymentRefunded");
        assertThat(event.get("payload").get("paymentId").asText()).isEqualTo(paymentId.toString());
        assertThat(event.get("payload").get("reason").asText()).isEqualTo("Cancelled by the customer");
        Payment refunded = payments.findByOrderId(orderId).orElseThrow();
        assertThat(refunded.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(refunded.getFailureReason()).isEqualTo("Cancelled by the customer");
    }

    @Test
    void refundingAFailedPaymentChangesNothingAndAnnouncesNothing() {
        UUID orderId = UUID.randomUUID();
        publishCommand(orderId, EventEnvelope.of("corr-refund-failed",
                new ProcessPayment(orderId, UUID.randomUUID(), new BigDecimal("2000.00"), "EUR")));
        awaitUntil(Duration.ofSeconds(45), () -> payments.findByOrderId(orderId).isPresent());
        UUID paymentId = payments.findByOrderId(orderId).orElseThrow().getId();

        publishCommand(orderId, EventEnvelope.of("corr-refund-failed", new RefundPayment(
                orderId, paymentId, new BigDecimal("2000.00"), "EUR", "Cancelled by the customer")));

        // There is no money to give back, and announcing a refund would tell the orchestrator that
        // something happened when nothing did.
        assertThat(findPaymentEvent(orderId, "PaymentRefunded", Duration.ofSeconds(5))).isNull();
        assertThat(payments.findByOrderId(orderId).orElseThrow().getStatus())
                .isEqualTo(PaymentStatus.FAILED);
    }

    @Test
    void refundingAnOrderThatWasNeverChargedIsASilentNoOp() {
        UUID orderId = UUID.randomUUID();

        publishCommand(orderId, EventEnvelope.of("corr-refund-unknown", new RefundPayment(
                orderId, UUID.randomUUID(), new BigDecimal("10.00"), "EUR", "timeout compensation")));

        // The orchestrator compensates without knowing how far the saga got, so this is normal.
        assertThat(findPaymentEvent(orderId, "PaymentRefunded", Duration.ofSeconds(5))).isNull();
        assertHoldsFor(Duration.ofSeconds(1), () -> assertThat(payments.count()).isZero());
    }

    @Test
    void aRedeliveredRefundDoesNotRefundTwiceButKeepsTheOrchestratorInformed() {
        UUID orderId = UUID.randomUUID();
        publishCommand(orderId, EventEnvelope.of("corr-refund-dup",
                new ProcessPayment(orderId, UUID.randomUUID(), new BigDecimal("30.00"), "EUR")));
        awaitUntil(Duration.ofSeconds(45), () -> payments.findByOrderId(orderId)
                .filter(Payment::isAuthorized).isPresent());
        UUID paymentId = payments.findByOrderId(orderId).orElseThrow().getId();

        publishCommand(orderId, EventEnvelope.of("corr-refund-dup", new RefundPayment(
                orderId, paymentId, new BigDecimal("30.00"), "EUR", "first refund")));
        awaitPaymentEvent(orderId, "PaymentRefunded");
        // A second refund command with a fresh event id: the payment is already refunded, so the
        // state must not change, while the confirmation is repeated for an orchestrator that may
        // still be waiting for it.
        publishCommand(orderId, EventEnvelope.of("corr-refund-dup-2", new RefundPayment(
                orderId, paymentId, new BigDecimal("30.00"), "EUR", "second refund")));

        awaitUntil(Duration.ofSeconds(45), () -> outbox.findAll().stream()
                .filter(row -> "PaymentRefunded".equals(row.getEventType()))
                .count() == 2);
        Payment payment = payments.findByOrderId(orderId).orElseThrow();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(payment.getFailureReason()).isEqualTo("first refund");
    }

    @Test
    void anEventTypeThatIsNotAPaymentCommandIsIgnored() {
        UUID orderId = UUID.randomUUID();

        publishCommand(orderId, EventEnvelope.of("corr-ignored",
                new com.example.orders.common.events.CommitStock(orderId)));

        assertHoldsFor(Duration.ofSeconds(2), () -> assertThat(payments.count()).isZero());
    }
}
