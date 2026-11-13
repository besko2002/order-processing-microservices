# Order Processing Microservices — Plan

Event-driven e-commerce order flow split into independent Spring Boot services that talk
through Kafka. The goal is to show microservices, event-driven design, the Saga pattern,
idempotent consumers and the Transactional Outbox — the skills the other portfolio
projects do not cover.

## Architecture

```
            Client
              │  (JWT)
        ┌─────▼──────┐
        │ API Gateway│  Spring Cloud Gateway: routing, JWT check, rate limit (Redis)
        └──┬───┬───┬─┘
           │   │   │
   ┌───────▼┐ ┌▼────────┐ ┌▼──────────┐
   │ Auth   │ │ Order   │ │ Inventory │   REST for commands/queries
   │Service │ │ Service │ │ Service   │
   └────────┘ └──┬──▲───┘ └──▲──┬─────┘
                 │  │        │  │
            ─────▼──┴────────┴──▼──────────────  Kafka (events)
                 │  ▲        │
          ┌──────▼──┴┐   ┌───▼──────────┐
          │ Payment  │   │ Notification │
          │ Service  │   │ Service      │
          └──────────┘   └──────────────┘
```

- **Database per service** — each service owns its own PostgreSQL database (one Postgres
  container, separate databases/users). No service reads another service's tables.
- **Communication** — synchronous REST only through the gateway; service-to-service
  business flow happens through Kafka events.

## Services

| Service | Responsibility | Store |
|---|---|---|
| api-gateway | Single entry point, routes, validates JWT, rate limiting, correlation id | Redis |
| auth-service | Register / login, issues JWT (RS256, public key shared with gateway) | Postgres `auth_db` |
| order-service | Create order, order status, Saga orchestrator | Postgres `order_db` |
| inventory-service | Products, stock, reserve / release stock | Postgres `inventory_db` |
| payment-service | Simulated payment (configurable success/failure), refunds | Postgres `payment_db` |
| notification-service | Consumes events, stores/sends notifications (email via MailHog in dev) | Postgres `notification_db` |

## Order Saga (orchestrated by order-service)

1. `POST /api/orders` → order `PENDING`, event `OrderCreated`.
2. inventory-service reserves stock → `StockReserved` or `StockRejected`.
3. On `StockReserved`, payment-service charges → `PaymentCompleted` or `PaymentFailed`.
4. `PaymentCompleted` → order `CONFIRMED`, notification sent.
5. `StockRejected` → order `REJECTED`. `PaymentFailed` → compensation: `ReleaseStock`,
   order `CANCELLED`.
6. User cancels a confirmed order → `RefundPayment` + `ReleaseStock` → `CANCELLED`.

## Reliability patterns

- **Transactional Outbox** — events are written to an `outbox` table in the same DB
  transaction as the state change, then published to Kafka by a relay. No lost or phantom
  events.
- **Idempotent consumers** — every consumer records processed event ids
  (`processed_events`), so Kafka redeliveries are no-ops.
- **Retries + Dead Letter Topic** for poison messages.
- **Correlation id** propagated from the gateway through HTTP headers and Kafka headers.
- **Optimistic locking** on stock to avoid overselling under concurrency.

## Tech stack

Java 21, Spring Boot 3.5, Maven multi-module, Spring Cloud Gateway, Spring Kafka,
Spring Data JPA, PostgreSQL 16, Flyway, Redis, Spring Security (JWT), springdoc,
Micrometer + Actuator, Docker Compose, Testcontainers (Postgres + Kafka), GitHub Actions.

## Repository layout

```
order-processing-microservices/
├── pom.xml                  (parent, dependency management)
├── common-events/           (shared event records + topic names)
├── api-gateway/
├── auth-service/
├── order-service/
├── inventory-service/
├── payment-service/
├── notification-service/
├── docker-compose.yml       (kafka KRaft, postgres, redis, mailhog, all services)
└── .github/workflows/ci.yml
```

## Local ports

Chosen to avoid ports already used on this machine (5433, 6379, 5000, 7000):
gateway 8080, auth 8081, order 8082, inventory 8083, payment 8084, notification 8085,
Postgres 5435, Kafka 9094, Redis 6380, MailHog UI 8025.

## Phases

1. **Setup** — Maven multi-module skeleton, common-events, docker-compose (Kafka KRaft,
   Postgres with 5 databases, Redis, MailHog), every service boots with health endpoint.
2. **Auth service** — register/login, RS256 JWT, tests.
3. **API Gateway** — routes, JWT validation, correlation id, Redis rate limiting, tests.
4. **Inventory service** — products & stock CRUD, reserve/release with optimistic locking.
5. **Order service + Outbox** — create/query orders, outbox relay publishing `OrderCreated`.
6. **Saga** — inventory & payment consumers, order status transitions, compensation,
   idempotent consumers, DLT; end-to-end tests with Testcontainers Kafka.
7. **Notification service** — consumes events, emails via MailHog, stored history.
8. **Observability & polish** — correlation id in logs, Actuator/metrics, Swagger per
   service, README with diagrams, Dockerfiles, full `docker compose up`.
9. **CI + GitHub** — GitHub Actions running all module tests, push to a public repo.
