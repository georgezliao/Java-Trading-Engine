#!/bin/sh
# Container entry point: generate the feed inside the container, then one run of <variant> at <rate>.
set -eu
VARIANT=${1:-spsc}
RATE=${2:-1000000}
COUNT=${COUNT:-2000000}
FEED=/tmp/burst.bin
java -jar /app/engine.jar gen --profile burst --count "$COUNT" --seed 1 --out "$FEED"
exec java -Xms1g -Xmx1g -XX:+AlwaysPreTouch -jar /app/engine.jar \
  --variant "$VARIANT" --feed "$FEED" --rate "$RATE" --warmup 200000
