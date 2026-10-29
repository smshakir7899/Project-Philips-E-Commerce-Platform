#!/usr/bin/env bash
# =============================================================================================
# stop.sh - stops and removes the platform containers (docker compose down).
#
# Usage: scripts/stop.sh [--volumes] [--ci] [--help]
#   --volumes   ALSO delete the data volumes (MySQL, Kafka, ZooKeeper, Redis, MinIO) => next start
#               re-runs docker/mysql/init and kafka-init from scratch. Irreversible.
#   --ci        also stop the "ci" profile (SonarQube, Jenkins). With --volumes their volumes
#               (SonarQube DB, Jenkins home) are deleted too - otherwise they are always kept.
#
# Images are kept (rebuild with scripts/build.sh). Network philips-net is removed.
# =============================================================================================
set -euo pipefail
# shellcheck source=scripts/common.sh
source "$(dirname "$0")/common.sh"

WIPE=0; CI=0
for arg in "$@"; do
  case "$arg" in
    -h|--help) print_header_help "$0"; exit 0 ;;
    --volumes|-v) WIPE=1 ;;
    --ci) CI=1 ;;
    *) die "Unknown option: $arg (see --help)" ;;
  esac
done

require_cmd docker
cd "$ROOT_DIR"

# Compose project name is fixed in docker-compose.yml ("name: philips-ecommerce"), so volume
# names are predictable: <project>_<volume>.
PROJECT="philips-ecommerce"
DATA_VOLUMES="mysql-data redis-data zookeeper-data zookeeper-log kafka-data minio-data"
CI_VOLUMES="sonarqube-data sonarqube-extensions sonarqube-logs jenkins-home"

if [ "$CI" -eq 1 ]; then
  log_step "docker compose --profile ci down --remove-orphans"
  compose --profile ci down --remove-orphans
else
  # Without the profile flag, compose only touches the default (non-ci) services.
  log_step "docker compose down --remove-orphans"
  compose down --remove-orphans
fi

if [ "$WIPE" -eq 1 ]; then
  vols="$DATA_VOLUMES"
  if [ "$CI" -eq 1 ]; then vols="$vols $CI_VOLUMES"; fi
  for v in $vols; do
    if docker volume inspect "${PROJECT}_$v" >/dev/null 2>&1; then
      docker volume rm "${PROJECT}_$v" >/dev/null && log_info "removed volume ${PROJECT}_$v"
    fi
  done
  log_warn "Data volumes deleted - the next start initialises MySQL, Kafka topics and MinIO again."
fi
log_info "Stopped."
