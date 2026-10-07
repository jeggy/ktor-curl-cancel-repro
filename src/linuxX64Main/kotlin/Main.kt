/*
 * Reproduction attempt: Ktor 3.6.0's Curl engine fails a NEW request with an OLD request's cancellation cause.
 *
 * Shape (see REPORT.md): request A runs under `withTimeoutOrNull(A_TIMEOUT_MS)` against an endpoint that answers in
 * about A_TIMEOUT_MS, so the timeout and the normal completion race. Request B is a plain request with no timeout of
 * its own, sent continuously on other coroutines. If B ever fails with a TimeoutCancellationException whose message is
 * "Timed out waiting for <A_TIMEOUT_MS> ms", B received A's cancellation: the bug.
 *
 * A local Ktor CIO server provides /slow?ms=N and /fast, so nothing leaves the machine.
 */
import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.concurrent.AtomicInt
import kotlin.random.Random
import platform.posix.getenv
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.toKString

@OptIn(ExperimentalForeignApi::class)
private fun env(name: String, default: Int): Int = getenv(name)?.toKString()?.toIntOrNull() ?: default

@OptIn(ExperimentalForeignApi::class)
fun main() = runBlocking {
    val port = env("PORT", 18089)
    val aTimeoutMs = env("A_TIMEOUT_MS", 137)          // a distinctive number, so a leaked message is unmistakable
    val rounds = env("ROUNDS", 2000)
    val bWorkers = env("B_WORKERS", 4)

    val server = embeddedServer(CIO, port = port) {
        routing {
            get("/slow") {
                val ms = call.request.queryParameters["ms"]?.toLongOrNull() ?: 0L
                delay(ms)
                call.respondText("slow")
            }
            get("/fast") { call.respondText("fast") }
        }
    }.start(wait = false)
    delay(300)

    val client = HttpClient(Curl)
    val base = "http://127.0.0.1:$port"
    val leaked = AtomicInt(0)
    val bOk = AtomicInt(0)
    val aTimedOut = AtomicInt(0)
    var running = true

    // B: plain requests, no timeout of their own. Any CancellationException here that names A's timeout is the bug.
    val bJobs = (1..bWorkers).map { w ->
        launch {
            while (running) {
                try {
                    client.get("$base/fast").bodyAsText()
                    bOk.incrementAndGet()
                } catch (e: TimeoutCancellationException) {
                    leaked.incrementAndGet()
                    println("LEAK worker=$w: B failed with A's cause: ${e.message}")
                } catch (e: CancellationException) {
                    if (!isActive) throw e
                    leaked.incrementAndGet()
                    println("LEAK worker=$w: B failed with a foreign cancellation: ${e::class.simpleName}: ${e.message}")
                }
            }
        }
    }

    // A: a timeout that races the response (the server answers within a few ms either side of the timeout).
    repeat(rounds) { i ->
        val serverMs = aTimeoutMs + Random.nextInt(-3, 4)
        val r = withTimeoutOrNull(aTimeoutMs.toLong()) { client.get("$base/slow?ms=$serverMs").bodyAsText() }
        if (r == null) aTimedOut.incrementAndGet()
        if (i % 200 == 0) println("round $i: A timed out ${aTimedOut.value}, B ok ${bOk.value}, leaks ${leaked.value}")
    }
    running = false
    bJobs.forEach { it.join() }

    println("DONE rounds=$rounds aTimedOut=${aTimedOut.value} bOk=${bOk.value} LEAKS=${leaked.value}")
    client.close()
    server.stop(100, 500)
    if (leaked.value > 0) platform.posix.exit(1)
}
