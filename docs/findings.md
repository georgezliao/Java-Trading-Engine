# Findings

Notes from building and measuring the variants. Everything here so far was measured on an M4 MacBook Air (JDK 25.0.2, G1 unless stated). macOS can't pin threads and moves them between performance and efficiency cores, so tail latency there varied by 3x or more between identical runs. I only use the Mac numbers for mechanisms, like whether something allocates or which queue is faster in a microbenchmark, and not for how big the tail is. The Oscar results will go in a separate section.

## The baseline tail wasn't GC

The original plan assumed allocation and GC would be the main source of tail latency. Rate sweeps from 200k to 2M msg/s had zero collections in the measured window with a 1 GB heap, and p99.9 was still in the milliseconds. A CPU profile of the baseline at 1M msg/s put about 31% of samples in `AbstractQueuedSynchronizer.signal()` and 9% in `LockSupport.unpark`, against 16% in `Codec.decode`. So most of the cost was the `ArrayBlockingQueue` handoff waking the other thread.

The allocation profile (JFR) did show where the baseline allocates: `Codec.decode` 22% of sampled allocation and `Long.valueOf` 17%, the latter from boxed keys in the `TreeMap` and `HashMap` the book uses.

## Escape analysis didn't remove the decoded record

`DecodeBench` decodes a frame two ways: once handing the record to a Blackhole, and once returning only a sum of its fields so the record never escapes. Both allocate 35.7 B/op. I expected C2 to scalar-replace the record in the second case. My guess is that it gives up because `decode` returns one of three record types through a single switch, so the allocations merge into one value. I haven't confirmed that with `-XX:+PrintEscapeAnalysis`.

JOL puts `NewOrder` at 48 bytes and `Cancel` and `Tick` at 32, which works out to 35.7 B for the feed's mix of message types.

## Warmup was harder than expected

The first end-to-end comparison had `alloc-free` losing to `baseline` in 5 of 6 runs, with p99 in the tens to hundreds of milliseconds. The pooled book alone was faster than the `TreeMap` book when run single-threaded (153 to 194 ms vs 187 to 250 ms for 2M messages), so the problem was somewhere else.

`-XX:+PrintCompilation` showed it. The warmup cutoff was an `index < warmup` check in the latency recorder. That branch went one way for the first 500k messages, C2 compiled it as if the other side never happened, and when the cutoff passed it hit an uncommon trap, threw away the compiled engine loop and recompiled it. That took about 48 ms, and with `ArrayBlockingQueue` every queued message then needs a wake-up to drain, so the backlog stuck around.

Replacing the cutoff with full unmeasured replays mostly fixed it. A second warmup pass turned out to be needed for the ladder book: the branch that sets up its price window on the first order runs once per replay, so it deopted at the start of the measured run too. With one warmup pass `ladder-book` had 20 to 45 ms tails; with two, 1 to 7 ms.

## Allocation-free, checked two ways

`AllocationTest` reads the engine thread's allocated-bytes counter over 300k warm messages: about 8 KB for the pooled book (probably a resize somewhere) and 0 bytes for the ladder book.

Epsilon GC never frees anything, so a run only survives if everything it allocates fits in the heap. With a 128 MB heap, `alloc-free` replays the 2M-message feed and matches the baseline hash; `baseline` runs out of memory. `alloc-free` still grows by about 8 MB per replay because each replay builds a new pooled book and new histograms, so it isn't literally zero-allocation over a whole run, but it doesn't grow per message.

## Book data structures

Single-threaded, same frame-decoding path for all three, 2M messages, steady repetitions:

| Book | ns per message |
|---|---|
| `OrderBook` (`TreeMap`) | 71 to 100 |
| `PooledOrderBook` | 36 to 38 |
| `LadderBook` | 29 to 31 |

Most of the gain is from dropping the boxed `TreeMap` and per-order allocation. The direct-indexed ladder adds a smaller improvement on top.

## Queue handoff

`HandoffBench` sends bursts of 1000 ints from the benchmark thread to a consumer thread that busy-spins, and waits until all of them arrive. Median of 5 fork means:

| Queue | ns per handoff |
|---|---|
| `ArrayBlockingQueue` | 189 |
| Disruptor (`EventPoller`) | 151 |
| `SpscIntRing` (shared head/tail indices) | 65 to 76 |
| Agrona `OneToOneRingBuffer` | 48 to 52 |
| `SlotSignalRing` | 27.6 |

My first ring, `SpscIntRing`, lost to Agrona. Its consumer reads the producer's `tail` index on every drain and the producer writes it on every offer, so that cache line moves between cores constantly. Agrona's consumer never reads the producer's position; it reads a length header stored with each message. `SlotSignalRing` uses the slots themselves as the signal: a slot holding 0 is empty, the consumer zeroes each slot after reading it, and neither side reads the other's index. That made it 2.3x faster than `SpscIntRing` and faster than Agrona in this benchmark. Fork-to-fork spread also got smaller (24 to 29 ns, against 61 to 78 for `SpscIntRing`). I don't know for sure why it beats Agrona by that much; Agrona's 8-byte record headers and alignment are one difference.

The padding in both rings is 128 bytes because Apple M-series cores use 128-byte cache lines. JOL shows the producer and consumer fields 136 to 144 bytes apart, and also that the JVM packed the read-only `mask` field into the gap after the object header.

Forks in this benchmark were bimodal on the Mac. In some forks Agrona sat around 17 to 30 ns and in others 50 to 65 ns, presumably depending on which cores the two threads landed on.

## Wait strategy

With `SlotSignalRing` and a spinning consumer, p50 dropped from about 2.5 µs (the `ArrayBlockingQueue` variants) to 0.2 µs. Two runs per setting:

| `--wait` | Process CPU (cores) | p50 at 1M msg/s | p50 at 100k msg/s |
|---|---|---|---|
| `spin` | 1.75 to 1.99 | 0.2 µs | 0.2 to 0.3 µs |
| `yield` | 1.46 to 1.97 | 0.6 µs (one run 26 µs) | 0.4 µs |
| `park` | 1.53 to 1.64 | 3.0 to 4.2 µs | 2.7 to 3.2 µs |

Parking costs about 3 µs at the median, which is roughly how long macOS takes to wake the thread. CPU never drops much below 1.5 cores because the reader thread also spins until each message is due. Tails ranged from 1 to over 100 ms across all three strategies with no pattern I could see, which is the Mac again.

The best `spsc` runs on the Mac were p99 0.6 to 0.9 µs and p99.9 6 to 28 µs. Other runs of the same configuration had millisecond spikes. Pinned runs on Oscar should show which of those is typical.
