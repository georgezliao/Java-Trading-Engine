#!/usr/bin/env bash
# Same variant, feed and rate under different collectors, interleaved (G1 ZGC G1 ZGC ...) so
# drift in machine state hits every collector equally.
#
#   scripts/gc_compare.sh [variant]
#
# Env: GCS (default "G1 ZGC"; also Parallel, Serial, Epsilon), REPS (3), RATE (1000000),
#      WARMUP (500000), HEAP (1g), FEED, OUT_ROOT. Then: python3 scripts/plot.py <run folder>
set -euo pipefail
cd "$(dirname "$0")/.."

VARIANT=${1:-baseline}
GCS=${GCS:-"G1 ZGC"}
REPS=${REPS:-3}
RATE=${RATE:-1000000}
WARMUP=${WARMUP:-500000}
HEAP=${HEAP:-1g}
. scripts/lib.sh

gc_flag() {
  case "$1" in
    G1) echo "-XX:+UseG1GC" ;;
    ZGC) echo "-XX:+UseZGC" ;;
    Parallel) echo "-XX:+UseParallelGC" ;;
    Serial) echo "-XX:+UseSerialGC" ;;
    Epsilon) echo "-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC" ;;
    *) echo "unknown GC $1" >&2; exit 1 ;;
  esac
}

RUN=$(new_run "gc-$VARIANT")
printf 'variant=%s\ngcs=%s\nreps=%s\nrate=%s\nwarmup=%s\nheap=%s\nfeed=%s\n' \
  "$VARIANT" "$GCS" "$REPS" "$RATE" "$WARMUP" "$HEAP" "$FEED" > "$RUN/gc_compare.txt"

for rep in $(seq 1 "$REPS"); do
  for gc in $GCS; do
    name="$VARIANT-$gc-r$rep"
    echo "== $name"
    # shellcheck disable=SC2046
    "$JAVA" $(gc_flag "$gc") -Xms"$HEAP" -Xmx"$HEAP" -XX:+AlwaysPreTouch \
      -jar "$JAR" --variant "$VARIANT" --feed "$FEED" --rate "$RATE" --warmup "$WARMUP" \
      --out "$RUN/$name.hgrm" | tee "$RUN/$name.log"
  done
done
echo "results in $RUN"
