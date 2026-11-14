# Order Processing Microservices

[![CI](https://github.com/besko2002/projects/actions/workflows/order-processing-microservices.yml/badge.svg)](https://github.com/besko2002/projects/actions/workflows/order-processing-microservices.yml)

An event-driven e-commerce order flow built as **six Spring Boot microservices** that talk through
**Kafka**. An order is placed, stock is reserved, payment is taken and the customer is notified —
and when any step fails, the system **compensates automatically** (orchestrated Saga).

Built to practise the patterns real distributed systems need: **Saga with compensation,
Transactional Outbox, idempotent consumers, Dead Letter Topics, database-per-service, an API
gateway with JWT and rate limiting, circuit breakers, and end-to-end correlation ids.**

## Architecture

```
                 Client (JWT)
                     │
             ┌───────▼────────┐  Spring Cloud Gateway: routing, JWT validation (JWKS),
             │  API Gateway   │  Redis rate limiting, correlation id, CORS
             └──┬───┬───┬───┬─┘
       REST     │   │   │   │
   ┌────────────▼┐ ┌▼───┴───▼──────┐ ┌──────────────┐
   │ auth-service│ │ order-service │ │  inventory   │──┐
   │ RS256 JWT + │ │ Saga          │ │  -service    │  │
   │ JWKS        │ │ orchestrator  │ └──────▲───────┘  │
   └─────────────┘ └──┬─────▲──────┘        │          │
                      │     │   Kafka topics (events + commands, keyed by orderId)
      ────────────────▼─────┴───────────────┴──────────▼────────────────
                      │     ▲                          │
             ┌────────▼─────┴┐               ┌─────────▼────────────┐
             │payment-service│               │ notification-service │──► email (MailHog)
             └───────────────┘               └──────────────────────┘

   Each service owns its own PostgreSQL database (auth_db, order_db, inventory_db, payment_db,
   notification_db). No service reads another service's tables.
```

| Module | Port | Responsibility |
|---|---|---|
| `api-gateway` | 8080 | Single entry point: routes, JWT check, rate limiting (Redis), correlation id |
| `auth-service` | 8081 | Register / login, issues RS256 JWTs, publishes the JWKS |
| `order-service` | 8082 | Orders, idempotent checkout, **Saga orchestrator**, timeout sweeper, tracked refunds |
| `inventory-service` | 8083 | Product catalogue, stock reservations (all-or-nothing, no overselling), durable release intents, operator reconciliation |
| `payment-service` | 8084 | **Simulated** payment provider: authorize, decline, refund |
| `notification-service` | 8085 | Emails the customer when an order is confirmed / cancelled / rejected |
| `common-events` | – | Event & command records, topic names, header names |
| `service-support` | – | Shared auto-configuration: security, errors, correlation id, outbox, idempotency, DLT, Swagger |

## The order Saga

```
POST /api/orders ─► order PENDING ─► OrderCreated ─► inventory reserves stock
                                                      │   (refuses if a release for this order
                                                      │    was already recorded → StockRejected)
                                                      ├─ StockRejected ─► order REJECTED
                                                      └─ StockReserved ─► ProcessPayment ─► payment (simulated)
                                                                           ├─ PaymentCompleted ─► CONFIRMED + CommitStock
                                                                           └─ PaymentFailed ─► ReleaseStock ─► CANCELLED
POST /api/orders/{id}/cancel (in flight) ─► ReleaseStock + CANCELLED, refund only if payment_id is known
POST /api/orders/{id}/cancel (CONFIRMED) ─► RefundPayment + ReleaseStock ─► CANCELLATION_PENDING ─► PaymentRefunded ─► CANCELLED
Late PaymentCompleted (the cancel/timeout beat it) ─► RefundPayment queued + orders.refund_status NONE→REQUESTED
                                                      in one transaction ─► PaymentRefunded ─► REFUNDED
Late StockReserved for a cancelled order ─► reservation remembered + ReleaseStock re-asserted
Orders stuck longer than ORDER_SAGA_TIMEOUT are cancelled with compensation by a sweeper. The same
tick runs a second, refund-only scan: a refund whose command was lost is re-commanded when it has
gone unanswered for ORDER_SAGA_REFUND_RETRY_INTERVAL, measured from the last attempt
(orders.refund_retry_at, booked atomically with each command) — an order that is already cancelled
or cancelling is not re-cancelled, re-announced or re-confirmed by that scan, only the money is
re-asked for. That scan is the only thing that retries a refund: the timeout scan skips every order
with an outstanding refund, so the two settings are independent (the retry interval may be longer
than the order timeout).
```

`orders.refund_status` (`NONE → REQUESTED → REFUNDED`, migration `V5__refund_tracking.sql`) tracks a
refund independently of the order status: a cancelled order is final, but a charge that completed
while it was cancelling still owes the customer their money. Every `RefundPayment` command and its
flag flip commit together, so "refund requested" is never a lie, duplicates queue no second command,
and the eventual `PaymentRefunded` closes the flag exactly once. `orders.refund_retry_at`
(migration `V6__refund_recovery.sql`) books each attempt so the recovery sweep re-commands
unanswered refunds on a deliberate interval instead of every tick.

| Topic | Messages | Producer → Consumer |
|---|---|---|
| `order.events` | OrderCreated, OrderConfirmed, OrderCancelled, OrderRejected | order → inventory, notification |
| `inventory.events` | StockReserved, StockRejected | inventory → order |
| `inventory.commands` | CommitStock, ReleaseStock | order → inventory |
| `payment.commands` | ProcessPayment, RefundPayment | order → payment |
| `payment.events` | PaymentCompleted, PaymentFailed, PaymentRefunded | payment → order |
| `<topic>.DLT` | messages that could not be processed | any consumer → operator |

## Reliability patterns

- **Transactional Outbox** — every outgoing message is written to an `outbox_events` table in the
  same database transaction as the state change, then published by a relay
  (`SELECT … FOR UPDATE SKIP LOCKED`, safe with several instances; `acks=all`, idempotent producer,
  per-order ordering). No lost events, no events for rolled-back changes.
- **Idempotent consumers** — each consumer records the event id in `processed_events` in the same
  transaction as its work, so Kafka redeliveries are no-ops.
- **Dead Letter Topics** — 3 retries with exponential backoff, then `<topic>.DLT`; malformed or
  unknown messages go to the DLT immediately. Consumers keep running.
- **No overselling** — stock rows are locked in id order (deadlock-free) with optimistic-locking
  retry; a 20-thread / 10-unit concurrency test proves exactly 10 reservations succeed.
- **No late-order reservations** — every `ReleaseStock` leaves a durable row in
  `order_release_intents` (migration `V5__order_release_intents.sql`), even when nothing was
  reserved yet, and every `OrderCreated` checks it before reserving. Per-order Postgres advisory
  locks (`pg_advisory_xact_lock`) serialize `OrderCreated` / `ReleaseStock` / `CommitStock` /
  reconciliation inside the database, so a release that overtakes its reservation on a different
  Kafka topic can never be forgotten, and the saga re-releases a reservation that escapes its own
  cancellation. The lock and the intent are enforced in the shared reserve/release/commit
  primitives (not only in the Kafka handlers), so a direct `StockReservationService` call gets the
  same guarantees as a message.
- **Resilience** — order-service prices items through inventory with a Resilience4j retry +
  circuit breaker; when inventory is down the API answers `503` with `Retry-After`.
- **Idempotent checkout** — `Idempotency-Key` header: a replay returns the same order, a different
  body with the same key is `409`. The gateway's CORS allow-list names the header, so browser
  pre-flights accept it (see `SecurityConfig` of the gateway).
- **Defense in depth** — the gateway validates the JWT, and every service validates it again.
  Client-supplied `X-User-Id` / `X-User-Roles` headers are stripped at the edge.
- **Correlation id** — `X-Correlation-Id` travels from the HTTP request through every Kafka hop
  (headers) and appears in every service's log line.

## Observability

Metrics, traces and logs are wired into every service:

- **Metrics** — Micrometer → Prometheus (`/actuator/prometheus`): HTTP rate / p95 latency / errors,
  Kafka messages consumed, processing time and consumer lag, JVM heap, DB connections.
- **Distributed tracing** — Micrometer Tracing + OpenTelemetry → **Jaeger**. The trace context is
  stored with each outbox row and sent as a W3C `traceparent` Kafka header, so **one trace follows an
  order across all services, through Kafka and the outbox** (gateway → order → inventory → payment →
  notification).
- **Logs** — every line carries the correlation id and the trace id:
  `[order-service,<correlationId>,<traceId>]`.
- **Grafana** — a provisioned "Order Processing — Overview" dashboard (opens as the home page).

Tracing itself — the trace id in every log line, the `traceparent` context across Kafka and the
outbox — is always on. Only the **export** of finished spans to Jaeger can be switched, with
`OTLP_TRACING_EXPORT_ENABLED` (Spring Boot's `management.otlp.tracing.export.enabled`):

| Services run… | Default | Why |
|---|---|---|
| on the host | `true` (spans go to `localhost:4318`) | Jaeger publishes that port when the `observability` profile is up |
| in Docker (`--profile app`) | `false` | Jaeger is not part of that profile, and an exporter with no collector logs `ERROR … Failed to export spans` on every batch |

```bash
# Grafana :3000, Prometheus :9090, Jaeger :16686 — services running on the host export to it by default
docker compose --profile observability up -d --build

# Services in Docker + observability: switch the export on so the containers' spans reach Jaeger
OTLP_TRACING_EXPORT_ENABLED=true docker compose --profile app --profile observability up -d --build
```

Without `OTLP_TRACING_EXPORT_ENABLED=true` the second command still starts Jaeger, Prometheus and
Grafana (metrics work), but the containers send no spans. Compose recreates the six service
containers when the variable changes; put it in `.env` to make it permanent.

**One order traced across five services (Jaeger)**

![Jaeger trace of one order](docs/screenshots/jaeger-trace.png)

**Grafana dashboard under load**

![Grafana dashboard](docs/screenshots/grafana-dashboard.png)

## Tech stack

Java 21 · Spring Boot 3.5 · Spring Cloud Gateway 2025.0 · Spring Kafka (Kafka 4, KRaft) ·
Spring Data JPA · PostgreSQL 16 · Micrometer · OpenTelemetry · Prometheus · Grafana · Jaeger · Flyway · Redis · Spring Security OAuth2 Resource Server (RS256 /
JWKS) · Resilience4j · springdoc-openapi · Testcontainers · Docker Compose · Maven multi-module.

## Run it

Requirements: JDK 21 and Docker (sized for Docker Desktop with ~4 GB).

**Everything in Docker:**

```bash
docker compose --profile app up -d --build
```

This starts no Jaeger and exports no spans, so the logs carry no exporter errors (trace ids are
still in every line). To trace into Jaeger as well, see [Observability](#observability).

**Infrastructure in Docker, services on the host** (handy while developing):

```bash
docker compose up -d --build --wait                 # Postgres, Kafka, Redis, MailHog
./mvnw -DskipTests install
java -jar auth-service/target/auth-service-0.0.1-SNAPSHOT.jar &
INVENTORY_SEED_DEMO=true java -jar inventory-service/target/inventory-service-0.0.1-SNAPSHOT.jar &
java -jar order-service/target/order-service-0.0.1-SNAPSHOT.jar &
java -jar payment-service/target/payment-service-0.0.1-SNAPSHOT.jar &
java -jar notification-service/target/notification-service-0.0.1-SNAPSHOT.jar &
java -jar api-gateway/target/api-gateway-0.0.1-SNAPSHOT.jar &
```

`--build` matters for Postgres: the per-service databases and their owners are created by the
bootstrap script **baked into that image** (`infra/postgres/init/`), so a changed script needs a
rebuilt image — and the script itself only ever runs on an empty volume.

Services on the host export spans to `localhost:4318` (Jaeger, `--profile observability`). Without
Jaeger, start them with `OTLP_TRACING_EXPORT_ENABLED=false` to keep exporter errors out of the logs.

| What | Where |
|---|---|
| API (always through the gateway) | http://localhost:8080 |
| Swagger UI of a service | http://localhost:8081..8085/swagger-ui.html |
| Emails (MailHog) | http://localhost:8025 |
| Grafana / Prometheus / Jaeger | http://localhost:3000 · :9090 · :16686 (profile `observability`) |

Host ports avoid common defaults: Postgres **5435**, Kafka **9094**, Redis **6380**.

Every published port is bound to **127.0.0.1** by default (`BIND_ADDRESS` in `.env`), so a
development stack is not reachable from the network — the containers talk to each other over the
compose network by service name and never need the host interface. Both workflows above work
unchanged; only the host's own access is affected, and it still goes through `localhost`. Before
putting this anywhere on a network, read [Before deploying on a network](#before-deploying-on-a-network).

## What is simulated

Two integrations here are simulations, and the API says so in every response:

- **Payments.** `payment-service` has no acquirer behind it. A "charge" only writes a row in its own
  `payments` table and a "refund" only flips that row; provider references start with `SIM-` and
  every payment response carries `"simulated": true`. No money moves, no card details exist, and
  none of this is correct for real payments.
- **E-mail.** `notification-service` hands the message to a local SMTP sink (MailHog in
  development). The recipient is a synthetic address built from the customer id
  (`<customerId>@customers.orders.local`) — never a real inbox — and every notification response
  carries `"simulated": true`. `status: SENT` means the sink accepted the message, not that
  anybody received it.

There are no real customers, no real orders and no real payment account in this project. The demo
catalog is fictional (`DEMO-*` products) and lives in a local database.

## Try the flow

The demo catalog is seeded with fictional products (`INVENTORY_SEED_DEMO=true`, the default under
docker compose). Pick the ids you need from the catalogue:

```bash
# register and keep the token
TOKEN=$(curl -s localhost:8080/api/auth/register -H 'Content-Type: application/json' \
  -d '{"name":"Ada","email":"ada@example.com","password":"Password123"}' | jq -r .accessToken)

# the catalogue (the default seed contains seven fictional products)
PRODUCTS=$(curl -fsS localhost:8080/api/products)
printf '%s\n' "$PRODUCTS" | jq '.content[] | {id, sku, price, availableQuantity}'
HEADSET_ID=$(printf '%s\n' "$PRODUCTS" | jq -er '.content[] | select(.sku == "DEMO-HEADSET-PRO") | .id')
SPEAKER_ID=$(printf '%s\n' "$PRODUCTS" | jq -er '.content[] | select(.sku == "DEMO-LIMITED-SPEAKER") | .id')
MONITOR_ID=$(printf '%s\n' "$PRODUCTS" | jq -er '.content[] | select(.sku == "DEMO-PREMIUM-MONITOR") | .id')
KEYBOARD_ID=$(printf '%s\n' "$PRODUCTS" | jq -er '.content[] | select(.sku == "DEMO-KEYBOARD-87") | .id')

# Saga steps run asynchronously. Wait for an expected final state instead of reading too soon.
wait_for_order() {
  order_id=$1 expected=$2
  for attempt in $(seq 1 30); do
    status=$(curl -fsS "localhost:8080/api/orders/$order_id" \
      -H "Authorization: Bearer $TOKEN" | jq -r .status) || return 1
    if [ "$status" = "$expected" ]; then printf '%s\n' "$status"; return 0; fi
    sleep 1
  done
  printf 'Order %s did not reach %s (last status: %s)\n' "$order_id" "$expected" "$status" >&2
  return 1
}
```

**1. A successful order** — `DEMO-HEADSET-PRO` (199.99, under the 1000.00 decline threshold):

```bash
ORDER_OK=$(curl -s localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: checkout-1' \
  -d "{\"items\":[{\"productId\":\"$HEADSET_ID\",\"quantity\":1}]}" | jq -r .id)

wait_for_order "$ORDER_OK" CONFIRMED
# CONFIRMED
curl -s localhost:8080/api/payments/order/$ORDER_OK -H "Authorization: Bearer $TOKEN" | jq '{status, simulated}'
# { "status": "AUTHORIZED", "simulated": true }
```

**2. Not enough stock** — `DEMO-LIMITED-SPEAKER` has 2 units; order 3:

```bash
ORDER_NOSTOCK=$(curl -s localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: checkout-2' \
  -d "{\"items\":[{\"productId\":\"$SPEAKER_ID\",\"quantity\":3}]}" | jq -r .id)

wait_for_order "$ORDER_NOSTOCK" REJECTED
# REJECTED — nothing was reserved
```

**3. A declined simulated payment** — `DEMO-PREMIUM-MONITOR` (1499.00, above the threshold):

```bash
ORDER_DECLINED=$(curl -s localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: checkout-3' \
  -d "{\"items\":[{\"productId\":\"$MONITOR_ID\",\"quantity\":1}]}" | jq -r .id)

wait_for_order "$ORDER_DECLINED" CANCELLED
# CANCELLED — the simulated provider declined, so the saga released the stock again
curl -s localhost:8080/api/payments/order/$ORDER_DECLINED -H "Authorization: Bearer $TOKEN" | jq .status
# FAILED
```

**4. Cancellation** — request cancellation of a fresh keyboard order. The saga may finish before
this request arrives; cancelling a confirmed order also requests a simulated refund:

```bash
ORDER_CANCEL=$(curl -s localhost:8080/api/orders -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' -H 'Idempotency-Key: checkout-4' \
  -d "{\"items\":[{\"productId\":\"$KEYBOARD_ID\",\"quantity\":1}]}" | jq -r .id)

curl -s -X POST localhost:8080/api/orders/$ORDER_CANCEL/cancel -H "Authorization: Bearer $TOKEN"
wait_for_order "$ORDER_CANCEL" CANCELLED
```

**5. Refund compensation** — cancel the **confirmed** order from step 1 (`$ORDER_OK`, not the one
from step 4): the saga asks the simulated provider to give the money back and releases the reserved
stock.

```bash
curl -s -X POST localhost:8080/api/orders/$ORDER_OK/cancel -H "Authorization: Bearer $TOKEN"

wait_for_order "$ORDER_OK" CANCELLED
# CANCELLED
curl -s localhost:8080/api/payments/order/$ORDER_OK -H "Authorization: Bearer $TOKEN" | jq '{status, simulated}'
# { "status": "REFUNDED", "simulated": true }
```

Each scenario keeps its own order id (`$ORDER_OK`, `$ORDER_NOSTOCK`, `$ORDER_DECLINED`,
`$ORDER_CANCEL`), so the refund in step 5 provably targets the order that was confirmed in step 1.
Re-running a step reuses its `Idempotency-Key`, which is the point of that header: the same key
returns the same order instead of creating a second one.

The mails that went to the local sink (not to anybody):

```bash
curl -s localhost:8080/api/notifications/me -H "Authorization: Bearer $TOKEN" | jq '.content[] | {type, recipient, status, simulated}'
```

A seeded admin (`admin@orders.local` / `Admin@LocalDevOnly`, change it with `AUTH_ADMIN_*`) can
manage products under `/api/inventory/**`. That password is used when the account is *created* —
an existing database keeps the password it was seeded with, so set `AUTH_ADMIN_PASSWORD` before the
first start of a fresh environment.

## API overview (through the gateway)

| Method & path | Access |
|---|---|
| `POST /api/auth/register`, `POST /api/auth/login` | public |
| `GET /api/auth/me` | user |
| `GET /api/products`, `GET /api/products/{id}` | public |
| `POST/PUT/PATCH/DELETE /api/inventory/products/**` | admin |
| `POST /api/inventory/reservations/reconcile` | admin (operator runbook below; verifies each order with order-service) |
| `GET /api/inventory/reservations?orderId=` | admin |
| `POST /api/orders` (optional `Idempotency-Key`) | user |
| `GET /api/orders/me`, `GET /api/orders/{id}`, `POST /api/orders/{id}/cancel` | owner or admin |
| `GET /api/orders/{id}/events` (events and commands the order emitted: topic, timing, trace id; no payloads) | owner or admin |
| `GET /api/orders` | admin |
| `GET /api/payments/order/{orderId}` | owner or admin |
| `GET /api/notifications/me` | user |

## Configuration

Every setting has a working local default and can be overridden by an environment variable; the
full list with explanations is in [`.env.example`](.env.example). Notable ones:
`AUTH_JWT_PRIVATE_KEY` / `AUTH_JWT_PUBLIC_KEY` (otherwise an ephemeral key pair is generated at
startup), `PAYMENT_DECLINE_ABOVE` (default 1000.00), `ORDER_SAGA_TIMEOUT` (default 2 minutes),
`ORDER_SAGA_REFUND_RETRY_INTERVAL` (default 30 seconds),
`RATE_LIMIT_REPLENISH_RATE` / `RATE_LIMIT_BURST_CAPACITY`, `INVENTORY_SEED_DEMO`,
`NOTIFICATION_RECIPIENT_DOMAIN` (demo e-mail domain, see below).

**Schema changes:** both new migrations (`order_db` → `V5__refund_tracking.sql` and
`V6__refund_recovery.sql`,
`inventory_db` → `V5__order_release_intents.sql`) are plain additive changes and are applied by
Flyway at startup, in place: columns with safe defaults (`'NONE'`; `refund_retry_at` is backfilled
from `updated_at` for refunds already requested), one check constraint, three indexes and one new
table. No data migration is needed — rows already in the database get a refund
status that matches the old behaviour (no outstanding refund), and orders that were cancelled
before the fix can be recovered with the runbook below.

### Recovering reservations leaked before the release-intent fix (operator runbook)

Deployments older than `V5__order_release_intents.sql` can have cancelled orders that still hold
stock (a release that raced a reserve lost the race, invisibly). To recover them:

```sql
-- 1) optional: list candidates in the order-service database. This is only a way to find ids; it
--    is NOT what authorizes the release (see below).
SELECT id, status, updated_at FROM orders WHERE status IN ('CANCELLED', 'REJECTED') ORDER BY updated_at DESC;
-- orders with rows in inventory_db.stock_reservations that are still RESERVED are the leak
```

```bash
# 2) hand the ids to inventory-service — explicit ids only, at most 200 per call, no null ids;
#    one entry per distinct order in the response tells you what happened to it
curl -X POST http://localhost:8080/api/inventory/reservations/reconcile \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"orderIds":["<id1>","<id2>"],"reason":"incident 1234 cleanup"}'
# acted on (the order is CANCELLED/REJECTED):  RELEASED | ALREADY_RELEASED | NO_RESERVATIONS | SKIPPED_COMMITTED
# refused, nothing touched: REFUSED_ACTIVE_ORDER | REFUSED_ORDER_NOT_FOUND | REFUSED_ACCESS_DENIED |
#                           REFUSED_UNVERIFIABLE | REFUSED_VERIFICATION_UNAVAILABLE |
#                           SKIPPED_VERIFICATION_UNAVAILABLE
```

**The server verifies, the operator does not.** For every id, inventory-service asks order-service
(`GET /api/orders/{id}`, at `ORDER_BASE_URL`, `http://localhost:8082` by default and
`http://order-service:8082` under docker compose) with *your own* administrator token, forwarded
as-is and never logged — there is no service credential and no extra privileged endpoint. It acts
only when the answer is a well-formed `200` for that exact order whose status is `CANCELLED` or
`REJECTED` (both immutable). An order in any other state (`PENDING`, `STOCK_RESERVED`,
`PAYMENT_PENDING`, `CONFIRMED`, `CANCELLATION_PENDING`) is refused, and so is anything doubtful:
`404`, `401`/`403`, a timeout, a `5xx`, an unreachable order-service, a malformed or oversized body,
another order's id, an unknown or missing status, a redirect. A refused id has **no effect at all**:
no stock counter, reservation row, release intent or outbox row changes — so a typo'd id of an
active order cannot release its stock or poison it with a tombstone. Each call has a 1 s connect and
2 s read timeout (`ORDER_CONNECT_TIMEOUT_MS`, `ORDER_READ_TIMEOUT_MS`), and after 3 consecutive
failures the remaining ids of the batch are reported as `SKIPPED_VERIFICATION_UNAVAILABLE` instead of
being sent into the same failure; fix the dependency and run the list again (the call is idempotent).

For a verified order the tool releases only `RESERVED` rows, records a release intent (so a later
duplicate `OrderCreated` cannot reserve again), and still refuses any order that has `COMMITTED`
rows — those are sales, and undoing them is the refund flow's job (a `ReleaseStock` command after a
`PaymentRefunded`), not an operator checkbox. The order-service call is made outside every inventory
transaction; the per-order transaction that follows is serialized with live traffic by the advisory
lock.

## Before deploying on a network

The defaults shipped here are DEVELOPMENT defaults. They keep a local stack working with no
configuration, and they are named so that nobody can mistake them for production values
(`*_local_dev_only`). Before this stack is reachable from anywhere but this machine:

1. **Publish the ports deliberately.** `BIND_ADDRESS` is `127.0.0.1` by default, so nothing is
   exposed until you set it (`BIND_ADDRESS=0.0.0.0` in `.env`). Keep the databases, Kafka, Redis and
   MailHog on a private network or behind a firewall even then — see item 5.
2. **Replace every credential** in `.env`: `POSTGRES_SUPERUSER_PASSWORD`, the five
   `*_DB_PASSWORD` values, `REDIS_PASSWORD`, `GRAFANA_ADMIN_PASSWORD` and `AUTH_ADMIN_PASSWORD`.
   The database bootstrap (`infra/postgres/init/01-create-databases.sh`) reads these and creates the
   database owners with them — but it runs **once, on an empty volume**, so set them before the first
   start. To change them later, `ALTER ROLE` the existing owners or recreate the volume.
3. **Set a real JWT key pair**: `AUTH_JWT_PRIVATE_KEY` and `AUTH_JWT_PUBLIC_KEY`. Without them
   auth-service generates a throw-away 2048-bit pair at every start and every token dies with it.
4. **Point the clients at the broker**: `KAFKA_EXTERNAL_HOST` is the address clients dial, and
   `OTLP_TRACING_EXPORT_ENABLED=true` only together with the `observability` profile.
5. **Know what has no authentication of its own**: Postgres (password per user, `scram-sha-256`),
   Kafka (no SASL/TLS), Redis (no password unless `REDIS_PASSWORD` is set) and MailHog (no auth at
   all — it is a development sink). None of them is hardened for an untrusted network.

## Tests

Integration tests against **real PostgreSQL and Kafka** started by Testcontainers (Docker must be
running): the Saga transitions and compensations, outbox atomicity and relay retries, idempotent
consumers, the DLT, stock concurrency, the gateway's security and rate limiting and CORS, and the
circuit breaker. Two suites exist for the regression corners fixed here:

- `OrderSagaLatePaymentTest` — a `PaymentCompleted` that arrives after the customer cancelled or
  the sweep gave up is refunded exactly once and tracked to completion, including duplicate
  deliveries, a lost refund command that the sweep re-commands, and a concurrent
  cancel-vs-charge race.
- `InventorySagaReleaseIntentTest` / `OrderSagaLateInventoryTest` /
  `InventoryReservationReconciliationTest` — release-before-reserve, duplicate releases,
  duplicate `OrderCreated`s rejected via the persisted intent, concurrent reserve/release
  serialization, the saga's defensive late `StockReserved`, and the operator reconciliation
  (which refuses to touch committed sales).
- `StockReservationPrimitiveGuardsTest` — the same guarantees through the public service
  (release intents, per-order serialization with forced commit/release and reserve/release races
  against a real PostgreSQL). `ReservationReconciliationVerificationTest` / `OrderStatusClientTest` —
  reconciliation only acts on orders order-service reports as cancelled or rejected: active,
  unknown, denied, timed-out, malformed and foreign answers are refused with zero effects, against
  a mock order-service.

```bash
./mvnw verify
```

## Known limitations

- **Payments are simulated, and nothing pretends otherwise.** No real provider is contacted:
  `payment-service` runs a deterministic simulated gateway (declines above
  `PAYMENT_DECLINE_ABOVE`, optional failure rate). Every provider reference it hands out starts
  with `SIM-` ("simulated provider") and its ledger — rows flipping `AUTHORIZED` → `REFUNDED` in
  payment-service's own database — is the whole "bank". A real integration would need provider-side
  webhooks, a secure key store and PCI scope, and is out of scope here.
- **E-mail delivery is demo-only.** Event payloads carry the customer id but never a trustworthy
  address (an arbitrary "To" address taken from a payload would be a spoofable identity), so every
  mail goes to the synthetic recipient `<customerId>@NOTIFICATION_RECIPIENT_DOMAIN`
  (`customers.orders.local` by default) and lands in MailHog. It can be inspected there, but it is
  not delivered to a real person — wiring real customer addresses is a deliberate future change
  that needs a verified address source and the privacy controls that go with it.
- **E-mail delivery is at-least-once.** The idempotency note and the mail send commit together:
  an SMTP failure rolls the step back and the retry/DLT policy handles it, but if the process dies
  *after* the send and *before* the commit, the redelivery sends the same mail again. Duplicate
  e-mails are possible by design here; exactly-once SMTP would need an outbox for mails or
  provider-side de-duplication.
- Without configured RSA keys, auth-service generates a new key pair on every start, so tokens
  issued before a restart stop working.
