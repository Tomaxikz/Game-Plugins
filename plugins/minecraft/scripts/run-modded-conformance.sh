#!/usr/bin/env bash
# Headless conformance run of a mod-loader bridge jar (Fabric / NeoForge / Forge) against a real dedicated server.
#
#   MINECRAFT_EULA=true scripts/run-modded-conformance.sh <fabric|neoforge|forge> <mc-version> [-- extra pm-conformance args]
#
# * Refuses to start unless MINECRAFT_EULA=true is set in the environment (the user accepts the Minecraft EULA,
#   this script only writes eula.txt from that variable).
# * Installs the server with the loader's own official installer / meta API into $PM_WORK/<loader>-<mc>
#   (default /tmp/claude-1000/pm-bridge-work/B):
#     fabric   https://meta.fabricmc.net/v2/versions/loader/<mc>/<loader>/<installer>/server/jar  (the server launcher jar)
#     neoforge https://maven.neoforged.net/releases/net/neoforged/neoforge/<ver>/neoforge-<ver>-installer.jar --install-server
#     forge    https://maven.minecraftforge.net/net/minecraftforge/forge/<mc>-<ver>/forge-<mc>-<ver>-installer.jar --installServer
#   The installers fetch the vanilla server jar from Mojang themselves.
# * Copies the built bridge jar into mods/, starts the server, waits for .playermanager/bridge.json, runs
#   conformance/bin/pm-conformance.mjs --profile minecraft-mod --allow-actions, stops the server.
# Environment:
#   PM_JAR=<path>        use this bridge jar (default: the jar in build/release whose name range covers <mc-version>; built with gradle if absent)
#   PM_BOT=1             join an offline-mode mineflayer bot (npm-installed under $PM_WORK/bot) so player-scoped tests have a player
#   PM_BOT_VERSION=<v>   mineflayer data version the bot uses (default: <mc-version>, else the newest mineflayer knows, translated by ViaProxy)
#   PM_LOADER_VERSION=<v> force the NeoForge / Forge version (default: newest stable for <mc-version>)
#   PM_JAVA_HOME=<dir>   JDK to run the server with (default: picked by Minecraft version)
#   PM_SMOKE=1           after conformance also run scripts/modded-smoke.mjs (state-changing actions + events; needs PM_BOT=1)
#   PM_HOOK=<script>     bash script run right before the conformance run (env: DIR, PORT)
#   PM_START_TIMEOUT=<s> default 420 (first start downloads and remaps)
# Exit codes: 0 pass, 1 conformance failure, 2 usage/setup error, 3 EULA not accepted (run pending), 4 server did not start.
set -u
set -o pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MC_DIR="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$MC_DIR/../.." && pwd)"
WORK="${PM_WORK:-/tmp/claude-1000/pm-bridge-work/B}"

LOADER="${1:-}"
MC_VERSION="${2:-}"
if [[ -z "$LOADER" || -z "$MC_VERSION" ]]; then
  echo "usage: MINECRAFT_EULA=true $0 <fabric|neoforge|forge> <mc-version> [-- pm-conformance args]" >&2
  exit 2
fi
shift 2
[[ "${1:-}" == "--" ]] && shift
EXTRA_ARGS=("$@")
case "$LOADER" in fabric|neoforge|forge) ;; *) echo "unsupported loader '$LOADER' (fabric, neoforge or forge)" >&2; exit 2;; esac

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
DIR="$WORK/$LOADER-$MC_VERSION"

