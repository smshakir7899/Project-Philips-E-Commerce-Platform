#!/usr/bin/env bash
# =============================================================================================
# Creates every Kafka topic of the platform (docs/CONTRACTS.md §6). Run by the one-shot
# `kafka-init` container (image confluentinc/cp-kafka, which ships the kafka-topics CLI) after the
# broker is healthy. Because the broker runs with auto.create.topics.enable=false, the services
# would otherwise fail to produce ("UNKNOWN_TOPIC_OR_PARTITION") - creating topics explicitly
# also means partitions / replication are chosen deliberately instead of by broker defaults.
#
# Idempotent: --if-not-exists makes re-runs (every `docker compose up`) a no-op.
#
# Env: KAFKA_BOOTSTRAP (default kafka:29092), TOPIC_PARTITIONS (3), TOPIC_REPLICATION_FACTOR (1;
#      must be <= number of brokers - use 3 with min.insync.replicas=2 on a real cluster / MSK).
# =============================================================================================
set -euo pipefail

BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:29092}"
PARTITIONS="${TOPIC_PARTITIONS:-3}"
RF="${TOPIC_REPLICATION_FACTOR:-1}"

# Key = orderNumber on every topic, so 3 partitions keep per-order ordering while allowing up to
# 3 consumer instances per group to share the load.
TOPICS="order-created order-confirmed order-cancelled payment-processed payment-refunded"

echo "[kafka-init] waiting for broker ${BOOTSTRAP} ..."
for i in $(seq 1 30); do
  if kafka-topics --bootstrap-server "$BOOTSTRAP" --list >/dev/null 2>&1; then break; fi
  if [ "$i" -eq 30 ]; then echo "[kafka-init] broker not reachable" >&2; exit 1; fi
  sleep 2
done

for t in $TOPICS; do
  # Main topic + its dead-letter topic (<topic>.DLT, the Spring Kafka DeadLetterPublishingRecoverer
  # default naming). The DLT has the same partition count because the recoverer publishes to the
  # SAME partition number as the failed record by default.
  for topic in "$t" "$t.DLT"; do
    echo "[kafka-init] creating ${topic} (partitions=${PARTITIONS}, rf=${RF})"
    kafka-topics --bootstrap-server "$BOOTSTRAP" --create --if-not-exists \
      --topic "$topic" --partitions "$PARTITIONS" --replication-factor "$RF" \
      --config retention.ms=604800000   # 7 days; fine for a learning setup
  done
done

echo "[kafka-init] topics now present:"
kafka-topics --bootstrap-server "$BOOTSTRAP" --list
echo "[kafka-init] done"
