#!/usr/bin/env bash
# =============================================================================================
# deploy/ec2/deploy.sh - pull and (re)start the platform on the EC2 instance.
#
# Usage (on the instance, normally invoked by Jenkins over SSH):
#   cd /opt/philips-ecommerce && ./deploy.sh [IMAGE_TAG]
#   ./deploy.sh --help
#
# What it does
#   1. Reads ./.env (DOCKER_REGISTRY, IMAGE_TAG, AWS_REGION, MySQL credentials, ...).
#   2. If IMAGE_TAG is given, writes it into .env so a reboot / manual `docker compose up` keeps it.
#   3. Logs in to Amazon ECR when DOCKER_REGISTRY is an ECR URI (uses the instance profile, which
#      needs the AmazonEC2ContainerRegistryReadOnly managed policy - no keys on disk).
#   4. docker compose pull && docker compose up -d --remove-orphans.
#   5. Waits up to DEPLOY_TIMEOUT seconds (default 300) for every long-running container to be
#      healthy and for http://localhost:8080/actuator/health to report UP.
#   6. Success: appends the tag to .deploy-history. Failure: prints diagnostics + a rollback hint.
#
# Assumptions
#   * Directory layout described at the top of docker-compose.prod.yml.
#   * Docker Engine + compose v2 plugin, curl, jq and aws cli installed (deploy/ec2/user-data.sh).
#   * Every Java image defines a HEALTHCHECK on /actuator/health.
# =============================================================================================
set -euo pipefail

APP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="$APP_DIR/docker-compose.prod.yml"
ENV_FILE="$APP_DIR/.env"
HISTORY_FILE="$APP_DIR/.deploy-history"
DEPLOY_TIMEOUT="${DEPLOY_TIMEOUT:-300}"
GATEWAY_HEALTH_URL="${GATEWAY_HEALTH_URL:-http://localhost:8080/actuator/health}"

if [ -t 1 ]; then
  RED=$'\033[0;31m'; GREEN=$'\033[0;32m'; YELLOW=$'\033[0;33m'; BLUE=$'\033[0;34m'; NC=$'\033[0m'
else
  RED=""; GREEN=""; YELLOW=""; BLUE=""; NC=""
fi
info() { printf '%s[deploy]%s %s\n' "$BLUE" "$NC" "$*"; }
warn() { printf '%s[deploy]%s %s\n' "$YELLOW" "$NC" "$*" >&2; }
err()  { printf '%s[deploy]%s %s\n' "$RED" "$NC" "$*" >&2; }

usage() { sed -n '3,24p' "$0" | sed 's/^# \{0,1\}//'; }

case "${1:-}" in
  -h|--help) usage; exit 0 ;;
esac

[ -f "$COMPOSE_FILE" ] || { err "Missing $COMPOSE_FILE"; exit 1; }
[ -f "$ENV_FILE" ] || { err "Missing $ENV_FILE (Jenkins copies it from the 'ec2-prod-env' secret file)"; exit 1; }

compose() { docker compose -f "$COMPOSE_FILE" --env-file "$ENV_FILE" --project-directory "$APP_DIR" "$@"; }

# Reads KEY=value from .env without sourcing it (values may contain characters bash would interpret).
env_value() { grep -E "^$1=" "$ENV_FILE" | tail -1 | cut -d= -f2- | sed -e 's/^"//' -e 's/"$//'; }

# ---- 1/2. Resolve the tag -----------------------------------------------------------------------
NEW_TAG="${1:-}"
if [ -n "$NEW_TAG" ]; then
  if grep -qE '^IMAGE_TAG=' "$ENV_FILE"; then
    # Portable in-place edit (GNU sed on AL2023, but keep it BSD-compatible too).
    tmp="$(mktemp)"; sed "s|^IMAGE_TAG=.*|IMAGE_TAG=$NEW_TAG|" "$ENV_FILE" > "$tmp"
    cat "$tmp" > "$ENV_FILE"; rm -f "$tmp"
  else
    printf 'IMAGE_TAG=%s\n' "$NEW_TAG" >> "$ENV_FILE"
  fi
