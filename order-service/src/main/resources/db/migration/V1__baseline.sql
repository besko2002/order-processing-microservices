-- Baseline migration for order_db (order-service).
-- Phase 1 only establishes the Flyway schema history; domain tables
-- (and the outbox / processed_events tables) arrive in later phases.
DO
$$
BEGIN
    RAISE NOTICE 'Flyway baseline applied for order-service';
END
$$;
