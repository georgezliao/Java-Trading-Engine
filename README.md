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

Other options: `--warmup-passes N` (default 2), `--cpus reader,engine` to pin the two threads (Linux only), `--wait spin|yield|park` for the `spsc` consumer, and `--outliers file.csv` to log individual slow messages with the engine's own processing time. `engine annotate` replays a feed and reports the book state at the logged positions.

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

`AllocationTest` measures bytes allocated by the engine thread over 300k warm messages for the pooled and ladder paths. `scripts/epsilon_proof.sh` runs `spsc` under Epsilon GC with a 128 MB heap, where nothing is ever freed, and checks it still matches the baseline hash. `alloc-free` doesn't reliably pass the same check because `ArrayBlockingQueue` allocates a node each time the engine thread waits.

## Scripts

| Script | What it does |
|---|---|
| `scripts/sweep.sh [variant]` | one JVM per rate across a list of rates, results in `results/local/` |
| `scripts/gc_compare.sh [variant]` | same run under several collectors, interleaved |
| `scripts/profile.sh [variant]` | JFR recording and, if installed, an async-profiler pass |
| `scripts/epsilon_proof.sh` | allocation check described above |
| `scripts/plot.py <run dirs>` | percentile plot and latency-vs-throughput table from `.hgrm` files |
| `scripts/oscar.sbatch` | the measurement job for Brown's Oscar cluster |
| `scripts/oscar_profile.sbatch` | follow-up job: outlier logs, safepoints, interrupt counts, `HashMap.resize` trace |

JMH benchmarks are in `benchmarks/`: `DecodeBench` (allocation of the decode step), `HandoffBench` (cross-thread handoff cost for `ArrayBlockingQueue`, both in-house rings, Agrona `OneToOneRingBuffer` and the Disruptor), and `LayoutDump` (JOL object layouts). Run them with `java -jar benchmarks/target/benchmarks.jar <name>`.

## Results

From Oscar job 7913307: an exclusive `48core` node (2x Intel Xeon Platinum 8268, 24 cores per socket, no SMT), JDK 25.0.2, G1 with a 1 GB heap unless noted, reader and engine pinned to cores 2 and 3, JVM housekeeping on core 1. Each configuration ran 5 times at each rate, interleaved, on a 2M-message burst feed with two warmup replays and the first 200k measured messages dropped. Latency is from each message's scheduled send time to the end of processing. Values are the median across the 5 runs.

| Config | p50 @ 1M/s | p99 @ 1M/s | p99.9 @ 1M/s | max @ 1M/s | Keeps up at 2M/s |
|---|---|---|---|---|---|
| `baseline` | 3.1 µs | 10.0 µs | 14.5 µs | 513 µs | 0 of 5 runs |
| `baseline` + ZGC | 3.3 µs | 10.7 µs | 95.4 µs | 572 µs | 0 of 5 |
| `alloc-free` | 3.1 µs | 9.8 µs | 12.7 µs | 77 µs | 1 of 5 |
| `ladder-book` | 3.0 µs | 9.4 µs | 11.7 µs | 38 µs | 1 of 5 |
| `spsc`, spin | 0.3 µs | 0.5 µs | 2.2 µs | 25 µs | 5 of 5 |
| `spsc`, yield | 0.4 µs | 0.8 µs | 2.6 µs | 28 µs | 5 of 5 |
| `spsc`, park | 26.8 µs | 53.9 µs | 54.4 µs | 62 µs | 5 of 5 |

"Keeps up" means the run delivered at least 99% of the target rate. When a run falls behind, the queue grows and latency goes to tens of milliseconds. The full table for all three rates (250k, 1M, 2M msg/s) with the p99.9 range across runs is in [`results/oscar/7913307/summary.md`](results/oscar/7913307/summary.md).

![p99.9 vs rate](results/oscar/7913307/p999_vs_rate.png)

![Latency percentiles at 1M msg/s](results/oscar/7913307/percentiles_1000000.png)

The percentile plot uses the run with the median p99.9 for each configuration. Two features of it are traced in [docs/findings.md](docs/findings.md): `baseline`'s ~500 µs maximum is a `java.util.HashMap` resize in the order book's id index, and the step in the `spsc` curves near p99.8 is the kernel's 1,000 Hz timer interrupt on the reader and engine cores. The follow-up job is `scripts/oscar_profile.sbatch` and its output is in `results/oscar/7954130/`.

The job script is `scripts/oscar.sbatch`; `.meta` files next to each histogram record the exact JVM flags, pinning and output hash. On Oscar there is no control over `isolcpus`, `nohz_full`, the frequency governor or turbo. `taskset` keeps the JVM on cores 1 to 3, but only the reader and engine threads are pinned to a single core each, so GC and JIT threads can still run on cores 2 and 3. Development runs on macOS aren't comparable to these: macOS has no thread pinning and moves threads between core types.

The Dockerfile builds and runs the engine anywhere (`docker build -t trading-engine . && docker run --rm trading-engine`). It hasn't been built yet, and container timings aren't used.

## Limitations

- One instrument and one toy strategy. The book handles price-time priority, partial fills and cancels, nothing else.
- The feed carries no timestamps. The `burst` profile changes message content (bigger price moves, more orders and cancels), not arrival timing.
- The reader thread busy-spins until each message is due, so process CPU time is at least one core regardless of wait strategy.
- Thread pinning uses OpenHFT affinity and does nothing on macOS. On Linux, each run's `.meta` file shows the CPU each thread actually ran on.
- The hyperthread-sibling configuration in `scripts/oscar.sbatch` needs a node with SMT; the `48core` nodes have none, so it hasn't run.

