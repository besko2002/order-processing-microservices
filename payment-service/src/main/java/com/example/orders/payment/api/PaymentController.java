package com.example.orders.payment.api;

import com.example.orders.payment.service.PaymentQueryService;
import com.example.orders.support.security.CurrentUser;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment API: read-only, and for one purpose — letting a customer (or an administrator) see
 * what happened to the money for a given order.
 *
 * <p>Everything under {@code /api/payments/**} needs a token; the ownership check happens in the
 * service, because a path rule cannot express "the caller is the customer of this payment".
 */
@RestController
@RequestMapping("/api/payments")
class PaymentController {

    /** Role that may see other people's payments. */
    private static final String ADMIN = "ADMIN";

    private final PaymentQueryService payments;

    PaymentController(PaymentQueryService payments) {
        this.payments = payments;
    }

    /**
     * @param orderId order to look up
     * @return the payment of that order, if the caller is its customer or an administrator
     */
    @GetMapping("/order/{orderId}")
    PaymentResponse byOrder(@PathVariable UUID orderId) {
        return payments.byOrder(orderId, CurrentUser.requireId(), CurrentUser.hasRole(ADMIN));
    }
}
