#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:?Set ANDROID_JAR to platforms/android-35/android.jar}"
: "${BUILD_TOOLS:?Set BUILD_TOOLS to Android build-tools/35.0.0}"
: "${ECJ_JAR:?Set ECJ_JAR to Eclipse ECJ 3.33.0 jar}"
KEYSTORE_PATH="${KEYSTORE_PATH:-$PWD/development.jks}"
[[ -f "$KEYSTORE_PATH" ]] || { echo 'Missing original signing key; refusing to generate a replacement.' >&2; exit 1; }
: "${KEYSTORE_PASSWORD:?Set KEYSTORE_PASSWORD}"
: "${KEY_PASSWORD:?Set KEY_PASSWORD}"
export KEYSTORE_PASSWORD KEY_PASSWORD
rm -rf build/classes build/dex
mkdir -p build/classes build/dex
java -jar "$ECJ_JAR" -8 -proc:none -classpath "$ANDROID_JAR" -d build/classes src/br/com/assistente/teste/*.java
"$BUILD_TOOLS/aapt2" compile --dir res -o build/resources.zip
version_args=()
if [[ -n "${VERSION_CODE:-}" ]]; then
  [[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || { echo 'Invalid VERSION_CODE' >&2; exit 1; }
  version_args+=(--version-code "$VERSION_CODE")
fi
"$BUILD_TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml "${version_args[@]}" -o build/base.apk build/resources.zip
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 23 --output build/dex build/classes/br/com/assistente/teste/*.class
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('build/base.apk','a') as z:
    z.write('build/dex/classes.dex','classes.dex')
PY
"$BUILD_TOOLS/zipalign" -f 4 build/base.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" sign --ks "$KEYSTORE_PATH" --ks-key-alias demo --ks-pass env:KEYSTORE_PASSWORD --key-pass env:KEY_PASSWORD --out build/Assistente-Teste.apk build/aligned.apk
certs=$("$BUILD_TOOLS/apksigner" verify --verbose --print-certs build/Assistente-Teste.apk)
if ! echo "$certs" | grep -Fq 'Signer #1 certificate SHA-256 digest: e7aacc33b1b7a65d3c86e8b6e489d133571f4eb589ea77c895465371e33cf69f'; then
  rm -f build/Assistente-Teste.apk
  echo 'Wrong signing certificate: APK would not update the installed app.' >&2
  exit 1
fi
echo 'APK verified with the original signing certificate: build/Assistente-Teste.apk'
