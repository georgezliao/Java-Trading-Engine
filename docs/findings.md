# Findings

Notes from building and measuring the variants. The first section is from the pinned Oscar runs (job 7913307, setup in the README). Everything after it was measured during development on an M4 MacBook Air (JDK 25.0.2, G1 unless stated). macOS can't pin threads and moves them between performance and efficiency cores, so tail latency there varied by 3x or more between identical runs. I only use the Mac numbers for mechanisms, like whether something allocates or which queue is faster in a microbenchmark, and not for how big the tail is.

## Oscar: what pinning showed

On the Mac the `ArrayBlockingQueue` variants had p99.9 anywhere from 1 to 40 ms. Pinned on Oscar at 1M msg/s, all three sit between 11.7 and 14.5 µs p99.9 (median of 5 runs), and the spread across runs is a couple of µs. The engine code is the same in both places, so I think most of the Mac tail came from macOS moving the threads around. I haven't measured that directly on the Mac.

Below p99.9 the three `ArrayBlockingQueue` variants are close to identical: p50 3.0 to 3.1 µs, p99 9.4 to 10.0 µs at 1M msg/s. Their medians are dominated by the handoff, since every message wakes the engine thread through the queue's lock. The differences show up past p99.9. In the percentile plot `baseline` climbs to about 500 µs, `alloc-free` to about 75 µs and `ladder-book` stays near 30 µs. Where `baseline`'s 500 µs comes from is traced in the next section.

At 250k msg/s `baseline` did one G1 young collection per run and its max went to 4.4 ms, while at 1M msg/s it did none. It's the same feed, so the lower rate must allocate more in total. That fits `ArrayBlockingQueue` allocating a node every time the engine thread waits, since at 250k msg/s it waits on almost every message. This is an inference from the GC counts, not something I measured directly.

None of the `ArrayBlockingQueue` variants reliably keep up at 2M msg/s. `baseline` fell behind in all 5 runs, `alloc-free` and `ladder-book` in 4 of 5. Once the queue backs up, latency goes to tens of milliseconds. The knee is somewhere between 1M and 2M msg/s on this hardware and I didn't sweep finer than that.

Replacing the queue with the `spsc` ring and a spinning consumer moved every percentile: p50 0.3 µs, p99 0.5 µs, p99.9 2.2 µs, and it keeps up at 2M msg/s in every run with the same p99.9. Max latency across a 1.8M-message measured window was about 25 µs. Its percentile curve has a step near p99.8, from about 0.7 µs to 2 µs, which also gets its own section below.

`--wait yield` behaves almost exactly like spin and uses the same 2.0 cores. On a dedicated core there's nothing else to run, so `Thread.yield()` returns immediately. `--wait park` has p50 27 µs and p99.9 54.5 µs at every rate, with almost no spread. `LockSupport.parkNanos(1000)` asks for 1 µs, but the sleep actually lasts about 50 µs, which matches Linux's default timer slack. Park drops CPU to about 1.1 cores, the remainder being the reader spinning on its schedule.

ZGC made `baseline` worse: p99.9 95 µs against 14.5 µs with G1 at 1M msg/s, and 247 µs against 11 µs at 250k. ZGC ran 18 collection cycles per run at 250k and 6 at 1M. `taskset` limits the JVM to three cores and only the reader and engine are pinned individually, so ZGC's concurrent threads can land on the same cores as them. That's my best explanation; I haven't checked where the GC threads actually ran.

The hyperthread-sibling experiment didn't run because these nodes have SMT turned off.

## Tracing the two tails (Oscar job 7954130)

For this follow-up the engine got an `--outliers` mode that logs, for every message over a latency threshold, both its end-to-end latency and how long the engine itself spent processing it (`OutlierRecorder`). The feed is deterministic, so `engine annotate` can rebuild the book at any position afterwards. The job ran each configuration 3 times with and 3 times without the logging, interleaved, plus safepoint logging, `/proc/interrupts` snapshots before and after every run, and one run with async-profiler tracing `HashMap.resize` calls slower than 20 µs. Runs with and without the logging had the same p50, p99 and max, so the extra `System.nanoTime()` per message didn't change the picture.

**`baseline`'s ~500 µs maximum is `HashMap` resizing.** The baseline book keeps an id index in a `java.util.HashMap<Long, Order>`, which doubles its table when it passes 75% load. Replaying the feed offline, the table resizes inside the measured window at messages 347,823, 615,713 and 1,629,898. In all three runs those were exactly the messages with large engine processing time:

