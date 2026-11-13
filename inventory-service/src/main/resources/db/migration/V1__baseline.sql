-- Baseline migration for inventory_db (inventory-service).
-- Phase 1 only establishes the Flyway schema history; domain tables
-- (and the outbox / processed_events tables) arrive in later phases.
DO
$$
BEGIN
    RAISE NOTICE 'Flyway baseline applied for inventory-service';
END
$$;
