#!/usr/bin/env bash
set -e
chmod +x /opt/data/home/scripts/stt_transcribe.py
C=/opt/hermes/.venv/bin/hermes
"$C" config set stt.enabled true
"$C" config set stt.provider local
"$C" config set stt.local.model base
"$C" config set stt.local.command /opt/data/home/scripts/stt_transcribe.py
echo "STT config set"
grep -A6 '^stt:' /opt/data/config.yaml