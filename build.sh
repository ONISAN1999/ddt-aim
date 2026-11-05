#!/usr/bin/env bash
# Build + sign the APK without Gradle.
# Needs: javac, aapt, zipalign, apksigner, and d8 (Android SDK) or dalvik-exchange (Ubuntu).
# Env: ANDROID_JAR (android.jar), KEYSTORE (path), KS_PASS, KS_ALIAS (default ddtaim)
set -euo pipefail
cd "$(dirname "$0")"
ANDROID_JAR="${ANDROID_JAR:?set ANDROID_JAR to android.jar}"
KEYSTORE="${KEYSTORE:?set KEYSTORE}"
KS_PASS="${KS_PASS:?set KS_PASS}"
KS_ALIAS="${KS_ALIAS:-ddtaim}"
AAPT="${AAPT:-aapt}"; ZIPALIGN="${ZIPALIGN:-zipalign}"; APKSIGNER="${APKSIGNER:-apksigner}"

rm -rf build && mkdir -p build/gen build/classes build/dex
"$AAPT" package -f -m -J build/gen -M app/AndroidManifest.xml -S app/res -I "$ANDROID_JAR"
javac -source 8 -target 8 -encoding UTF-8 -nowarn -Xlint:-options \
  -bootclasspath "$ANDROID_JAR" -d build/classes \
  $(find app/java build/gen -name '*.java')

if command -v "${D8:-d8}" >/dev/null 2>&1; then
  "${D8:-d8}" --release --min-api 24 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name '*.class')
else
  dalvik-exchange --dex --min-sdk-version=24 --output=build/dex/classes.dex build/classes
fi

"$AAPT" package -f -M app/AndroidManifest.xml -S app/res -A app/assets -I "$ANDROID_JAR" -F build/unsigned.apk
(cd build/dex && "$AAPT" add ../unsigned.apk classes.dex >/dev/null)
"$ZIPALIGN" -f -p 4 build/unsigned.apk build/aligned.apk
"$APKSIGNER" sign --ks "$KEYSTORE" --ks-key-alias "$KS_ALIAS" --ks-pass "pass:$KS_PASS" --key-pass "pass:$KS_PASS" \
  --v1-signing-enabled true --v2-signing-enabled true --out build/ddt-aim.apk build/aligned.apk
"$APKSIGNER" verify build/ddt-aim.apk
ls -l build/ddt-aim.apk
