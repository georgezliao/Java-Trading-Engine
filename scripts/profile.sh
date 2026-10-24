#!/usr/bin/env bash
# Profile one full replay of a variant.
#
#   scripts/profile.sh [variant]
#
# Pass 1: JFR with the "profile" settings, plus text views (allocation by site, hot methods,
#         GC pauses) so the results can be read without Mission Control.
# Pass 2: only if ASYNC_PROFILER_HOME is set (or a Homebrew install is found), async-profiler in ASPROF_EVENT mode (default
#         alloc) as a flame graph. On Oscar use ASPROF_EVENT=itimer for CPU, since perf events
#         are usually locked down. Separate pass so the two profilers don't interfere.
#
# Env: RATE (default 1000000), WARMUP (200000), FEED, JVM_FLAGS, OUT_ROOT, ASPROF_EVENT.
set -euo pipefail
cd "$(dirname "$0")/.."

VARIANT=${1:-baseline}
RATE=${RATE:-1000000}
WARMUP=${WARMUP:-200000}
JVM_FLAGS=${JVM_FLAGS:-"-Xms1g -Xmx1g -XX:+AlwaysPreTouch"}
ASPROF_EVENT=${ASPROF_EVENT:-alloc}
. scripts/lib.sh

RUN=$(new_run "profile-$VARIANT")
ARGS=(--variant "$VARIANT" --feed "$FEED" --rate "$RATE" --warmup "$WARMUP")
JFR_BIN="$(dirname "$JAVA")/jfr"

echo "== JFR"
# shellcheck disable=SC2086
"$JAVA" $JVM_FLAGS -XX:StartFlightRecording=filename="$RUN/$VARIANT.jfr",settings=profile \
  -jar "$JAR" "${ARGS[@]}" --out "$RUN/$VARIANT-jfr.hgrm" | tee "$RUN/$VARIANT-jfr.log"
for view in allocation-by-site allocation-by-class hot-methods gc-pauses; do
  "$JFR_BIN" view --width 160 "$view" "$RUN/$VARIANT.jfr" > "$RUN/jfr-$view.txt" 2>&1 || true
done

# Homebrew installs the agent under /opt/homebrew/lib.
if [ -z "${ASYNC_PROFILER_HOME:-}" ] && [ -f /opt/homebrew/lib/libasyncProfiler.dylib ]; then
  ASYNC_PROFILER_HOME=/opt/homebrew
fi
if [ -n "${ASYNC_PROFILER_HOME:-}" ]; then
  LIB=$(ls "$ASYNC_PROFILER_HOME"/lib/libasyncProfiler.* 2>/dev/null | head -1)
  if [ -z "$LIB" ]; then
    echo "no libasyncProfiler in $ASYNC_PROFILER_HOME/lib" >&2
    exit 1
  fi
  echo "== async-profiler ($ASPROF_EVENT)"
  # shellcheck disable=SC2086
  "$JAVA" $JVM_FLAGS -agentpath:"$LIB"=start,event="$ASPROF_EVENT",file="$RUN/$VARIANT-$ASPROF_EVENT.html" \
    -jar "$JAR" "${ARGS[@]}" --out "$RUN/$VARIANT-asprof.hgrm" | tee "$RUN/$VARIANT-asprof.log"
else
  echo "(ASYNC_PROFILER_HOME not set, skipping async-profiler pass)"
fi
echo "results in $RUN"
