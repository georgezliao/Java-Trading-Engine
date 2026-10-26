#!/bin/bash
#SBATCH -J trading-latency
#SBATCH -N 1
#SBATCH --exclusive
#SBATCH -t 03:00:00
#SBATCH -o results/oscar-%j.out
#
# Headline measurement job for Brown's Oscar cluster. Every number in the README comes from this
# script on one node type.
#
# One-time setup on a login node (no root needed):
#   module avail java maven                 # use the modules if there's a JDK 25, else:
#   cd ~ && curl -L -o temurin25.tar.gz \
#     "https://api.adoptium.net/v3/binary/latest/25/ga/linux/x64/jdk/hotspot/normal/eclipse"
#   tar xzf temurin25.tar.gz && export JAVA_HOME=$(ls -d ~/jdk-25*) && export PATH=$JAVA_HOME/bin:$PATH
#   (put the two exports in ~/.bashrc; batch jobs inherit them)
#   Maven: module load maven, or untar apache-maven-3.9.x-bin.tar.gz into ~ the same way.
#   Optional profiles: untar the async-profiler linux-x64 release into ~ and
#   export ASYNC_PROFILER_HOME=~/async-profiler-<version>-linux-x64
#
# Every time (building on the login node is fine; benchmarking there is NOT: login nodes are
# shared and noisy, and any number from them is meaningless):
#   git pull && mvn -q -B package -DskipTests
#   sinfo -o "%N %f"                     # pick ONE node feature and keep using it
#   mkdir -p results
#   sbatch --constraint=<feature> scripts/oscar.sbatch
#   squeue -u $USER                      # then results/oscar/<jobid>/ and results/oscar-<jobid>.out
#   python3 scripts/plot.py results/oscar/<jobid>
#
# For poking around, use an interactive allocation (interact / salloc), never the login node.
# If --exclusive sits in the queue too long on the exploratory account, email support@ccv.brown.edu.
#
# What it does:
#   - records lscpu, hostname, kernel, java -version, the Slurm job (incl. features), git sha
#   - generates the feed straight into node-local /tmp (deterministic from the seed), so no
#     network storage is touched while timing
#   - picks CPUs from /sys topology: skips core 0, takes two physical cores on socket 0 for the
#     reader/engine threads and a third for JVM housekeeping (GC, JIT); taskset limits the JVM
#     to those three
#   - interleaves every config within each rep (A B C ... A B C ...) over RATES x REPS
#   - one async-profiler pass per profiled variant (itimer CPU, alloc), since perf events are
#     usually locked down here, plus Epsilon runs (spsc must survive 128 MB)
#   - copies everything to results/oscar/<jobid>/ at the end, or on failure
#
# Without Slurm (e.g. a laptop) it runs as a smoke test: no taskset, no topology, small feed.
#
# Env overrides: REPS (5), RATES ("250000 1000000 2000000"), COUNT (2000000), WARMUP (200000),
#                ASYNC_PROFILER_HOME, HEAP (1g), OUT_DIR (results/oscar/<jobid>).
set -euo pipefail
cd "${SLURM_SUBMIT_DIR:-$(dirname "$0")/..}"

REPS=${REPS:-5}
RATES=${RATES:-"250000 1000000 2000000"}
COUNT=${COUNT:-2000000}
WARMUP=${WARMUP:-200000}
HEAP=${HEAP:-1g}
JAR=$PWD/engine/target/engine.jar

if [ -n "${SLURM_JOB_ID:-}" ]; then
  RUNID=$SLURM_JOB_ID
  if scontrol show job "$SLURM_JOB_ID" | grep -q 'Features=(null)'; then
    echo "submit with --constraint=<one node feature> so every run is on the same hardware" >&2
    exit 1
  fi
else
  RUNID=local-$(date +%Y%m%d-%H%M%S)
  echo "no Slurm job: smoke-test mode"
fi

