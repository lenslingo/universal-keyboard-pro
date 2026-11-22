#!/usr/bin/env bash
# Kotlin (Android core) ↔ C# (Windows hub) interoperability test over real TLS WebSocket on loopback.
# Requires: dotnet 10 SDK, JDK 17+, kotlinc.
set -euo pipefail
export LC_ALL=C.UTF-8 LANG=C.UTF-8
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'kill ${HUB_PID:-0} 2>/dev/null || true; rm -rf "$WORK"' EXIT

echo "== build C# hub"
dotnet build "$ROOT/windows/tests/UKPro.Core.Tests" -nologo -v q >/dev/null
echo "== build Kotlin core"
# CoreTestsJUnit.kt needs JUnit (Gradle only); everything else compiles with a bare kotlinc
mapfile -t KT < <(find "$ROOT/android/core/src" -name '*.kt' ! -name 'CoreTestsJUnit.kt')
if kotlinc "${KT[@]}" -include-runtime -d "$WORK/core.jar" 2>&1 | grep -E "error:"; then exit 1; fi
echo "== Kotlin core unit tests"
java -cp "$WORK/core.jar" com.ukpro.core.CoreTests "$ROOT/shared/test-vectors"

echo "== start hub"
dotnet run --no-build --project "$ROOT/windows/tests/UKPro.Core.Tests" -- --interop-hub "$WORK/uri.txt" &
HUB_PID=$!
for _ in $(seq 1 100); do [ -s "$WORK/uri.txt" ] && break; sleep 0.1; done

echo "== run Kotlin client"
java -cp "$WORK/core.jar" com.ukpro.core.InteropClient "$WORK/uri.txt"
wait $HUB_PID