## Planned work

`scripts/oscar_checks.sbatch` is written but hasn't run yet. It tests three explanations in the findings that are still inferred.

- Does pre-sizing the baseline's `HashMap` (`--book-capacity`) remove its ~515 µs maximum?
- Does ZGC's p99.9 drop toward G1's when GC and JIT threads can't run on the reader and engine cores?
- Does `alloc-free` allocate more at 250k msg/s than at 1M msg/s because `ArrayBlockingQueue` allocates when the engine thread waits?

After that:

- Finer rate sweeps to locate the `ArrayBlockingQueue` knee between 1M and 2M msg/s, and to find where `spsc` falls behind, since it never did up to 2M.
- Poisson and clustered arrivals. Evenly spaced messages hide queueing and let the send schedule phase-lock with the timer tick.
- CPU placement: reader and engine on different sockets, and on hyperthread siblings on a node type with SMT.
- A real network hop (UDP or Aeron between two nodes). The feed currently comes from memory, so no number here includes a network.
- An input journal with replay recovery, using the output hash to prove the recovered state matches.
- Topics that could each become an experiment: huge pages and TLB misses, NUMA, `nanoTime` and TSC cost, kernel bypass (DPDK, Onload, AF_XDP), and Project Leyden's AOT cache as an alternative to warmup replays.

## Mistakes from this project

Each of these is written up in [docs/findings.md](docs/findings.md).

- [`alloc-free` first measured worse than `baseline`](docs/findings.md#warmup-was-harder-than-expected). The warmup cutoff caused a C2 deoptimization inside the measured run.
- [The first SPSC ring lost to Agrona](docs/findings.md#queue-handoff), because its consumer kept reading the producer's index.
- [The `spsc` step near p99.8 was blamed on expensive messages](docs/findings.md#tracing-the-two-tails-oscar-job-7954130). Per-message logs showed it was the timer interrupt.
- [`alloc-free` only passed the Epsilon check with room to spare from a single warmup replay](docs/findings.md#allocation-free-checked-two-ways). With a second replay it ran out of memory on Oscar, because `ArrayBlockingQueue` still allocates.

## Background reading

Talks:

- Gil Tene, [How NOT to Measure Latency](https://qconsf.com/sf2012/dl/qcon-sanfran-2012/slides/GilTene_HowNotToMeasureLatency.pdf) (slides): coordinated omission, which the open-loop harness is built to avoid.
- Carl Cook, [When a Microsecond Is an Eternity: High Performance Trading Systems in C++](https://isocpp.org/blog/2018/09/cppcon-2017-when-a-microsecond-is-an-eternity-high-performance-trading-syst) (CppCon 2017).
- David Gross, [When Nanoseconds Matter: Ultrafast Trading Systems in C++](https://cppcon.org/2024-keynote-david-gross/) (CppCon 2024 keynote).
- Martin Thompson, [Designing for Performance](https://gotocon.com/chicago-2015/speaker/Martin+Thompson) (GOTO Chicago 2015).
- Mike Acton, [Data-Oriented Design and C++](https://isocpp.org/blog/2015/01/cppcon-2014-data-oriented-design-and-c-mike-acton) (CppCon 2014): the data-layout argument behind `LadderBook`.
- Chandler Carruth, [Tuning C++: Benchmarks, and CPUs, and Compilers! Oh My!](https://isocpp.org/blog/2016/05/cppcon-2015-tuning-cpp-benchmarks-and-cpus-and-compilers-oh-my-chandler-car) (CppCon 2015).

Incidents:

- Knight Capital, 2012 (SEC Release 34-70694): new code missed one of eight servers, and a reused flag activated old Power Peg code on it. About 4 million executions in 45 minutes and a loss the SEC put above $460 million.
- [The Flash Crash, 2010](https://www.sec.gov/files/marketevents-report.pdf) (joint SEC and CFTC staff report): an automated sell of 75,000 E-mini contracts, paced by volume with no regard to price, during a period of thin liquidity. The role of high-frequency traders is still debated.
- [Nasdaq and the Facebook IPO, 2012](https://www.pcworld.com/article/464664/nasdaqs_facebook_glitch_came_from_race_conditions.html): a race in the opening auction between processing orders and calculating the final price. Tests had used 40,000 orders; the real auction had more than 496,000.
- [BATS IPO, 2012](https://www2.computerworld.co.nz/article/print/419885/bats_software_failure_behind_ipo_cancellation/): a bug in the exchange's auction software forced it to withdraw its own IPO the day it listed.
- [Mizuho and J-Com, 2005](https://www.aljazeera.com/news/2005/12/9/224m-typo-shakes-tokyo-exchange): a trader entered 610,000 shares at ¥1 instead of 1 share at ¥610,000, and cancellation attempts failed.
- [Tokyo Stock Exchange, 2020](https://www.aljazeera.com/economy/2020/10/1/in-its-worst-ever-glitch-tokyo-stock-exchange-trading-halted): a memory failure in a shared disk device, automatic failover that didn't work, and no tested way to restart, which cost a full day of trading.
- [The 2012 leap second](https://lkml.iu.edu/hypermail/linux/kernel/1207.0/02318.html): the Linux timekeeping code didn't notify `hrtimer`, so `futex` timeouts fired early and applications including Java and MySQL looped at 100% CPU.