fi
IMAGE_TAG="$(env_value IMAGE_TAG)"
DOCKER_REGISTRY="$(env_value DOCKER_REGISTRY)"
AWS_REGION="$(env_value AWS_REGION)"; AWS_REGION="${AWS_REGION:-eu-west-1}"
[ -n "$IMAGE_TAG" ] || { err "IMAGE_TAG is empty (pass it as argument or set it in .env)"; exit 1; }
[ -n "$DOCKER_REGISTRY" ] || { err "DOCKER_REGISTRY is empty in .env"; exit 1; }

PREVIOUS_TAG="$(tail -1 "$HISTORY_FILE" 2>/dev/null | awk '{print $2}' || true)"
info "Deploying IMAGE_TAG=$IMAGE_TAG from $DOCKER_REGISTRY (previous successful: ${PREVIOUS_TAG:-none})"

# ---- 3. Registry login ------------------------------------------------------------------------
case "$DOCKER_REGISTRY" in
  *.dkr.ecr.*.amazonaws.com*)
    info "Logging in to Amazon ECR ($AWS_REGION) with the instance profile"
    aws ecr get-login-password --region "$AWS_REGION" \
      | docker login --username AWS --password-stdin "${DOCKER_REGISTRY%%/*}"
    ;;
  *)
    info "Non-ECR registry: assuming 'docker login $DOCKER_REGISTRY' was done once on this host"
    ;;
esac

# ---- 4. Pull + up -----------------------------------------------------------------------------
info "Pulling images"
compose pull --quiet
info "Starting containers"
compose up -d --remove-orphans

# ---- 5. Wait for health -----------------------------------------------------------------------
# A container is OK when it is running+healthy, running without a healthcheck, or a one-shot
# init container (kafka-init) that exited with code 0. Prints unhealthy service names otherwise.
unhealthy_services() {
  local svc cid state
  for svc in $(compose config --services); do
    cid="$(compose ps -a -q "$svc" | head -1)"
    if [ -z "$cid" ]; then echo "$svc"; continue; fi
    state="$(docker inspect -f '{{.State.Status}}/{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}/{{.State.ExitCode}}' "$cid")"
    case "$state" in
      running/healthy/*|running/none/*|exited/none/0) ;;
      *) echo "$svc" ;;
    esac
  done
}

gateway_up() {
  curl -fsS --max-time 5 "$GATEWAY_HEALTH_URL" 2>/dev/null | jq -e '.status == "UP"' >/dev/null 2>&1
}

info "Waiting up to ${DEPLOY_TIMEOUT}s for all containers to be healthy"
start=$(date +%s)
pending=""
while true; do
  pending="$(unhealthy_services | tr '\n' ' ')"
  if [ -z "${pending// /}" ] && gateway_up; then break; fi
  if [ $(( $(date +%s) - start )) -ge "$DEPLOY_TIMEOUT" ]; then
    err "Deployment of $IMAGE_TAG is NOT healthy after ${DEPLOY_TIMEOUT}s. Pending: ${pending:-api-gateway health}"
    compose ps -a >&2 || true
    for svc in ${pending:-api-gateway}; do
      printf '\n%s--- last 80 log lines of %s ---%s\n' "$YELLOW" "$svc" "$NC" >&2
      compose logs --no-color --tail 80 "$svc" >&2 || true
    done
    if [ -n "$PREVIOUS_TAG" ] && [ "$PREVIOUS_TAG" != "$IMAGE_TAG" ]; then
      warn "Rollback: ./deploy.sh $PREVIOUS_TAG"
    else
      warn "No previous successful tag recorded in $HISTORY_FILE - fix forward or pick a known-good tag."
    fi
    exit 1
  fi
  sleep 5
done

# ---- 6. Record success ------------------------------------------------------------------------
printf '%s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$IMAGE_TAG" >> "$HISTORY_FILE"
compose ps
printf '%s[deploy] SUCCESS:%s IMAGE_TAG=%s is healthy (%s)\n' "$GREEN" "$NC" "$IMAGE_TAG" "$GATEWAY_HEALTH_URL"
# Reclaim disk from old image layers (keeps images used by running containers).
docker image prune -f >/dev/null 2>&1 || true