# ---- Java selection
ver_ge() { [[ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -n1)" == "$2" ]]; }  # ver_ge A B  => A >= B
pick_java() {
  local want
  if ver_ge "$MC_VERSION" "26.1"; then want=25
  elif ver_ge "$MC_VERSION" "1.20.5"; then want=21
  elif ver_ge "$MC_VERSION" "1.18"; then want=17
  else want=17; fi
  local cand=("/usr/lib/jvm/java-$want-openjdk" "/usr/lib/jvm/java-$want-openjdk-amd64" "/usr/lib/jvm/jdk-$want" "$HOME/.sdkman/candidates/java/$want."* "$WORK/jdks/jdk$want")
  for c in "${cand[@]}"; do [[ -x "$c/bin/java" ]] && { echo "$c"; return; }; done
  echo ""
}
JAVA_HOME_SEL="${PM_JAVA_HOME:-$(pick_java)}"
if [[ -z "$JAVA_HOME_SEL" ]]; then echo "no suitable JDK found for Minecraft $MC_VERSION (set PM_JAVA_HOME)" >&2; exit 2; fi
JAVA="$JAVA_HOME_SEL/bin/java"

# ---- bridge jar
VERSION="$(tr -d '[:space:]' < "$REPO/VERSION")"
pick_jar() {
  local f base range first last
  for f in "$MC_DIR/build/release/playermanager-bridge-minecraft-$LOADER-"*"-$VERSION.jar"; do
    [[ -f "$f" ]] || continue
    base="${f##*/}"; range="${base#playermanager-bridge-minecraft-$LOADER-}"; range="${range%-$VERSION.jar}"
    # range is "<first>-<last>" or a single version (versions never contain '-')
    first="${range%%-*}"; last="${range##*-}"
    if ver_ge "$MC_VERSION" "$first" && ver_ge "$last" "$MC_VERSION"; then echo "$f"; return; fi
  done
}
if [[ -n "${PM_JAR:-}" ]]; then
  JAR="$PM_JAR"
else
  JAR="$(pick_jar)"
  if [[ -z "$JAR" ]]; then
    echo ">> building $LOADER jars (incremental)"
    (cd "$MC_DIR" && ./gradlew ":$LOADER:build" -q) || { echo "build failed" >&2; exit 2; }
    JAR="$(pick_jar)"
  fi
fi
[[ -n "$JAR" && -f "$JAR" ]] || { echo "no $LOADER bridge jar covers Minecraft $MC_VERSION (build/release has: $(ls "$MC_DIR/build/release" 2>/dev/null | tr '\n' ' '))" >&2; exit 2; }
echo ">> bridge jar: $JAR"

api() { curl -fsSL --retry 3 "$1"; }

# ---- server installation
rm -rf "$DIR"
mkdir -p "$DIR/mods"
START_CMD=()
case "$LOADER" in
  fabric)
    LV="$(api https://meta.fabricmc.net/v2/versions/loader | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).find(v=>v.stable).version))')"
    IV="$(api https://meta.fabricmc.net/v2/versions/installer | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).find(v=>v.stable).version))')"
    SERVER_JAR="$WORK/jars/fabric-server-$MC_VERSION-loader.$LV-launcher.$IV.jar"
    if [[ ! -f "$SERVER_JAR" ]]; then
      echo ">> downloading Fabric server launcher (loader $LV, installer $IV)"
      curl -fsSL --retry 3 -o "$SERVER_JAR.part" "https://meta.fabricmc.net/v2/versions/loader/$MC_VERSION/$LV/$IV/server/jar" && mv "$SERVER_JAR.part" "$SERVER_JAR" \
        || { echo "no Fabric launcher for $MC_VERSION" >&2; rm -f "$SERVER_JAR.part"; exit 2; }
    fi
    cp "$SERVER_JAR" "$DIR/fabric-server-launch.jar"
    START_CMD=("$JAVA" -Xms512M -Xmx1536M ${PM_JAVA_OPTS:-} -jar fabric-server-launch.jar nogui)
    ;;
  neoforge)
    if [[ -n "${PM_LOADER_VERSION:-}" ]]; then NV="$PM_LOADER_VERSION"; else
      NV="$(api https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml | MCV="$MC_VERSION" node -e '
        let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{
          const vs=[...s.matchAll(/<version>([^<]+)<\/version>/g)].map(m=>m[1]);
          const mc=process.env.MCV; const p=mc.split("."); let prefix;
          if(p[0]==="1"){ prefix=p.slice(1).join("."); if(p.length===2) prefix+=".0"; } else { while(p.length<3)p.push("0"); prefix=p.join("."); }
          const ok=vs.filter(v=>v.startsWith(prefix+".")||v===prefix);
          const stable=ok.filter(v=>!/-(alpha|beta|rc|pre)/.test(v)); const pick=(stable.length?stable:ok).pop(); console.log(pick||"");});')"
    fi
    [[ -n "$NV" ]] || { echo "no NeoForge version for Minecraft $MC_VERSION" >&2; exit 2; }
    INST="$WORK/jars/neoforge-$NV-installer.jar"
    if [[ ! -f "$INST" ]]; then
      echo ">> downloading NeoForge $NV installer"
      curl -fsSL --retry 3 -o "$INST.part" "https://maven.neoforged.net/releases/net/neoforged/neoforge/$NV/neoforge-$NV-installer.jar" && mv "$INST.part" "$INST" || { echo "installer download failed" >&2; exit 2; }
    fi
    echo ">> installing NeoForge $NV"
    (cd "$DIR" && "$JAVA" -jar "$INST" --install-server . > install.log 2>&1) || { echo "NeoForge install failed; tail:" >&2; tail -20 "$DIR/install.log" >&2; exit 2; }
    ARGS_FILE="libraries/net/neoforged/neoforge/$NV/unix_args.txt"
    [[ -f "$DIR/$ARGS_FILE" ]] || { echo "unix_args.txt missing after install" >&2; exit 2; }
    printf -- '-Xms512M\n-Xmx1536M\n' > "$DIR/user_jvm_args.txt"
    START_CMD=("$JAVA" @user_jvm_args.txt "@$ARGS_FILE" nogui)
    ;;
  forge)
    if [[ -n "${PM_LOADER_VERSION:-}" ]]; then FV="$PM_LOADER_VERSION"; else
      FV="$(api https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json | MCV="$MC_VERSION" node -e '
        let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const p=JSON.parse(s).promos;console.log(p[process.env.MCV+"-recommended"]||p[process.env.MCV+"-latest"]||"");});')"
    fi
    [[ -n "$FV" ]] || { echo "no Forge version for Minecraft $MC_VERSION" >&2; exit 2; }
    FULL="$MC_VERSION-$FV"
    INST="$WORK/jars/forge-$FULL-installer.jar"
    if [[ ! -f "$INST" ]]; then
      echo ">> downloading Forge $FULL installer"
      curl -fsSL --retry 3 -o "$INST.part" "https://maven.minecraftforge.net/net/minecraftforge/forge/$FULL/forge-$FULL-installer.jar" && mv "$INST.part" "$INST" || { echo "installer download failed" >&2; exit 2; }
    fi
    echo ">> installing Forge $FULL"
    (cd "$DIR" && "$JAVA" -jar "$INST" --installServer > install.log 2>&1) || { echo "Forge install failed; tail:" >&2; tail -20 "$DIR/install.log" >&2; exit 2; }
    ARGS_FILE="libraries/net/minecraftforge/forge/$FULL/unix_args.txt"
    if [[ -f "$DIR/$ARGS_FILE" ]]; then
      printf -- '-Xms512M\n-Xmx1536M\n' > "$DIR/user_jvm_args.txt"
      START_CMD=("$JAVA" @user_jvm_args.txt "@$ARGS_FILE" nogui)
    else
      FJ="$(ls "$DIR"/forge-*.jar 2>/dev/null | head -1)"
      [[ -n "$FJ" ]] || { echo "Forge launch files missing after install" >&2; exit 2; }
      START_CMD=("$JAVA" -Xms512M -Xmx1536M -jar "${FJ##*/}" nogui)
    fi
    ;;
