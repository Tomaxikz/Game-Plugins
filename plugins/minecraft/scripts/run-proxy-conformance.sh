#!/usr/bin/env bash
# Headless conformance run of the Minecraft universal jar on a real proxy (Velocity or BungeeCord) with one Paper backend.
#
#   MINECRAFT_EULA=true scripts/run-proxy-conformance.sh <velocity|bungeecord> [-- extra pm-conformance args]
#
# * Refuses to start unless MINECRAFT_EULA=true (the user accepts the Minecraft EULA; this script only writes eula.txt).
# * Downloads from official sources into $PM_WORK (default /tmp/claude-1000/pm-bridge-work/C):
#     Velocity   PaperMC fill API (version PM_VELOCITY_VERSION, default 4.2.0), SHA-256 verified
#     BungeeCord official Jenkins artifact of the BungeeCord CI (hub.spigotmc.org), SHA-256 recorded on first use
#     Paper      PaperMC fill API (version PM_BACKEND_VERSION, default 1.21.4), SHA-256 verified
# * The proxy and the backend run offline-mode (no Mojang auth) on 127.0.0.1 with the proxy's own player-info
#   forwarding turned off. The universal jar is copied into the proxy's plugins/ folder.
# * Optional PM_BOT=1 (default) joins a mineflayer bot through the proxy so player-scoped tests have a player.
# * Waits for <proxy>/.playermanager/bridge.json, runs conformance/bin/pm-conformance.mjs --profile minecraft-proxy,
#   then stops both servers.
# Exit codes: 0 pass, 1 conformance failure, 2 usage/setup error, 3 EULA not accepted (run pending), 4 server did not start.
# Env: PM_HOOK (script run before the suite; used for live event/action checks), PM_JAR (prebuilt universal jar), PM_JAVA_HOME (proxy JDK; Velocity 4.x needs 25), PM_BACKEND_JAVA_HOME (Paper JDK, 21),
#      PM_START_TIMEOUT (seconds, default 300), PM_BOT=0 to skip the bot.
set -u
set -o pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MC_DIR="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$MC_DIR/../.." && pwd)"
WORK="${PM_WORK:-/tmp/claude-1000/pm-bridge-work/C}"

PROXY="${1:-}"
if [[ "$PROXY" != "velocity" && "$PROXY" != "bungeecord" ]]; then
  echo "usage: MINECRAFT_EULA=true $0 <velocity|bungeecord> [-- pm-conformance args]" >&2
  exit 2
fi
shift
[[ "${1:-}" == "--" ]] && shift
EXTRA_ARGS=("$@")

if [[ "${MINECRAFT_EULA:-}" != "true" ]]; then
  echo "Minecraft EULA not accepted: set MINECRAFT_EULA=true (https://aka.ms/MinecraftEULA) to run a headless server." >&2
  echo "Headless proxy conformance run is PENDING." >&2
  exit 3
fi

for tool in curl node sha256sum; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 2; }
done
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
[[ "$NODE_MAJOR" -ge 22 ]] || { echo "Node >= 22 required (found $NODE_MAJOR)" >&2; exit 2; }

# ---- Java
pick_java() {  # pick_java <major> -> prints a JAVA_HOME or nothing
  local want="$1" c
  for c in "/usr/lib/jvm/java-$want-openjdk" "/usr/lib/jvm/java-$want-openjdk-amd64" "/usr/lib/jvm/jdk-$want" "$WORK/jdks/jdk$want"; do
    [[ -x "$c/bin/java" ]] && { echo "$c"; return; }
  done
  echo ""
}
if [[ "$PROXY" == "velocity" ]]; then PROXY_JAVA_WANT=25; else PROXY_JAVA_WANT=21; fi
PROXY_JAVA_HOME="${PM_JAVA_HOME:-$(pick_java "$PROXY_JAVA_WANT")}"
BACK_JAVA_HOME="${PM_BACKEND_JAVA_HOME:-$(pick_java 21)}"
[[ -n "$PROXY_JAVA_HOME" ]] || { echo "no JDK $PROXY_JAVA_WANT found for $PROXY (set PM_JAVA_HOME)" >&2; exit 2; }
[[ -n "$BACK_JAVA_HOME" ]] || { echo "no JDK 21 found for the Paper backend (set PM_BACKEND_JAVA_HOME)" >&2; exit 2; }

