# "Anyone can run it" path. Numbers from inside a container (especially Docker Desktop on macOS,
# which runs a Linux VM) are not comparable to the Oscar runs and aren't used in the README.
#
#   docker build -t trading-engine .
#   docker run --rm trading-engine                      # spsc @ 1M msg/s on a 2M-message feed
#   docker run --rm trading-engine baseline 500000      # <variant> <rate>
#   docker run --rm --cpuset-cpus=2-4 trading-engine spsc 1000000

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /src
COPY pom.xml .
COPY engine/pom.xml engine/
COPY benchmarks/pom.xml benchmarks/
RUN mvn -q -B dependency:go-offline
COPY engine engine
COPY benchmarks benchmarks
RUN mvn -q -B package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /src/engine/target/engine.jar /src/benchmarks/target/benchmarks.jar /app/
COPY scripts/docker-run.sh /app/run.sh
ENTRYPOINT ["/app/run.sh"]
CMD ["spsc", "1000000"]
