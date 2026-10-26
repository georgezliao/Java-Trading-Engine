# Findings

Notes from building and measuring the variants. The first section is from the pinned Oscar runs (job 7913307, setup in the README). Everything after it was measured during development on an M4 MacBook Air (JDK 25.0.2, G1 unless stated). macOS can't pin threads and moves them between performance and efficiency cores, so tail latency there varied by 3x or more between identical runs. I only use the Mac numbers for mechanisms, like whether something allocates or which queue is faster in a microbenchmark, and not for how big the tail is.

## Oscar: what pinning showed

Pinning changed the picture more than any of the variants did. On the Mac the `ArrayBlockingQueue` variants had p99.9 anywhere from 1 to 40 ms. Pinned on Oscar at 1M msg/s, all three sit between 11.7 and 14.5 µs p99.9 (median of 5 runs), and the spread across runs is a couple of µs. So most of what I was fighting on the Mac was the OS scheduler, not the engine.

Below p99.9 the three `ArrayBlockingQueue` variants are close to identical: p50 3.0 to 3.1 µs, p99 9.4 to 10.0 µs at 1M msg/s. Their medians are dominated by the handoff, since every message wakes the engine thread through the queue's lock. The differences show up past p99.9. In the percentile plot `baseline` climbs to about 500 µs, `alloc-free` to about 75 µs and `ladder-book` stays near 30 µs. `baseline` had zero GCs in those runs, so its far tail isn't a collection. My guess is a resize inside the boxed `HashMap` or `TreeMap`, which costs time proportional to the number of entries, but I haven't profiled it.

At 250k msg/s `baseline` did one G1 young collection per run and its max went to 4.4 ms, while at 1M msg/s it did none. It's the same feed, so the lower rate must allocate more in total. That fits `ArrayBlockingQueue` allocating a node every time the engine thread waits, since at 250k msg/s it waits on almost every message. This is an inference from the GC counts, not something I measured directly.

None of the `ArrayBlockingQueue` variants reliably keep up at 2M msg/s. `baseline` fell behind in all 5 runs, `alloc-free` and `ladder-book` in 4 of 5. Once the queue backs up, latency goes to tens of milliseconds. The knee is somewhere between 1M and 2M msg/s on this hardware and I didn't sweep finer than that.

The `spsc` ring with a spinning consumer is the clear change: p50 0.3 µs, p99 0.5 µs, p99.9 2.2 µs, and it keeps up at 2M msg/s in every run with the same p99.9. Max latency across a 1.8M-message measured window was about 25 µs. There is a step in its percentile curve near p99.8, from about 0.7 µs to 2 µs, that I can't explain yet. Triggering ticks do more work (the strategy sends an order into the book), so they're one candidate.

`--wait yield` behaves almost exactly like spin and uses the same 2.0 cores. On a dedicated core there's nothing else to run, so `Thread.yield()` returns immediately. `--wait park` has p50 27 µs and p99.9 54.5 µs at every rate, with almost no spread. `LockSupport.parkNanos(1000)` asks for 1 µs, but the sleep actually lasts about 50 µs, which matches Linux's default timer slack. Park drops CPU to about 1.1 cores, the remainder being the reader spinning on its schedule.

ZGC made `baseline` worse: p99.9 95 µs against 14.5 µs with G1 at 1M msg/s, and 247 µs against 11 µs at 250k. ZGC ran 18 collection cycles per run at 250k and 6 at 1M. `taskset` limits the JVM to three cores and only the reader and engine are pinned individually, so ZGC's concurrent threads can land on the same cores as them. That's my best explanation; I haven't checked where the GC threads actually ran.

The hyperthread-sibling experiment didn't run because these nodes have SMT turned off.

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

Epsilon GC never frees anything, so a run only survives if everything it allocates fits in the heap. With one warmup replay and a 128 MB heap, `alloc-free` finished the 2M-message feed with the baseline hash and `baseline` ran out of memory. That turned out to be less solid than it looked. With two warmup replays, `alloc-free` ran out of memory on Oscar and only just fit (119 of 128 MB) on the Mac. Its heap use climbs during the run at about 14 B per message, which I think is `ArrayBlockingQueue` allocating a wait node whenever the engine thread blocks. The engine code itself doesn't allocate per message, but the queue does. `spsc` has no such queue: it finishes at 80 MB, which is the 64 MB feed plus about 5 MB of setup per replay, and heap only grows when a replay starts. `scripts/epsilon_proof.sh` now uses `spsc` as the strict check.

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