esac

cp "$JAR" "$DIR/mods/"
# test pack served through config.json platform.asset_paths (no third-party art; solid colours generated by seed-test-pack.mjs)
SEEDTMP="$DIR/.seed-tmp"; mkdir -p "$SEEDTMP"
node "$HERE/seed-test-pack.mjs" "$SEEDTMP" >/dev/null && mv "$SEEDTMP/plugins/Oraxen/pack" "$DIR/pm-test-pack"; rm -rf "$SEEDTMP"
mkdir -p "$DIR/.playermanager"
echo '{"platform":{"asset_paths":["pm-test-pack"]}}' > "$DIR/.playermanager/config.json"

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
VIA_PID=""
cleanup() {
  [[ -n "$BOT_PID" ]] && kill "$BOT_PID" 2>/dev/null
  [[ -n "$VIA_PID" ]] && kill "$VIA_PID" 2>/dev/null
  if [[ -n "$SERVER_PID" ]] && kill -0 "$SERVER_PID" 2>/dev/null; then
    echo "stop" > "$CONSOLE" 2>/dev/null
    for _ in $(seq 1 90); do kill -0 "$SERVER_PID" 2>/dev/null || break; sleep 1; done
    kill -0 "$SERVER_PID" 2>/dev/null && { echo ">> server did not stop, killing" >&2; kill -9 "$SERVER_PID" 2>/dev/null; }
  fi
  [[ -n "$HOLD_PID" ]] && kill "$HOLD_PID" 2>/dev/null
}
trap cleanup EXIT

