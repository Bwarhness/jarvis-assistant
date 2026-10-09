#!/usr/bin/env bash
set -e
APK=/opt/data/jarvis-assistant/app/build/outputs/apk/debug/app-debug.apk
echo "Uploading to catbox.moe..."
curl -s -F "reqtype=fileupload" -F "fileToUpload=@$APK" https://catbox.moe/user/api.php
echo