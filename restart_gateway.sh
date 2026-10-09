#!/usr/bin/env bash
set -e
C=/opt/hermes/.venv/bin/hermes
echo "Restarting gateway..."
"$C" gateway restart 2>&1 || "$C" gateway start 2>&1
sleep 3
"$C" gateway status 2>&1 | head -20