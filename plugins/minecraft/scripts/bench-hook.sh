#!/usr/bin/env bash
# PM_HOOK for run-*-conformance.sh: runs the benchmark while the server is up.
#   PM_HOOK=scripts/bench-hook.sh PM_BENCH_ARGS="--n 64" scripts/headless-conformance.sh paper
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
node "$HERE/bench-bridge.mjs" --root "$DIR" ${PM_BENCH_ARGS:-}
