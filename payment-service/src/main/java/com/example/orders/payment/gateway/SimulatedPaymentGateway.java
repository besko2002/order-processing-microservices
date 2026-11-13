package com.example.orders.payment.gateway;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stands in for a real payment provider — <strong>simulated money only</strong>.
 *
 * <p>Nothing here moves real funds, talks to a real acquirer, or is correct for real money: the
 * charge writes an {@code AUTHORIZED} row in this service's own database and the refund flips it,
 * and that ledger is the whole "bank". Documentation, configuration examples and the README label
 * every charging/refunding behaviour as simulated — an integration with a real payment provider
 * does not exist and is deliberately out of scope (a real one would need PCI-relevant credentials,
 * liability and settlement semantics this project does not model).
 *
 * <p>It is deliberately <strong>deterministic</strong>: the same order always gets the same answer.
 * A saga is only testable if its failure branch can be triggered on purpose, and a gateway that
 * decided randomly per call would also break idempotency — a redelivered {@code ProcessPayment}
 * could then contradict the first answer.
 *
 * <p>Two knobs:
 * <ul>
 *   <li>{@code PAYMENT_DECLINE_ABOVE} (default {@code 1000.00}) — anything above this amount is
 *       declined. This is what the end-to-end "payment declined" scenario uses: the amount alone
 *       decides, so a test only has to order enough.</li>
 *   <li>{@code PAYMENT_FAILURE_RATE} (default {@code 0}) — the share of orders that are declined
 *       regardless of amount, for demonstrating compensation under "normal" traffic. The choice is
 *       made from a hash of the order id, not from a random number, so it stays stable per order.</li>
 * </ul>
 */
@Component
public class SimulatedPaymentGateway {

    /** Reason reported when the amount is above the configured ceiling. */
    public static final String DECLINED_AMOUNT = "Payment declined: amount %s %s exceeds the limit of %s";

    /** Reason reported when the order fell into the configured failure share. */
    public static final String DECLINED_RATE = "Payment declined by the provider (simulated failure rate)";

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentGateway.class);

    private final BigDecimal declineAbove;
    private final double failureRate;

    /**
     * @param declineAbove amounts strictly greater than this are declined
     * @param failureRate  share of orders declined regardless of amount, {@code 0}..{@code 1}
     */
    SimulatedPaymentGateway(
            @Value("${PAYMENT_DECLINE_ABOVE:1000.00}") BigDecimal declineAbove,
            @Value("${PAYMENT_FAILURE_RATE:0}") double failureRate) {
        this.declineAbove = declineAbove;
        this.failureRate = failureRate;
    }

    /**
     * @param orderId  order being charged; the only source of randomness, so the answer is stable
     * @param amount   amount to charge
     * @param currency ISO 4217 code
     * @return the provider's answer
     */
    public GatewayDecision charge(UUID orderId, BigDecimal amount, String currency) {
        if (amount == null || amount.compareTo(declineAbove) > 0) {
            String reason = DECLINED_AMOUNT.formatted(amount, currency, declineAbove.toPlainString());
            log.info("Simulated gateway declined order {}: {}", orderId, reason);
            return GatewayDecision.declined(reason);
        }
        if (failsByRate(orderId)) {
            log.info("Simulated gateway declined order {} by failure rate {}", orderId, failureRate);
            return GatewayDecision.declined(DECLINED_RATE);
        }
        return GatewayDecision.approved(providerReference(orderId));
    }

    /**
     * @param orderId order being charged
     * @return whether this order falls into the configured failure share; a stable property of the
     *         order id, so every retry of the same charge lands on the same side
     */
    private boolean failsByRate(UUID orderId) {
        if (failureRate <= 0 || orderId == null) {
            return false;
        }
        if (failureRate >= 1) {
            return true;
        }
        int bucket = Math.floorMod(orderId.hashCode(), 100);
        return bucket < Math.round(failureRate * 100);
    }

    /**
     * @param orderId order being charged
     * @return a stable, provider-looking reference. Derived from the order id rather than generated,
     *         so the same charge reported twice carries the same reference.
     */
    private static String providerReference(UUID orderId) {
        UUID derived = UUID.nameUUIDFromBytes(("payment:" + orderId).getBytes(StandardCharsets.UTF_8));
        return "SIM-" + derived.toString().substring(0, 13).replace("-", "").toUpperCase(Locale.ROOT);
    }
}
