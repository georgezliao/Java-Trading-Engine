#!/usr/bin/env bash
# Allocation proof for experiment A. Epsilon GC never frees memory, so a replay only survives
# if everything it allocates fits in the heap.
#
#   scripts/epsilon_proof.sh
#
# 1. baseline under G1 gives the reference output hash for this feed;
# 2. alloc-free under Epsilon with a small heap must finish and match that hash (exit 1 if not);
# 3. baseline under Epsilon with the same heap, for contrast (expected to run out of memory).
#
# Heap has to hold the feed (2M messages = 64 MB) plus per-replay setup: each replay builds a
# fresh pooled book and histograms (~8 MB). Latency numbers from Epsilon runs are not meaningful.
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

echo "== alloc-free, Epsilon, heap $HEAP"
if "$JAVA" "${EPSILON[@]}" -jar "$JAR" --variant alloc-free "${ARGS[@]}" > "$RUN/alloc-free-epsilon.log" 2>&1; then
  got=$(hash_of "$RUN/alloc-free-epsilon.log")
  grep -E 'Heap: .*used' "$RUN/alloc-free-epsilon.log" | tail -1
  if [ "$got" != "$ref" ]; then
    echo "FAIL: alloc-free finished but output $got != $ref"
    exit 1
  fi
  echo "PASS: alloc-free finished under Epsilon with output $got"
else
  tail -3 "$RUN/alloc-free-epsilon.log"
  echo "FAIL: alloc-free did not survive Epsilon with heap $HEAP"
  exit 1
fi

echo "== baseline, Epsilon, heap $HEAP (contrast)"
if "$JAVA" "${EPSILON[@]}" -jar "$JAR" --variant baseline "${ARGS[@]}" > "$RUN/baseline-epsilon.log" 2>&1; then
  echo "baseline also finished:"; grep -E 'Heap: .*used' "$RUN/baseline-epsilon.log" | tail -1
else
  echo "baseline ran out of memory, as expected:"; grep -E 'OutOfMemory' "$RUN/baseline-epsilon.log" | head -1
fi
echo "results in $RUN"