# ---- bridge jar (universal: contains velocity-plugin.json and bungee.yml)
VERSION="$(tr -d '[:space:]' < "$REPO/VERSION")"
if [[ -n "${PM_JAR:-}" ]]; then
  JAR="$PM_JAR"
else
  JAR="$MC_DIR/build/release/playermanager-bridge-minecraft-universal-$VERSION.jar"
  echo ">> building universal jar"
  (cd "$MC_DIR" && ./gradlew :universal:build -q) || { echo "build failed" >&2; exit 2; }
fi
[[ -f "$JAR" ]] || { echo "bridge jar not found: $JAR" >&2; exit 2; }

mkdir -p "$WORK/jars"
fetch_verified() {  # fetch_verified <url> <dest> <sha256 or ''>
  local url="$1" dest="$2" sha="$3"
  if [[ ! -f "$dest" ]]; then
    curl -fsSL --retry 3 -o "$dest.part" "$url" && mv "$dest.part" "$dest" || { echo "download failed: $url" >&2; rm -f "$dest.part"; return 1; }
  fi
  if [[ -n "$sha" ]]; then
    echo "$sha  $dest" | sha256sum -c --quiet - || { echo "checksum mismatch for $dest" >&2; rm -f "$dest"; return 1; }
  else
    sha256sum "$dest" | sed "s|  .*|  $dest|" >> "$WORK/jars/SHA256SUMS-recorded.txt"
    echo ">> $(basename "$dest") sha256 $(sha256sum "$dest" | cut -d' ' -f1) (recorded in $WORK/jars/SHA256SUMS-recorded.txt)"
  fi
}
fill_build() {  # fill_build <project> <version> -> prints "url sha name"
  local json
  json="$(curl -fsSL --retry 3 "https://fill.papermc.io/v3/projects/$1/versions/$2/builds/latest")" || return 1
  printf '%s' "$json" | node -e '
    let s=""; process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s).downloads["server:default"];
    console.log(j.url, j.checksums.sha256, j.name);});'
}

# ---- proxy jar
if [[ "$PROXY" == "velocity" ]]; then
  read -r PROXY_URL PROXY_SHA PROXY_NAME < <(fill_build velocity "${PM_VELOCITY_VERSION:-4.2.0}") || { echo "no Velocity build found" >&2; exit 2; }
  PROXY_JAR="$WORK/jars/$PROXY_NAME"
  fetch_verified "$PROXY_URL" "$PROXY_JAR" "$PROXY_SHA" || exit 2
else
  PROXY_NAME="BungeeCord.jar"
  PROXY_JAR="$WORK/jars/BungeeCord.jar"
  fetch_verified "https://hub.spigotmc.org/jenkins/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar" "$PROXY_JAR" "" || exit 2
fi

# ---- backend jar (Paper)
read -r BACK_URL BACK_SHA BACK_NAME < <(fill_build paper "${PM_BACKEND_VERSION:-1.21.4}") || { echo "no Paper build found" >&2; exit 2; }
BACK_JAR="$WORK/jars/$BACK_NAME"
fetch_verified "$BACK_URL" "$BACK_JAR" "$BACK_SHA" || exit 2

# ---- directories
RUN="$WORK/run-$PROXY"
BACK="$RUN/backend"
PDIR="$RUN/proxy"
rm -rf "$RUN"
mkdir -p "$BACK" "$PDIR/plugins"
cp "$JAR" "$PDIR/plugins/"
printf 'eula=true\n' > "$BACK/eula.txt"
cp "$BACK_JAR" "$BACK/paper.jar"
cp "$PROXY_JAR" "$PDIR/$PROXY_NAME"
free_port() { node -e 'const s=require("net").createServer().listen(0,()=>{console.log(s.address().port);s.close()})'; }
BACK_PORT="$(free_port)"
PROXY_PORT="$(free_port)"
cat > "$BACK/server.properties" <<PROPS
online-mode=false
server-port=$BACK_PORT
server-ip=127.0.0.1
motd=pm-proxy-backend
max-players=8
view-distance=4
simulation-distance=4
spawn-protection=0
generate-structures=false
level-type=minecraft\:flat
enable-rcon=false
white-list=false
enable-query=false
enable-command-block=false
sync-chunk-writes=false
PROPS

