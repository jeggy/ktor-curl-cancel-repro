# ktor-curl-cancel-repro

A minimal reproduction and draft report for a Ktor 3.6.0 Curl-engine bug: a cancelled request's cause can fail a
different, later request that reuses the same curl easy-handle address. See **REPORT.md**.

```sh
./run.sh                     # builds (Kotlin/Native linuxX64) and runs; stops at the first symptom, max 60 s
MAX_SECONDS=300 B_WORKERS=8 ./run.sh
```

Needs JDK 21 for Gradle (e.g. `JAVA_HOME=~/.jdks/jbr-21.0.11 ./run.sh`) and libcurl development headers on the host.
Exit 1 = reproduced (either form, see REPORT.md), 0 = not reproduced in time, 2 = hung (watchdog). It reproduces in
seconds on linuxX64. Next step: file an issue or open a PR against ktorio/ktor.

## Sample run (full output)

`./run.sh`, run on 2026-10-07 on Debian GNU/Linux 13 (trixie) (x86_64), curl 8.14.1, Kotlin 2.3.21, Ktor 3.6.0, kotlinx.coroutines 1.11.0.
Exit code **1** (reproduced). The `JAVA_TOOL_OPTIONS` line comes from that machine's environment, not from this project.

```
$ ./run.sh
Building… (the first build downloads the Kotlin/Native toolchain and can take several minutes)
Picked up JAVA_TOOL_OPTIONS: -Xmx32g -XX:+HeapDumpOnOutOfMemoryError

Ktor 3.6.0 Curl engine — does a cancelled request's cause leak into a different request?
  A: withTimeoutOrNull(137 ms) { GET /slow?ms=137±3 }   (timeout races the response)
  B: plain GET /fast on 4 coroutines, NO timeout                 (must never be cancelled)
  Same HttpClient(Curl) for both. Stops at the first leak, or after 60 s.

[INFO] (io.ktor.server.Application): Application started in 0.001 seconds.
[INFO] (io.ktor.server.Application): Responding at http://0.0.0.0:18089

RESULT: BUG REPRODUCED
  B request #3155 (worker 1) — a plain GET /fast with no timeout of its own — failed with
      TimeoutCancellationException: Timed out waiting for 137 ms
  That is request A's withTimeoutOrNull(137 ms) cause. B was cancelled by a different request.
  Seen 1917 ms into the run, after 2.002 s: A 15 requests (9 timed out), B 3153 ok.
  Exit 1.
$ echo $?
1
```
