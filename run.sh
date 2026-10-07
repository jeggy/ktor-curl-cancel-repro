#!/bin/sh
# Builds the release executable and runs the reproduction. Exit code 1 = the leak was seen.
# Knobs: ROUNDS (2000), A_TIMEOUT_MS (137), B_WORKERS (4), PORT (18089).
set -e
cd "$(dirname "$0")"
./gradlew linkReleaseExecutableLinuxX64 --console=plain -q
exec build/bin/linuxX64/releaseExecutable/repro.kexe
