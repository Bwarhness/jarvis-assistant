#!/usr/bin/env bash
set -e
cd /opt/data/jarvis-assistant
export JAVA_HOME=/opt/data/toolchain/jdk17/jdk-17.0.20+8
export ANDROID_HOME=/opt/data/android-sdk
echo "JAVA_HOME=$JAVA_HOME"
"$JAVA_HOME/bin/java" -version 2>&1 || echo "no java in JAVA_HOME"
echo "--- starting build ---"
./gradlew :app:assembleDebug
