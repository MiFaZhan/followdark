#!/usr/bin/env bash
# 极暗跟随深色模式 —— 纯命令行构建（不依赖 Gradle）
set -euo pipefail

SDK="${ANDROID_HOME:-$HOME/android-sdk}"
BT="$SDK/build-tools/37.0.0"
PLATFORM="$SDK/platforms/android-36/android.jar"
API_JAR="libs/api-82.jar"
OUT="build"
NAME="followdark-v2.8"

[ -x "$BT/aapt2" ] || { echo "缺少 build-tools: $BT"; exit 1; }
[ -f "$PLATFORM" ] || { echo "缺少 platform: $PLATFORM"; exit 1; }
[ -f "$API_JAR" ] || { echo "缺少 Xposed API: $API_JAR"; exit 1; }

rm -rf "$OUT"
mkdir -p "$OUT"/{res,classes,dex}

echo "==> aapt2 compile"
"$BT/aapt2" compile --dir res -o "$OUT/res.zip"

echo "==> aapt2 link"
"$BT/aapt2" link \
  -o "$OUT/base.apk" \
  -I "$PLATFORM" \
  --manifest AndroidManifest.xml \
  -R "$OUT/res.zip" \
  -A assets \
  --min-sdk-version 29 \
  --target-sdk-version 36 \
  --version-code 16 \
  --version-name 2.8 \
  --auto-add-overlay

echo "==> javac"
find src -name '*.java' > "$OUT/sources.txt"
javac -nowarn -encoding UTF-8 -source 8 -target 8 \
  -bootclasspath "$PLATFORM" \
  -classpath "$API_JAR" \
  -d "$OUT/classes" \
  @"$OUT/sources.txt"

echo "==> d8"
find "$OUT/classes" -name '*.class' > "$OUT/classes.txt"
"$BT/d8" --release --min-api 29 --lib "$PLATFORM" --output "$OUT/dex" @"$OUT/classes.txt"

echo "==> package"
cp "$OUT/base.apk" "$OUT/unsigned.apk"
(cd "$OUT" && zip -q -j unsigned.apk dex/classes.dex)

echo "==> zipalign"
"$BT/zipalign" -f -p 4 "$OUT/unsigned.apk" "$OUT/aligned.apk"

if [ ! -f keystore.jks ]; then
  echo "==> keytool（生成自签名调试密钥）"
  keytool -genkeypair -v \
    -keystore keystore.jks \
    -alias followdark \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass android -keypass android \
    -dname "CN=FollowDark, OU=Mifazhan, O=Mifazhan, L=Jinan, ST=Shandong, C=CN" >/dev/null 2>&1
fi

echo "==> apksigner"
"$BT/apksigner" sign \
  --ks keystore.jks --ks-pass pass:android --key-pass pass:android \
  --ks-key-alias followdark \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "$OUT/$NAME.apk" "$OUT/aligned.apk"

"$BT/apksigner" verify --print-certs "$OUT/$NAME.apk" | head -5
echo
echo "产物: $OUT/$NAME.apk"
ls -la "$OUT/$NAME.apk"
