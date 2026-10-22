#!/usr/bin/env bash
# PM_HOOK for run-*-conformance.sh: verifies pack.manifest / pack.get while the server is up.
#   PM_HOOK=scripts/pack-hook.sh scripts/run-paper-conformance.sh 1.21.4            (Bukkit stack, --layers)
#   PM_HOOK=scripts/pack-hook.sh PM_PACK_ARGS="" scripts/run-modded-conformance.sh fabric 26.3
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
node "$HERE/pack-hook.mjs" --root "$DIR" ${PM_PACK_ARGS---layers} ${PM_PACK_JSON:+--json "$PM_PACK_JSON"}
