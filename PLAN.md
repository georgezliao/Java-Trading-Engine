# Plan

The trading engine is the test rig; the latency investigation is the project. Every phase ends with a build that compiles, passes tests, and runs end to end. Each bullet is roughly one commit.

## Phase 0: Skeleton (working build)

- Two-module Maven build: `engine` (app) + `benchmarks` (JMH). Java 25, `maven-shade` for a runnable `engine.jar`.
- Dependencies: `agrona`, `HdrHistogram`, `junit-jupiter`; `jmh-core` + `jmh-generator-annprocess`, `disruptor`, `jol-core` in `benchmarks`; `net.openhft:affinity` added later (Phase 4).
- `Main` with plain arg parsing: `--variant`, `--feed`, `--rate`, `--out`.
- GitHub Actions: `mvn -B verify` on Temurin 25.

## Phase 1: Baseline engine (deliberately naive)

- Binary message format: fixed-size little-endian `NewOrder` / `Cancel` / `Tick` encoded via `ByteBuffer.order(LITTLE_ENDIAN)`. Encoder + decoder with round-trip tests.
- Feed generator in Java (reuses the encoder): seeded RNG, steady and burst profiles, writes `feeds/*.bin`.
- Baseline pipeline: reader thread → decode to a `record` → `ArrayBlockingQueue` → strategy/matching thread (`TreeMap<Long, ArrayDeque<Order>>` book, simple threshold strategy that emits orders on some ticks).
- Correctness tests: book matching, partial fills, cancels, deterministic output hash for a given feed. This hash is the oracle every optimized variant must match.

## Phase 2: Measurement harness (before any optimization)

- Open-loop load: replay at a fixed target rate; timestamp the *intended* send time, so latency includes queueing (coordinated-omission safe). Feed fully loaded into memory before timing.
- HdrHistogram per run, written as `.hgrm`; separate histograms for triggering ticks vs. all ticks.
- Warmup phase discarded; JVM flags and env captured into the results folder.
- Rate sweep script to find the knee (latency vs. throughput table, like exchange-core's).
- Python/matplotlib plotting script for percentile curves (reads `.hgrm`; no encoding in Python).
- Watch Gil Tene's "How NOT to Measure Latency" before writing this.

## Phase 3: Baseline characterisation

- JMH `-prof gc` on decode path to confirm the record really allocates (escape analysis may remove it in isolation).
- JFR + async-profiler allocation flame graph on the full replay.
- G1 vs ZGC on the baseline: how much of the tail is GC vs. our code.

## Phase 4: Experiments (one variant flag each, same oracle hash)

- **A. Allocation-free decode:** flyweight over the buffer (SBE-style), object pools, Agrona primitive maps. Proof: survives a full replay under `-XX:+UseEpsilonGC` with a small heap.
- **B. Book data structures:** primitive arrays / price-level ladder instead of `TreeMap`.
- **C. SPSC ring buffer:** own implementation (padding, `lazySet`/`setRelease`, batching) vs. `ArrayBlockingQueue` vs. Agrona `OneToOneRingBuffer` vs. Disruptor. JMH + end-to-end.
- **D. Thread pinning:** OpenHFT affinity, producer/consumer on separate physical cores (check `thread_siblings_list`); extra run on hyperthread siblings.
- **E. Wait strategy:** busy-spin vs. yield vs. park (Disruptor's three).
- JOL object layouts for the writeup.

## Phase 5: Oscar runs

- Build jar on the login node; Temurin 25 untarred to home if no module.
- `scripts/oscar.sbatch`: `--exclusive`, one `--constraint` node type, save `lscpu`/hostname/`java -version`, copy feeds to `/tmp`, interleave variants (A B C A B C…) × 5 reps in one job, `taskset`, async-profiler with `-e itimer`, copy results back at the end.
- Committed results under `results/<jobid>/`.

## Phase 6: Writeup

- README: method, hardware, what wasn't controlled (no isolcpus/turbo/governor), latency-vs-throughput table, percentile plots, negative results.
- Dockerfile as the "anyone can run it" path; container timings excluded from the report.

## Environment notes

- JDK 25 is installed (`/usr/libexec/java_home -v 25`) but the shell default is Java 8; set `JAVA_HOME`.
- Still to install: async-profiler (standalone), JDK Mission Control.
- Mac numbers are for development only; Docker on macOS runs in a VM and can't pin threads.
- Realistic budget: 50–70 h.
