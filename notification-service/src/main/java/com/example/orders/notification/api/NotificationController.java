package com.example.orders.notification.api;

import com.example.orders.notification.domain.Notification;
import com.example.orders.notification.domain.NotificationRepository;
import com.example.orders.support.security.CurrentUser;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** The caller's own notification history, newest first. */
@RestController
@RequestMapping("/api/notifications")
class NotificationController {

    private final NotificationRepository repository;

    NotificationController(NotificationRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/me")
    PageResponse mine(@RequestParam(defaultValue = "0") int page,
                      @RequestParam(defaultValue = "20") int size) {
        int safeSize = Math.min(Math.max(size, 1), 100);
        Page<Notification> result = repository.findByCustomerIdOrderByCreatedAtDesc(
                CurrentUser.requireId(), PageRequest.of(Math.max(page, 0), safeSize));
        return new PageResponse(result.map(NotificationResponse::from).getContent(), result.getNumber(),
                result.getSize(), result.getTotalElements(), result.getTotalPages(), result.isLast());
    }

    record NotificationResponse(UUID id, UUID orderId, String type, String channel, String recipient,
                                String subject, String body, String status, Instant createdAt) {
        static NotificationResponse from(Notification n) {
            return new NotificationResponse(n.getId(), n.getOrderId(), n.getType().name(), n.getChannel().name(),
                    n.getRecipient(), n.getSubject(), n.getBody(), n.getStatus().name(), n.getCreatedAt());
        }
    }

    record PageResponse(List<NotificationResponse> content, int page, int size, long totalElements,
                        int totalPages, boolean last) {
    }
}
