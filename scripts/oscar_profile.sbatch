#!/bin/bash
#SBATCH -J trading-tails
#SBATCH -N 1
#SBATCH --exclusive
#SBATCH -t 00:40:00
#SBATCH -o results/oscar-profile-%j.out
#
# Follow-up to oscar.sbatch: where do the baseline ~500 us tail and the spsc step near p99.8
# come from? Same node type and pinning as the main job. Submit with the same constraint:
#   sbatch --constraint=<feature> scripts/oscar_profile.sbatch
#
# Per config, interleaved: plain runs and runs with --outliers (logs per-message latency and
# engine service time for slow or expensive messages), each with safepoint logging and a
# /proc/interrupts snapshot before and after, so interrupts on the engine core can be counted.
# Then one baseline run with async-profiler tracing HashMap.resize calls slower than 20 us.
#
# Env: REPS (3), ASYNC_PROFILER_HOME (default ~/tools/async-profiler-4.6-linux-x64), OUT_DIR.
set -euo pipefail
cd "${SLURM_SUBMIT_DIR:-$(dirname "$0")/..}"

REPS=${REPS:-3}
COUNT=2000000
WARMUP=200000
HEAP=1g
JAR=$PWD/engine/target/engine.jar
ASYNC_PROFILER_HOME=${ASYNC_PROFILER_HOME:-$HOME/tools/async-profiler-4.6-linux-x64}
RUNID=${SLURM_JOB_ID:-local-$(date +%Y%m%d-%H%M%S)}
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
"$JAVA" -version 2>&1 | grep -qE 'version "(2[5-9]|[3-9][0-9])' || { echo "need JDK 25+" >&2; exit 1; }

WORK=/tmp/trading-tails-$RUNID
DEST=${OUT_DIR:-$PWD/results/oscar/$RUNID}
mkdir -p "$WORK" "$DEST"
copy_back() { cp -r "$WORK"/. "$DEST"/ 2>/dev/null || true; echo "results in $DEST"; }
trap copy_back EXIT

{ echo "host=$(hostname)"; echo "kernel=$(uname -srm)"; echo "git=$(git rev-parse --short HEAD)"; } > "$WORK/run.txt"
"$JAVA" -version 2> "$WORK/jvm.txt"
command -v lscpu >/dev/null && lscpu > "$WORK/lscpu.txt"

# Same CPU choice as oscar.sbatch: skip core 0, two physical cores on socket 0 for reader and
# engine, one more for JVM housekeeping.
TOPO=/sys/devices/system/cpu
TASKSET=(); PIN=""; ENGINE=""
if [ -d "$TOPO/cpu0/topology" ] && command -v taskset >/dev/null; then
  cores=$(for c in "$TOPO"/cpu[0-9]*; do
            n=${c##*cpu}
            [ "$(cat "$c/topology/physical_package_id")" = 0 ] || continue
            first=$(cut -d, -f1 "$c/topology/thread_siblings_list" | cut -d- -f1)
            [ "$first" = "$n" ] && echo "$n"
          done | sort -n)
  set -- $cores
  HOUSEKEEPING=$2; READER=$3; ENGINE=$4
  TASKSET=(taskset -c "$HOUSEKEEPING,$READER,$ENGINE")
  PIN="--cpus $READER,$ENGINE"
  echo "housekeeping=$HOUSEKEEPING reader=$READER engine=$ENGINE" > "$WORK/cpus.txt"
fi

FEED=/tmp/trading-tails-$RUNID.bin
"$JAVA" -jar "$JAR" gen --profile burst --count "$COUNT" --seed 1 --out "$FEED" > /dev/null

snap() { [ -r /proc/interrupts ] && cp /proc/interrupts "$1" || true; }

run() { # name variant rate mode(plain|outliers) rep latency_us service_us
  local base="$WORK/$1-$3-$4-r$5"
  local extra=""
  [ "$4" = outliers ] && extra="--outliers $base.csv --outlier-latency-us $6 --outlier-service-us $7"
  echo "== rep $5  $1 @ $3  ($4)"
  snap "$base.irq-before"
  # shellcheck disable=SC2086
  ${TASKSET[@]+"${TASKSET[@]}"} "$JAVA" -XX:+UseG1GC -Xms$HEAP -Xmx$HEAP -XX:+AlwaysPreTouch \
    -Xlog:safepoint=info:file="$base.safepoint.log":uptimenanos \
    -jar "$JAR" --variant "$2" --feed "$FEED" --rate "$3" --warmup "$WARMUP" $PIN $extra --out "$base.hgrm" \
    > "$base.log" 2>&1 || echo "   FAILED"
  snap "$base.irq-after"
  grep -E '^all |outliers' "$base.log" || true
}

for rep in $(seq 1 "$REPS"); do
  for mode in plain outliers; do
    run baseline  baseline 1000000 "$mode" "$rep" 50 20
    run spsc-spin spsc     1000000 "$mode" "$rep" 1 1
    run spsc-spin spsc     2000000 "$mode" "$rep" 1 1
  done
done

if [ -f "$ASYNC_PROFILER_HOME/lib/libasyncProfiler.so" ]; then
  echo "== async-profiler: HashMap.resize calls over 20 us, baseline @ 1M"
  ${TASKSET[@]+"${TASKSET[@]}"} "$JAVA" -Xms$HEAP -Xmx$HEAP \
    -agentpath:"$ASYNC_PROFILER_HOME/lib/libasyncProfiler.so"=start,trace=java.util.HashMap.resize:20us,file="$WORK/trace-hashmap-resize.jfr" \
    -jar "$JAR" --variant baseline --feed "$FEED" --rate 1000000 --warmup "$WARMUP" $PIN \
    > "$WORK/trace-hashmap-resize.log" 2>&1 || echo "   trace run failed (see log)"
  tail -3 "$WORK/trace-hashmap-resize.log"
  # One jdk.MethodTrace event per slow resize call, with duration and stack.
  "$(dirname "$(command -v "$JAVA")")/jfr" print --events jdk.MethodTrace --stack-depth 8 \
    "$WORK/trace-hashmap-resize.jfr" > "$WORK/trace-hashmap-resize.txt" 2>&1 || true
  grep -cE 'eventThread = "engine"' "$WORK/trace-hashmap-resize.txt" || true
else
  echo "no async-profiler at $ASYNC_PROFILER_HOME"
fi

echo "engine cpu: ${ENGINE:-none}"
rm -f "$FEED"
