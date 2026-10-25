# Java Trading Engine

A single-instrument limit order book and trading strategy in Java, plus a harness for measuring its tail latency. A reader thread replays a binary market-data feed at a fixed rate, an engine thread runs the book and strategy, and each run writes latency histograms and a hash of every event the book produced.

The engine comes in several variants. Each one changes one part of the hot path (allocation, book data structure, thread handoff, wait strategy) and has to produce the same output hash as the baseline. Notes on what the experiments showed are in [docs/findings.md](docs/findings.md).

## Build and run

Requires JDK 25 and Maven 3.9.

```bash
mvn -q -B verify
java -jar engine/target/engine.jar gen --profile burst --count 2000000 --seed 1 --out feeds/burst.bin
java -jar engine/target/engine.jar --variant spsc --feed feeds/burst.bin --rate 1000000 --warmup 200000 --out run/spsc.hgrm
```

The last command prints throughput, the output hash, and p50/p99/p99.9/p99.99/max latency, and writes `run/spsc.hgrm`, `run/spsc-triggered.hgrm` and `run/spsc.meta`.

Other options: `--warmup-passes N` (default 2), `--cpus reader,engine` to pin the two threads (Linux only), `--wait spin|yield|park` for the `spsc` consumer.

## Variants

| `--variant` | Handoff | Decode | Book |
|---|---|---|---|
| `baseline` | `ArrayBlockingQueue` of records | new `record` per message | `TreeMap<Long, ArrayDeque<Order>>` |
| `alloc-free` | `ArrayBlockingQueue` of reused offset slots | `FrameView` flyweight over the feed buffer | `PooledOrderBook`: pooled orders, Agrona primitive maps |
| `ladder-book` | same as `alloc-free` | same as `alloc-free` | `LadderBook`: price levels indexed directly by price, orders in primitive arrays |
| `spsc` | `SlotSignalRing`, a lock-free single-producer single-consumer ring | same as `alloc-free` | `LadderBook` |

All books implement `Book` and share one set of contract tests. `PooledOrderBookTest` and `LadderBookTest` also replay generated feeds through both the new book and the `TreeMap` book and compare the event hashes.

## How latency is measured

- The feed is generated from a seed and read fully into memory before timing starts. Frames are 32 bytes, little-endian: new order, cancel, or tick (`Codec` documents the layout).
- Replay is open-loop. Message `i` is due at `start + i / rate`, and latency is measured from that due time to the moment the engine finishes the message. If the engine stalls, every message queued behind the stall records the delay. `OpenLoopTest` checks this by stalling the engine for 20 ms.
- Latency goes into two HdrHistograms: all messages, and only the ticks that made the strategy send an order.
- Before the measured run, the full feed is replayed twice with a fresh engine so JIT compilation and deoptimization happen outside the measurement. The first `--warmup` messages of the measured run are dropped as well.
- The `.meta` file records the JVM, flags, collector, host, CPU count, pinning, wait strategy, output hash, GC count and process CPU time for the run.

## Correctness

`GoldenOutputTest` replays a committed 5,000-message feed (`engine/src/test/resources/feeds/golden-burst-5k.bin`) through every variant and checks the output hash, event count and trigger count. If engine behavior changes on purpose, the constants in that test change in their own commit.

`AllocationTest` measures bytes allocated by the engine thread over 300k warm messages for the pooled and ladder paths. `scripts/epsilon_proof.sh` runs `alloc-free` under Epsilon GC with a 128 MB heap, where nothing is ever freed, and checks it still matches the baseline hash.

## Scripts

| Script | What it does |
|---|---|
| `scripts/sweep.sh [variant]` | one JVM per rate across a list of rates, results in `results/local/` |
| `scripts/gc_compare.sh [variant]` | same run under several collectors, interleaved |
| `scripts/profile.sh [variant]` | JFR recording and, if installed, an async-profiler pass |
| `scripts/epsilon_proof.sh` | allocation check described above |
| `scripts/plot.py <run dirs>` | percentile plot and latency-vs-throughput table from `.hgrm` files |
| `scripts/oscar.sbatch` | the measurement job for Brown's Oscar cluster |

JMH benchmarks are in `benchmarks/`: `DecodeBench` (allocation of the decode step), `HandoffBench` (cross-thread handoff cost for `ArrayBlockingQueue`, both in-house rings, Agrona `OneToOneRingBuffer` and the Disruptor), and `LayoutDump` (JOL object layouts). Run them with `java -jar benchmarks/target/benchmarks.jar <name>`.

## Measurement environment

Reported numbers come from `scripts/oscar.sbatch` on an exclusive Oscar `48core` node (Intel Cascade Lake). The job picks two physical cores on one socket for the reader and engine threads and a third for JVM housekeeping, restricts the JVM to them with `taskset`, and interleaves all configurations within each of 5 repetitions. Results are not in yet.

On Oscar there is no control over `isolcpus`, `nohz_full`, the frequency governor or turbo. Development runs on macOS are not comparable: macOS has no thread pinning and moves threads between performance and efficiency cores.

The Dockerfile builds and runs the engine anywhere (`docker build -t trading-engine . && docker run --rm trading-engine`). It hasn't been built yet, and container timings aren't used.

## Limitations

- One instrument and one toy strategy. The book handles price-time priority, partial fills and cancels, nothing else.
- The feed carries no timestamps. The `burst` profile changes message content (bigger price moves, more orders and cancels), not arrival timing.
- The reader thread busy-spins until each message is due, so process CPU time is at least one core regardless of wait strategy.
- Thread pinning uses OpenHFT affinity and does nothing on macOS. The Linux path hasn't been checked yet.
