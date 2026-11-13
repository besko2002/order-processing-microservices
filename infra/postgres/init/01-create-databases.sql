-- Database per service: five databases, five owners, no cross-service access.
-- Executed once by the postgres image on an empty data volume.

CREATE USER auth_user          WITH PASSWORD 'auth_pass';
CREATE USER order_user         WITH PASSWORD 'order_pass';
CREATE USER inventory_user     WITH PASSWORD 'inventory_pass';
CREATE USER payment_user       WITH PASSWORD 'payment_pass';
CREATE USER notification_user  WITH PASSWORD 'notification_pass';

CREATE DATABASE auth_db          OWNER auth_user;
CREATE DATABASE order_db         OWNER order_user;
CREATE DATABASE inventory_db     OWNER inventory_user;
CREATE DATABASE payment_db       OWNER payment_user;
CREATE DATABASE notification_db  OWNER notification_user;

GRANT ALL PRIVILEGES ON DATABASE auth_db         TO auth_user;
GRANT ALL PRIVILEGES ON DATABASE order_db        TO order_user;
GRANT ALL PRIVILEGES ON DATABASE inventory_db    TO inventory_user;
GRANT ALL PRIVILEGES ON DATABASE payment_db      TO payment_user;
GRANT ALL PRIVILEGES ON DATABASE notification_db TO notification_user;

-- Postgres 15+ locks down the public schema, so hand it to each service owner
-- explicitly; Flyway needs to create its schema history table there.
\connect auth_db
GRANT ALL ON SCHEMA public TO auth_user;
ALTER SCHEMA public OWNER TO auth_user;

\connect order_db
GRANT ALL ON SCHEMA public TO order_user;
ALTER SCHEMA public OWNER TO order_user;

\connect inventory_db
GRANT ALL ON SCHEMA public TO inventory_user;
ALTER SCHEMA public OWNER TO inventory_user;

\connect payment_db
GRANT ALL ON SCHEMA public TO payment_user;
ALTER SCHEMA public OWNER TO payment_user;

\connect notification_db
GRANT ALL ON SCHEMA public TO notification_user;
ALTER SCHEMA public OWNER TO notification_user;
