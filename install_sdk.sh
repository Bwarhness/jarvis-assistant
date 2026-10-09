#!/usr/bin/env bash
set -e
export ANDROID_HOME=/opt/data/android-sdk
export JAVA_HOME=/opt/data/toolchain/jdk17/jdk-17.0.20+8
export PATH="$JAVA_HOME/bin:$PATH"
LM="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
yes | "$LM" --licenses >/dev/null 2>&1 || true
echo "Installing platform-34 + build-tools 34.0.0..."
"$LM" "platforms;android-34" "build-tools;34.0.0"
echo "--- installed ---"
ls "$ANDROID_HOME/platforms" "$ANDROID_HOME/build-tools"
echo "SDK_OK"