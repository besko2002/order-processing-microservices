#!/bin/sh
# Creates the five per-service databases and their owners.
#
# The names and passwords come from the environment (docker-compose.yml passes them), so a
# deployment can set its own instead of the local-development defaults baked in below. The
# defaults are deliberately obvious about being local-only; replace them before putting this
# stack on a network (README: "Before deploying on a network").
#
# Runs once, on an empty data volume. The .sql version of this script could not do this: psql
# does not read environment variables, so the passwords had to be baked into the image.

set -eu

psql -v ON_ERROR_STOP=1 \
  --username "${POSTGRES_USER:-postgres}" \
  --dbname "${POSTGRES_DB:-postgres}" \
  --set=auth_db="${AUTH_DB_NAME:-auth_db}" \
  --set=auth_user="${AUTH_DB_USER:-auth_user}" \
  --set=auth_password="${AUTH_DB_PASSWORD:-auth_local_dev_only}" \
  --set=order_db="${ORDER_DB_NAME:-order_db}" \
  --set=order_user="${ORDER_DB_USER:-order_user}" \
  --set=order_password="${ORDER_DB_PASSWORD:-order_local_dev_only}" \
  --set=inventory_db="${INVENTORY_DB_NAME:-inventory_db}" \
  --set=inventory_user="${INVENTORY_DB_USER:-inventory_user}" \
  --set=inventory_password="${INVENTORY_DB_PASSWORD:-inventory_local_dev_only}" \
  --set=payment_db="${PAYMENT_DB_NAME:-payment_db}" \
  --set=payment_user="${PAYMENT_DB_USER:-payment_user}" \
  --set=payment_password="${PAYMENT_DB_PASSWORD:-payment_local_dev_only}" \
  --set=notification_db="${NOTIFICATION_DB_NAME:-notification_db}" \
  --set=notification_user="${NOTIFICATION_DB_USER:-notification_user}" \
  --set=notification_password="${NOTIFICATION_DB_PASSWORD:-notification_local_dev_only}" \
  <<'EOSQL'
CREATE USER :"auth_user" WITH PASSWORD :'auth_password';
CREATE USER :"order_user" WITH PASSWORD :'order_password';
CREATE USER :"inventory_user" WITH PASSWORD :'inventory_password';
CREATE USER :"payment_user" WITH PASSWORD :'payment_password';
CREATE USER :"notification_user" WITH PASSWORD :'notification_password';

CREATE DATABASE :"auth_db" OWNER :"auth_user";
CREATE DATABASE :"order_db" OWNER :"order_user";
CREATE DATABASE :"inventory_db" OWNER :"inventory_user";
CREATE DATABASE :"payment_db" OWNER :"payment_user";
CREATE DATABASE :"notification_db" OWNER :"notification_user";

GRANT ALL PRIVILEGES ON DATABASE :"auth_db" TO :"auth_user";
GRANT ALL PRIVILEGES ON DATABASE :"order_db" TO :"order_user";
GRANT ALL PRIVILEGES ON DATABASE :"inventory_db" TO :"inventory_user";
GRANT ALL PRIVILEGES ON DATABASE :"payment_db" TO :"payment_user";
GRANT ALL PRIVILEGES ON DATABASE :"notification_db" TO :"notification_user";

-- Postgres 15+ locks down the public schema, so hand it to each service owner
-- explicitly; Flyway needs to create its schema history table there.
\connect :"auth_db"
GRANT ALL ON SCHEMA public TO :"auth_user";
ALTER SCHEMA public OWNER TO :"auth_user";

\connect :"order_db"
GRANT ALL ON SCHEMA public TO :"order_user";
ALTER SCHEMA public OWNER TO :"order_user";

\connect :"inventory_db"
GRANT ALL ON SCHEMA public TO :"inventory_user";
ALTER SCHEMA public OWNER TO :"inventory_user";

\connect :"payment_db"
GRANT ALL ON SCHEMA public TO :"payment_user";
ALTER SCHEMA public OWNER TO :"payment_user";

\connect :"notification_db"
GRANT ALL ON SCHEMA public TO :"notification_user";
ALTER SCHEMA public OWNER TO :"notification_user";
EOSQL
