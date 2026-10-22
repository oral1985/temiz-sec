#!/usr/bin/env bash
set -euo pipefail
mkdir -p .signing
if [ ! -f .signing/debug.keystore ]; then
  keytool -genkeypair -keystore .signing/debug.keystore -storepass android -keypass android -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 -noprompt
fi
