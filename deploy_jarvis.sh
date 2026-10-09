#!/usr/bin/env bash
# deploy_jarvis.sh — build the Jarvis app and deploy the APK to the Tower download page.
# Usage: /opt/data/jarvis-assistant/deploy_jarvis.sh [version]
#   version defaults to the one in the built APK's output-metadata.json.
set -euo pipefail

REPO=/opt/data/jarvis-assistant
TOWER=root@192.168.1.200
TOWER_DIR=/mnt/user/appdata/jarvis-download
PORT=8300

cd "$REPO"
export JAVA_HOME=/opt/data/toolchain/jdk17/jdk-17.0.20+8
export ANDROID_HOME=/opt/data/android-sdk

# ── 1. Build ─────────────────────────────────────────────────────────────
echo "── building ──"
./gradlew :app:assembleDebug

APK_IN="$REPO/app/build/outputs/apk/debug/app-debug.apk"
[ -f "$APK_IN" ] || { echo "ERROR: build produced no APK" >&2; exit 1; }

# ── 2. Version stamp (CLI arg > APK metadata > epoch fallback) ───────────
if [ $# -ge 1 ]; then
  VER="$1"
else
  # Default to the APK's versionName (debug suffix stripped) so names stay stable
  VER=$(python3 -c "import json;v=json.load(open('$REPO/app/build/outputs/apk/debug/output-metadata.json'))['elements'][0]['versionName'];print(v.removesuffix('-debug'))" 2>/dev/null || true)
  [ -n "$VER" ] || VER=$(date +%Y%m%d-%H%M)
fi
APK_NAME="jarvis-${VER}.apk"

# ── 3. Push to Tower, clean older versions, write index.html ────────────
echo "── pushing $APK_NAME to $TOWER ──"
scp -q "$APK_IN" "$TOWER:$TOWER_DIR/$APK_NAME"

ssh "$TOWER" bash -s "$APK_NAME" "$VER" <<'REMOTE'
set -euo pipefail
NAME=$1; VER=$2
cd /mnt/user/appdata/jarvis-download
# keep only the newest version on the page
find . -maxdepth 1 -name 'jarvis-*.apk' ! -name "$NAME" -delete
SUM=$(md5sum "$NAME" | cut -d' ' -f1)
SIZE=$(stat -c%s "$NAME")
MBSIZE=$(awk "BEGIN{printf \"%.1f\", $SIZE/1024/1024}")
NOW=$(date '+%Y-%m-%d %H:%M')
cat > index.html <<HTML
<!doctype html>
<html><head><meta charset="utf-8"><title>Jarvis builds</title>
<style>body{font-family:sans-serif;background:#111;color:#eee;display:flex;min-height:100vh;align-items:center;justify-content:center;text-align:center}
a{color:#7fd1ff}</style></head>
<body><div><h1>Jarvis builds</h1>
<p><a href="$NAME" download>$NAME</a> ($MBSIZE MB)</p>
<p style="color:#888;font-size:.8em">built $NOW &nbsp;|&nbsp; md5 $SUM</p>
</div></body></html>
HTML
echo "remote md5: $SUM"
REMOTE

# ── 4. Verify Nginx serves the new file ──────────────────────────────────
echo "── verifying ──"
HTTP=$(curl -s -o /dev/null -w "%{http_code}" --max-time 20 "http://192.168.1.200:${PORT}/${APK_NAME}")
[ "$HTTP" = "200" ] || { echo "ERROR: Nginx returned HTTP $HTTP for /${APK_NAME}" >&2; exit 1; }

LOCAL_MD5=$(md5sum "$APK_IN" | cut -d' ' -f1)
echo "✅ deployed: http://tower:${PORT}/"
echo "   direct:   http://tower:${PORT}/${APK_NAME}"
echo "   version:  ${VER}   md5: ${LOCAL_MD5}"