PIDS=()
HOLD_PIDS=()
cleanup() {
  local p
  for p in "${PIDS[@]:-}"; do [[ -n "$p" ]] && kill -0 "$p" 2>/dev/null && kill "$p" 2>/dev/null; done
  for p in "${HOLD_PIDS[@]:-}"; do [[ -n "$p" ]] && kill "$p" 2>/dev/null; done
  PIDS=(); HOLD_PIDS=()
}
trap cleanup EXIT

# start_server <name> <dir> <log> <java-home> <args...>: stdin comes from <dir>/console.in (kept open).
# Sets LAST_PID (not echoed: callers must not run this in a subshell, the PID bookkeeping would be lost).
declare -A FIFO_OF
LAST_PID=""
start_server() {
  local name="$1" dir="$2" log="$3" jhome="$4"; shift 4
  local fifo="$dir/console.in"
  rm -f "$fifo"; mkfifo "$fifo"
  ( while true; do sleep 3600; done ) > "$fifo" &
  HOLD_PIDS+=("$!")
  (cd "$dir" && exec "$jhome/bin/java" -Xms256M -Xmx1024M "$@" < "$fifo" > "$log" 2>&1) &
  LAST_PID=$!
  PIDS+=("$LAST_PID")
  FIFO_OF[$name]="$fifo"
}
stop_server() {  # stop_server <name> <pid> <stop-command>
  local name="$1" pid="$2" cmd="$3"
  if [[ -n "$pid" ]] && kill -0 "$pid" 2>/dev/null; then
    echo "$cmd" > "${FIFO_OF[$name]}" 2>/dev/null
    for _ in $(seq 1 60); do kill -0 "$pid" 2>/dev/null || break; sleep 1; done
    kill -0 "$pid" 2>/dev/null && { echo ">> $name did not stop, killing" >&2; kill -9 "$pid" 2>/dev/null; }
  fi
}
wait_for() {  # wait_for <seconds> <pid> <description> <test command...>
  local secs="$1" pid="$2" what="$3"; shift 3
  for ((i=0; i<secs; i++)); do
    kill -0 "$pid" 2>/dev/null || { echo "$what: process exited early" >&2; return 1; }
    "$@" && return 0
    sleep 1
  done
  echo "$what: timed out after ${secs}s" >&2
  return 1
}

# ---- backend
echo ">> starting Paper $BACK_NAME on port $BACK_PORT with $("$BACK_JAVA_HOME/bin/java" -version 2>&1 | head -1)"
start_server backend "$BACK" "$BACK/server.log" "$BACK_JAVA_HOME" -jar paper.jar nogui; BACK_PID="$LAST_PID"
TIMEOUT="${PM_START_TIMEOUT:-300}"
wait_for "$TIMEOUT" "$BACK_PID" "backend" grep -q 'Done (' "$BACK/server.log" 2>/dev/null || { tail -40 "$BACK/server.log" >&2; exit 4; }

# ---- proxy: first start writes the default config, then the ports and auth are patched
if [[ "$PROXY" == "velocity" ]]; then CONF="velocity.toml"; else CONF="config.yml"; fi
echo ">> generating the default $PROXY config"
start_server proxy "$PDIR" "$PDIR/proxy-first.log" "$PROXY_JAVA_HOME" -jar "$PROXY_NAME"; PROXY_PID="$LAST_PID"
wait_for 120 "$PROXY_PID" "proxy config" test -f "$PDIR/$CONF" || { tail -40 "$PDIR/proxy-first.log" >&2; exit 4; }
sleep 2
if [[ "$PROXY" == "velocity" ]]; then
  stop_server proxy "$PROXY_PID" "shutdown"
else
  stop_server proxy "$PROXY_PID" "end"
fi

if [[ "$PROXY" == "velocity" ]]; then
  sed -i -e "s|^bind = .*|bind = \"127.0.0.1:$PROXY_PORT\"|" \
         -e 's|^online-mode = .*|online-mode = false|' \
         -e 's|^player-info-forwarding-mode = .*|player-info-forwarding-mode = "none"|' \
         -e "s|^lobby = .*|lobby = \"127.0.0.1:$BACK_PORT\"|" "$PDIR/$CONF"
