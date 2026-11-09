#!/usr/bin/env bash
set -euo pipefail
umask 077
PROBE_ROOT="$(cd "$(dirname "$0")" && pwd)"
PROBE_JDK=/workspace/netstrazh-tools/jdk-21.0.13
PROBE_SDK=/workspace/android-sdk
PROBE_BT="$PROBE_SDK/build-tools/35.0.0"
export JAVA_HOME="$PROBE_JDK"
export PATH="$PROBE_JDK/bin:$PATH"
mkdir -p "$PROBE_ROOT/out/classes" "$PROBE_ROOT/out/dex" "$PROBE_ROOT/signing"
"$PROBE_JDK/bin/javac" --release 8 -cp "$PROBE_SDK/platforms/android-35/android.jar" \
  -d "$PROBE_ROOT/out/classes" "$PROBE_ROOT/src/lab/netstrazh/reviewclient/ProbeActivity.java"
"$PROBE_JDK/bin/jar" cf "$PROBE_ROOT/out/classes.jar" -C "$PROBE_ROOT/out/classes" .
"$PROBE_BT/d8" --min-api 26 --lib "$PROBE_SDK/platforms/android-35/android.jar" \
  --output "$PROBE_ROOT/out/dex" "$PROBE_ROOT/out/classes.jar"
"$PROBE_BT/aapt2" link -I "$PROBE_SDK/platforms/android-35/android.jar" \
  --manifest "$PROBE_ROOT/AndroidManifest.xml" --version-code 1 --version-name 1.0 \
  -o "$PROBE_ROOT/out/base.apk"
python3 - "$PROBE_ROOT" <<'PY'
from pathlib import Path
import sys
import zipfile
root = Path(sys.argv[1])
with zipfile.ZipFile(root / 'out/base.apk', 'a', compression=zipfile.ZIP_DEFLATED) as apk:
    apk.write(root / 'out/dex/classes.dex', 'classes.dex')
PY
"$PROBE_BT/zipalign" -f -p 4 "$PROBE_ROOT/out/base.apk" "$PROBE_ROOT/out/aligned.apk"
if [ ! -f "$PROBE_ROOT/signing/review-debug.jks" ]; then
  "$PROBE_JDK/bin/keytool" -genkeypair -keystore "$PROBE_ROOT/signing/review-debug.jks" \
    -storepass android -keypass android -alias review-debug -keyalg RSA -keysize 2048 \
    -validity 365 -dname 'CN=NetStrazh disposable review client' \
    >"$PROBE_ROOT/out/key-generation.log" 2>&1
fi
"$PROBE_BT/apksigner" sign --ks "$PROBE_ROOT/signing/review-debug.jks" \
  --ks-key-alias review-debug --ks-pass pass:android --key-pass pass:android \
  --out "$PROBE_ROOT/review-client.apk" "$PROBE_ROOT/out/aligned.apk"
"$PROBE_BT/apksigner" verify "$PROBE_ROOT/review-client.apk"
"$PROBE_BT/aapt2" dump badging "$PROBE_ROOT/review-client.apk"
sha256sum "$PROBE_ROOT/review-client.apk"
