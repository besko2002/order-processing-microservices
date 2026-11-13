# Compose overlays in this directory

Small override files for `docker-compose.yml` in the repository root. They never contain secrets:
secret values live in the git-ignored `.env` and reach the containers through Compose variable
passthrough. A plain `docker compose -f docker-compose.yml --profile app up -d` (without an overlay)
applies the base desired tuning and would DROP the currently running memory settings.

## runtime-memory-preservation.yml

Reproduces the memory tuning currently running in the containers (verified from the running stack on
2026-11-13): the 50% G1 JVM bundle plus `MALLOC_ARENA_MAX=2` on all six Java services, their 384m
memory limits, and Kafka's current heap (`-Xmx512m -Xms256m`). It exists for reproducibility, not as
a claim of proven memory savings — it preserves today's settings exactly as they run.

To apply it (a runtime change that recreates containers — do this only when explicitly approved),
go ONE SERVICE AT A TIME from the repository root, with a health gate after each step:

```bash
docker compose -f docker-compose.yml -f infra/compose/runtime-memory-preservation.yml \
  -p order-processing --profile app up -d --no-deps --no-build kafka
# health gate: `docker ps` shows op-kafka healthy before the next step
docker compose -f docker-compose.yml -f infra/compose/runtime-memory-preservation.yml \
  -p order-processing --profile app up -d --no-deps --no-build auth-service
# health gate: op-auth healthy (and JWT checks pass) before the next step
docker compose -f docker-compose.yml -f infra/compose/runtime-memory-preservation.yml \
  -p order-processing --profile app up -d --no-deps --no-build inventory-service
# health gate: op-inventory healthy; then repeat for order-service, payment-service,
# notification-service, api-gateway — one at a time, each with its own gate.
```

No apply is needed just to preserve the already-matching runtime: the running containers already
carry these settings (verified 2026-11-13); the overlay exists so future recreations reproduce them.

Notes:

- Run from the repository root (the directory containing `docker-compose.yml`): the `-f` paths are
  relative to it, and Compose loads the project's git-ignored `.env` from there.
- `-p order-processing` and the base file's `name:` mean both project checkouts drive the SAME
  containers, network and volumes — applying this from the sibling checkout would affect the same
  shared stack, not a separate one.
- `--profile app` is included deliberately and explicitly: Compose can auto-enable a service's
  profile when that service is named explicitly on the command line, but stating the profile keeps
  the selection unambiguous. The six Java services are defined under the `app` profile in the base
  file.
- Without the overlay, the base file's `-XX:MaxRAMPercentage=65` and Kafka `-Xms128m` would take
  effect. Changing those desired values is a separate decision; the optional Kafka `Xms 128m`
  change stays separately approved and is deliberately not in this overlay.
- The `kafka` block pins the CURRENT heap so applying the overlay does not silently change the
  broker in either direction.

## auth-jwt-preserve-memory.yml

Auth-service only, from the JWT activation: pins the same current JVM/arena settings for
auth-service so activating the persistent signing keys did not change its memory tuning. Its values
are identical to this service's block in `runtime-memory-preservation.yml`, so the two overlays can
be used together or separately without conflict.

## kafka-memory-trial.yml (INACTIVE — rolled back)

Kafka-only trial (user-approved), applied and then rolled back on 2026-11-13: it pinned
`KAFKA_HEAP_OPTS: -Xmx512m -Xms128m` (initial heap reservation 256m -> 128m, Xmx unchanged). The
stack ran the three-file composition (base + preservation + trial) for ~9 minutes, then the trial
was rolled back because the benefit was UNSUPPORTED BY THE AVAILABLE EVIDENCE: early measured RSS
went UP during the trial (470.7 MiB at 85s uptime and 496.5 MiB at 386s, against pretrial 347.6–
381.7 MiB at ~3h uptime). Those comparisons are limited by uptime/restart confounding — a fresh
JVM's early RSS says nothing about sustained-load behavior — so the figures are recorded as
observations, not proof in either direction; unproven benefit is not a reason to keep a change
active. The broker was healthy with zero restarts/OOM and all six consumer groups Stable/assigned/
zero-lag throughout the trial and the rollback.

Active composition after rollback is the two-file stack (base + runtime-memory-preservation.yml),
Kafka heap `-Xmx512m -Xms256m` again; the rollback command was exactly:

```bash
docker compose -f docker-compose.yml -f infra/compose/runtime-memory-preservation.yml \
  -p order-processing up -d --no-deps --no-build kafka
```

The trial file is retained as an INACTIVE experiment for a separately approved future trial; it
must not be included in any composition unless explicitly approved again.