else
  sed -i -e "s|host: .*:[0-9]*|host: 127.0.0.1:$PROXY_PORT|" \
         -e 's|^online_mode: .*|online_mode: false|' \
         -e "s|address: .*:[0-9]*|address: 127.0.0.1:$BACK_PORT|" "$PDIR/$CONF"
fi
grep -q "$PROXY_PORT" "$PDIR/$CONF" || { echo "could not patch $CONF for port $PROXY_PORT" >&2; exit 4; }
rm -rf "$PDIR/.playermanager"

echo ">> starting $PROXY on port $PROXY_PORT"
start_server proxy "$PDIR" "$PDIR/proxy.log" "$PROXY_JAVA_HOME" -jar "$PROXY_NAME"; PROXY_PID="$LAST_PID"
wait_for "$TIMEOUT" "$PROXY_PID" "proxy bridge" test -f "$PDIR/.playermanager/bridge.json" || { tail -60 "$PDIR/proxy.log" >&2; exit 4; }
echo ">> bridge.json:"; cat "$PDIR/.playermanager/bridge.json"; echo
grep -iE 'playermanager|pm-bridge' "$PDIR/proxy.log" | head -20

# ---- optional test bot through the proxy
if [[ "${PM_BOT:-1}" == "1" ]]; then
  mkdir -p "$WORK/bot"
  if [[ ! -d "$WORK/bot/node_modules/mineflayer" ]]; then
    echo ">> installing mineflayer into $WORK/bot"
    (cd "$WORK/bot" && npm init -y >/dev/null 2>&1 && npm install --silent mineflayer@latest) || echo "mineflayer install failed; continuing without a bot" >&2
  fi
  if [[ -d "$WORK/bot/node_modules/mineflayer" ]]; then
    cat > "$WORK/bot/bot.cjs" <<'BOT'
const mineflayer = require('mineflayer');
const [host, port, version] = process.argv.slice(2);
const bot = mineflayer.createBot({ host, port: +port, username: 'Notch', version: version || false, auth: 'offline', physicsEnabled: false });
bot.once('spawn', () => console.log('bot spawned'));
bot.on('error', e => console.log('bot error', e.message));
bot.on('kicked', r => console.log('bot kicked', JSON.stringify(r)));
bot.on('end', () => { console.log('bot ended'); process.exit(0); });
BOT
    (cd "$WORK/bot" && exec node bot.cjs 127.0.0.1 "$PROXY_PORT" "${PM_BOT_VERSION:-1.21.4}" > "$RUN/bot.log" 2>&1) &
    BOT_PID=$!
    PIDS+=("$BOT_PID")
    for _ in $(seq 1 90); do grep -q 'bot spawned' "$RUN/bot.log" 2>/dev/null && break; sleep 1; done
    cat "$RUN/bot.log"
  fi
fi

# ---- optional hook (PM_HOOK=<script>): runs with PDIR and PROXY_PORT set, before the conformance suite
if [[ -n "${PM_HOOK:-}" ]]; then
  PDIR="$PDIR" PROXY_PORT="$PROXY_PORT" bash "$PM_HOOK" || echo "hook $PM_HOOK exited with $?" >&2
fi

# ---- conformance
echo ">> running conformance (profile minecraft-proxy)"
node "$REPO/conformance/bin/pm-conformance.mjs" --root "$PDIR" --profile minecraft-proxy --allow-actions \
  --json "$RUN/conformance-report.json" "${EXTRA_ARGS[@]}"
RC=$?
echo ">> conformance exit code: $RC (report: $RUN/conformance-report.json, proxy log: $PDIR/proxy.log)"

# ---- stop and check shutdown behaviour
if [[ "$PROXY" == "velocity" ]]; then stop_server proxy "$PROXY_PID" "shutdown"; else stop_server proxy "$PROXY_PID" "end"; fi
stop_server backend "$BACK_PID" "stop"
cleanup
trap - EXIT
if [[ -e "$PDIR/.playermanager/bridge.sock" || -e "$PDIR/.playermanager/bridge.json" ]]; then
  echo "WARN: bridge.sock / bridge.json still present after shutdown" >&2
fi
exit "$RC"