( while true; do sleep 3600; done ) > "$CONSOLE" &
HOLD_PID=$!

echo ">> starting $LOADER $MC_VERSION on port $PORT with $("$JAVA" -version 2>&1 | head -1)"
(cd "$DIR" && exec "${START_CMD[@]}" < "$CONSOLE" > "$LOG" 2>&1) &
SERVER_PID=$!

TIMEOUT="${PM_START_TIMEOUT:-420}"
echo ">> waiting up to ${TIMEOUT}s for the server"
READY=0
for ((i=0; i<TIMEOUT; i++)); do
  kill -0 "$SERVER_PID" 2>/dev/null || { echo "server exited early; log tail:" >&2; tail -60 "$LOG" >&2; exit 4; }
  if grep -q 'Done (' "$LOG" 2>/dev/null && [[ -f "$DIR/.playermanager/bridge.json" ]]; then READY=1; break; fi
  sleep 1
done
[[ "$READY" == 1 ]] || { echo "bridge did not appear in ${TIMEOUT}s; log tail:" >&2; tail -80 "$LOG" >&2; exit 4; }
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
if (process.env.BOT_PASSIVE === '1') { const w = bot._client.write.bind(bot._client); bot._client.write = (n, p) => /^(position|position_look|look|flying)$/.test(n) ? undefined : w(n, p); }   // a passive bot never sends movement (translated movement packets get it kicked)
bot.once('spawn', () => { console.log('bot spawned'); if (process.env.BOT_CHATTER === '1') setInterval(() => { try { bot.chat('hello from bot'); } catch (e) {} }, 1500); if (process.env.BOT_CMD) setTimeout(() => { try { bot.chat(process.env.BOT_CMD); } catch (e) {} }, 2500); if (process.env.BOT_PASSIVE === '1') { bot.physicsEnabled = false; return; } setInterval(() => { try { bot.look(bot.entity.yaw + 0.3, 0, true); bot.setControlState('jump', true); setTimeout(() => bot.setControlState('jump', false), 200); } catch (e) {} }, 1500); });
bot.on('error', e => console.log('bot error', e.message));
bot.on('kicked', r => console.log('bot kicked', JSON.stringify(r)));
bot.on('end', () => { console.log('bot ended'); process.exit(0); });
BOT
    # Minecraft versions newer than mineflayer's protocol data: the bot speaks the newest version it knows and ViaProxy
    # (test tooling, downloaded from the official ViaVersion GitHub release into $WORK/jars, never shipped) translates to the server's version.
    BOT_V="$(cd "$WORK/bot" && node -e 'const md=require("minecraft-data");const s=md.supportedVersions.pc;const mc=process.argv[1];console.log(s.includes(mc)?mc:s[s.length-1])' "$MC_VERSION")"
    BOT_V="${PM_BOT_VERSION:-$BOT_V}"
    BOT_PORT="$PORT"
    if [[ "$BOT_V" != "$MC_VERSION" ]]; then
      VIA_JAR="$WORK/jars/ViaProxy-${PM_VIAPROXY_VERSION:-3.4.14}.jar"
      if [[ ! -f "$VIA_JAR" ]]; then
        curl -fsSL -o "$VIA_JAR.part" "https://github.com/ViaVersion/ViaProxy/releases/download/v${PM_VIAPROXY_VERSION:-3.4.14}/ViaProxy-${PM_VIAPROXY_VERSION:-3.4.14}.jar" && mv "$VIA_JAR.part" "$VIA_JAR" || echo "ViaProxy download failed" >&2
      fi
      if [[ -f "$VIA_JAR" ]]; then
        BOT_PORT="$(node -e 'const s=require("net").createServer().listen(0,()=>{console.log(s.address().port);s.close()})')"
        mkdir -p "$DIR/viaproxy"
        (cd "$DIR/viaproxy" && exec "$JAVA" -jar "$VIA_JAR" cli --bind-address "127.0.0.1:$BOT_PORT" --target-address "127.0.0.1:$PORT" --target-version "$MC_VERSION" > "$DIR/viaproxy.log" 2>&1) &
        VIA_PID=$!
        for _ in $(seq 1 30); do (echo > "/dev/tcp/127.0.0.1/$BOT_PORT") 2>/dev/null && break; sleep 1; done
        echo ">> bot: mineflayer $BOT_V via ViaProxy (port $BOT_PORT) -> server $MC_VERSION"
      fi
    fi
    (cd "$WORK/bot" && BOT_PASSIVE="${PM_BOT_PASSIVE:-$([[ "$BOT_V" != "$MC_VERSION" ]] && echo 1 || echo 0)}" exec node bot.cjs 127.0.0.1 "$BOT_PORT" "$BOT_V" > "$DIR/bot.log" 2>&1) &
    BOT_PID=$!
    for _ in $(seq 1 60); do grep -q 'bot spawned' "$DIR/bot.log" 2>/dev/null && break; sleep 1; done
    cat "$DIR/bot.log"
  fi
