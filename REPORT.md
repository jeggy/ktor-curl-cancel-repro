# Curl engine: a cancelled request's cause can fail a different, later request

**Ktor 3.6.0, `ktor-client-curl`, Kotlin/Native linuxX64** (Kotlin 2.3.21, kotlinx.coroutines 1.11.0).
Draft for an upstream issue or pull request. Not posted yet.

## What we saw

In a long-running server that shares one `HttpClient(Curl)`, a request with **no timeout of its own** sometimes
failed with `TimeoutCancellationException: Timed out waiting for 6000 ms`. In the whole process only one call site has a
6000 ms timer: a `withTimeoutOrNull(6000)` around a few parallel requests. The foreign 6000 ms cause showed up in other,
unrelated requests 1–37 s after that call site timed out. Examples: a music scan, and a background refresh whose own
timers are 5 s and 20 s.

Because the exception is a `CancellationException`, code that correctly re-throws cancellation
(`catch (e: CancellationException) { throw e }`) treated it as its own cancellation. In our case one long-lived
`launch { while (true) … }` loop ended silently. `withTimeoutOrNull` re-throws it too, because the
`TimeoutCancellationException` belongs to a different coroutine (`e.coroutine !== coroutine`).

## Where it comes from (reading the 3.6.0 source)

`CurlProcessor.handleSendRequest`:

```kotlin
val requestHandler = api.scheduleRequest(requestData, completionHandler)   // returns the EasyHandle (a C pointer)
val requestCleaner = requestData.callContext.invokeOnCompletion { cause ->
    if (cause == null) return@invokeOnCompletion
    cancelRequest(requestHandler, cause)        // → curlScope.launch { curlApi!!.cancelRequest(easyHandle, cause) }
}
completionHandler.invokeOnCompletion { requestCleaner.dispose() }
```

`CurlMultiApiHandler`:

```kotlin
private val activeHandles = mutableMapOf<EasyHandle, RequestHolder>()       // keyed by the pointer
private val cancelledHandles = mutableSetOf<Pair<EasyHandle, Throwable>>()
fun cancelRequest(easyHandle: EasyHandle, cause: Throwable) { cancelledHandles += Pair(easyHandle, cause) }
private fun handleCompleted() {
    for ((easyHandle, cause) in cancelledHandles) removeEasyHandle(easyHandle, cause)   // looks up by pointer
    …
    activeHandles.remove(easyHandle)!!.dispose()                                     // normal completion frees it
}
private fun removeEasyHandle(easyHandle: EasyHandle, cause: Throwable) {
    val handler = activeHandles.remove(easyHandle) ?: return
    … handler.responseCompletable.completeExceptionally(cause)
}
```

The cancellation is **deferred twice**: first via `curlScope.launch`, then via the `cancelledHandles` set that is
processed on a later `perform()`. It is also identified only by the **easy-handle pointer**. So:

1. Request A is in flight. Its call context is cancelled (the caller's `withTimeout` fires).
2. `requestCleaner` runs and *launches* `cancelRequest(handleA, timeoutCause)`; it has not run yet.
3. On the event loop, curl finishes A normally. `handleCompleted` removes `handleA` from `activeHandles` and
   `dispose()` → `curl_easy_cleanup` frees it.
4. Request B is scheduled; `curl_easy_init()` returns **the same address** (malloc reuse), so
   `activeHandles[handleA'] = holderB`.
5. The launched coroutine now runs: `cancelledHandles += (handleA, timeoutCause)`; the next `perform()` calls
   `removeEasyHandle(handleA)` and finds **B**. B fails with A's `TimeoutCancellationException`.

This is an ABA problem on the handle pointer. Step 3 can also come before step 2's `cancelRequest` is enqueued
whenever completion and cancellation race. That is common under load, or with a timeout close to the response time.

## Reproduction

`src/linuxX64Main/kotlin/Main.kt` starts a local CIO server and shares one `HttpClient(Curl)` between two kinds of
request:
- **A:** `withTimeoutOrNull(137) { get("/slow?ms=137±3") }`, so the timeout races the response.
- **B:** plain `get("/fast")` calls in a loop on 4 coroutines.

Any B that fails with a `CancellationException`, especially `Timed out waiting for 137 ms`, is the bug. Run
`./run.sh`; it exits 1 when a leak is seen. Knobs: `MAX_SECONDS`, `A_TIMEOUT_MS`, `B_WORKERS`, `STALL_MS`.

**Status: reproduces reliably** (2026-10-07, on linuxX64, release build). Each run stopped at the first symptom,
usually within 4–10 s and always under 30 s. Two forms appear:

1. **A foreign cancellation.** A plain B request fails with
   `TimeoutCancellationException: Timed out waiting for 137 ms`, request A's cause. Sometimes B's **own coroutine** is
   left cancelled with that cause. Every later request in it then fails at once: one worker spun through ~786 000
   instant failures in 30 s.
2. **A lost cancellation.** Request A's `withTimeoutOrNull(137)` fires but never returns, and the call waits for ever.
   A's cancellation went to another handle, so its own transfer is never completed or removed. The first version of
   this program "ran for ever" for this reason.

Sample output:

```
RESULT: BUG REPRODUCED
  B request #8367 (worker 2) — a plain GET /fast with no timeout of its own — failed with
      TimeoutCancellationException: Timed out waiting for 137 ms
  That is request A's withTimeoutOrNull(137 ms) cause. B was cancelled by a different request.

RESULT: BUG REPRODUCED (second form)
  Request A #34 — withTimeoutOrNull(137 ms) { GET /slow } — has not returned after 3183 ms.
```

Exit codes: 1 = reproduced (either form), 0 = not reproduced within `MAX_SECONDS`, 2 = the program itself hung.
A watchdog thread and `run.sh`'s `timeout -k` make sure it always ends. The process does not stop on a plain
`SIGTERM` while it is in this state; the watchdog uses `exit()`, and `run.sh` follows up with `SIGKILL`.

## Possible fixes (for the PR)

1. Key the pending cancellation by the request, not the pointer. For example, hold the `RequestHolder` (or its
   `responseCompletable`) in `cancelledHandles`, and in `removeEasyHandle` act only if
   `activeHandles[easyHandle] === thatHolder`.
2. Or drop the cancellation when the request has already completed. `completionHandler.invokeOnCompletion` disposes
   `requestCleaner`, but the already-launched `cancelRequest` is not withdrawn. Checking
   `completionHandler.isCompleted` inside `cancelRequest` (on the curl thread) closes the window.
3. Either way, never complete a request exceptionally with a `CancellationException` that did not come from that
   request's own context. If one must cross over, wrap it in an `IOException`.

## Workaround we use

At our single outbound-HTTP choke point we treat a `CancellationException` as foreign whenever the caller's own
coroutine is still active. We convert it to an `IOException` and retry once.
