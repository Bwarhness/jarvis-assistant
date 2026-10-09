#!/usr/bin/env bash
set -e
V=/opt/hermes/.venv/bin/python
echo "Installing faster-whisper into Hermes venv..."
"$V" -m pip install faster-whisper 2>&1 | tail -5 || {
  echo "pip failed, trying uv..."
  cd /opt/hermes
  source .venv/bin/activate
  uv pip install faster-whisper 2>&1 | tail -5
}
echo "--- verifying import ---"
"$V" -c "import faster_whisper; print('faster-whisper OK', faster_whisper.__version__.split()[:1])"