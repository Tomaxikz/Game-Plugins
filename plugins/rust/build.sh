#!/usr/bin/env bash
# Single build command for the Rust bridge:  ./build.sh   (from plugins/rust, or anywhere)
#  1. stamps the version into the plugin and writes build/release/playermanager-bridge-rust-<ver>.cs
#  2. compile-checks the plugin against hand-written stubs, without and with -define:CARBON
#  3. runs the xUnit tests (msgpack, hash/fingerprint vectors, framing, quantisation, validation, protocol core
#     driven over a real Unix socket / TCP by a fake game provider)
#  4. runs the conformance client against the test host if conformance/bin/pm-conformance.mjs exists
# Version: env BRIDGE_VERSION, else the repo-root VERSION file.
set -euo pipefail
here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
root="$(cd "$here/../.." && pwd)"
ver="${BRIDGE_VERSION:-$(tr -d '[:space:]' < "$root/VERSION")}"
[[ "$ver" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?$ ]] || { echo "bad version: $ver" >&2; exit 1; }

out="$here/build/release"
mkdir -p "$out"
art="$out/playermanager-bridge-rust-$ver.cs"
# only lines carrying the @VERSION@ marker are rewritten
sed -E '/@VERSION@/ s/"[0-9]+\.[0-9]+\.[0-9]+([-+][0-9A-Za-z.-]+)?"/"'"$ver"'"/' "$here/PlayerManagerRust.cs" > "$art"
grep -q "\"$ver\"" "$art" || { echo "version stamp failed" >&2; exit 1; }
[ "$(grep -c '@VERSION@' "$art")" = "$(grep '@VERSION@' "$art" | grep -c "\"$ver\"")" ] || { echo "a @VERSION@ line was not stamped" >&2; exit 1; }
grep -q 'pm\.hurt' "$art" || { echo "marker pm.hurt missing" >&2; exit 1; }
echo "wrote $art"

cd "$here/tests"
echo "== compile check (Oxide) =="
dotnet build CompileCheck.csproj -c Release --no-incremental -nologo -v q -o "$here/build/compilecheck-oxide" -p:Carbon=false
echo "== compile check (Carbon, -define:CARBON) =="
dotnet build CompileCheck.csproj -c Release --no-incremental -nologo -v q -o "$here/build/compilecheck-carbon" -p:Carbon=true
echo "== unit + protocol tests =="
dotnet test PlayerManagerRust.Tests.csproj -nologo -v q

conf="$root/conformance/bin/pm-conformance.mjs"
if [ -f "$conf" ]; then
  echo "== conformance client against the test host =="
  dotnet run --project "$here/tests/host/Host.csproj" -c Release -- "$here/build/conformance-root" "$conf" || exit 1
  echo "== conformance client, TCP+token fallback =="
  dotnet run --project "$here/tests/host/Host.csproj" -c Release -- "$here/build/conformance-root-tcp" "$conf" tcp || exit 1
else
  echo "conformance client not present ($conf); skipped"
fi
echo "OK: $art"
