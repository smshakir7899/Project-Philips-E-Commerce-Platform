#!/usr/bin/env bash
# =============================================================================================
# build.sh - Maven build of all modules, then the Docker images.
#
# Usage: scripts/build.sh [--skip-tests] [--with-it] [--no-docker] [--help]
#   (default)     ./mvnw -B clean verify          unit tests + JaCoCo reports + enforcer checks
#   --skip-tests  ./mvnw -B clean package -DskipTests
#   --with-it     adds -Pintegration-tests        *IT.java via failsafe + Testcontainers (needs Docker)
#   --no-docker   skip `docker compose build`
#
# Assumptions: run from anywhere inside the repo; uses ./mvnw when present, else mvn.
# Image tag comes from IMAGE_TAG (.env or environment), default "local".
# =============================================================================================
set -euo pipefail
# shellcheck source=scripts/common.sh
source "$(dirname "$0")/common.sh"

SKIP_TESTS=0; WITH_IT=0; DOCKER_BUILD=1
for arg in "$@"; do
  case "$arg" in
    -h|--help) print_header_help "$0"; exit 0 ;;
    --skip-tests) SKIP_TESTS=1 ;;
    --with-it) WITH_IT=1 ;;
    --no-docker) DOCKER_BUILD=0 ;;
    *) die "Unknown option: $arg (see --help)" ;;
  esac
done
if [ "$SKIP_TESTS" -eq 1 ] && [ "$WITH_IT" -eq 1 ]; then die "--skip-tests and --with-it are mutually exclusive"; fi

cd "$ROOT_DIR"
if [ -x ./mvnw ]; then MVN=./mvnw; elif have_cmd mvn; then MVN=mvn; else die "No ./mvnw or mvn found"; fi

MVN_ARGS=(-B -ntp clean)
if [ "$SKIP_TESTS" -eq 1 ]; then
  MVN_ARGS+=(package -DskipTests)
else
  MVN_ARGS+=(verify)
  if [ "$WITH_IT" -eq 1 ]; then MVN_ARGS+=(-Pintegration-tests); fi
fi

log_section "Maven build"
log_step "$MVN ${MVN_ARGS[*]}"
START=$(date +%s)
if "$MVN" "${MVN_ARGS[@]}"; then
  pass "maven ${MVN_ARGS[*]}" "$(( $(date +%s) - START ))s"
else
  fail "maven ${MVN_ARGS[*]}" "see the Maven output above (surefire reports in <module>/target/surefire-reports)"
  print_summary "Build" || true
  exit 1
fi

# Every app module must have produced target/<module>.jar (finalName = artifactId).
for s in $PLATFORM_SERVICES; do
  m="$(svc_name "$s")"
  if [ -f "$m/target/$m.jar" ]; then
    pass "jar $m/target/$m.jar" "$(du -h "$m/target/$m.jar" | cut -f1)"
  else
    fail "jar $m/target/$m.jar" "missing - check <finalName>\${project.artifactId}</finalName> in $m/pom.xml"
  fi
done

if [ "$DOCKER_BUILD" -eq 1 ]; then
  log_section "Docker images"
  if [ "$FAIL_COUNT" -gt 0 ]; then
    skip "docker compose build" "jar(s) missing"
  else
    log_step "docker compose build (IMAGE_TAG=${IMAGE_TAG:-local})"
    if compose build; then pass "docker compose build"; else fail "docker compose build"; fi
  fi
else
  skip "docker compose build" "--no-docker"
fi

print_summary "Build"
