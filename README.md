# ktor-curl-cancel-repro

A minimal reproduction and draft report for a Ktor 3.6.0 Curl-engine bug: a cancelled request's cause can fail a
different, later request that reuses the same curl easy-handle address. See **REPORT.md**.

```sh
./run.sh            # builds (Kotlin/Native linuxX64) and runs; exit 1 = leak reproduced
ROUNDS=20000 B_WORKERS=8 ./run.sh
```

Needs JDK 21 for Gradle (e.g. `JAVA_HOME=~/.jdks/jbr-21.0.11 ./run.sh`) and libcurl development headers on the host.
Next step: run it, then file an issue or open a PR against ktorio/ktor.