| Feed position | Entries rehashed | Engine time on that message (3 runs) |
|---|---|---|
| 347,823 | 6,145 | 129 to 131 µs |
| 615,713 | 12,289 | 256 to 264 µs |
| 1,629,898 | 24,577 | 507 to 514 µs |

The cost doubles with the table, roughly 21 µs per thousand entries, and the last resize is the run's maximum. Messages that arrive during a resize queue behind it, which is what fills in `baseline`'s p99.99 (about 390 µs). The async-profiler trace shows the same calls on the engine thread under `OrderBook.add` → `HashMap.put` → `HashMap.resize`. They took up to 0.9 ms there, but tracing adds its own overhead. There were no safepoints in the measured window; the two G1 pauses in each baseline run happened during the warmup replays. The pooled and ladder books pre-size their Agrona id maps for 65,536 orders, so their id index never resizes on this feed (it peaks around 28k resting orders), which fits their much lower maximums.

**The `spsc` step near p99.8 is the kernel's timer interrupt.** I first guessed it was messages queueing behind an occasionally expensive message, because slow messages per second grew faster than the rate. That turned out to be wrong. With the per-message processing time logged, almost none of the slow messages were expensive themselves or queued behind an expensive one: in five of the six runs 3,860 to 4,164 slow messages (over 1.5 µs) per run had neither explanation. There were no safepoints at all during the `spsc` runs.

`/proc/interrupts` showed about 6,200 local timer interrupts (`LOC`) on each of the reader and engine cores per run at 1M msg/s, and about 3,200 at 2M msg/s, both roughly 1,000 per second per core. The kernel is built with `CONFIG_HZ=1000`. The measured window at 1M msg/s is 1.8 s, so about 1,800 ticks hit each core, 3,600 in total, which is 0.2% of 1.8M messages. That's where the step is. Each interrupt costs about 1.5 to 2 µs: the median latency of the slow messages was 2.0 µs and p90 2.7 µs. At 2M msg/s messages are 0.5 µs apart, so each tick delays about two of them, which is why the slow count per second grew with the rate. At 250k msg/s, messages are 4 µs apart and a tick usually lands while the engine is idle, so few messages cross the 1.5 µs threshold at all.

In one of the six runs, 588 messages did have engine processing times over 1 µs (median 2.5 µs). They were spaced in exact multiples of 1,000 messages, which at 1M msg/s is the 1 ms tick period. The send schedule and the timer are both periodic, so their relative phase stays fixed for a whole run. In that run the tick kept landing while a message was being processed, and in the others it landed between messages.

`nohz_full` on the reader and engine cores would stop most of these ticks, but that needs a kernel boot parameter and isn't available on Oscar.

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

## Checking the three explanations (Oscar job 8382578)

All on node1912, 1M msg/s, 3 reps each, 1 GB heap, reader on CPU 2 and engine on CPU 3. These numbers are from one job, so they're comparable with each other but not with job 7913307.

| config | p99.9 (µs, 3 reps) | max (µs, 3 reps) |
|---|---|---|
| baseline G1 shared | 13.7, 14.7, 14.7 | 513, 512, 516 |
| baseline G1 shared, presized map | 11.2, 11.8, 13.0 | 60, 58, 44 |
| baseline G1 isolated | 13.7, 14.5, 14.7 | 519, 517, 511 |
| baseline ZGC shared | 67.1, 68.2, 4057 | 582, 564, 4805 |
| baseline ZGC isolated | 66.0, 76.0, 76.8 | 576, 578, 572 |
| spsc spin isolated | 2.4, 2.5, 2.5 | 28, 43, 29 |

Presizing the `HashMap` took the max from about 515 µs to 44-60 µs, so the resize explanation held up. I'd predicted tens of µs, and that's what happened. p99.99 fell from about 375 µs to about 18 µs as well.

The ZGC explanation failed. I thought ZGC's tail came from its threads sharing cores with the pinned threads, so isolating them should have pulled p99.9 down toward 14 µs. It stayed around 70 µs and the max stayed near 575 µs, so sharing doesn't explain this tail. I don't have a replacement explanation yet. The 575 µs max looks a lot like G1's 515 µs resize max, but I haven't traced it. One shared rep hit 4057 µs at p99.9, and I haven't looked into that either. The `.meta` files record the pinned CPUs but not the taskset mask, so I still need to check from the script that isolation did what I meant it to.

The queue allocation check came out the way I expected. With Epsilon (4 GB, no warmup), alloc-free used 118-119 MB at exit at 250K msg/s and 98-101 MB at 1M, so the slower rate, with more waiting on the `ArrayBlockingQueue`, used more heap. spsc used exactly 75934 KB in all six runs. That fits allocation on queue waits, but heap at exit doesn't show where the bytes were allocated.
