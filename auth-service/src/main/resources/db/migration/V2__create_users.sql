-- Phase 2: auth-service user accounts.
-- Emails are always stored trimmed + lowercased by the application, so a plain UNIQUE
-- constraint is enough to make duplicate detection case-insensitive.
CREATE TABLE users
(
    id            UUID         PRIMARY KEY,
    name          VARCHAR(100) NOT NULL,
    email         VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
