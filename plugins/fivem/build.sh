#!/usr/bin/env bash
# Build the FiveM/RedM bridge release zip.
#   ./build.sh            -> build/release/playermanager-bridge-fivem-<ver>.zip
# Version: $BRIDGE_VERSION, else the repo-root VERSION file. Source files are never modified:
# the resource is copied to build/stage and stamped there.
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
SRC="$HERE/playermanager_bridge"
OUT="$HERE/build"
STAGE="$OUT/stage"
RELEASE="$OUT/release"

VER="${BRIDGE_VERSION:-}"
if [ -z "$VER" ]; then VER="$(tr -d '[:space:]' < "$REPO/VERSION")"; fi
if ! [[ "$VER" =~ ^[0-9]+\.[0-9]+\.[0-9]+([-.][0-9A-Za-z.]+)?$ ]]; then
  echo "build.sh: invalid version '$VER'" >&2; exit 1
fi

echo "== lint: node --check"
mapfile -t JS < <(find "$SRC" "$HERE/test" -name '*.js' | sort)
for f in "${JS[@]}"; do node --check "$f"; done
echo "   ${#JS[@]} JavaScript files OK"

echo "== lint: luac -p"
if command -v luac >/dev/null 2>&1; then
  while IFS= read -r f; do luac -p "$f"; done < <(find "$SRC" -name '*.lua' | sort)
  echo "   Lua syntax OK"
else
  echo "   luac not found: Lua syntax check skipped"
fi

echo "== test"
(cd "$REPO" && node --test plugins/fivem/test/)

echo "== stage $VER"
rm -rf "$STAGE" "$RELEASE"
mkdir -p "$STAGE" "$RELEASE"
cp -r "$SRC" "$STAGE/playermanager_bridge"
python3 - "$STAGE/playermanager_bridge" "$VER" <<'PY'
import pathlib, re, sys
root, ver = pathlib.Path(sys.argv[1]), sys.argv[2]
mf = root / "fxmanifest.lua"
text = mf.read_text()
text, n = re.subn(r"^version '[^']*'", f"version '{ver}'", text, flags=re.M)
assert n == 1, "fxmanifest version line not found"
mf.write_text(text)
vf = root / "server" / "version.js"
vtext, n = re.subn(r"BRIDGE_VERSION: '[^']*'", f"BRIDGE_VERSION: '{ver}'", vf.read_text())
assert n == 1, "version.js stamp not found"
vf.write_text(vtext)
PY
find "$STAGE/playermanager_bridge" -name '*.tmp-*' -delete || true
# Ship the resource-local working directory: the SaveResourceFile write strategy cannot create it.
mkdir -p "$STAGE/playermanager_bridge/.playermanager"
printf 'Player Manager bridge working directory (used when the server root is not writable).\n' \
  > "$STAGE/playermanager_bridge/.playermanager/README.txt"

ZIP="$RELEASE/playermanager-bridge-fivem-$VER.zip"
echo "== zip $ZIP"
(cd "$STAGE" && zip -qr -X "$ZIP" playermanager_bridge)
(cd "$RELEASE" && sha256sum "$(basename "$ZIP")" > "$(basename "$ZIP").sha256")
echo "built: $ZIP"
sha256sum "$ZIP"
