#!/bin/bash
# =============================================================================================
# Run by the one-shot "mysql-init" compose service on EVERY `docker compose up` (idempotent).
# Why not /docker-entrypoint-initdb.d? Scripts there only run on the FIRST start with an empty
# volume, and *.sh files there are executed or sourced depending on how the host file system
# reports the executable bit (Docker Desktop file sharing differs between macOS, Windows and Linux).
# Invoking it explicitly with "bash" behaves the same everywhere.
#
# Local dev shortcut: one app user with full rights on the six schemas (Flyway needs DDL).
# Production: one user per service granted only on its own schema, plus a separate migration
# user with DDL rights so runtime users can be limited to SELECT/INSERT/UPDATE/DELETE.
# =============================================================================================
set -euo pipefail

: "${MYSQL_PWD:?MYSQL_PWD (root password) must be set}"
: "${APP_USER:?APP_USER must be set}"
: "${APP_PASSWORD:?APP_PASSWORD must be set}"
MYSQL_HOST="${MYSQL_HOST:-mysql}"
export MYSQL_PWD   # read by the mysql client, keeps the password off the command line

run_sql() {
  mysql -h "$MYSQL_HOST" -uroot --protocol=TCP -e "$1"
}

run_sql "CREATE USER IF NOT EXISTS '${APP_USER}'@'%' IDENTIFIED BY '${APP_PASSWORD}';"
for db in philips_product philips_inventory philips_cart philips_order philips_payment philips_notification; do
  run_sql "CREATE DATABASE IF NOT EXISTS \`${db}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;"
  run_sql "GRANT ALL PRIVILEGES ON \`${db}\`.* TO '${APP_USER}'@'%';"
  echo "[mysql-init] ${db}: ready, granted to ${APP_USER}"
done
run_sql "FLUSH PRIVILEGES;"
echo "[mysql-init] done"
