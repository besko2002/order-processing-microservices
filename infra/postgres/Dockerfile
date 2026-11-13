# Plain postgres:16 plus the per-service database bootstrap scripts.
#
# The scripts are COPYed instead of bind-mounted from the host on purpose: on some
# machines (e.g. a checkout under ~/Downloads on macOS) Docker Desktop is not allowed
# to share the project directory, and a bind mount of ./infra/postgres/init then fails.
# Baking them into the image keeps `docker compose up` working everywhere.
FROM postgres:16

COPY init/ /docker-entrypoint-initdb.d/

# The entrypoint runs these as the unprivileged `postgres` user, so make sure they are
# world-readable regardless of the umask on the machine that built the image.
RUN chmod 0644 /docker-entrypoint-initdb.d/*
