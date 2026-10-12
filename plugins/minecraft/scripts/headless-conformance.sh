#!/usr/bin/env bash
# CI entry point: run the headless conformance check for one Minecraft platform.
#
#   MINECRAFT_EULA=true scripts/headless-conformance.sh <paper|folia|velocity|bungeecord|fabric|neoforge|forge>
#
# Picks the Minecraft version and JDK per platform and delegates to the platform script.
# Logs go under $PM_WORK (default /tmp/pm-headless/<platform>).
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM="${1:?usage: $0 <platform>}"
export PM_WORK="${PM_WORK:-/tmp/pm-headless/$PLATFORM}"
mkdir -p "$PM_WORK/logs"

# setup-java exports JAVA_HOME_<n>_X64; fall back to local JDKs.
jdk() { local v="JAVA_HOME_$1_X64"; echo "${!v:-/usr/lib/jvm/java-$1-openjdk}"; }

# Versions exercised in CI (the full matrix is in README.md; CI keeps one end-point per platform).
PAPER_MC="${PM_PAPER_MC:-1.21.4}"
MOD_MC="${PM_MOD_MC:-26.3}"

case "$PLATFORM" in
  paper|folia)
    PM_BOT="${PM_BOT:-1}" PM_JAVA_HOME="$(jdk 21)" \
      exec "$HERE/run-paper-conformance.sh" "$PAPER_MC" "$PLATFORM"
    ;;
  velocity|bungeecord)
    PM_JAVA_HOME="$(jdk 25)" exec "$HERE/run-proxy-conformance.sh" "$PLATFORM"
    ;;
  fabric|neoforge|forge)
    PM_JAVA_HOME="$(jdk 25)" exec "$HERE/run-modded-conformance.sh" "$PLATFORM" "$MOD_MC"
    ;;
  *)
    echo "unknown platform $PLATFORM" >&2
    exit 2
    ;;
esac
