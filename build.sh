#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"
: "${ANDROID_JAR:?Set ANDROID_JAR to platforms/android-35/android.jar}"
: "${BUILD_TOOLS:?Set BUILD_TOOLS to Android build-tools/35.0.0}"
: "${ECJ_JAR:?Set ECJ_JAR to Eclipse ECJ 3.33.0 jar}"
mkdir -p build/classes build/dex
java -jar "$ECJ_JAR" -8 -proc:none -classpath "$ANDROID_JAR" -d build/classes src/br/com/assistente/teste/*.java
"$BUILD_TOOLS/aapt2" compile --dir res -o build/resources.zip
"$BUILD_TOOLS/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -o build/base.apk build/resources.zip
"$BUILD_TOOLS/d8" --lib "$ANDROID_JAR" --min-api 23 --output build/dex build/classes/br/com/assistente/teste/*.class
python3 - <<'PY'
import zipfile
with zipfile.ZipFile('build/base.apk','a') as z: z.write('build/dex/classes.dex','classes.dex')
PY
"$BUILD_TOOLS/zipalign" -f 4 build/base.apk build/aligned.apk
# Development key only. Preserve this file to install future updates.
if [ ! -f development.jks ]; then
 keytool -genkeypair -keystore development.jks -storepass android -keypass android -alias demo -keyalg RSA -keysize 2048 -validity 10000 -dname 'CN=Assistant Test Development'
fi
"$BUILD_TOOLS/apksigner" sign --ks development.jks --ks-pass pass:android --key-pass pass:android --out build/Assistente-Teste.apk build/aligned.apk
"$BUILD_TOOLS/apksigner" verify --verbose build/Assistente-Teste.apk
