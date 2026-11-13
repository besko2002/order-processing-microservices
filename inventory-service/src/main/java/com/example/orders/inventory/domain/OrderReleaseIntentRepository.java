package com.example.orders.inventory.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Lookups for durable cancellation intents. Writes go through {@code ON CONFLICT DO NOTHING}
 * (below): concurrent or repeated {@code ReleaseStock} commands all queue the same intent, and the
 * first one's reason should stick without either having to wait for the other.
 */
public interface OrderReleaseIntentRepository extends JpaRepository<OrderReleaseIntent, UUID> {

    /**
     * @param orderId order whose cancellation intent to record
     * @param reason  what the cancellation said
     * @return 1 when a row was created, 0 when the intent already existed (a duplicate release)
     */
    @Modifying
    @Query(value = """
            INSERT INTO order_release_intents (order_id, reason)
            VALUES (:orderId, :reason)
            ON CONFLICT (order_id) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("orderId") UUID orderId, @Param("reason") String reason);
}
