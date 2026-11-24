#!/usr/bin/env bash
# Build + install del APK WebView "Urano" para el celular (sin Gradle).
#   ./build.sh          -> compila + instala en el telefono
#   ./build.sh build    -> solo compila
set -euo pipefail

PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-28/android.jar"
PKG="com.kgranados.uranoremote"
SERIAL="${SERIAL:-192.168.0.179:35455}"
KS="$HOME/.android/debug.keystore"
BUILD="$PROJ/build"
ADB="$(command -v adb)"
aapt2="$BT/aapt2"; d8="$BT/d8"; zipalign="$BT/zipalign"; apksigner="$BT/apksigner"

gen_buildconfig() {
  local props="$PROJ/local.properties" token
  [ -f "$props" ] || { echo "ERROR: falta $props (copia local.properties.example y completa URANO_TOKEN)" >&2; exit 1; }
  token="$(sed -n 's/^URANO_TOKEN=//p' "$props" | head -n1 | tr -d '\r')"
  [[ "$token" =~ ^[A-Za-z0-9_-]+$ ]] || { echo "ERROR: URANO_TOKEN vacio o con caracteres invalidos en $props" >&2; exit 1; }
  mkdir -p "$BUILD/gen/com/kgranados/uranoremote"
  cat > "$BUILD/gen/com/kgranados/uranoremote/BuildConfig.java" <<EOF
package com.kgranados.uranoremote;

public final class BuildConfig {
    public static final String TOKEN = "$token";
    private BuildConfig() {}
}
EOF
}

do_build() {
  [ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
  rm -rf "$BUILD"; mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"
  echo ">> aapt2"
  "$aapt2" compile --dir "$PROJ/res" -o "$BUILD/res.zip"
  "$aapt2" link -o "$BUILD/base.apk" -I "$ANDROID_JAR" \
    --manifest "$PROJ/AndroidManifest.xml" -R "$BUILD/res.zip" --java "$BUILD/gen" \
    --min-sdk-version 22 --target-sdk-version 28 --auto-add-overlay
  gen_buildconfig
  echo ">> javac"
  find "$PROJ/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
  javac -source 8 -target 8 -encoding UTF-8 -nowarn -bootclasspath "$ANDROID_JAR" -d "$BUILD/classes" @"$BUILD/sources.txt"
  echo ">> d8"
  mapfile -t CLASSES < <(find "$BUILD/classes" -name '*.class')
  "$d8" --release --min-api 22 --lib "$ANDROID_JAR" --output "$BUILD/dex" "${CLASSES[@]}"
  echo ">> empaquetar + firmar"
  cp "$BUILD/base.apk" "$BUILD/app.unsigned.apk"
  cp "$BUILD/dex/classes.dex" "$BUILD/classes.dex"
  ( cd "$BUILD" && zip -q app.unsigned.apk classes.dex )
  "$zipalign" -p -f 4 "$BUILD/app.unsigned.apk" "$BUILD/app.aligned.apk"
  "$apksigner" sign --ks "$KS" --ks-pass pass:android --ks-key-alias androiddebugkey --key-pass pass:android \
    --out "$BUILD/app.apk" "$BUILD/app.aligned.apk"
  echo ">> OK: $BUILD/app.apk"
}

do_deploy() {
  echo ">> install -r en $SERIAL"
  "$ADB" -s "$SERIAL" install -r "$BUILD/app.apk"
  echo ">> listo. Busca la app 'Urano' en el cajon de apps."
}

case "${1:-all}" in
  build)  do_build ;;
  deploy) do_deploy ;;
  all)    do_build; do_deploy ;;
  *) echo "uso: $0 [build|deploy|all]"; exit 1 ;;
esac
