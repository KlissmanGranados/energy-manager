#!/usr/bin/env bash
#
# Build manual (sin Gradle) del APK "Urano Power" y despliegue al Fire TV.
#
#   ./build.sh            -> compila + instala + lanza + whitelist deviceidle
#   ./build.sh config     -> empuja config/urano.json + config/id_urano y reinicia el servicio
#   ./build.sh build      -> solo compila el APK (build/app.apk)
#
set -euo pipefail

# ---------- rutas ----------
PROJ="$(cd "$(dirname "$0")" && pwd)"
SDK="${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}"
BT="$SDK/build-tools/35.0.0"
ANDROID_JAR="$SDK/platforms/android-28/android.jar"
JSCH="$PROJ/libs/jsch-mwiede.jar"
PKG="com.kgranados.urano"
SERIAL="${SERIAL:-192.168.0.7:5555}"
KS="$HOME/.android/debug.keystore"
BUILD="$PROJ/build"
ADB="$(command -v adb)"

aapt2="$BT/aapt2"; d8="$BT/d8"; zipalign="$BT/zipalign"; apksigner="$BT/apksigner"

# ---------- keystore de debug ----------
ensure_keystore() {
  if [ ! -f "$KS" ]; then
    echo ">> generando keystore de debug"
    mkdir -p "$(dirname "$KS")"
    keytool -genkeypair -keystore "$KS" -storepass android -keypass android \
      -alias androiddebugkey -dname "CN=Android Debug,O=Android,C=US" \
      -keyalg RSA -keysize 2048 -validity 10000 >/dev/null 2>&1
  fi
}

# ---------- compilar ----------
do_build() {
  ensure_keystore
  echo ">> limpiando build/"
  rm -rf "$BUILD"; mkdir -p "$BUILD/gen" "$BUILD/classes" "$BUILD/dex"

  echo ">> aapt2 compile (recursos)"
  "$aapt2" compile --dir "$PROJ/res" -o "$BUILD/res.zip"

  echo ">> aapt2 link (recursos + manifest -> base.apk, genera R.java)"
  "$aapt2" link -o "$BUILD/base.apk" \
    -I "$ANDROID_JAR" \
    --manifest "$PROJ/AndroidManifest.xml" \
    -R "$BUILD/res.zip" \
    --java "$BUILD/gen" \
    --min-sdk-version 22 --target-sdk-version 28 \
    -A "$PROJ/assets" \
    --auto-add-overlay

  echo ">> javac"
  find "$PROJ/src" "$BUILD/gen" -name '*.java' > "$BUILD/sources.txt"
  javac -source 8 -target 8 -encoding UTF-8 -nowarn \
    -bootclasspath "$ANDROID_JAR" -classpath "$JSCH" \
    -d "$BUILD/classes" @"$BUILD/sources.txt"

  echo ">> d8 (dex, incluye jsch)"
  mapfile -t CLASSES < <(find "$BUILD/classes" -name '*.class')
  "$d8" --release --min-api 22 --lib "$ANDROID_JAR" \
    --output "$BUILD/dex" "${CLASSES[@]}" "$JSCH"

  echo ">> empaquetar dex en el apk"
  cp "$BUILD/base.apk" "$BUILD/app.unsigned.apk"
  cp "$BUILD/dex/classes.dex" "$BUILD/classes.dex"
  ( cd "$BUILD" && zip -q app.unsigned.apk classes.dex )

  echo ">> zipalign"
  "$zipalign" -p -f 4 "$BUILD/app.unsigned.apk" "$BUILD/app.aligned.apk"

  echo ">> apksigner"
  "$apksigner" sign --ks "$KS" --ks-pass pass:android \
    --ks-key-alias androiddebugkey --key-pass pass:android \
    --out "$BUILD/app.apk" "$BUILD/app.aligned.apk"

  echo ">> OK: $BUILD/app.apk"
}

# ---------- instalar + lanzar + whitelist ----------
do_deploy() {
  echo ">> install -r en $SERIAL"
  "$ADB" -s "$SERIAL" install -r "$BUILD/app.apk"
  echo ">> primer lanzamiento (habilita BOOT_COMPLETED)"
  "$ADB" -s "$SERIAL" shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1 || \
    "$ADB" -s "$SERIAL" shell am start -n "$PKG/.LauncherActivity" >/dev/null 2>&1 || true
  echo ">> sacar de optimizaciones de energia (deviceidle whitelist)"
  "$ADB" -s "$SERIAL" shell dumpsys deviceidle whitelist +"$PKG" >/dev/null 2>&1 || true
  echo ">> listo. UI: http://<ip-del-stick>:8080/"
}

# ---------- empujar secretos ----------
do_config() {
  echo ">> empujando config/urano.json y config/id_urano al storage interno"
  "$ADB" -s "$SERIAL" push "$PROJ/config/urano.json" /data/local/tmp/urano.json >/dev/null
  "$ADB" -s "$SERIAL" shell "run-as $PKG cp /data/local/tmp/urano.json files/urano.json"
  if [ -f "$PROJ/config/id_urano" ]; then
    "$ADB" -s "$SERIAL" push "$PROJ/config/id_urano" /data/local/tmp/id_urano >/dev/null
    "$ADB" -s "$SERIAL" shell "run-as $PKG cp /data/local/tmp/id_urano files/id_urano"
    "$ADB" -s "$SERIAL" shell "run-as $PKG chmod 600 files/id_urano" || true
  else
    echo "   (aviso: no existe config/id_urano; el apagado por SSH no funcionara hasta copiarla)"
  fi
  "$ADB" -s "$SERIAL" shell rm -f /data/local/tmp/urano.json /data/local/tmp/id_urano
  echo ">> reiniciando servicio para recargar config"
  "$ADB" -s "$SERIAL" shell am force-stop "$PKG"
  "$ADB" -s "$SERIAL" shell am start -n "$PKG/.LauncherActivity" >/dev/null 2>&1 || true
  echo ">> config aplicada."
}

# ---------- tests de logica pura (JDK local, no van en el APK) ----------
do_test() {
  local out="$BUILD/test"
  rm -rf "$out"; mkdir -p "$out"
  # solo las clases de logica pura (sin Android) + los tests
  local U="$PROJ/src/com/kgranados/urano"
  javac -encoding UTF-8 -d "$out" \
    "$U/PoliticaCorte.java" "$U/ColaAlertas.java" "$U/TelegramTransporte.java" \
    "$U/PowerHistoryQuery.java" "$U/ResumenCortes.java" \
    "$PROJ/test/TestPoliticaCorte.java" "$PROJ/test/TestColaAlertas.java" \
    "$PROJ/test/TestPowerHistoryQuery.java"
  local rc=0
  for t in TestPoliticaCorte TestColaAlertas TestPowerHistoryQuery; do
    echo "######## $t"
    java -cp "$out" "$t" || rc=1
  done
  return $rc
}

case "${1:-all}" in
  test)   do_test ;;
  build)  do_build ;;
  deploy) do_deploy ;;
  config) do_config ;;
  all)    do_build; do_deploy ;;
  *) echo "uso: $0 [test|build|deploy|config|all]"; exit 1 ;;
esac
