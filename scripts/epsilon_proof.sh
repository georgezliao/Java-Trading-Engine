#!/usr/bin/env bash
# Allocation proof. Epsilon GC never frees memory, so a replay only survives if everything it
# allocates fits in the heap.
#
#   scripts/epsilon_proof.sh
#
# 1. baseline under G1 gives the reference output hash for this feed;
# 2. spsc under Epsilon with a small heap must finish and match that hash (exit 1 if not);
# 3. alloc-free and baseline under Epsilon with the same heap, for contrast. alloc-free has no
#    per-message allocation of its own, but ArrayBlockingQueue allocates a node each time the
#    engine thread waits, so with two warmup passes it runs out of memory too.
#
# Heap has to hold the feed (2M messages = 64 MB) plus per-replay setup: each replay builds a
# fresh book and histograms (~5-8 MB). Latency numbers from Epsilon runs are not meaningful.
#
# Env: HEAP (default 128m), RATE (1000000), FEED, OUT_ROOT.
set -euo pipefail
cd "$(dirname "$0")/.."

HEAP=${HEAP:-128m}
RATE=${RATE:-1000000}
. scripts/lib.sh

RUN=$(new_run epsilon)
EPSILON=(-XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms"$HEAP" -Xmx"$HEAP" -Xlog:gc)
ARGS=(--feed "$FEED" --rate "$RATE" --warmup 200000)
hash_of() { grep -oE 'output [0-9a-f]{16}' "$1" | awk '{print $2}'; }

echo "== baseline, G1 (reference hash)"
"$JAVA" -Xmx1g -jar "$JAR" --variant baseline "${ARGS[@]}" > "$RUN/baseline-g1.log"
ref=$(hash_of "$RUN/baseline-g1.log")
echo "reference $ref"

echo "== spsc, Epsilon, heap $HEAP"
if "$JAVA" "${EPSILON[@]}" -jar "$JAR" --variant spsc "${ARGS[@]}" > "$RUN/spsc-epsilon.log" 2>&1; then
  got=$(hash_of "$RUN/spsc-epsilon.log")
  grep -E 'Heap: .*used' "$RUN/spsc-epsilon.log" | tail -1
  if [ "$got" != "$ref" ]; then
    echo "FAIL: spsc finished but output $got != $ref"
    exit 1
  fi
  echo "PASS: spsc finished under Epsilon with output $got"
else
  tail -3 "$RUN/spsc-epsilon.log"
  echo "FAIL: spsc did not survive Epsilon with heap $HEAP"
  exit 1
fi

for v in alloc-free baseline; do
  echo "== $v, Epsilon, heap $HEAP (contrast)"
  if "$JAVA" "${EPSILON[@]}" -jar "$JAR" --variant "$v" "${ARGS[@]}" > "$RUN/$v-epsilon.log" 2>&1; then
    echo "$v finished:"; grep -E 'Heap: .*used' "$RUN/$v-epsilon.log" | tail -1
  else
    echo "$v ran out of memory:"; grep -E 'OutOfMemory' "$RUN/$v-epsilon.log" | head -1
  fi
done
echo "results in $RUN"
