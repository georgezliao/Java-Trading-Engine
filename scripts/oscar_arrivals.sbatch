#!/bin/bash
#SBATCH -J trading-arrivals
#SBATCH -N 1
#SBATCH --exclusive
#SBATCH -t 01:00:00
#SBATCH -o results/oscar-arrivals-%j.out
#
# Fixed vs Poisson vs bursty arrivals at the same mean rate. Same pinning and warmup as
# oscar.sbatch, same feed, so the output hash must match across modes. Questions: does the
# ArrayBlockingQueue handoff drain bursts worse than the ring, does the timer-tick step near
# p99.8 change shape once arrivals stop being evenly spaced, and where does each variant fall
# behind?
#
#   sbatch --constraint=<feature> scripts/oscar_arrivals.sbatch
#
# Env: REPS (3), RATES ("250000 500000 1000000"), JAR, OUT_DIR.
set -euo pipefail
cd "${SLURM_SUBMIT_DIR:-$(dirname "$0")/..}"

REPS=${REPS:-3}
RATES=${RATES:-"250000 500000 1000000"}
WARMUP=200000
JAR=${JAR:-$PWD/engine/target/engine.jar}
RUNID=${SLURM_JOB_ID:-local-$(date +%Y%m%d-%H%M%S)}
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
"$JAVA" -version 2>&1 | grep -qE 'version "(2[5-9]|[3-9][0-9])' || { echo "need JDK 25+" >&2; exit 1; }

WORK=/tmp/trading-arrivals-$RUNID
DEST=${OUT_DIR:-$PWD/results/oscar/$RUNID}
mkdir -p "$WORK" "$DEST"
copy_back() { cp -r "$WORK"/. "$DEST"/ 2>/dev/null || true; echo "results in $DEST"; }
trap copy_back EXIT
{ echo "host=$(hostname)"; echo "kernel=$(uname -srm)"; echo "git=$(git rev-parse --short HEAD)"; echo "reps=$REPS"; echo "rates=$RATES"; } > "$WORK/run.txt"
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
HK=$2; READER=$3; ENGINE=$4
echo "housekeeping=$HK reader=$READER engine=$ENGINE" | tee "$WORK/cpus.txt"

FEED=/tmp/trading-arrivals-$RUNID.bin
"$JAVA" -jar "$JAR" gen --profile burst --count 2000000 --seed 1 --out "$FEED" > /dev/null

for rep in $(seq 1 "$REPS"); do
  for rate in $RATES; do
    for arrivals in fixed poisson bursty; do
      for v in baseline ladder-book spsc; do
        name="$v-$arrivals-$rate-r$rep"
        echo "== $name"
        taskset -c "$HK,$READER,$ENGINE" "$JAVA" -XX:+UseG1GC -Xms1g -Xmx1g -XX:+AlwaysPreTouch \
          -jar "$JAR" --variant "$v" --feed "$FEED" --rate "$rate" --warmup "$WARMUP" \
          --cpus "$READER,$ENGINE" --arrivals "$arrivals" --out "$WORK/$name.hgrm" \
          > "$WORK/$name.log" 2>&1 || echo "   FAILED"
        grep -E '^all ' "$WORK/$name.log" || true
      done
    done
  done
done
rm -f "$FEED"
