package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.OrderItem;
import com.example.orders.common.events.ReleaseStock;
import com.example.orders.inventory.api.ReconciliationRequest;
import com.example.orders.inventory.domain.OrderReleaseIntentRepository;
import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.saga.InventorySagaTransactions;
import com.example.orders.inventory.service.ReservationReconciliation;
import com.example.orders.inventory.service.StockReservationService;
import com.example.orders.support.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/**
 * Priority 2, recovery half: inventory holds that leaked <em>before</em> the release-intent fix
 * (a cancelled order whose late {@code StockReserved} was processed here), and the operator
 * endpoint that can give them back safely.
 *
 * <p>The safety contract under test — the reason a human may run this in production:
 * <ul>
 *   <li>only {@code RESERVED} rows move; {@code COMMITTED} rows are a sale and are refused
 *       entirely — a typo'd order id cannot turn merchandise into a fake refund;</li>
 *   <li>every processed order gets a durable release intent, so a late or replayed
 *       {@code OrderCreated} for a recovered order cannot hold stock again — the tool is safe
 *       to run twice;</li>
 *   <li>requests are validated (at least one id, at most 200) and ADMIN-only;</li>
 *   <li>the report names one entry per distinct order id, in the order the ids were passed;</li>
 *   <li>every id is first verified with (a stubbed) order-service as {@code CANCELLED} or
 *       {@code REJECTED} — the refusals of everything else are the subject of
 *       {@code ReservationReconciliationVerificationTest}.</li>
 * </ul>
 */
class InventoryReservationReconciliationTest extends InventoryIntegrationTest {

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private OrderReleaseIntentRepository releaseIntents;

    @Autowired
    private InventorySagaTransactions inventorySaga;

    @Autowired
    private StockReservationService reservationService;

    @Autowired
    private ObjectMapper json;

    @BeforeEach
    void clearState() {
        outbox.deleteAllInBatch();
        releaseIntents.deleteAllInBatch();
    }

    // ------------------------------------------------------------------ the happy recovery

    @Test
    void theRecoveryReleasesLeakedHoldsAndALateOrderCreatedCannotReserveAgain() throws Exception {
        Product product = givenProduct("SKU-RECONCILE-1", 10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(
                new OrderItem(product.getId(), product.getSku(), 3, product.getPrice())));
        assertThat(products.findById(product.getId()).orElseThrow().getReservedQuantity()).isEqualTo(3);

        stubOrderStatus(orderId, "CANCELLED");

        String reason = "operator cleanup, incident " + orderId.toString().substring(0, 8);
        String body = reconcile(List.of(orderId), reason)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(parse(body).get("entries")).hasSize(1);
        assertThat(parse(body).get("entries").get(0).get("outcome").asText()).isEqualTo("RELEASED");

        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
        Product after = products.findById(product.getId()).orElseThrow();
        assertThat(after.getAvailableQuantity()).isEqualTo(10);
        assertThat(after.getReservedQuantity()).isZero();
        // The operator's reason travels into the audit trail.
        assertThat(releaseIntents.findById(orderId).orElseThrow().getReason()).isEqualTo(reason);

        // This is what the intent is for: the lagging OrderCreated (the saga's duplicate
        // re-announcement habit, with a fresh event id) must not hold stock now that the operator
        // has recovered the order.
        inventorySaga.onOrderCreated(UUID.randomUUID(), orderCreatedOf(orderId, product, 3));
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
        assertThat(outboxTypes(orderId)).contains("StockRejected").doesNotContain("StockReserved");
        assertThat(products.findById(product.getId()).orElseThrow().getReservedQuantity()).isZero();
    }