fi

# ---- seed some items for the bot so item/inventory tests have data (console commands through the fifo)
if [[ -n "$BOT_PID" && "${PM_SEED:-1}" == "1" ]]; then
  sleep 2
  if ver_ge "$MC_VERSION" "1.21.5"; then ENCH='enchantments={"minecraft:sharpness":5}'; else ENCH='enchantments={levels:{"minecraft:sharpness":5}}'; fi
  if ver_ge "$MC_VERSION" "1.21.4"; then CMD='custom_model_data={floats:[1234.0]}'; else CMD='custom_model_data=1234'; fi
  {
    echo "give Notch minecraft:diamond_sword[custom_name='{\"text\":\"Excalibur\",\"color\":\"gold\"}',$ENCH,damage=12]"
    echo "give Notch minecraft:shulker_box[container=[{slot:0,item:{id:\"minecraft:cobblestone\",count:64}},{slot:3,item:{id:\"minecraft:diamond\",count:3}}]]"
    echo "give Notch minecraft:leather_chestplate[dyed_color=10511680]"
    echo "give Notch minecraft:paper[$CMD]"
  } > "$CONSOLE"
  sleep 2
fi
[[ -n "${PM_HOOK:-}" ]] && DIR="$DIR" PORT="$PORT" bash "$PM_HOOK"

# ---- conformance
echo ">> running conformance"
node "$REPO/conformance/bin/pm-conformance.mjs" --root "$DIR" --profile minecraft-mod --allow-actions \
  --json "$DIR/conformance-report.json" "${EXTRA_ARGS[@]}"
RC=$?
echo ">> conformance exit code: $RC (report: $DIR/conformance-report.json, server log: $LOG)"

# ---- optional behavioural smoke test: runs the state-changing actions and checks events (needs PM_BOT=1; not part of conformance)
if [[ "${PM_SMOKE:-}" == "1" ]]; then
  echo ">> running modded-smoke.mjs"
  node "$HERE/modded-smoke.mjs" --root "$DIR" 2>&1 | tee "$DIR/smoke.out" | grep -v '^PASS' || true
  grep '^smoke:' "$DIR/smoke.out" || true
  grep -q '^FAIL' "$DIR/smoke.out" && [[ "$RC" == 0 ]] && RC=1
fi

# ---- stop and check shutdown behaviour
cleanup
SERVER_PID=""; HOLD_PID=""; BOT_PID=""
trap - EXIT
if [[ -e "$DIR/.playermanager/bridge.sock" || -e "$DIR/.playermanager/bridge.json" ]]; then
  echo "WARN: bridge.sock / bridge.json still present after shutdown" >&2
fi
exit "$RC"
