#!/usr/bin/env bash
# =============================================================================================
# check-prereqs.sh - verifies the local toolchain before building / starting the platform.
#
# Usage: scripts/check-prereqs.sh [--help] [--no-ports]
#   --no-ports   skip the "host port is free" checks (e.g. when the stack is already running)
#
# Checks
#   * java: JDK 8 is the reference build JDK. Any JDK >= 8 also works: on 9+ the pom activates
#     "maven.compiler.release=8" so Java-9+ APIs are rejected at compile time - but tests then
#     run on a newer JVM than production (Java 8 images), so prefer JDK 8 for real verification.
#   * Maven: ./mvnw (preferred, downloads Maven 3.9.9) or mvn >= 3.6.3 on the PATH.
#   * docker CLI + running daemon, docker compose v2 (`docker compose`, not `docker-compose`).
#   * curl, jq, python3 (used by the scripts / perf tooling).
#   * Free host ports: 3306 6379 2181 9092 9000 9001 8090 8080-8086 8761 8888.
#   * Docker memory >= 6 GB (warning only; the full stack needs ~5.7 GB).
# Exit status: 0 when no check FAILED (warnings are allowed), 1 otherwise.
# =============================================================================================
set -euo pipefail
# shellcheck source=scripts/common.sh
source "$(dirname "$0")/common.sh"

CHECK_PORTS=1
for arg in "$@"; do
  case "$arg" in
    -h|--help) print_header_help "$0"; exit 0 ;;
    --no-ports) CHECK_PORTS=0 ;;
    *) die "Unknown option: $arg (see --help)" ;;
  esac
done

log_section "Toolchain"

# ---- Java ----------------------------------------------------------------------------------
if have_cmd java; then
  JAVA_VER_LINE="$(java -version 2>&1 | head -1)"
  # "1.8.0_402" -> 8 ; "17.0.10" -> 17 ; "21" -> 21
  JAVA_RAW="$(printf '%s' "$JAVA_VER_LINE" | sed -E 's/.*version "([^"]+)".*/\1/')"
  case "$JAVA_RAW" in
    1.*) JAVA_MAJOR="$(printf '%s' "$JAVA_RAW" | cut -d. -f2)" ;;
    *)   JAVA_MAJOR="$(printf '%s' "$JAVA_RAW" | cut -d. -f1)" ;;
  esac
  if [ "$JAVA_MAJOR" = "8" ]; then
    pass "java is JDK 8" "$JAVA_VER_LINE"
  elif [ "$JAVA_MAJOR" -gt 8 ] 2>/dev/null; then
    pass "java >= 8 (JDK $JAVA_MAJOR)" "builds with --release 8; JDK 8 recommended so tests run on the production JVM"
    log_warn "JDK $JAVA_MAJOR detected. Set JAVA_HOME to a JDK 8 for a build identical to CI/Docker."
  else
    fail "java version" "could not parse: $JAVA_VER_LINE"
  fi
  if ! have_cmd javac; then fail "javac present" "only a JRE found - install a JDK (e.g. Temurin 8)"; else pass "javac present"; fi
else
  fail "java on PATH" "install a JDK 8 (e.g. 'brew install --cask temurin@8' or sdkman 8.0.x-tem)"
fi

# ---- Maven ---------------------------------------------------------------------------------
if [ -x "$ROOT_DIR/mvnw" ]; then
  pass "Maven wrapper ./mvnw" "$(grep -o 'apache-maven-[0-9.]*' "$ROOT_DIR/.mvn/wrapper/maven-wrapper.properties" 2>/dev/null | head -1)"
elif have_cmd mvn; then
  pass "mvn on PATH" "$(mvn -v 2>/dev/null | head -1)"
else
  fail "Maven" "neither ./mvnw (executable) nor mvn found"
fi

# ---- Docker --------------------------------------------------------------------------------
if have_cmd docker; then
  if docker info >/dev/null 2>&1; then
    pass "docker daemon running" "server $(docker version --format '{{.Server.Version}}' 2>/dev/null)"
    if docker compose version >/dev/null 2>&1; then
      pass "docker compose v2" "$(docker compose version --short 2>/dev/null)"
    else
      fail "docker compose v2" "install the compose plugin (Docker Desktop includes it); 'docker-compose' v1 is not supported"
    fi
    MEM_BYTES="$(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0)"
    MEM_GB=$((MEM_BYTES / 1024 / 1024 / 1024))
    if [ "$MEM_BYTES" -ge $((6 * 1024 * 1024 * 1024 - 300 * 1024 * 1024)) ]; then
      pass "Docker memory >= 6 GB" "${MEM_GB} GB available to containers"
    else
      log_warn "Docker has only ~${MEM_GB} GB. Full stack needs ~6 GB (Docker Desktop > Settings > Resources)."
      skip "Docker memory >= 6 GB" "only ~${MEM_GB} GB - warning, not a failure"
    fi
    ARCH="$(docker info --format '{{.Architecture}}' 2>/dev/null)"
    log_info "Docker architecture: $ARCH (all images used are multi-arch: amd64 + arm64)"
  else
    fail "docker daemon running" "start Docker Desktop / 'sudo systemctl start docker'"
  fi
else
  fail "docker CLI" "install Docker Desktop (macOS) or docker-ce + compose plugin (Linux)"
fi

# ---- Misc tools ----------------------------------------------------------------------------
for c in curl jq python3; do
  if have_cmd "$c"; then pass "$c present"; else fail "$c present" "install it (brew install $c / apt-get install $c)"; fi
done
if [ "${BASH_VERSINFO[0]}" -lt 4 ]; then
  log_info "bash ${BASH_VERSION} (macOS default) - scripts are written to be bash 3.2 compatible"
fi

# ---- Ports ---------------------------------------------------------------------------------
port_in_use() {
  if have_cmd lsof; then
    lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  elif have_cmd ss; then
    ss -ltn "sport = :$1" 2>/dev/null | grep -q LISTEN
  else
    # bash /dev/tcp fallback: connect succeeds => something listens
    (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1
  fi
}
port_owner() {
  if have_cmd lsof; then lsof -nP -iTCP:"$1" -sTCP:LISTEN 2>/dev/null | awk 'NR==2 {print $1" (pid "$2")"}'; fi
}

if [ "$CHECK_PORTS" -eq 1 ]; then
  log_section "Host ports"
  RUNNING="$(compose ps -q 2>/dev/null | wc -l | tr -d ' ')"
  if [ "${RUNNING:-0}" -gt 0 ]; then
    log_warn "$RUNNING philips-ecommerce container(s) already running - their ports will show as busy (use --no-ports)."
  fi
  for p in 3306 6379 2181 9092 9000 9001 8090 8080 8081 8082 8083 8084 8085 8086 8761 8888; do
    if port_in_use "$p"; then
      fail "port $p free" "in use by $(port_owner "$p")"
    else
      pass "port $p free"
    fi
  done
else
  skip "host port checks" "--no-ports"
fi

print_summary "Prerequisites"
