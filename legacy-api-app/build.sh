#!/bin/sh
set -eu
cd "$(dirname "$0")"

export ANDROID_HOME=${ANDROID_HOME:-/data/data/com.termux/files/home/android-sdk}
export ANDROID_SDK_ROOT=$ANDROID_HOME
export JAVA_HOME=${JAVA_HOME:-/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk}
export PATH="$JAVA_HOME/bin:$PATH"

AAPT2=${AAPT2:-/data/data/com.termux/files/usr/bin/aapt2}
APKSIGNER=${APKSIGNER:-$ANDROID_HOME/build-tools/36.0.0/apksigner}
KS=${KS:-app/keystore.jks}
KS_PASS=${KS_PASS:-ruh2026}
KEY_ALIAS=${KEY_ALIAS:-ruh}
OUT_APK=${OUT_APK:-dist/ruh-flight-api.apk}

./gradlew :app:assembleRelease --no-daemon \
  -Pandroid.aapt2FromMavenOverride="$AAPT2"

SRC_APK=app/build/outputs/apk/release/app-release.apk
mkdir -p "$(dirname "$OUT_APK")"

bash "$APKSIGNER" sign \
  --ks "$KS" \
  --ks-key-alias "$KEY_ALIAS" \
  --ks-pass "pass:$KS_PASS" \
  --key-pass "pass:$KS_PASS" \
  --out "$OUT_APK" \
  "$SRC_APK"

bash "$APKSIGNER" verify --verbose "$OUT_APK"

ls -l "$OUT_APK"
echo "BUILD DONE -> $OUT_APK"
