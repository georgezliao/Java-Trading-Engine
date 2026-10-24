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
FEED=${FEED:-feeds/burst.bin}
FEED_COUNT=${FEED_COUNT:-2000000}
WARMUP=${WARMUP:-500000}
JVM_FLAGS=${JVM_FLAGS:-"-Xms1g -Xmx1g -XX:+AlwaysPreTouch"}
OUT_ROOT=${OUT_ROOT:-results/local}

# Prefer JDK 25 on macOS even if JAVA_HOME points somewhere older.
if [ -x /usr/libexec/java_home ] && /usr/libexec/java_home -v 25 >/dev/null 2>&1; then
  JAVA_HOME=$(/usr/libexec/java_home -v 25)
fi
JAVA=${JAVA_HOME:+$JAVA_HOME/bin/}java
export JAVA_HOME
if ! "$JAVA" -version 2>&1 | grep -qE 'version "(2[5-9]|[3-9][0-9])'; then
  echo "need JDK 25+, got: $("$JAVA" -version 2>&1 | head -1)" >&2
  exit 1
fi

JAR=engine/target/engine.jar
[ -f "$JAR" ] || mvn -q -B package -DskipTests

if [ ! -f "$FEED" ]; then
  $JAVA -jar "$JAR" gen --profile burst --count "$FEED_COUNT" --seed 1 --out "$FEED"
fi

RUN="$OUT_ROOT/$(date +%Y%m%d-%H%M%S)-$VARIANT"
mkdir -p "$RUN"
{
  echo "variant=$VARIANT"
  echo "feed=$FEED"
  echo "rates=$RATES"
  echo "warmup=$WARMUP"
  echo "jvm_flags=$JVM_FLAGS"
  echo "git=$(git rev-parse --short HEAD)$(git diff --quiet || echo -dirty)"
} > "$RUN/sweep.txt"
$JAVA -version 2> "$RUN/jvm.txt"

for rate in $RATES; do
  echo "== $VARIANT @ $rate msg/s"
  # shellcheck disable=SC2086
  $JAVA $JVM_FLAGS -jar "$JAR" --variant "$VARIANT" --feed "$FEED" --rate "$rate" \
    --warmup "$WARMUP" --out "$RUN/$VARIANT-$rate.hgrm" | tee "$RUN/$VARIANT-$rate.log"
done
echo "results in $RUN"
