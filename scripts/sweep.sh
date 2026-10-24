#!/usr/bin/env bash
# Rate sweep for one variant. One fresh JVM per rate, so JIT and heap state don't carry over.
#
#   scripts/sweep.sh [variant]
#
# Env overrides:
#   RATES       space-separated msg/s           (default: 100k 250k 500k 1M 2M 4M)
#   FEED        feed file                       (default: feeds/burst.bin, generated if missing)
#   FEED_COUNT  messages when generating FEED   (default: 2000000)
#   WARMUP      messages dropped per run        (default: 500000)
#   JVM_FLAGS   extra JVM flags                 (default: -Xms1g -Xmx1g -XX:+AlwaysPreTouch)
#   OUT_ROOT    where run folders go            (default: results/local)
set -euo pipefail
cd "$(dirname "$0")/.."

VARIANT=${1:-baseline}
RATES=${RATES:-"100000 250000 500000 1000000 2000000 4000000"}
WARMUP=${WARMUP:-500000}
JVM_FLAGS=${JVM_FLAGS:-"-Xms1g -Xmx1g -XX:+AlwaysPreTouch"}
. scripts/lib.sh

RUN=$(new_run "$VARIANT")
{
  echo "variant=$VARIANT"
  echo "feed=$FEED"
  echo "rates=$RATES"
  echo "warmup=$WARMUP"
  echo "jvm_flags=$JVM_FLAGS"
} > "$RUN/sweep.txt"

for rate in $RATES; do
  echo "== $VARIANT @ $rate msg/s"
  # shellcheck disable=SC2086
  $JAVA $JVM_FLAGS -jar "$JAR" --variant "$VARIANT" --feed "$FEED" --rate "$rate" \
    --warmup "$WARMUP" --out "$RUN/$VARIANT-$rate.hgrm" | tee "$RUN/$VARIANT-$rate.log"
done
echo "results in $RUN"
