# Java Trading Engine

A small order-matching engine in Java, built as a test rig for one question: where does tail latency come from, and what actually moves it?

The engine itself is deliberately boring. One instrument, a limit order book, a toy mean-reversion strategy, two threads. The interesting part is the measurement setup and the sequence of variants, each one changing one thing and producing the exact same output as the baseline.

## What's here

```
engine/       the engine, feed generator, pipelines, measurement harness
benchmarks/   JMH benchmarks (decode, queue handoff) and a JOL layout dump
scripts/      sweeps, GC comparison, profiling, Epsilon proof, Oscar Slurm job, plotting
```

The pipeline: a reader thread replays a binary feed (32-byte little-endian frames: new order, cancel, tick) and hands messages to an engine thread that runs the book and strategy. Every run hashes every book event (rest, fill, cancel, cancel-reject) into one 64-bit value. A 5,000-message golden feed is committed with its hash, and every variant has to reproduce it.

## Variants

| variant | change from the previous one |
|---|---|
| `baseline` | decode each frame into a `record`, `ArrayBlockingQueue` handoff, `TreeMap<Long, ArrayDeque<Order>>` book |
| `alloc-free` | flyweight reads straight off the feed buffer, pooled orders, Agrona primitive maps; same `ArrayBlockingQueue` |
| `ladder-book` | book becomes direct-indexed price levels over primitive arrays |
| `spsc` | `ArrayBlockingQueue` replaced by a lock-free SPSC ring; consumer wait strategy via `--wait spin\|yield\|park` |

Plus `--cpus reader,engine` to pin threads (Linux), and any JVM flags for GC comparisons.

## How it's measured

- The feed is generated from a seed and loaded fully into memory before timing.
- Replay is open-loop. Message `i` is due at `start + i / rate`, and latency is measured from that due time, not from when the reader actually sent it. If the engine stalls, every message queued behind the stall is charged for it. This is the coordinated omission thing from Gil Tene's talk; a test stalls the engine for 20 ms and checks the messages behind it report it.
- Two HdrHistograms per run: all messages, and only ticks that triggered a strategy order.
- JIT warmup is two full unmeasured replays with a fresh engine, then the first 200k messages of the measured run are dropped too. Getting this right took a while; see below.
- Every histogram has a `.meta` file next to it: JVM, flags, GC, host, CPUs, pinning, output hash, GC count and CPU time during the run.

## Running it

Needs JDK 25 and Maven.

```bash
mvn -q -B verify
java -jar engine/target/engine.jar gen --profile burst --count 2000000 --seed 1 --out feeds/burst.bin
java -jar engine/target/engine.jar --variant spsc --feed feeds/burst.bin --rate 1000000 --warmup 200000
scripts/sweep.sh spsc
python3 scripts/plot.py results/local/<run>
```

Docker (`docker build -t trading-engine . && docker run --rm trading-engine`) works as a way to run it anywhere. Container numbers aren't used below.

## Results

All headline numbers come from `scripts/oscar.sbatch` on one node type of Brown's Oscar cluster: an exclusive `48core` node (Intel Cascade Lake), threads pinned to separate physical cores on one socket, configs interleaved within each repetition.

**TODO: Oscar results.** Latency-vs-throughput table, percentile plot, per-config spread across 5 reps.

What wasn't controlled on Oscar: no `isolcpus`, no `nohz_full`, no control over the frequency governor or turbo. It's a shared cluster and I don't have root. The node is exclusive, so nothing else runs on it, but the OS still does.

## Things I found along the way

These come from development on an M4 MacBook Air. macOS can't pin threads and moves them between performance and efficiency cores, so run-to-run tail latency varied by 3x or more. I don't compare Mac numbers against Oscar numbers, and I only trust Mac results that are about mechanism, not magnitude.

**The baseline tail on the Mac wasn't GC.** Sweeps at 200k to 2M msg/s had zero collections in the measured window, yet p99.9 was in the milliseconds. A CPU profile put about 40% of samples in `ArrayBlockingQueue`'s `signal()` and `unpark`, against 16% in decoding.

**Escape analysis didn't remove the decoded record.** A JMH benchmark where the record never leaves the method still allocates 35.7 B/op, same as when it escapes to a Blackhole. My guess is that `decode` returns one of three record types through one switch and C2 gives up on scalar replacement. I haven't confirmed that with `-XX:+PrintEscapeAnalysis`. JOL says the records are 48 and 32 bytes, which matches 35.7 B for the feed's message mix.

**The alloc-free variant first looked worse than baseline.** 5 of 6 runs had p99 in the tens to hundreds of ms. `-XX:+PrintCompilation` showed why: the warmup cutoff was an `index < warmup` branch that was always true until message 500k, so C2 compiled it as never-false, hit an uncommon trap mid-run and took ~48 ms to recompile while messages piled up. The fix was warming up with full separate replays. A second warmup pass was needed later because the ladder book's first-order branch did the same thing.

**Epsilon is a good allocation test.** With a 128 MB heap and no GC, `alloc-free` replays the 2M-message feed and matches the reference hash, `baseline` dies with OutOfMemoryError. A thread-allocation-counter test puts the warm ladder path at 0 bytes over 300k messages.

**My first SPSC ring lost to Agrona's.** JMH handoff cost, 1000-int bursts to a spinning consumer, median of 5 fork means:

| queue | ns/handoff |
|---|---|
| `ArrayBlockingQueue` | 189 |
| Disruptor (`EventPoller`) | 151 |
| my ring v1 (shared head/tail indices) | 65-76 |
| Agrona `OneToOneRingBuffer` | 48-52 |
| my ring v2 (slot-signalled) | 27.6 |

v1's consumer read the producer's `tail` on every drain, so that cache line bounced between cores constantly. Agrona's consumer never reads the producer's position; it reads a length header inside each record. v2 takes the same idea further: a zero slot means empty, the consumer clears each slot after reading it, and neither side reads the other's index. That's the ring the `spsc` variant uses. Padding is 128 bytes because M-series cores use 128-byte lines; JOL confirms the producer and consumer fields end up 136-144 bytes apart.

**Wait strategy shows up in the median.** With the ring, p50 went from about 2.5 us (`ArrayBlockingQueue` variants) to 0.2 us with a spinning consumer. Parking costs about 3 us at the median on macOS, which is roughly its wake-up time. Spinning costs a core. The reader thread always spins on the send schedule, so parking the consumer only saves about half a core of total CPU.

## Not done / open

- Oscar results (above).
- Hyperthread siblings vs separate physical cores for the spsc pair is in the Oscar job but hasn't run yet.
- The reader busy-spins between sends, which inflates CPU numbers for every wait strategy.
- `docker build` hasn't been tried.
