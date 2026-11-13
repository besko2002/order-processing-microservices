package com.example.orders.notification.service;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param from            sender address
 * @param recipientDomain events carry no email address, so the demo recipient is
 *                        {@code <customerId>@<recipientDomain>}
 * @param emailEnabled    when false, notifications are stored as SKIPPED and no mail is sent
 */
@ConfigurationProperties("notifications")
public record NotificationProperties(String from, String recipientDomain, boolean emailEnabled) {
}