# --- Java -------------------------------------------------------------------
if [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 25 >/dev/null 2>&1; then
  JAVA_HOME=$(/usr/libexec/java_home -v 25)
fi
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
"$JAVA" -version 2>&1 | grep -qE 'version "(2[5-9]|[3-9][0-9])' || { echo "need JDK 25+" >&2; exit 1; }
[ -f "$JAR" ] || { echo "build first on a login node: mvn -q -B package -DskipTests" >&2; exit 1; }

# --- Output: node-local while running, copied back at the end ---------------
WORK=/tmp/trading-latency-$RUNID
DEST=${OUT_DIR:-$PWD/results/oscar/$RUNID}
mkdir -p "$WORK" "$DEST"
copy_back() { cp -r "$WORK"/. "$DEST"/ 2>/dev/null || true; echo "results in $DEST"; }
trap copy_back EXIT

{
  echo "host=$(hostname)"
  echo "kernel=$(uname -srm)"
  echo "git=$(git rev-parse --short HEAD)$(git diff --quiet || echo -dirty)"
  echo "reps=$REPS"; echo "rates=$RATES"; echo "count=$COUNT"; echo "warmup=$WARMUP"; echo "heap=$HEAP"
} > "$WORK/run.txt"
"$JAVA" -version 2> "$WORK/jvm.txt"
command -v lscpu >/dev/null && lscpu > "$WORK/lscpu.txt"
[ -n "${SLURM_JOB_ID:-}" ] && scontrol show job "$SLURM_JOB_ID" > "$WORK/slurm-job.txt"
[ -r /proc/cmdline ] && cp /proc/cmdline "$WORK/kernel-cmdline.txt"

# --- CPU selection ------------------------------------------------------------
TOPO=/sys/devices/system/cpu
TASKSET=()
READER=""; ENGINE=""; SIBLING=""
if [ -d "$TOPO/cpu0/topology" ] && command -v taskset >/dev/null; then
  # First hardware thread of each physical core on socket 0, in core order.
  cores=$(for c in "$TOPO"/cpu[0-9]*; do
            n=${c##*cpu}
            [ "$(cat "$c/topology/physical_package_id")" = 0 ] || continue
            first=$(cut -d, -f1 "$c/topology/thread_siblings_list" | cut -d- -f1)
            [ "$first" = "$n" ] && echo "$n"
          done | sort -n)
  set -- $cores
  [ $# -ge 4 ] || { echo "need 4+ physical cores on socket 0, found $#" >&2; exit 1; }
  HOUSEKEEPING=$2; READER=$3; ENGINE=$4   # $1 (usually cpu0) left to the OS
  SIBLING=$(tr ',' '\n' < "$TOPO/cpu$READER/topology/thread_siblings_list" | tr '-' '\n' | grep -vx "$READER" | head -1 || true)
  TASKSET=(taskset -c "$HOUSEKEEPING,$READER,$ENGINE${SIBLING:+,$SIBLING}")
  {
    echo "housekeeping=$HOUSEKEEPING reader=$READER engine=$ENGINE reader_ht_sibling=${SIBLING:-none}"
    for n in "$HOUSEKEEPING" "$READER" "$ENGINE"; do
      echo "cpu$n siblings=$(cat "$TOPO/cpu$n/topology/thread_siblings_list")"
    done
  } > "$WORK/cpus.txt"
else
  echo "no /sys topology or taskset: running unpinned" | tee "$WORK/cpus.txt"
fi
pin() { if [ -n "$READER" ]; then echo "--cpus $1,$2"; fi; }

# --- Feed ---------------------------------------------------------------------
FEED=/tmp/trading-latency-$RUNID-burst.bin
"$JAVA" -jar "$JAR" gen --profile burst --count "$COUNT" --seed 1 --out "$FEED" > "$WORK/feed.txt"

# --- Configs: name | variant | extra JVM flags | extra engine args -------------
CONFIGS=(
  "baseline|baseline|-XX:+UseG1GC|$(pin "$READER" "$ENGINE")"
  "baseline-zgc|baseline|-XX:+UseZGC|$(pin "$READER" "$ENGINE")"
  "alloc-free|alloc-free|-XX:+UseG1GC|$(pin "$READER" "$ENGINE")"
  "ladder-book|ladder-book|-XX:+UseG1GC|$(pin "$READER" "$ENGINE")"
  "spsc-spin|spsc|-XX:+UseG1GC|$(pin "$READER" "$ENGINE") --wait spin"
  "spsc-yield|spsc|-XX:+UseG1GC|$(pin "$READER" "$ENGINE") --wait yield"
  "spsc-park|spsc|-XX:+UseG1GC|$(pin "$READER" "$ENGINE") --wait park"
)
if [ -n "$SIBLING" ]; then
  CONFIGS+=("spsc-spin-ht|spsc|-XX:+UseG1GC|--cpus $READER,$SIBLING --wait spin")
fi

run() { # name variant jvmflags engineargs rate rep
  local out="$WORK/$1-$5-r$6.hgrm"
  echo "== rep $6  $1  @ $5 msg/s"
  # shellcheck disable=SC2086
  ${TASKSET[@]+"${TASKSET[@]}"} "$JAVA" $3 -Xms"$HEAP" -Xmx"$HEAP" -XX:+AlwaysPreTouch \
    -jar "$JAR" --variant "$2" --feed "$FEED" --rate "$5" --warmup "$WARMUP" $4 --out "$out" \
    > "$WORK/$1-$5-r$6.log" 2>&1 || echo "   FAILED (see $1-$5-r$6.log)"
  grep -E '^all ' "$WORK/$1-$5-r$6.log" || true
}

for rep in $(seq 1 "$REPS"); do
  for rate in $RATES; do
    for cfg in "${CONFIGS[@]}"; do
      IFS='|' read -r name variant jvm args <<< "$cfg"
      run "$name" "$variant" "$jvm" "$args" "$rate" "$rep"
    done
  done
done

# --- Profiles (separate from measured runs) -------------------------------------
if [ -n "${ASYNC_PROFILER_HOME:-}" ]; then
  LIB=$(ls "$ASYNC_PROFILER_HOME"/lib/libasyncProfiler.* | head -1)
  for spec in "baseline|itimer" "baseline|alloc" "spsc|itimer"; do
    IFS='|' read -r variant event <<< "$spec"
    echo "== profile $variant ($event)"
    # shellcheck disable=SC2046
    ${TASKSET[@]+"${TASKSET[@]}"} "$JAVA" -Xms"$HEAP" -Xmx"$HEAP" \
      -agentpath:"$LIB"=start,event="$event",file="$WORK/profile-$variant-$event.html" \
      -jar "$JAR" --variant "$variant" --feed "$FEED" --rate 1000000 --warmup "$WARMUP" \
      $(pin "$READER" "$ENGINE") > "$WORK/profile-$variant-$event.log" 2>&1 || echo "   profile failed"
  done
else
  echo "ASYNC_PROFILER_HOME not set: skipping profiles"
fi

# --- Epsilon: spsc must survive a small no-GC heap; the others are for contrast ------------
for v in spsc alloc-free baseline; do
  echo "== epsilon $v"
  ${TASKSET[@]+"${TASKSET[@]}"} "$JAVA" -XX:+UnlockExperimentalVMOptions -XX:+UseEpsilonGC -Xms128m -Xmx128m -Xlog:gc \
    -jar "$JAR" --variant "$v" --feed "$FEED" --rate 1000000 --warmup "$WARMUP" $(pin "$READER" "$ENGINE") \
    > "$WORK/epsilon-$v.log" 2>&1 && echo "   survived" || echo "   ran out of memory (see log)"
done

rm -f "$FEED"