    @Test
    void theRecoveryRefusesACommittedSaleButTheRefundFlowStaysAbleToReleaseIt() throws Exception {
        Product product = givenProduct("SKU-RECONCILE-2", 10);
        UUID orderId = UUID.randomUUID();
        reservationService.reserve(orderId, List.of(
                new OrderItem(product.getId(), product.getSku(), 2, product.getPrice())));

        // The order was paid: its hold is now a sale — exactly what COMMITTED rows mean.
        inventorySaga.onCommitStock(UUID.randomUUID(), new CommitStock(orderId));
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.COMMITTED));
        assertThat(products.findById(product.getId()).orElseThrow().getAvailableQuantity()).isEqualTo(8);

        // Cancelled and refunded-or-not, the sale is still a sale as far as this tool is concerned.
        stubOrderStatus(orderId, "CANCELLED");
        String body = reconcile(List.of(orderId), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(parse(body).get("entries").get(0).get("outcome").asText())
                .isEqualTo("SKIPPED_COMMITTED");
        // Untouched: not released, not re-committed, and no intent written either — a committed
        // row is money turned into merchandise, and reconciliation refuses to unring that bell.
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.COMMITTED));
        assertThat(products.findById(product.getId()).orElseThrow().getAvailableQuantity()).isEqualTo(8);
        assertThat(releaseIntents.existsById(orderId)).isFalse();

        // A legitimate refund goes through the saga's ReleaseStock command, not this tool: that
        // path keeps its ability to give the units back after a payment was reversed.
        inventorySaga.onReleaseStock(UUID.randomUUID(),
                new ReleaseStock(orderId, null, "The order was refunded", List.of()));
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(orderId))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
        assertThat(products.findById(product.getId()).orElseThrow().getAvailableQuantity()).isEqualTo(10);
    }

    @Test
    void aRecoveryRunIsReportedPerOrderAndSafeToRunTwice() throws Exception {
        Product product = givenProduct("SKU-RECONCILE-3", 20);
        UUID leaked = UUID.randomUUID();
        UUID alreadyReleased = UUID.randomUUID();
        UUID neverReserved = UUID.randomUUID();
        reservationService.reserve(leaked, List.of(
                new OrderItem(product.getId(), product.getSku(), 5, product.getPrice())));
        reservationService.reserve(alreadyReleased, List.of(
                new OrderItem(product.getId(), product.getSku(), 1, product.getPrice())));
        inventorySaga.onReleaseStock(UUID.randomUUID(),
                new ReleaseStock(alreadyReleased, null, "was released before", List.of()));
        assertThat(reservations.findByOrderIdOrderByCreatedAtAsc(alreadyReleased))
                .allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));

        stubOrderStatus(leaked, "CANCELLED");
        stubOrderStatus(alreadyReleased, "REJECTED");
        stubOrderStatus(neverReserved, "REJECTED");

        // A blank reason falls back to the documented default; a duplicate id is collapsed and
        // the report keeps first-occurrence order.
        String body = reconcile(List.of(leaked, neverReserved, alreadyReleased, leaked), " ")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode entries = parse(body).get("entries");
        assertThat(entries).hasSize(3);
        assertThat(entries.get(0).get("outcome").asText()).isEqualTo("RELEASED");
        assertThat(entries.get(1).get("outcome").asText()).isEqualTo("NO_RESERVATIONS");
        assertThat(entries.get(2).get("outcome").asText()).isEqualTo("ALREADY_RELEASED");
        assertThat(releaseIntents.findById(neverReserved).orElseThrow().getReason())
                .isEqualTo(ReservationReconciliation.DEFAULT_REASON);

        // Running the same list again answers without moving anything: recovery is idempotent.
        String second = reconcile(List.of(leaked, neverReserved, alreadyReleased), null)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode rerun = parse(second).get("entries");
        assertThat(rerun.get(0).get("outcome").asText()).isEqualTo("ALREADY_RELEASED");
        assertThat(rerun.get(1).get("outcome").asText()).isEqualTo("NO_RESERVATIONS");
        assertThat(rerun.get(2).get("outcome").asText()).isEqualTo("ALREADY_RELEASED");
        assertThat(products.findById(product.getId()).orElseThrow().getAvailableQuantity()).isEqualTo(20);
        assertThat(products.findById(product.getId()).orElseThrow().getReservedQuantity()).isZero();
    }

    // ------------------------------------------------------------------ access and validation

    @Test
    void theRecoveryNeedsAnAdminAndAReasonableRequest() throws Exception {
        List<UUID> tooMany = IntStream.range(0, ReservationReconciliation.MAX_ORDER_IDS + 1)
                .mapToObj(i -> UUID.randomUUID())
                .toList();

        mvc.perform(post("/api/inventory/reservations/reconcile")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new ReconciliationRequest(List.of(UUID.randomUUID()), "anything")))
                )
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/reservations/reconcile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                new ReconciliationRequest(List.of(UUID.randomUUID()), "anything")))
                )
                .andExpect(status().isUnauthorized());
        // More ids than the hard ceiling and an empty list are rejected before any work starts.
        mvc.perform(post("/api/inventory/reservations/reconcile")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReconciliationRequest(tooMany, "anything")))
                )
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/inventory/reservations/reconcile")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReconciliationRequest(List.of(), "anything")))
                )
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    /**
     * @param orderIds explicit order ids for the recovery run
     * @param reason   the audit reason; blank or {@code null} is sent as {@code null}, so the
     *                 service-side default kicks in
     * @return the started request against {@code POST /api/inventory/reservations/reconcile}
     */
    private org.springframework.test.web.servlet.ResultActions reconcile(List<UUID> orderIds, String reason)
            throws Exception {
        ReconciliationRequest request = new ReconciliationRequest(
                orderIds, reason == null || reason.isBlank() ? null : reason);
        return mvc.perform(post("/api/inventory/reservations/reconcile")
                .header("Authorization", "Bearer " + adminToken())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)));
    }

    private JsonNode parse(String body) throws IOException {
        return json.readTree(body);
    }

    private List<String> outboxTypes(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .map(row -> row.getEventType())
                .toList();
    }

    private OrderCreated orderCreatedOf(UUID orderId, Product product, int quantity) {
        OrderItem item = new OrderItem(product.getId(), product.getSku(), quantity, product.getPrice());
        BigDecimal total = product.getPrice().multiply(BigDecimal.valueOf(quantity));
        return new OrderCreated(orderId, "ORD-RECONCILE", UUID.randomUUID(), total, "EUR", List.of(item));
    }
}
