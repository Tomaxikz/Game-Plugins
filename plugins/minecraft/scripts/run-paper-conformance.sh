#!/usr/bin/env bash
# Headless conformance run of the Minecraft universal jar against a real Paper / Folia server.
#
#   MINECRAFT_EULA=true scripts/run-paper-conformance.sh <mc-version> [paper|folia|spigot] [-- extra pm-conformance args]
#
# * Refuses to start unless MINECRAFT_EULA=true is set in the environment (the user accepts the Minecraft EULA,
#   this script only writes eula.txt from that variable).
# * Downloads the server jar from the official PaperMC download API (fill.papermc.io) into $PM_WORK
#   (default /tmp/claude-1000/pm-bridge-work/A), verifies its SHA-256, starts it with the built bridge jar,
#   waits for .playermanager/bridge.json, runs conformance/bin/pm-conformance.mjs --profile minecraft-bukkit,
#   stops the server.
# * Spigot: build spigot-<ver>.jar with BuildTools yourself and pass PM_SPIGOT_JAR=/path/to/it.
# * Optional: PM_BOT=1 joins an offline-mode test bot (mineflayer, npm-installed under $PM_WORK/bot) so the
#   player-scoped tests have a player. PM_JAR=<path> uses a prebuilt universal jar. PM_JAVA_HOME overrides the JDK.
# Exit codes: 0 pass, 1 conformance failure, 2 usage/setup error, 3 EULA not accepted (run pending), 4 server did not start.
set -u
set -o pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MC_DIR="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$MC_DIR/../.." && pwd)"
WORK="${PM_WORK:-/tmp/claude-1000/pm-bridge-work/A}"

MC_VERSION="${1:-}"
FLAVOR="${2:-paper}"
if [[ -z "$MC_VERSION" ]]; then
  echo "usage: MINECRAFT_EULA=true $0 <mc-version> [paper|folia] [-- pm-conformance args]" >&2
  exit 2
