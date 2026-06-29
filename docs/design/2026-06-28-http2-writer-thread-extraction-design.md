# Extract the HTTP/2 writer thread into `HTTP2WriterThread` — design

**Date:** 2026-06-28
**Status:** Draft — awaiting review
**Scope:** `org.lattejava.http.server.internal.h2` — extract the outbound writer-thread machinery out of `HTTP2Connection` into a new `HTTP2WriterThread implements Runnable`. Touches `HTTP2Connection`, `HTTP2OutputStream`, and two unit tests. No wire-behavior change.

## Problem

`HTTP2Connection` mixes two unrelated concerns:

1. **Protocol/reader logic** — parsing inbound frames, the stream state machine, HPACK, GOAWAY semantics, rate limits.
2. **Outbound transport** — a single virtual thread that drains a `BlockingQueue<HTTP2Frame>`, coalesces a batch, writes each frame through `HTTP2FrameWriter`, and flushes once per batch.

The transport concern is currently spread across the connection as loose pieces:

- Four fields: `writerQueue`, `writerThread` (the `Thread`), `writerDead` (a volatile death flag), and the `WRITER_BATCH_SIZE` constant.
- A `public static runWriterLoop(...)` — the drain/batch/flush loop, already pulled out as a static method purely so it can be unit-tested.
- An inline lambda in `run()` that spawns the writer virtual-thread, runs the loop, and in its `finally` sets `writerDead` and interrupts the reader.
- A private `enqueueForWriter(...)` — the reader-side bounded-wait enqueue that declares writer death on a 5-second timeout.
- Two `writerThread.join(...)` sites (the `run()` teardown `finally` and `shutdown()`).

Because the queue is shared, it is also handed out by reference: `HTTP2OutputStream` takes the raw `BlockingQueue` (so handler threads can enqueue DATA frames directly), and the handler error-cleanup path in `spawnHandlerThread` calls `writerQueue.offer(...)` directly. The result is that "the writer thread" is not a thing you can point at — it is a half-dozen fields, lambdas, and a leaked queue scattered through a 1167-line class.

This refactor collects all of it into one cohesive class whose single responsibility is: *own the outbound queue and the thread that serializes it to the socket.*

## Goals

- One class, `HTTP2WriterThread implements Runnable`, owns the outbound queue, the writer thread's lifecycle, the drain/batch/flush loop, the `closed` flag, and every enqueue policy.
- The `BlockingQueue` becomes a **private** field of that class. Producers (the reader thread, handler threads, the error-cleanup path) enqueue **through methods** on the writer object; nobody holds the raw queue.
- `HTTP2Connection` keeps only protocol concerns and a single `volatile HTTP2WriterThread writer` reference, which it talks to through a small, intention-revealing API.
- **No behavior change.** Same 32-frame batching, same single-flush-per-batch, same writer-shutdown sentinel, same writer-closed-flag + reader-interrupt teardown, same GOAWAY-before-close ordering, same three enqueue policies with their distinct timeouts.
- The loop body lives directly in `run()` (no separate static entry point). The existing `HTTP2WriterCoalescingTest` drives `run()` directly via a queue-injecting test constructor.

## Non-goals

