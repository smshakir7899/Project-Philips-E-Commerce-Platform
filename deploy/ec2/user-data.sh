#!/bin/bash
# =============================================================================================
# deploy/ec2/user-data.sh - EC2 user data for Amazon Linux 2023 (runs ONCE, as root, at first boot).
#
# Paste it into "Advanced details > User data" or pass it with
#   aws ec2 run-instances ... --user-data file://deploy/ec2/user-data.sh \
#     --iam-instance-profile Name=philips-ecommerce-ec2 \
#     --metadata-options HttpTokens=required,HttpPutResponseHopLimit=2
#
# Instance size: t3.large (2 vCPU, 8 GB) is the MINIMUM. The stack runs ~9 JVMs (mem_limit 400m each
# ~= 3.6 GB) + MySQL (~0.7 GB) + Kafka (~0.7 GB) + ZooKeeper (~0.3 GB) + Redis (~0.3 GB) + OS/Docker.
# t3.medium (4 GB) will OOM-kill containers. On arm64 (Graviton, e.g. t4g.large) the images must be
# built for linux/arm64 (docker buildx --platform linux/amd64,linux/arm64).
#
# What it does: installs Docker Engine, the compose v2 CLI plugin (pinned), jq; adds ec2-user to the
# docker group; creates /opt/philips-ecommerce owned by ec2-user. The aws cli v2 is preinstalled on AL2023.
# Logs: /var/log/cloud-init-output.log and /var/log/philips-user-data.log
# =============================================================================================
set -euo pipefail
exec > >(tee -a /var/log/philips-user-data.log) 2>&1

COMPOSE_VERSION="v2.29.7"          # pin: reproducible hosts; bump deliberately
APP_DIR="/opt/philips-ecommerce"
APP_USER="ec2-user"

echo "[user-data] Updating packages and installing docker, jq, git"
dnf -y update
dnf -y install docker jq git

echo "[user-data] Enabling Docker"
systemctl enable --now docker
# Lets ec2-user run docker without sudo (takes effect on next login). NOTE: docker group == root-equivalent.
usermod -aG docker "$APP_USER"

echo "[user-data] Installing docker compose plugin $COMPOSE_VERSION"
ARCH="$(uname -m)"                  # x86_64 or aarch64 - matches the release asset names
PLUGIN_DIR="/usr/local/lib/docker/cli-plugins"
mkdir -p "$PLUGIN_DIR"
curl -fsSL "https://github.com/docker/compose/releases/download/${COMPOSE_VERSION}/docker-compose-linux-${ARCH}" \
  -o "$PLUGIN_DIR/docker-compose"
chmod +x "$PLUGIN_DIR/docker-compose"
docker compose version

echo "[user-data] Creating $APP_DIR"
mkdir -p "$APP_DIR"
chown -R "$APP_USER:$APP_USER" "$APP_DIR"
chmod 750 "$APP_DIR"

# Docker daemon log rotation as a safety net (compose files also set it per service).
cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "10m", "max-file": "3" }
}
JSON
systemctl restart docker

# Optional: a 2 GB swap file gives headroom during startup spikes (all JVMs booting at once).
# Swap hurts latency under sustained pressure - size the instance properly instead for real loads.
# fallocate -l 2G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile \
#   && echo '/swapfile none swap sw 0 0' >> /etc/fstab

echo "[user-data] Done. Jenkins will copy the deployment files to $APP_DIR and run ./deploy.sh <tag>"
