/*
 * Reproduction: Ktor 3.6.0's Curl engine fails a NEW request with an OLD request's cancellation cause.
 *
 * Request A runs under `withTimeoutOrNull(A_TIMEOUT_MS)` against an endpoint that answers in about A_TIMEOUT_MS, so the
 * timeout and the normal completion race. Request B is a plain GET with NO timeout, sent continuously on other
 * coroutines through the same HttpClient(Curl). B can only fail with "Timed out waiting for <A_TIMEOUT_MS> ms" if it
 * received A's cancellation. See REPORT.md for the source walk-through.
 *
 * Stops at the first leak (or after MAX_SECONDS), prints one verdict, and exits:
 *   1 = bug reproduced (either form) · 0 = not reproduced in time · 2 = the program itself hung (watchdog fired)
 * A watchdog thread outside the coroutine machinery guarantees the process ends.
 */
@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.native.concurrent.ObsoleteWorkersApi::class)

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.cinterop.toKString
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.AtomicInt
import kotlin.native.concurrent.Worker
import kotlin.random.Random
import kotlin.time.TimeSource
import platform.posix.exit
import platform.posix.fflush
import platform.posix.getenv
import platform.posix.stdout

private fun env(name: String, default: Int): Int = getenv(name)?.toKString()?.toIntOrNull() ?: default
private fun say(s: String) { println(s); fflush(stdout) }

private class Leak(val worker: Int, val bRequestNo: Int, val atMs: Long, val type: String, val message: String?, val bCoroutineCancelled: Boolean)

