package com.example.orders.payment.gateway;

/**
 * What the simulated gateway answered.
 *
 * @param approved    whether the charge went through
 * @param providerRef the provider's reference for the transaction; {@code null} when declined
 * @param reason      why it was declined; {@code null} when approved
 */
public record GatewayDecision(boolean approved, String providerRef, String reason) {

    /**
     * @param providerRef the provider's reference
     * @return an approval
     */
    public static GatewayDecision approved(String providerRef) {
        return new GatewayDecision(true, providerRef, null);
    }

    /**
     * @param reason why the charge was declined
     * @return a decline
     */
    public static GatewayDecision declined(String reason) {
        return new GatewayDecision(false, null, reason);
    }
}
