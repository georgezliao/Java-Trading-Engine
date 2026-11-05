# Shared setup for the run scripts. Source it from the repo root.
# Sets JAVA (JDK 25+), JAR (built if missing), and FEED (generated if missing).

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

FEED=${FEED:-feeds/burst.bin}
FEED_COUNT=${FEED_COUNT:-2000000}
if [ ! -f "$FEED" ]; then
  "$JAVA" -jar "$JAR" gen --profile burst --count "$FEED_COUNT" --seed 1 --out "$FEED"
fi

# CPUS="reader,engine" pins the two pipeline threads (Linux only; ignored on macOS).
PIN=${CPUS:+--cpus $CPUS}
# WAIT=spin|yield|park sets the spsc consumer wait strategy.
PIN="$PIN${WAIT:+ --wait $WAIT}"
# ARRIVALS=fixed|poisson|bursty sets the arrival process.
PIN="$PIN${ARRIVALS:+ --arrivals $ARRIVALS}"

# new_run <name>: make results/local/<timestamp>-<name>, record git + java, print the path.
new_run() {
  local dir="${OUT_ROOT:-results/local}/$(date +%Y%m%d-%H%M%S)-$1"
  mkdir -p "$dir"
  echo "git=$(git rev-parse --short HEAD)$(git diff --quiet || echo -dirty)" > "$dir/git.txt"
  "$JAVA" -version 2> "$dir/jvm.txt"
  echo "$dir"
}