fun main() {
    val port = env("PORT", 18089)
    val aTimeoutMs = env("A_TIMEOUT_MS", 137)     // distinctive, so a leaked message is unmistakable
    val bWorkers = env("B_WORKERS", 4)
    val maxSeconds = env("MAX_SECONDS", 60)

    say("""
        |Ktor ${"3.6.0"} Curl engine — does a cancelled request's cause leak into a different request?
        |  A: withTimeoutOrNull($aTimeoutMs ms) { GET /slow?ms=$aTimeoutMs±3 }   (timeout races the response)
        |  B: plain GET /fast on $bWorkers coroutines, NO timeout                 (must never be cancelled)
        |  Same HttpClient(Curl) for both. Stops at the first leak, or after $maxSeconds s.
        |""".trimMargin())

    // Watchdog: a separate thread that ends the process even if every coroutine is stuck.
    Worker.start(name = "watchdog").executeAfter((maxSeconds + 15) * 1_000_000L) {
        println("\nRESULT: HUNG — the program did not finish within ${maxSeconds + 15} s (watchdog). Exit 2.")
        fflush(stdout)
        exit(2)
    }

    val code = runBlocking {
        val server = embeddedServer(CIO, port = port) {
            routing {
                get("/slow") {
                    delay(call.request.queryParameters["ms"]?.toLongOrNull() ?: 0L)
                    call.respondText("slow")
                }
                get("/fast") { call.respondText("fast") }
            }
        }.start(wait = false)
        delay(300)

        val client = HttpClient(Curl)
        val base = "http://127.0.0.1:$port"
        val started = TimeSource.Monotonic.markNow()
        val bOk = AtomicInt(0)
        val bNo = AtomicInt(0)
        val aRounds = AtomicInt(0)
        val aTimedOut = AtomicInt(0)
        val aStartedAtMs = AtomicInt(0)   // when the current A request began (ms into the run)
        val firstLeak = CompletableDeferred<Leak>()
        val stopping = AtomicInt(0)

        val bJobs = (1..bWorkers).map { w ->
            launch {
                while (isActive && !firstLeak.isCompleted) {
                    val n = bNo.incrementAndGet()
                    try {
                        client.get("$base/fast").bodyAsText()
                        bOk.incrementAndGet()
                    } catch (e: CancellationException) {
                        if (stopping.value == 1) break            // our own shutdown, not the bug
                        // Anything else is the bug. `isActive` false means the leak did not just fail this request:
                        // it cancelled B's own coroutine, so every later call in it fails at once.
                        firstLeak.complete(Leak(w, n, started.elapsedNow().inWholeMilliseconds, e::class.simpleName ?: "?", e.message, !isActive))
                        break
                    }
                }
            }
        }
        val aJob = launch {
            while (isActive && !firstLeak.isCompleted) {
                val serverMs = aTimeoutMs + Random.nextInt(-3, 4)
                aRounds.incrementAndGet()
                aStartedAtMs.value = started.elapsedNow().inWholeMilliseconds.toInt()
                if (withTimeoutOrNull(aTimeoutMs.toLong()) { client.get("$base/slow?ms=$serverMs").bodyAsText() } == null) aTimedOut.incrementAndGet()
            }
        }
        val ticker = launch {
            while (isActive) {
                delay(5_000)
                say("  … ${started.elapsedNow().inWholeSeconds} s: A ${aRounds.value} requests (${aTimedOut.value} timed out), B ${bOk.value} ok, no leak yet")
            }
        }

        // Wait for either symptom, or the deadline. Symptom 2: A's withTimeoutOrNull(aTimeoutMs) has not returned for
        // far longer than its timeout — its cancellation never reached its own request, so it waits for ever.
        val stallMs = env("STALL_MS", 3_000)
        var leak: Leak? = null
        var stuckForMs = 0L
        while (started.elapsedNow().inWholeMilliseconds < maxSeconds * 1_000L) {
            if (firstLeak.isCompleted) { leak = firstLeak.await(); break }
            val inFlight = started.elapsedNow().inWholeMilliseconds - aStartedAtMs.value
            if (aRounds.value > 0 && inFlight > aTimeoutMs + stallMs) { stuckForMs = inFlight; break }
            delay(100)
        }
        stopping.value = 1
        ticker.cancel(); aJob.cancel(); bJobs.forEach { it.cancel() }
        val stats = "after ${started.elapsedNow().inWholeMilliseconds / 1000.0} s: A ${aRounds.value} requests (${aTimedOut.value} timed out), B ${bOk.value} ok"
        if (leak != null) {
            val worse = if (leak.bCoroutineCancelled) "  Worse: B's own coroutine is now cancelled with A's cause, so every later request in it fails at once\n" +
                "  (left running, that worker spins, failing ~25 000 requests a second — why the first version never ended).\n" else ""
            say("""
                |
                |RESULT: BUG REPRODUCED
                |  B request #${leak.bRequestNo} (worker ${leak.worker}) — a plain GET /fast with no timeout of its own — failed with
                |      ${leak.type}: ${leak.message}
                |  That is request A's withTimeoutOrNull($aTimeoutMs ms) cause. B was cancelled by a different request.
                |${worse}  Seen ${leak.atMs} ms into the run, $stats.
                |  Exit 1.
                |""".trimMargin())
        } else if (stuckForMs > 0) {
            say("""
                |
                |RESULT: BUG REPRODUCED (second form)
                |  Request A #${aRounds.value} — withTimeoutOrNull($aTimeoutMs ms) { GET /slow } — has not returned after ${stuckForMs} ms.
                |  Its timeout fired long ago, but the cancellation never reached its own request, so the call waits for ever.
                |  (This is what makes a program using the Curl engine appear to hang.) $stats.
                |  Exit 1.
                |""".trimMargin())
        } else {
            say("\nRESULT: NOT REPRODUCED in $maxSeconds s ($stats). Try MAX_SECONDS=300 or B_WORKERS=8. Exit 0.")
        }
        // Shut down without waiting on anything that might itself be stuck; the watchdog backs this up.
        withTimeoutOrNull<Unit>(2_000) { client.close(); server.stop(100, 500) }
        if (leak != null || stuckForMs > 0) 1 else 0
    }
    exit(code)
}
