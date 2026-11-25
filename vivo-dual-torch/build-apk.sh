#!/usr/bin/env bash
# 不依赖 Gradle 的构建脚本：aapt2 -> javac -> d8 -> zipalign -> apksigner
# 用法: ./build-apk.sh [输出路径]
set -euo pipefail

SDK="${ANDROID_HOME:-/opt/android-sdk}"
BT="$SDK/build-tools/35.0.0"
PLATFORM="$SDK/platforms/android-35/android.jar"
PROJ="$(cd "$(dirname "$0")" && pwd)"
OUT="${TMPDIR:-/tmp}/dualtorch-build"
APK_OUT="${1:-$PROJ/../DualTorch-v1.0.apk}"

rm -rf "$OUT"
mkdir -p "$OUT/compiled" "$OUT/gen" "$OUT/classes" "$OUT/dex"

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir "$PROJ/app/src/main/res" -o "$OUT/compiled/res.zip"

echo "==> aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest "$PROJ/app/src/main/AndroidManifest.xml" \
  -R "$OUT/compiled/res.zip" \
  --java "$OUT/gen" \
  --min-sdk-version 29 \
  --target-sdk-version 35 \
  --version-code 10000 \
  --version-name 1.0.0 \
  --auto-add-overlay

echo "==> javac"
find "$PROJ/app/src/main/java" "$OUT/gen" -name '*.java' > "$OUT/sources.txt"
javac -encoding UTF-8 -source 8 -target 8 -nowarn \
  -classpath "$PLATFORM" \
  -d "$OUT/classes" @"$OUT/sources.txt"

echo "==> d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 29 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT/dex" && zip -q "$OUT/unsigned.apk" classes.dex)

echo "==> zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

echo "==> sign"
if [ ! -f "$PROJ/debug.keystore" ]; then
  keytool -genkeypair -keystore "$PROJ/debug.keystore" -alias debug \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android -dname "CN=DualTorch, O=Lab, C=CN" >/dev/null 2>&1
fi
"$BT/apksigner" sign --ks "$PROJ/debug.keystore" --ks-pass pass:android \
  --key-pass pass:android --out "$APK_OUT" "$OUT/aligned.apk"

echo "==> done: $APK_OUT"
ls -lh "$APK_OUT"
