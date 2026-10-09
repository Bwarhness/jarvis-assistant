#!/usr/bin/env bash
# watch_jarvis.sh — watch the Jarvis repo for Kotlin/Java/manifest changes and
# auto-run deploy_jarvis.sh when something relevant changes.
#   ./watch_jarvis.sh            (foreground run, press Ctrl+C to stop)
#   ./watch_jarvis.sh --once     (just do an initial sync, no loop)
# Designed for a dedicated durable process — NOT to run inside Hermes, since
# Hermes restarts would kill it. Long-lived file-watcher, needs its own home.
set -uo pipefail

REPO=/opt/data/jarvis-assistant
WATCH_DIRS=("$REPO/app" "$REPO/docs")
EXTS=('kt' 'kts' 'java' 'xml' 'gradle' 'properties' 'sh')
DEBOUNCE=10        # seconds to wait after last change before deploying
ONLY_ONCE=0
[ "${1:-}" = "--once" ] && ONLY_ONCE=1

is_relevant() {
  case "$1" in
    *.kt|*.kts|*.java|*.xml|*.gradle|*.gradle.kts|*.properties|*.pro) return 0 ;;
    *) return 1 ;;
  esac
}

latest_snapshot() {
  # find newest mtime among relevant files
  find "${WATCH_DIRS[@]}" -type f \( -name '*.kt' -o -name '*.kts' -o -name '*.java' \
      -o -name '*.xml' -o -name '*.gradle' -o -name '*.gradle.kts' -o -name '*.properties' -o -name '*.pro' \) \
      -printf '%T@\n' 2>/dev/null | sort -rn | head -1
}

last=$(latest_snapshot)
echo "watch_jarvis: baseline mtime=$last"

if [ "$ONLY_ONCE" = 1 ]; then
  echo "watch_jarvis: --once, exiting (no deploy on startup)"
  exit 0
fi

while true; do
  sleep "$DEBOUNCE"
  now=$(latest_snapshot)
  if [ -n "$now" ] && [ -n "$last" ] && [ "$now" != "$last" ]; then
    echo "watch_jarvis: change detected ($last → $now), deploying…"
    # Debounce: confirm the mtime has settled (no changes during the wait)
    sleep 5
    again=$(latest_snapshot)
    if [ "$again" != "$now" ]; then
      echo "watch_jarvis: more changes landing, waiting…"
      continue
    fi
    if "$REPO/deploy_jarvis.sh" >> "$REPO/deploy.log" 2>&1; then
      echo "watch_jarvis: deploy OK"
    else
      echo "watch_jarvis: deploy FAILED (see deploy.log)"
    fi
    last=$(latest_snapshot)
  fi
done
