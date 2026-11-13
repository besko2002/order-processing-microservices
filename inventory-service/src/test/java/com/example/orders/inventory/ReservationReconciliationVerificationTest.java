package com.example.orders.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.orders.common.events.CommitStock;
import com.example.orders.common.events.OrderCreated;
import com.example.orders.common.events.OrderItem;
import com.example.orders.inventory.api.ReconciliationRequest;
import com.example.orders.inventory.domain.OrderReleaseIntentRepository;
import com.example.orders.inventory.domain.Product;
import com.example.orders.inventory.domain.ReservationStatus;
import com.example.orders.inventory.domain.StockReservation;
import com.example.orders.inventory.saga.InventorySagaTransactions;
import com.example.orders.inventory.service.ReconciliationOutcome;
import com.example.orders.inventory.service.ReservationReconciliation;
import com.example.orders.inventory.service.ReservationResult;
import com.example.orders.support.error.BadRequestException;
import com.example.orders.support.outbox.OutboxRepository;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 2: the operator reconciliation must not be able to release a legitimate reservation.
 *
 * <p>Before, it treated whatever order ids the administrator typed (checked by hand against the
 * order database, says the old runbook) as proof of cancellation: an id of an <em>active</em> order
 * released its {@code RESERVED} hold, and an active order that had not reserved yet got a release
 * intent that later rejected its real {@code OrderCreated}. Now every id is verified server-side
 * with order-service (a mock HTTP server here, answering to the administrator's own token) and
 * only {@code CANCELLED}/{@code REJECTED} orders are acted on.
 *
 * <p>"Zero effects" is checked on the database, not on a response: a fingerprint of every product
 * counter, every reservation row (status and {@code updated_at}), and the release intents and
 * outbox rows of the orders involved is taken before and compared after.
 */
class ReservationReconciliationVerificationTest extends InventoryIntegrationTest {

    private static final String REASON = "phase 2 verification";

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private OrderReleaseIntentRepository releaseIntents;

    @Autowired
    private InventorySagaTransactions inventorySaga;

    @Autowired
    private ReservationReconciliation reconciliation;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ active orders: refused, zero effects

    @Test
    void anActiveOrderHoldingReservedStockIsRefusedWithZeroEffects() throws Exception {
        for (String status : List.of(
                "PENDING", "STOCK_RESERVED", "PAYMENT_PENDING", "CONFIRMED", "CANCELLATION_PENDING")) {
            Product product = givenProduct("SKU-ACTIVE-" + status, 10);
            UUID orderId = leakedHold(product, 3);
            stubOrderStatus(orderId, status);
            String before = fingerprint(orderId);

            JsonNode entries = reconcile(adminToken(), List.of(orderId), REASON);

            assertThat(outcomeOf(entries, orderId)).as(status).isEqualTo("REFUSED_ACTIVE_ORDER");
            assertThat(fingerprint(orderId)).as("effects of reconciling a %s order", status).isEqualTo(before);
            assertThat(rowsOf(orderId)).singleElement()
                    .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RESERVED));
            assertStock(product, 7, 3);
            assertThat(releaseIntents.existsById(orderId)).isFalse();
            assertThat(outbox.findByAggregateIdOrderByCreatedAtAsc(orderId)).isEmpty();
        }
    }

    @Test
    void anActiveOrderThatHasNotReservedYetGetsNoTombstoneAndStillReservesNormally() throws Exception {
        Product product = givenProduct("SKU-NOT-YET", 10);
        UUID orderId = UUID.randomUUID();
        stubOrderStatus(orderId, "PENDING");
        String before = fingerprint(orderId);

        JsonNode entries = reconcile(adminToken(), List.of(orderId), REASON);

        assertThat(outcomeOf(entries, orderId)).isEqualTo("REFUSED_ACTIVE_ORDER");
        assertThat(fingerprint(orderId)).isEqualTo(before);
        assertThat(releaseIntents.existsById(orderId)).isFalse();

        // The proof that no intent was planted: the order's real OrderCreated arrives afterwards
        // and gets its stock.
        inventorySaga.onOrderCreated(UUID.randomUUID(), orderCreatedOf(orderId, product, 4));
        assertThat(rowsOf(orderId)).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RESERVED));
        assertThat(outboxTypes(orderId)).containsExactly("StockReserved");
        assertStock(product, 6, 4);
    }

    // ------------------------------------------------------------------ verified terminal orders: recovered once

    @Test
    void verifiedCancelledAndRejectedLeaksAreRecoveredOnceWithTheCallersOwnToken() throws Exception {
        Product product = givenProduct("SKU-RECOVER", 20);
        UUID cancelled = leakedHold(product, 5);
        UUID rejected = leakedHold(product, 4);
        stubOrderStatus(cancelled, "CANCELLED");
        stubOrderStatus(rejected, "REJECTED");
        assertStock(product, 11, 9);
        String token = adminToken();

        // Duplicate ids collapse to one lookup and one line; the report keeps first-occurrence order.
        JsonNode first = reconcile(token, List.of(rejected, cancelled, rejected), REASON);

        assertThat(first).hasSize(2);
        assertThat(first.get(0).get("orderId").asText()).isEqualTo(rejected.toString());
        assertThat(first.get(0).get("outcome").asText()).isEqualTo("RELEASED");
        assertThat(first.get(1).get("orderId").asText()).isEqualTo(cancelled.toString());
        assertThat(first.get(1).get("outcome").asText()).isEqualTo("RELEASED");
        assertStock(product, 20, 0);
        for (UUID orderId : List.of(cancelled, rejected)) {
            assertThat(rowsOf(orderId)).singleElement()
                    .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
            assertThat(releaseIntents.findById(orderId).orElseThrow().getReason()).isEqualTo(REASON);
        }
        assertThat(orderLookups()).hasSize(2)
                .extracting(OrderLookup::authorization).containsOnly("Bearer " + token);
        assertThat(orderLookups()).extracting(OrderLookup::orderId).containsExactly(rejected, cancelled);

        // Run again: asked again (nothing is cached), changes nothing.
        String settled = fingerprint(cancelled, rejected);
        JsonNode second = reconcile(token, List.of(cancelled, rejected), REASON);

        assertThat(outcomeOf(second, cancelled)).isEqualTo("ALREADY_RELEASED");
        assertThat(outcomeOf(second, rejected)).isEqualTo("ALREADY_RELEASED");
        assertThat(fingerprint(cancelled, rejected)).isEqualTo(settled);
        assertThat(orderLookups()).hasSize(4);
        assertStock(product, 20, 0);
    }

    @Test
    void aVerifiedCancelledOrderThatNeverReservedGetsItsIntentSoALateOrderCreatedIsRejected() throws Exception {
        Product product = givenProduct("SKU-NEVER", 10);
        UUID orderId = UUID.randomUUID();
        stubOrderStatus(orderId, "REJECTED");

        JsonNode entries = reconcile(adminToken(), List.of(orderId), REASON);

        assertThat(outcomeOf(entries, orderId)).isEqualTo("NO_RESERVATIONS");
        assertThat(releaseIntents.existsById(orderId)).isTrue();
        inventorySaga.onOrderCreated(UUID.randomUUID(), orderCreatedOf(orderId, product, 2));
        assertThat(rowsOf(orderId)).isEmpty();
        assertThat(outboxTypes(orderId)).containsExactly("StockRejected");
        assertStock(product, 10, 0);
    }

    // ------------------------------------------------------------------ committed sales: two defenses

    @Test
    void aCommittedSaleIsRefusedByOrderStateAndByTheCommittedGuard() throws Exception {
        Product product = givenProduct("SKU-SOLD", 10);
        UUID confirmed = leakedHold(product, 2);
        UUID cancelledAfterSale = leakedHold(product, 3);
        reservationService.commit(confirmed);
        reservationService.commit(cancelledAfterSale);
        stubOrderStatus(confirmed, "CONFIRMED");
        stubOrderStatus(cancelledAfterSale, "CANCELLED");
        String before = fingerprint(confirmed, cancelledAfterSale);

        JsonNode entries = reconcile(adminToken(), List.of(confirmed, cancelledAfterSale), REASON);

        // First defense: order-service says CONFIRMED, so nothing is even attempted.
        assertThat(outcomeOf(entries, confirmed)).isEqualTo("REFUSED_ACTIVE_ORDER");
        // Second defense: a cancelled order whose sale was never refunded still has COMMITTED rows.
        assertThat(outcomeOf(entries, cancelledAfterSale)).isEqualTo("SKIPPED_COMMITTED");
        assertThat(fingerprint(confirmed, cancelledAfterSale)).isEqualTo(before);
        for (UUID orderId : List.of(confirmed, cancelledAfterSale)) {
            assertThat(rowsOf(orderId)).singleElement()
                    .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.COMMITTED));
            assertThat(releaseIntents.existsById(orderId)).isFalse();
        }
        assertStock(product, 5, 0);
    }

    // ------------------------------------------------------------------ lookup failures fail closed

    @Test
    void everyLookupFailureAndDoubtfulAnswerFailsClosedWithZeroEffects() throws Exception {
        Product product = givenProduct("SKU-FAILCLOSED", 100);
        record Case(String label, Supplier<MockResponse> answer, String expected) {
        }
        List<Case> cases = new ArrayList<>();
        cases.add(new Case("404", () -> new MockResponse().setResponseCode(404), "REFUSED_ORDER_NOT_FOUND"));
        cases.add(new Case("401", () -> new MockResponse().setResponseCode(401), "REFUSED_ACCESS_DENIED"));
        cases.add(new Case("403", () -> new MockResponse().setResponseCode(403), "REFUSED_ACCESS_DENIED"));
        cases.add(new Case("500", () -> new MockResponse().setResponseCode(500),
                "REFUSED_VERIFICATION_UNAVAILABLE"));
        cases.add(new Case("503", () -> new MockResponse().setResponseCode(503),
                "REFUSED_VERIFICATION_UNAVAILABLE"));
        // (DISCONNECT_AFTER_REQUEST: the stub's custom dispatcher does not honour DISCONNECT_AT_START.)
        cases.add(new Case("hang-up", () -> new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST),
                "REFUSED_VERIFICATION_UNAVAILABLE"));
        for (String body : List.of(
                "{", "", "null", "[]", "{\"status\":\"CANCELLED\"}", "{\"id\":null,\"status\":\"CANCELLED\"}",
                "{\"id\":\"%s\",\"status\":\"CANCELLED\"}".formatted(UUID.randomUUID()),
                "{\"id\":\"$ID\",\"status\":\"cancelled\"}", "{\"id\":\"$ID\",\"status\":null}",
                "{\"id\":\"$ID\"}", "{\"id\":\"$ID\",\"status\":\"ARCHIVED\"}",
                "{\"id\":\"$ID\",\"status\":42}")) {
            cases.add(new Case("200 " + body, null, "REFUSED_UNVERIFIABLE"));
        }
        cases.add(new Case("302", () -> new MockResponse().setResponseCode(302)
                .setHeader("Location", "http://localhost:9/elsewhere"), "REFUSED_UNVERIFIABLE"));
        cases.add(new Case("204", () -> new MockResponse().setResponseCode(204), "REFUSED_UNVERIFIABLE"));

        for (Case failure : cases) {
            UUID orderId = leakedHold(product, 1);
            if (failure.answer() != null) {
                stubOrderAnswer(orderId, failure.answer());
            }
            else {
                String body = failure.label().substring("200 ".length()).replace("$ID", orderId.toString());
                stubOrderAnswer(orderId, () -> new MockResponse().setResponseCode(200)
                        .setHeader("Content-Type", "application/json").setBody(body));
            }
            String before = fingerprint(orderId);

            JsonNode entries = reconcile(adminToken(), List.of(orderId), REASON);

            assertThat(outcomeOf(entries, orderId)).as(failure.label()).isEqualTo(failure.expected());
            assertThat(fingerprint(orderId)).as("effects of <%s>", failure.label()).isEqualTo(before);
            assertThat(rowsOf(orderId)).singleElement()
                    .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RESERVED));
            assertThat(releaseIntents.existsById(orderId)).isFalse();
            assertThat(outbox.findByAggregateIdOrderByCreatedAtAsc(orderId)).isEmpty();
        }
        assertStock(product, 100 - cases.size(), cases.size());
    }

    @Test
    void aLookupThatTimesOutFailsClosedWithinTheBoundAndChangesNothing() throws Exception {
        Product product = givenProduct("SKU-TIMEOUT", 10);
        UUID orderId = leakedHold(product, 3);
        stubOrderAnswer(orderId, () -> new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
        String before = fingerprint(orderId);
        long started = System.nanoTime();

        JsonNode entries = reconcile(adminToken(), List.of(orderId), REASON);

        assertThat(outcomeOf(entries, orderId)).isEqualTo("REFUSED_VERIFICATION_UNAVAILABLE");
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
        assertThat(fingerprint(orderId)).isEqualTo(before);
        assertStock(product, 7, 3);
    }

    @Test
    void aBatchStopsAskingAfterConsecutiveFailuresAndALaterRunCompletesIt() throws Exception {
        Product product = givenProduct("SKU-BATCH", 50);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            UUID orderId = leakedHold(product, 1);
            ids.add(orderId);
            stubOrderAnswer(orderId, () -> new MockResponse().setResponseCode(503));
        }
        String before = fingerprint(ids.toArray(UUID[]::new));
        assertThat(ReservationReconciliation.LOOKUP_FAILURE_LIMIT).isEqualTo(3);

        JsonNode entries = reconcile(adminToken(), ids, REASON);

        for (int i = 0; i < ids.size(); i++) {
            assertThat(outcomeOf(entries, ids.get(i))).as("entry %d", i).isEqualTo(
                    i < 3 ? "REFUSED_VERIFICATION_UNAVAILABLE" : "SKIPPED_VERIFICATION_UNAVAILABLE");
        }
        // The dependency was asked three times, not eight.
        assertThat(orderLookups()).hasSize(3);
        assertThat(fingerprint(ids.toArray(UUID[]::new))).isEqualTo(before);

        // Once order-service answers again the same list completes — the call is repeatable.
        ids.forEach(orderId -> stubOrderStatus(orderId, "CANCELLED"));
        JsonNode retry = reconcile(adminToken(), ids, REASON);
        ids.forEach(orderId -> assertThat(outcomeOf(retry, orderId)).isEqualTo("RELEASED"));
        assertStock(product, 50, 0);
    }

    @Test
    void aDecisiveAnswerResetsTheFailureCountSoScatteredFailuresDoNotAbortTheBatch() throws Exception {
        Product product = givenProduct("SKU-SCATTERED", 50);
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID orderId = leakedHold(product, 1);
            ids.add(orderId);
            if (i == 2 || i == 5) {
                stubOrderStatus(orderId, "CANCELLED");
            }
            else {
                stubOrderAnswer(orderId, () -> new MockResponse().setResponseCode(503));
            }
        }

        JsonNode entries = reconcile(adminToken(), ids, REASON);

        List<String> outcomes = ids.stream().map(id -> outcomeOf(entries, id)).collect(Collectors.toList());
        assertThat(outcomes).containsExactly(
                "REFUSED_VERIFICATION_UNAVAILABLE", "REFUSED_VERIFICATION_UNAVAILABLE", "RELEASED",
                "REFUSED_VERIFICATION_UNAVAILABLE", "REFUSED_VERIFICATION_UNAVAILABLE", "RELEASED");
        assertThat(orderLookups()).hasSize(6);
        assertStock(product, 46, 4);
    }

    // ------------------------------------------------------------------ access and validation

    @Test
    void aPlainUserAndAnAnonymousCallerAreRefusedBeforeAnyLookup() throws Exception {
        Product product = givenProduct("SKU-ACCESS", 10);
        UUID orderId = leakedHold(product, 2);
        stubOrderStatus(orderId, "CANCELLED");
        String before = fingerprint(orderId);
        String body = json.writeValueAsString(new ReconciliationRequest(List.of(orderId), REASON));

        mvc.perform(post("/api/inventory/reservations/reconcile")
                        .header("Authorization", "Bearer " + userToken())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/inventory/reservations/reconcile")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/inventory/reservations/reconcile")
                        .header("Authorization", "Bearer " + tokenSignedByAnotherKey())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());

        assertThat(orderLookups()).isEmpty();
        assertThat(fingerprint(orderId)).isEqualTo(before);
    }

    @Test
    void nullAndMalformedIdsAreRejectedCleanlyBeforeAnyWork() throws Exception {
        UUID real = UUID.randomUUID();
        stubOrderStatus(real, "CANCELLED");
        for (String body : List.of(
                "{\"orderIds\":[null]}",
                "{\"orderIds\":[\"%s\",null]}".formatted(real),
                "{\"orderIds\":[null,\"%s\"]}".formatted(real))) {
            mvc.perform(post("/api/inventory/reservations/reconcile")
                            .header("Authorization", "Bearer " + adminToken())
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.message").value("Request validation failed"))
                    .andExpect(jsonPath("$.fieldErrors").isNotEmpty());
        }
        mvc.perform(post("/api/inventory/reservations/reconcile")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"orderIds\":[\"not-a-uuid\"]}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/inventory/reservations/reconcile")
                        .header("Authorization", "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(orderLookups()).isEmpty();
        assertThat(releaseIntents.existsById(real)).isFalse();
    }

    @Test
    void theServiceGuardsItsOwnBoundaryForDirectCallers() {
        UUID real = UUID.randomUUID();
        stubOrderStatus(real, "CANCELLED");
        String token = adminToken();
        List<UUID> tooMany = IntStream.range(0, ReservationReconciliation.MAX_ORDER_IDS + 1)
                .mapToObj(i -> UUID.randomUUID()).toList();

        assertThatThrownBy(() -> reconciliation.recover(Arrays.asList(real, null), REASON, token))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> reconciliation.recover(null, REASON, token))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> reconciliation.recover(List.of(), REASON, token))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> reconciliation.recover(tooMany, REASON, token))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> reconciliation.recover(List.of(real), REASON, null))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> reconciliation.recover(List.of(real), REASON, "  "))
                .isInstanceOf(BadRequestException.class);

        assertThat(orderLookups()).isEmpty();
        assertThat(releaseIntents.existsById(real)).isFalse();
    }

    // ------------------------------------------------------------------ serialization and idempotency

    @Test
    void concurrentIdenticalRequestsReleaseTheHoldExactlyOnce() throws Exception {
        Product product = givenProduct("SKU-DUP-RUN", 10);
        UUID orderId = leakedHold(product, 4);
        stubOrderStatus(orderId, "CANCELLED");
        String token = adminToken();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<String> outcomes = new ArrayList<>();
        try {
            List<Future<ReconciliationOutcome>> runs = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                runs.add(pool.submit(() -> {
                    start.await();
                    List<ReservationReconciliation.ReconciliationLine> lines =
                            reconciliation.recover(List.of(orderId, orderId), REASON, token);
                    assertThat(lines).hasSize(1);
                    return lines.get(0).outcome();
                }));
            }
            start.countDown();
            for (Future<ReconciliationOutcome> run : runs) {
                outcomes.add(run.get(60, TimeUnit.SECONDS).name());
            }
        }
        finally {
            pool.shutdownNow();
        }

        assertThat(outcomes).containsExactlyInAnyOrder("RELEASED", "ALREADY_RELEASED");
        assertStock(product, 10, 0);
        assertThat(rowsOf(orderId)).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.RELEASED));
    }

    @Test
    void aCommitRacingTheReconciliationOfAnActiveOrderIsNeverOverruledByIt() throws Exception {
        Product product = givenProduct("SKU-RACE-COMMIT", 10);
        UUID orderId = leakedHold(product, 3);
        stubOrderStatus(orderId, "PAYMENT_PENDING");
        String token = adminToken();
        CountDownLatch start = new CountDownLatch(1);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<List<ReservationReconciliation.ReconciliationLine>> run = pool.submit(() -> {
                start.await();
                return reconciliation.recover(List.of(orderId), REASON, token);
            });
            Future<Boolean> commit = pool.submit(() -> {
                start.await();
                inventorySaga.onCommitStock(UUID.randomUUID(), new CommitStock(orderId));
                return true;
            });
            start.countDown();

            assertThat(run.get(60, TimeUnit.SECONDS)).singleElement()
                    .satisfies(line -> assertThat(line.outcome()).isEqualTo(ReconciliationOutcome.REFUSED_ACTIVE_ORDER));
            commit.get(60, TimeUnit.SECONDS);
        }
        finally {
            pool.shutdownNow();
        }

        // However the two interleaved, the sale stands: committed, not released, no intent.
        assertThat(rowsOf(orderId)).singleElement()
                .satisfies(row -> assertThat(row.getStatus()).isEqualTo(ReservationStatus.COMMITTED));
        assertThat(releaseIntents.existsById(orderId)).isFalse();
        assertStock(product, 7, 0);
    }

    @Test
    void aVerifiedRecoveryRacingALateOrderCreatedNeverLeavesAHoldBehindTheIntent() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 5; round++) {
                Product product = givenProduct("SKU-RACE-LATE-" + round, 6);
                UUID orderId = UUID.randomUUID();
                stubOrderStatus(orderId, "CANCELLED");
                String token = adminToken();
                CountDownLatch start = new CountDownLatch(1);

                Future<List<ReservationReconciliation.ReconciliationLine>> run = pool.submit(() -> {
                    start.await();
                    return reconciliation.recover(List.of(orderId), REASON, token);
                });
                Future<Boolean> created = pool.submit(() -> {
                    start.await();
                    inventorySaga.onOrderCreated(UUID.randomUUID(), orderCreatedOf(orderId, product, 3));
                    return true;
                });
                start.countDown();
                ReconciliationOutcome outcome = run.get(60, TimeUnit.SECONDS).get(0).outcome();
                created.get(60, TimeUnit.SECONDS);

                // Recovery first: no rows yet, intent recorded, the OrderCreated is rejected.
                // OrderCreated first: the hold exists, and the recovery then releases it.
                assertThat(outcome).isIn(ReconciliationOutcome.NO_RESERVATIONS, ReconciliationOutcome.RELEASED);
                assertThat(releaseIntents.existsById(orderId)).isTrue();
                assertThat(rowsOf(orderId)).noneMatch(row -> row.getStatus() == ReservationStatus.RESERVED);
                assertStock(product, 6, 0);
            }
        }
        finally {
            pool.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ helpers

    /** A hold taken through the live path and never given back: the leak the tool exists for. */
    private UUID leakedHold(Product product, int quantity) {
        UUID orderId = UUID.randomUUID();
        ReservationResult result = reservationService.reserve(orderId, List.of(item(product, quantity)));
        assertThat(result.outcome()).isEqualTo(ReservationResult.Outcome.RESERVED);
        return orderId;
    }

    private JsonNode reconcile(String token, List<UUID> orderIds, String reason) throws Exception {
        String body = mvc.perform(post("/api/inventory/reservations/reconcile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new ReconciliationRequest(orderIds, reason))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).get("entries");
    }

    private static String outcomeOf(JsonNode entries, UUID orderId) {
        for (JsonNode entry : entries) {
            if (orderId.toString().equals(entry.get("orderId").asText())) {
                return entry.get("outcome").asText();
            }
        }
        throw new AssertionError("No entry for order " + orderId + " in " + entries);
    }

    /**
     * Every product counter and version, every reservation row with its status and
     * {@code updated_at}, plus the release intents and outbox rows of the named orders.
     */
    private String fingerprint(UUID... orderIds) {
        String ids = Arrays.stream(orderIds).map(UUID::toString).collect(Collectors.joining(","));
        return jdbc.queryForObject("""
                SELECT coalesce(string_agg(x, '|' ORDER BY x), '') FROM (
                    SELECT 'p:' || id || ':' || available_quantity || ':' || reserved_quantity || ':' || version AS x
                      FROM products
                    UNION ALL
                    SELECT 'r:' || id || ':' || status || ':' || updated_at FROM stock_reservations
                    UNION ALL
                    SELECT 'i:' || order_id || ':' || reason FROM order_release_intents
                     WHERE order_id::text = ANY (string_to_array(?, ','))
                    UNION ALL
                    SELECT 'o:' || id FROM outbox_events
                     WHERE aggregate_id::text = ANY (string_to_array(?, ','))
                ) t
                """, String.class, ids, ids);
    }

    private List<StockReservation> rowsOf(UUID orderId) {
        return reservations.findByOrderIdOrderByCreatedAtAsc(orderId);
    }

    private List<String> outboxTypes(UUID orderId) {
        return outbox.findByAggregateIdOrderByCreatedAtAsc(orderId).stream()
                .map(row -> row.getEventType())
                .toList();
    }

    private void assertStock(Product product, int available, int reserved) {
        Product reloaded = products.findById(product.getId()).orElseThrow();
        assertThat(reloaded.getAvailableQuantity()).as("available of %s", product.getSku()).isEqualTo(available);
        assertThat(reloaded.getReservedQuantity()).as("reserved of %s", product.getSku()).isEqualTo(reserved);
    }

    private static OrderItem item(Product product, int quantity) {
        return new OrderItem(product.getId(), product.getSku(), quantity, product.getPrice());
    }

    private static OrderCreated orderCreatedOf(UUID orderId, Product product, int quantity) {
        return new OrderCreated(orderId, "ORD-VERIFY", UUID.randomUUID(),
                product.getPrice().multiply(BigDecimal.valueOf(quantity)), "EUR", List.of(item(product, quantity)));
    }
}
