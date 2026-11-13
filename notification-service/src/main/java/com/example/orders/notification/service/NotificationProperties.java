package com.example.orders.notification.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>Demo email delivery — never a customer's real mailbox.</b>
 *
 * <p>Order events carry the customer <em>id</em> only; there is no registered customer email in
 * this system, and none is accepted from an event payload either (an arbitrary recipient from a
 * payload would be a spoofable identity). Instead every mail is addressed to the synthetic
 * {@code <customerId>@<recipientDomain>} and lands in MailHog, where it can be inspected without
 * reaching a real person. Swapping this for verified customer emails is a deliberate future change:
 * it requires a trustworthy, explicitly user-verified address source plus the privacy controls a
 * real address book needs, and is out of scope for this demo (see README).
 *
 * @param from            demo sender address
 * @param recipientDomain demo-only domain appended to the customer id to produce the recipient
 * @param emailEnabled    when false, notifications are stored as SKIPPED and no mail is sent
 */
@ConfigurationProperties("notifications")
public record NotificationProperties(String from, String recipientDomain, boolean emailEnabled) {
}