fi
shift; [[ $# -gt 0 && "$1" != "--" ]] && shift
[[ "${1:-}" == "--" ]] && shift
EXTRA_ARGS=("$@")

if [[ "$FLAVOR" != "paper" && "$FLAVOR" != "folia" && "$FLAVOR" != "spigot" ]]; then
  echo "unsupported flavour '$FLAVOR' (paper, folia or spigot)" >&2
  exit 2
fi
if [[ "$FLAVOR" == "spigot" && ( -z "${PM_SPIGOT_JAR:-}" || ! -f "${PM_SPIGOT_JAR:-}" ) ]]; then
  echo "Spigot has no download API: build spigot-<ver>.jar with BuildTools (https://www.spigotmc.org/wiki/buildtools/) and pass PM_SPIGOT_JAR=/path/spigot-<ver>.jar" >&2
  exit 2
fi

if [[ "${MINECRAFT_EULA:-}" != "true" ]]; then
  echo "Minecraft EULA not accepted: set MINECRAFT_EULA=true (https://aka.ms/MinecraftEULA) to run a headless server." >&2
  echo "Headless conformance run is PENDING." >&2
  exit 3
fi

for tool in curl node sha256sum; do
  command -v "$tool" >/dev/null || { echo "missing tool: $tool" >&2; exit 2; }
done
NODE_MAJOR="$(node -p 'process.versions.node.split(".")[0]')"
[[ "$NODE_MAJOR" -ge 22 ]] || { echo "Node >= 22 required (found $NODE_MAJOR)" >&2; exit 2; }

mkdir -p "$WORK/jars"
DIR="$WORK/$FLAVOR-$MC_VERSION"

# ---- Java selection
ver_ge() { [[ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n1)" == "$2" ]]; }  # ver_ge A B  => A >= B
pick_java() {
  local want
  if ver_ge "$MC_VERSION" "26.1"; then want=25
  elif ver_ge "$MC_VERSION" "1.20.5"; then want=21
  elif ver_ge "$MC_VERSION" "1.17"; then want=17
  else want=8; fi
  local cand=("/usr/lib/jvm/java-$want-openjdk" "/usr/lib/jvm/java-$want-openjdk-amd64" "/usr/lib/jvm/jdk-$want" "/usr/lib/jvm/java-1.$want.0-openjdk" "$WORK/jdks/jdk$want")
  for c in "${cand[@]}"; do [[ -x "$c/bin/java" ]] && { echo "$c"; return; }; done
  echo ""
}
JAVA_HOME_SEL="${PM_JAVA_HOME:-$(pick_java)}"
if [[ -z "$JAVA_HOME_SEL" ]]; then echo "no suitable JDK found for Minecraft $MC_VERSION (set PM_JAVA_HOME)" >&2; exit 2; fi
JAVA="$JAVA_HOME_SEL/bin/java"

# ---- bridge jar
VERSION="$(tr -d '[:space:]' < "$REPO/VERSION")"
if [[ -n "${PM_JAR:-}" ]]; then
  JAR="$PM_JAR"
else
  JAR="$MC_DIR/build/release/playermanager-bridge-minecraft-universal-$VERSION.jar"
  echo ">> building universal jar (incremental)"
  (cd "$MC_DIR" && ./gradlew :universal:build -q) || { echo "build failed" >&2; exit 2; }
fi
[[ -f "$JAR" ]] || { echo "bridge jar not found: $JAR" >&2; exit 2; }

# ---- server jar (official PaperMC API; Spigot: user-built jar)
api() { curl -fsSL --retry 3 "$1"; }
if [[ "$FLAVOR" == "spigot" ]]; then
  SERVER_JAR="$PM_SPIGOT_JAR"
else
  BUILD_JSON="$(api "https://fill.papermc.io/v3/projects/$FLAVOR/versions/$MC_VERSION/builds/latest")" \
    || { echo "no $FLAVOR build for $MC_VERSION" >&2; exit 2; }
  read -r SERVER_URL SERVER_SHA SERVER_NAME < <(printf '%s' "$BUILD_JSON" | node -e '
    let s=""; process.stdin.on("data",d=>s+=d).on("end",()=>{const j=JSON.parse(s).downloads["server:default"];
    console.log(j.url, j.checksums.sha256, j.name);});')
  SERVER_JAR="$WORK/jars/$SERVER_NAME"
  if [[ ! -f "$SERVER_JAR" ]]; then
    echo ">> downloading $SERVER_NAME"
    curl -fsSL --retry 3 -o "$SERVER_JAR.part" "$SERVER_URL" && mv "$SERVER_JAR.part" "$SERVER_JAR" || { echo "download failed" >&2; exit 2; }
  fi
  echo "$SERVER_SHA  $SERVER_JAR" | sha256sum -c --quiet - || { echo "checksum mismatch for $SERVER_JAR" >&2; rm -f "$SERVER_JAR"; exit 2; }
fi

# ---- server directory
rm -rf "$DIR"
mkdir -p "$DIR/plugins"
cp "$JAR" "$DIR/plugins/"
# Optional test helper: ViaVersion lets the (older) mineflayer bot join servers newer than mineflayer knows (PM_VIA=1).
if [[ "${PM_VIA:-}" == "1" ]]; then
  for proj in viaversion viabackwards; do
    VIA_JAR="$WORK/jars/$proj-latest.jar"
    if [[ ! -f "$VIA_JAR" ]]; then
      VIA_URL="$(curl -fsSL "https://api.modrinth.com/v2/project/$proj/version?loaders=%5B%22paper%22%5D" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s)[0].files[0].url))')"
      curl -fsSL -o "$VIA_JAR" "$VIA_URL" || echo "$proj download failed; continuing without it" >&2
    fi
    [[ -f "$VIA_JAR" ]] && cp "$VIA_JAR" "$DIR/plugins/"
  done
fi
printf 'eula=true\n' > "$DIR/eula.txt"
PORT="$(node -e 'const s=require("net").createServer().listen(0,()=>{console.log(s.address().port);s.close()})')"
cat > "$DIR/server.properties" <<PROPS
online-mode=false
server-port=$PORT
server-ip=127.0.0.1
motd=pm-bridge-conformance
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
allow-nether=true
sync-chunk-writes=false
PROPS
ver_ge "$MC_VERSION" "1.16" || sed -i 's/^level-type=.*/level-type=FLAT/' "$DIR/server.properties"
echo '[{"uuid":"069a79f4-44e9-4726-a5be-fca90e38aaf5","name":"Notch","level":4,"bypassesPlayerLimit":false}]' > "$DIR/ops.json"

CONSOLE="$DIR/console.in"
mkfifo "$CONSOLE"
LOG="$DIR/server.log"
SERVER_PID=""
HOLD_PID=""
BOT_PID=""
cleanup() {
  [[ -n "$BOT_PID" ]] && kill "$BOT_PID" 2>/dev/null
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "stop" > "$CONSOLE" 2>/dev/null
    for _ in $(seq 1 60); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    kill -0 "$SERVER_PID" 2>/dev/null && { echo ">> server did not stop, killing" >&2; kill -9 "$SERVER_PID" 2>/dev/null; }
  fi
  [[ -n "$HOLD_PID" ]] && kill "$HOLD_PID" 2>/dev/null
}
trap cleanup EXIT

# keep the fifo open so the server never sees EOF on stdin
( while true; do sleep 3600; done ) > "$CONSOLE" &
HOLD_PID=$!

echo ">> starting $FLAVOR $MC_VERSION on port $PORT with $("$JAVA" -version 2>&1 | head -1)"
(cd "$DIR" && exec "$JAVA" -Xms512M -Xmx1536M ${PM_JAVA_OPTS:-} -jar "$SERVER_JAR" nogui < "$CONSOLE" > "$LOG" 2>&1) &
SERVER_PID=$!

TIMEOUT="${PM_START_TIMEOUT:-300}"
echo ">> waiting up to ${TIMEOUT}s for the server"
READY=0
for ((i=0; i<TIMEOUT; i++)); do
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "server exited early; log tail:" >&2; tail -40 "$LOG" >&2; exit 4; }
  if grep -q 'Done (' "$LOG" 2>/dev/null && [[ -f "$DIR/.playermanager/bridge.json" ]]; then READY=1; break; fi
  sleep 1
done
[[ "$READY" == 1 ]] || { echo "bridge did not appear in ${TIMEOUT}s; log tail:" >&2; tail -60 "$LOG" >&2; exit 4; }
echo ">> bridge.json:"; cat "$DIR/.playermanager/bridge.json"
grep -iE 'PlayerManagerBridge|pm-bridge|playermanager' "$LOG" | head -20

# ---- optional test bot (mineflayer)
if [[ "${PM_BOT:-}" == "1" ]]; then
  mkdir -p "$WORK/bot"
  if [[ ! -d "$WORK/bot/node_modules/mineflayer" ]]; then
    echo ">> installing mineflayer into $WORK/bot"
    (cd "$WORK/bot" && npm init -y >/dev/null 2>&1 && npm install --silent mineflayer@latest) || echo "mineflayer install failed; continuing without a bot" >&2
  fi
  if [[ -d "$WORK/bot/node_modules/mineflayer" ]]; then
    cat > "$WORK/bot/bot.cjs" <<'BOT'
const mineflayer = require('mineflayer');
const [host, port, version] = process.argv.slice(2);
const bot = mineflayer.createBot({ host, port: +port, username: process.env.BOT_NAME || 'Notch', version: version && version !== 'auto' ? version : false, auth: 'offline', physicsEnabled: process.env.BOT_PASSIVE !== '1' });
bot.once('spawn', () => { console.log('bot spawned'); if (process.env.BOT_CHATTER === '1') setInterval(() => { try { bot.chat('hello from bot'); } catch (e) {} }, 1500); if (process.env.BOT_CMD) setTimeout(() => { try { bot.chat(process.env.BOT_CMD); } catch (e) {} }, 2500); if (process.env.BOT_PASSIVE === '1') { bot.physicsEnabled = false; return; } setInterval(() => { try { bot.look(bot.entity.yaw + 0.3, 0, true); bot.setControlState('jump', true); setTimeout(() => bot.setControlState('jump', false), 200); } catch (e) {} }, 1500); });
bot.on('error', e => console.log('bot error', e.message));
bot.on('kicked', r => console.log('bot kicked', JSON.stringify(r)));
bot.on('end', () => { console.log('bot ended'); process.exit(0); });
BOT
    (cd "$WORK/bot" && BOT_PASSIVE="${PM_BOT_PASSIVE:-${PM_VIA:-0}}" exec node bot.cjs 127.0.0.1 "$PORT" "${PM_BOT_VERSION:-$MC_VERSION}" > "$DIR/bot.log" 2>&1) &
    BOT_PID=$!
    for _ in $(seq 1 60); do grep -q 'bot spawned' "$DIR/bot.log" 2>/dev/null && break; sleep 1; done
    cat "$DIR/bot.log"
  fi
fi

# ---- seed some items for the bot so item/inventory tests have data (console commands through the fifo)
if [[ -n "$BOT_PID" && "${PM_SEED:-1}" == "1" ]]; then
  sleep 2
  if ver_ge "$MC_VERSION" "1.20.5"; then
    {
      echo "give Notch minecraft:diamond_sword[custom_name='{\"text\":\"Excalibur\",\"color\":\"gold\"}',enchantments={levels:{\"minecraft:sharpness\":5}},damage=12]"
      echo "give Notch minecraft:shulker_box[container=[{slot:0,item:{id:\"minecraft:cobblestone\",count:64}},{slot:3,item:{id:\"minecraft:diamond\",count:3}}]]"
      echo "give Notch minecraft:leather_chestplate[dyed_color=10511680]"
      echo "give Notch minecraft:paper[custom_model_data=1234]"
    } > "$CONSOLE"
  else
    {
      echo "give Notch minecraft:diamond_sword{display:{Name:'{\"text\":\"Excalibur\"}'},Enchantments:[{id:\"minecraft:sharpness\",lvl:5}],Damage:12}"
      echo "give Notch minecraft:shulker_box{BlockEntityTag:{Items:[{Slot:0b,id:\"minecraft:cobblestone\",Count:64b}]}}"
      echo "give Notch minecraft:leather_chestplate{display:{color:10511680}}"
    } > "$CONSOLE"
  fi
  sleep 2
fi
[[ "${PM_SEED_PACK:-1}" == "1" ]] && node "$HERE/seed-test-pack.mjs" "$DIR" && sleep 1
[[ -n "${PM_HOOK:-}" ]] && DIR="$DIR" PORT="$PORT" bash "$PM_HOOK"

# ---- conformance
echo ">> running conformance"
node "$REPO/conformance/bin/pm-conformance.mjs" --root "$DIR" --profile minecraft-bukkit --allow-actions \
  --json "$DIR/conformance-report.json" "${EXTRA_ARGS[@]}"
RC=$?
echo ">> conformance exit code: $RC (report: $DIR/conformance-report.json, server log: $LOG)"

# ---- stop and check shutdown behaviour
cleanup
SERVER_PID=""; HOLD_PID=""; BOT_PID=""
trap - EXIT
if [[ -e "$DIR/.playermanager/bridge.sock" || -e "$DIR/.playermanager/bridge.json" ]]; then
  echo "WARN: bridge.sock / bridge.json still present after shutdown" >&2
fi
exit "$RC"
