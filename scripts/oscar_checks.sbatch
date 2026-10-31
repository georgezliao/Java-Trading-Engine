#!/bin/bash
#SBATCH -J trading-checks
#SBATCH -N 1
#SBATCH --exclusive
#SBATCH -t 00:40:00
#SBATCH -o results/oscar-checks-%j.out
#
# Checks for three explanations in docs/findings.md that were inferred, not tested:
#
# 1. baseline's ~515 us max is java.util.HashMap resizing: rerun baseline with
#    --book-capacity 65536 so the id index never resizes on this feed.
# 2. ZGC's worse tail comes from its threads sharing cores 2/3 with the pinned reader and
#    engine. Two layouts:
#      shared:   taskset {hk, reader, engine}; other JVM threads can run on reader/engine cores
#      isolated: taskset {hk, 3 more cores}; reader/engine then pin themselves onto their own
#                cores, so no other JVM thread is allowed there
# 3. ArrayBlockingQueue allocates when the engine thread waits: alloc-free under Epsilon with a
#    large heap at 250k vs 1M msg/s, spsc as the no-queue control. Compare heap used at exit.
#
#   sbatch --constraint=<feature> scripts/oscar_checks.sbatch
#
# Env: REPS (3), OUT_DIR.
set -euo pipefail
cd "${SLURM_SUBMIT_DIR:-$(dirname "$0")/..}"

REPS=${REPS:-3}
WARMUP=200000
JAR=$PWD/engine/target/engine.jar
RUNID=${SLURM_JOB_ID:-local-$(date +%Y%m%d-%H%M%S)}
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
"$JAVA" -version 2>&1 | grep -qE 'version "(2[5-9]|[3-9][0-9])' || { echo "need JDK 25+" >&2; exit 1; }

WORK=/tmp/trading-checks-$RUNID
DEST=${OUT_DIR:-$PWD/results/oscar/$RUNID}
mkdir -p "$WORK" "$DEST"
copy_back() { cp -r "$WORK"/. "$DEST"/ 2>/dev/null || true; echo "results in $DEST"; }
trap copy_back EXIT
{ echo "host=$(hostname)"; echo "kernel=$(uname -srm)"; echo "git=$(git rev-parse --short HEAD)"; } > "$WORK/run.txt"
"$JAVA" -version 2> "$WORK/jvm.txt"
command -v lscpu >/dev/null && lscpu > "$WORK/lscpu.txt"

TOPO=/sys/devices/system/cpu
cores=$(for c in "$TOPO"/cpu[0-9]*; do
          n=${c##*cpu}
          [ "$(cat "$c/topology/physical_package_id")" = 0 ] || continue
          first=$(cut -d, -f1 "$c/topology/thread_siblings_list" | cut -d- -f1)
          [ "$first" = "$n" ] && echo "$n"
        done | sort -n)
set -- $cores
[ $# -ge 7 ] || { echo "need 7+ physical cores on socket 0" >&2; exit 1; }
HK=$2; READER=$3; ENGINE=$4
SHARED="$HK,$READER,$ENGINE"
ISOLATED="$HK,$5,$6,$7"
PIN="--cpus $READER,$ENGINE"
echo "reader=$READER engine=$ENGINE shared=$SHARED isolated=$ISOLATED" | tee "$WORK/cpus.txt"

FEED=/tmp/trading-checks-$RUNID.bin
"$JAVA" -jar "$JAR" gen --profile burst --count 2000000 --seed 1 --out "$FEED" > /dev/null

run() { # name cpus gcflags variant rate extra rep
  local base="$WORK/$1-$5-r$7"
  echo "== rep $7  $1 @ $5"
  # shellcheck disable=SC2086
  taskset -c "$2" "$JAVA" $3 -Xms1g -Xmx1g -XX:+AlwaysPreTouch \
    -Xlog:safepoint=info:file="$base.safepoint.log":uptimenanos \
    -jar "$JAR" --variant "$4" --feed "$FEED" --rate "$5" --warmup "$WARMUP" $PIN $6 --out "$base.hgrm" \
    > "$base.log" 2>&1 || echo "   FAILED"
  grep -E '^all ' "$base.log" || true
  grep -E '^pinning' "$base.meta" || true
}

for rep in $(seq 1 "$REPS"); do
  run baseline-g1-shared            "$SHARED"   -XX:+UseG1GC baseline 1000000 ""                     "$rep"
  run baseline-g1-shared-presized   "$SHARED"   -XX:+UseG1GC baseline 1000000 "--book-capacity 65536" "$rep"
  run baseline-zgc-shared           "$SHARED"   -XX:+UseZGC  baseline 1000000 ""                     "$rep"
  run baseline-zgc-isolated         "$ISOLATED" -XX:+UseZGC  baseline 1000000 ""                     "$rep"
  run baseline-g1-isolated          "$ISOLATED" -XX:+UseG1GC baseline 1000000 ""                     "$rep"
  run spsc-spin-isolated            "$ISOLATED" -XX:+UseG1GC spsc     1000000 ""                     "$rep"
done

# Epsilon: large heap so nothing runs out; compare what's used at exit. One measured replay,
# no warmup replays, so every run allocates the same setup.
for rep in $(seq 1 "$REPS"); do
  for spec in "alloc-free|250000" "alloc-free|1000000" "spsc|250000" "spsc|1000000"; do
    IFS='|' read -r v rate <<< "$spec"
    base="$WORK/epsilon-$v-$rate-r$rep"
    echo "== rep $rep  epsilon $v @ $rate"
    taskset -c "$SHARED" "$JAVA" -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms4g -Xmx4g -Xlog:gc:file="$base.gc.log" \
      -jar "$JAR" --variant "$v" --feed "$FEED" --rate "$rate" --warmup 0 --warmup-passes 0 $PIN > "$base.log" 2>&1 || echo "   FAILED"
    grep -E 'Heap: .*used' "$base.gc.log" | tail -1 | sed -E 's/.*committed, /   used at exit: /'
  done
done
rm -f "$FEED"
