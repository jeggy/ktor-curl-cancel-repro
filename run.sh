#!/bin/sh
# Builds (if needed) and runs the reproduction. It stops at the first leak, or after MAX_SECONDS (default 60).
# Exit: 1 = bug reproduced · 0 = not reproduced in time · 2 = hung (watchdog). Knobs: MAX_SECONDS, A_TIMEOUT_MS (137),
# B_WORKERS (4), PORT (18089).
set -e
cd "$(dirname "$0")"
echo "Building… (the first build downloads the Kotlin/Native toolchain and can take several minutes)"
./gradlew linkReleaseExecutableLinuxX64 --console=plain -q
echo
set +e
# Belt and braces: the program has its own watchdog; `timeout` ends it even if that fails.
timeout --foreground -k 5 $(( ${MAX_SECONDS:-60} + 30 )) build/bin/linuxX64/releaseExecutable/repro.kexe
code=$?
[ $code -eq 124 ] || [ $code -eq 137 ] && { echo "RESULT: HUNG — killed by run.sh's timeout."; code=2; }
exit $code