- No change to frame encoding (`HTTP2FrameWriter`), flow control (`HTTP2OutputStream`'s window acquire/release logic), HPACK, or the stream state machine.
- GOAWAY **semantics** stay in `HTTP2Connection`: `goawaySent`, `goAway(...)`, and the GOAWAY-frame construction in `shutdown()` are protocol decisions, not transport. The writer only learns about GOAWAY as "another frame to write," plus the private shutdown sentinel.
- No change to the connection's TCP teardown (`shutdownOutput` / drain / `close`) or the handler-thread interrupt set.
- No unrelated refactoring of `HTTP2Connection`'s reader loop beyond swapping `enqueueForWriter(x)` call sites for `writer.enqueueOrCloseWriter(x)`.

## Architecture

### What moves, what stays

| Element | Today (in `HTTP2Connection`) | After |
|---|---|---|
| Outbound queue | `private final BlockingQueue<HTTP2Frame> writerQueue` | **private** field of `HTTP2WriterThread` |
| Batch size | `WRITER_BATCH_SIZE` constant | `HTTP2WriterThread` constant |
| Closed flag | `volatile boolean writerDead` | `volatile boolean closed`, exposed via `isClosed()` |
| Thread handle | `volatile Thread writerThread` | internal to `HTTP2WriterThread`; started/joined via methods |
| Drain/flush loop | `public static runWriterLoop(...)` | inlined into instance `run()` |
| Spawn + close `finally` | inline lambda in `run()` | `HTTP2WriterThread.start()` + `run()`'s `finally` |
| Reader-side enqueue | `private boolean enqueueForWriter(...)` | `HTTP2WriterThread.enqueueOrCloseWriter(...)` |
| Handler DATA enqueue | `HTTP2OutputStream` → `writerQueue.put(...)` | `HTTP2WriterThread.enqueueBlocking(...)` |
| Cleanup-path enqueue | `writerQueue.offer(f, 100ms)` in `spawnHandlerThread` | `HTTP2WriterThread.tryEnqueue(f, 100ms, ...)` |
| Shutdown sentinel | `new GoawayFrame(-1, 0, …)` built in connection | `HTTP2WriterThread.requestStop()` (sentinel encoding is now private) |
| GOAWAY semantics | `goawaySent`, `goAway(...)`, `shutdown()` GOAWAY frame | **unchanged**, stays in `HTTP2Connection` |

### `HTTP2WriterThread` — the new class

A `Runnable` whose `run()` is the drain loop. It owns its queue and its thread; the connection drives it through methods only. Sketch (field/method order per the project's `code-conventions` rule):

```java
public class HTTP2WriterThread implements Runnable {
  // 32: see existing WRITER_BATCH_SIZE rationale (≈512 KB max batch at 16 KiB frames).
  private static final int BATCH_SIZE = 32;

  // The writer-shutdown sentinel: a GOAWAY whose lastStreamId is -1 (never valid for a real GOAWAY).
  // Encoding is private to this class — callers say requestStop(), not new GoawayFrame(-1, …).
  private static final HTTP2Frame SHUTDOWN_SENTINEL = new HTTP2Frame.GoawayFrame(-1, 0, new byte[0]);

  private final Logger logger;
  private final OutputStream out;
  private final BlockingQueue<HTTP2Frame> queue;   // PRIVATE — never handed out
  private final Thread readerThread;               // interrupted when the writer closes
  private final HTTP2FrameWriter writer;

  private volatile boolean closed;
  private volatile Thread thread;                  // the writer virtual-thread, set by start()

  // Production constructor. The queue is created here and never escapes.
  public HTTP2WriterThread(HTTP2FrameWriter writer, OutputStream out, Thread readerThread, Logger logger) {
    this(writer, out, readerThread, logger, new LinkedBlockingQueue<>(128));
  }

  // Test constructor — injects a caller-controlled queue so HTTP2WriterCoalescingTest can pre-load frames,
  // drive run() directly, and assert on the output stream. Production delegates here with a fresh queue.
  public HTTP2WriterThread(HTTP2FrameWriter writer, OutputStream out, Thread readerThread, Logger logger,
                           BlockingQueue<HTTP2Frame> queue) {
    this.writer = writer;
    this.out = out;
    this.readerThread = readerThread;
    this.logger = logger;
    this.queue = queue;
  }

  // Test seam — wraps a caller-supplied queue so HTTP2OutputStreamFragmentationTest can inject DATA frames
  // via enqueueBlocking() and drain them for assertions, with no socket/thread. Only enqueueBlocking()/tryEnqueue()
  // are valid on this instance (frame writer / reader thread / logger are null). Mirrors HTTP2OutputStream's test ctor.
  public static HTTP2WriterThread forQueue(BlockingQueue<HTTP2Frame> queue) {
    return new HTTP2WriterThread(null, null, null, null, queue);
  }

  // Spawns the "h2-writer" virtual thread running this, captures the handle.
  public void start() { thread = Thread.ofVirtual().name("h2-writer").start(this); }

  public boolean isClosed() { return closed; }

  // Bounded, best-effort join used by both the connection's teardown finally and shutdown().
  // Null-guards the internal thread (no-op if start() has not run yet — mirrors today's `if (w != null)`),
  // swallows InterruptedException, and re-interrupts the caller, so call sites stay clean.
  public void join(Duration timeout) { … }

  // Reader/connection-side enqueue: bounded 5 s wait; on timeout close the writer and drop; never throws.
  // (Verbatim port of enqueueForWriter.) Used for PING acks, WINDOW_UPDATE, RST_STREAM, SETTINGS ack,
  // response HEADERS/trailers, and the graceful GOAWAY.
  public boolean enqueueOrCloseWriter(HTTP2Frame frame) { … }

  // Handler DATA path: blocks until space, surfaces interrupt as InterruptedIOException. The blocking IS the
  // queue-full back-pressure on the producing handler. (Verbatim port of HTTP2OutputStream.enqueue.)
  public void enqueueBlocking(HTTP2Frame frame) throws InterruptedIOException { … }

  // Best-effort timed enqueue, non-fatal (never closes the writer), for the handler error-cleanup RST_STREAM.
  // (Verbatim port of the spawnHandlerThread offer.) Caller handles the boolean and InterruptedException.
  public boolean tryEnqueue(HTTP2Frame frame, long timeout, TimeUnit unit) throws InterruptedException { … }

  // Enqueue the private shutdown sentinel so the loop flushes what's queued and exits. Uses
  // enqueueOrCloseWriter()'s policy (a no-op if the writer is already closed).
  public boolean requestStop() { return enqueueOrCloseWriter(SHUTDOWN_SENTINEL); }

  // The drain/batch/flush loop. Blocking head-take preserves idle-park; the non-blocking drainTo coalesces
  // whatever concurrent producers have already enqueued so the syscall + queue-lock are amortized across the
  // batch with a single flush. Exits on the shutdown sentinel; an IOException from a mid-batch write (broken
  // pipe / peer reset) is caught below, marking the writer closed and signaling the reader to tear down.
  @Override
  public void run() {
    try {
      List<HTTP2Frame> batch = new ArrayList<>(BATCH_SIZE);
      while (true) {
        HTTP2Frame head = queue.take();
        batch.add(head);
        queue.drainTo(batch, BATCH_SIZE - 1);
        for (HTTP2Frame f : batch) {
          if (f instanceof HTTP2Frame.GoawayFrame g && g.lastStreamId() == -1) {
            out.flush();   // flush pre-sentinel frames, then exit; post-sentinel frames are discarded
            return;
          }
          writer.writeFrame(f);
        }
        out.flush();
        batch.clear();
      }
    } catch (Exception e) {
      logger.debug("Writer thread ended unexpectedly; signaling reader", e);
    } finally {
      closed = true;
      readerThread.interrupt();
    }
  }
}
```

Two design points worth calling out:

- **`readerThread` is final and non-null.** Today the close-signaling `finally` reads a `volatile Thread readerThread` and null-checks it. Because the writer is constructed inside `HTTP2Connection.run()` *after* the reader thread exists, we pass `Thread.currentThread()` into the constructor and the null-check disappears.
- **The sentinel encoding is now private.** `requestStop()` hides the magic `GoawayFrame(-1, …)` value. The connection no longer constructs it in two places; the loop's sentinel detection and the constant live together in one file.

### Wiring inside `HTTP2Connection`

The writer is built *after* the SETTINGS exchange — the same point in `run()` where `writerThread` is spawned today — because the `HTTP2FrameWriter` may be rebuilt if the peer advertises a larger `MAX_FRAME_SIZE`. The local `HTTP2FrameWriter` (today named `writer`) is renamed to `frameWriter` to free the name; the new field is `private volatile HTTP2WriterThread writer`.

```java
// after the SETTINGS exchange and the possible frameWriter rebuild:
writer = new HTTP2WriterThread(frameWriter, out, Thread.currentThread(), logger);
writer.start();
```

`volatile` for the same reason `writerThread` is volatile today: `shutdown()` runs on the acceptor thread and reads it, while `run()` (reader thread) writes it.

Call-site swaps (mechanical, no logic change):

- `enqueueForWriter(x)` → `writer.enqueueOrCloseWriter(x)` everywhere: `goAway`, `rstStream`, `handlePing`, `handleSettings`, `handleWindowUpdate`, `handleData`, `handleHeadersFrame`, `commitHeaders`, `commitTrailers`.
- Reader loop early-exit `if (writerDead)` → `if (writer.isClosed())`.
- `run()` frame-loop `finally`: `enqueueForWriter(new GoawayFrame(-1, …))` → `writer.requestStop()`.
- `spawnHandlerThread` cleanup: `writerQueue.offer(rst, 100, MILLISECONDS)` → `writer.tryEnqueue(rst, 100, MILLISECONDS)` (the surrounding `catch (InterruptedException)` stays).
- `commitHeaders` sink construction: `new HTTP2OutputStream(stream, writerQueue, connectionSendWindow, …)` → `new HTTP2OutputStream(stream, writer, connectionSendWindow, …)`.
- `run()` teardown `finally`: `if (writerThread != null) writerThread.join(…)` → `if (writer != null) writer.join(Duration.ofSeconds(5))` (the `try/catch (InterruptedException)` is absorbed into `join`).
- `shutdown()`: `enqueueForWriter(GOAWAY(NO_ERROR))` then `enqueueForWriter(sentinel)` → `writer.enqueueOrCloseWriter(GOAWAY(NO_ERROR))` then `writer.requestStop()`; the `writerThread.join(1s)` becomes `writer.join(Duration.ofSeconds(1))`. All three are guarded by a single `HTTP2WriterThread w = writer; if (w != null) { … }` snapshot, because `shutdown()` runs on the acceptor thread and the `writer` field is null until the SETTINGS exchange completes. Today this path is implicitly safe because `enqueueForWriter` references the always-non-null `writerQueue` field; the guard restores that safety now that the whole writer object can be absent.

Deleted from `HTTP2Connection`: the `writerQueue`, `writerThread`, `writerDead` fields, the `WRITER_BATCH_SIZE` constant, the `runWriterLoop` static method, the writer-spawn lambda body, and the `enqueueForWriter` method. The class Javadoc's threading-model bullet is updated to point at `HTTP2WriterThread`.

### `HTTP2OutputStream` change

`HTTP2OutputStream` stops taking a raw `BlockingQueue` and takes the `HTTP2WriterThread` instead. Its private `enqueue(DataFrame)` calls `writer.enqueueBlocking(frame)`, which has the identical signature/behavior (blocks, throws `InterruptedIOException` on interrupt) as today's `writerQueue.put(...)` wrapper — so the method body is effectively unchanged.

- Production ctor: `HTTP2OutputStream(HTTP2Stream stream, HTTP2WriterThread writer, HTTP2ConnectionWindow connectionWindow, int peerMaxFrameSize)`.
- Standalone/test ctor: `HTTP2OutputStream(HTTP2Stream stream, HTTP2WriterThread writer, int peerMaxFrameSize)` → delegates with the unbounded connection window, exactly as today.

## Naming

| Concept | Name |
|---|---|
| Wire-frame encoder (existing) | `HTTP2FrameWriter` — unchanged |
| Outbound queue + thread + drain loop (new) | `HTTP2WriterThread` |
| The `HTTP2FrameWriter` local inside `HTTP2Connection.run()` | renamed `writer` → `frameWriter` |
| The new field on `HTTP2Connection` | `writer` (type `HTTP2WriterThread`) |

`HTTP2WriterThread` is a `Runnable`, not a `Thread` subclass; the suffix names its role (the code that runs on the writer thread), consistent with `HTTPServerAcceptorThread` / `ConnectionReaperThread` elsewhere in the server package, which are also role-named thread bodies.

## Error handling and lifecycle (faithful port)

The three enqueue policies differ deliberately and are preserved exactly:

- **`enqueueOrCloseWriter` (reader/connection side):** 5-second bounded `queue.offer`; on timeout, log, set `closed`, return `false` (drop). Short-circuits to `false` if already `closed`. Catches `InterruptedException`, re-interrupts, returns `false`. The connection's callers already ignore the boolean on fire-and-forget paths.
- **`enqueueBlocking` (handler DATA):** blocks on `queue.put`; on interrupt, re-interrupt and throw `InterruptedIOException`. The teardown path's handler-thread interrupts are what unblock it — unchanged.
- **`tryEnqueue` (handler error cleanup):** 100 ms `queue.offer`, returns the boolean, never sets `closed`; deliberately short so connection teardown never blocks. The caller logs a dropped frame.

Writer-close signaling is unchanged: when the loop in `run()` falls out — either the clean sentinel `return` or an exception (broken pipe / peer reset mid-write) caught by its `catch` — `run()`'s `finally` sets `closed = true` and interrupts the reader, so a reader parked on a full-queue `enqueueOrCloseWriter` or blocked in `readFrame` unblocks and tears down. The reader's own `finally` then interrupts the handler virtual-threads, exactly as today.

GOAWAY-before-close ordering is unchanged: `shutdown()` enqueues `GOAWAY(NO_ERROR)`, then `requestStop()` (sentinel), then `join`s the writer so the GOAWAY reaches the wire before the acceptor interrupts the connection thread. The `run()` teardown `finally` likewise `join`s before `socket.close()`.

## Testing

No new behavior, so existing HTTP/2 integration coverage (`HTTP2BasicTest`, `HTTP2GoawayTest`, `HTTP2FlowControlTest`, `HTTP2RawFrameTest`, the h2spec batches, etc.) must stay green and is the primary regression guard for the teardown/GOAWAY/writer-close paths.

Two unit tests touch the moved code and get mechanical updates:

- **`HTTP2WriterCoalescingTest`** — drives the loop directly. Each test now constructs an `HTTP2WriterThread` via the queue-injecting constructor — `new HTTP2WriterThread(frameWriter, out, readerStub, new AccumulatingLogger(), queue)` where `out` is the existing `CountingOutputStream`/`ThrowingOutputStream`, `frameWriter` wraps it, `readerStub` is an unstarted throwaway `Thread`, and `queue` is the pre-loaded test queue — then calls `wt.run()`. The first two tests assert on `out` (flush count, bytes) exactly as before. The third, `io_exception_mid_batch_propagates`, changes shape: because `run()` *catches* the mid-batch `IOException` (that is its production contract) rather than propagating it, the test drops `assertThrows(IOException.class, …)` and instead asserts the close-signaling behavior — `out.written == 28` (frames 1–2 written before the throw), `wt.isClosed()` is `true`, and `readerStub.isInterrupted()` is `true`. This verifies what production actually relies on (writer closes, marks itself closed, interrupts the reader) instead of an exception path no caller ever observes. Using an unstarted `readerStub` keeps `run()`'s `readerThread.interrupt()` off the test thread, so no interrupt-status cleanup is needed.
- **`HTTP2OutputStreamFragmentationTest`** — constructs `HTTP2OutputStream` with a raw queue and drains it to assert on the produced DATA frames. Change each construction to wrap the test's queue via `HTTP2WriterThread.forQueue(queue)` and pass that writer to `HTTP2OutputStream`; the drain-and-assert logic is unchanged because `enqueueBlocking()` lands frames on the very queue the test holds.

Run with `latte test`; CI form `latte clean int --excludePerformance --excludeTimeouts`.

## License / file header

`HTTP2WriterThread.java` is brand-new Latte code → MIT SPDX header (`Copyright (c) 2026 The Latte Project`), matching its sibling `h2` files. No FusionAuth/Apache-derived content is involved.
