#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
KSU_SRC="$ROOT_DIR/ksu-module"
STAGE="$ROOT_DIR/build/ksu/stage"
OUT_DIR="$ROOT_DIR/build/ksu"
VER_PROPS="$ROOT_DIR/version.properties"
VERSION_NAME=$(awk -F= '$1 == "versionName" { print $2; exit }' "$VER_PROPS" | tr -d '\r\n')
VERSION_CODE=$(awk -F= '$1 == "versionCode" { print $2; exit }' "$VER_PROPS" | tr -d '\r\n')
VERSION="v${VERSION_NAME}"
OUT_ZIP="$OUT_DIR/VoltSet-KSU-${VERSION}.zip"

rm -rf "$STAGE"
mkdir -p "$STAGE/webroot" "$STAGE/backend/bin" "$STAGE/backend/modules/sm8750" \
    "$STAGE/backend/modules/sm8850/android16-5-4k" "$STAGE/backend/modules/sm8850/android16-6-4k"

awk -v ver="$VERSION" -v code="$VERSION_CODE" '
    /^version=/ { print "version=" ver; next }
    /^versionCode=/ { print "versionCode=" code; next }
    { print }
' "$KSU_SRC/module.prop" > "$STAGE/module.prop"
sed "s/v[0-9][0-9.]*/${VERSION}/g" "$KSU_SRC/customize.sh" > "$STAGE/customize.sh"
cp "$KSU_SRC/action.sh" "$STAGE/"
cp "$KSU_SRC/boot-completed.sh" "$STAGE/"
cp "$KSU_SRC/uninstall.sh" "$STAGE/"
cp "$KSU_SRC/webroot/index.html" "$STAGE/webroot/"
sed "s/const APP_VERSION = '[^']*';/const APP_VERSION = '${VERSION} (KSU WebUI)';/" "$KSU_SRC/webroot/app.js" > "$STAGE/webroot/app.js"
cp "$KSU_SRC/webroot/core.js" "$STAGE/webroot/"

cp "$ROOT_DIR/app/src/main/assets/backend/voltset.sh" "$STAGE/backend/voltset.sh"
cp "$ROOT_DIR/native/vsetns/prebuilt/arm64-v8a/vsetns" "$STAGE/backend/bin/vsetns"
cp "$ROOT_DIR/native/qpt/prebuilt/arm64-v8a/vsetqpt" "$STAGE/backend/bin/vsetqpt"
cp "$ROOT_DIR/kernel/prebuilt/sm8750/qcom-scmi-ro.ko" "$STAGE/backend/modules/sm8750/qcom-scmi-ro.ko"
cp "$ROOT_DIR/kernel/prebuilt/sm8850/android16-5-4k/qcom-scmi-ro.ko" "$STAGE/backend/modules/sm8850/android16-5-4k/qcom-scmi-ro.ko"
cp "$ROOT_DIR/kernel/prebuilt/sm8850/android16-6-4k/qcom-scmi-ro.ko" "$STAGE/backend/modules/sm8850/android16-6-4k/qcom-scmi-ro.ko"

chmod 0755 "$STAGE/customize.sh" "$STAGE/action.sh" "$STAGE/boot-completed.sh" "$STAGE/uninstall.sh" \
    "$STAGE/backend/voltset.sh" "$STAGE/backend/bin/vsetns" "$STAGE/backend/bin/vsetqpt"
chmod 0644 "$STAGE/module.prop" "$STAGE/webroot/"* "$STAGE/backend/modules/"*/*.ko "$STAGE/backend/modules/"*/*/*.ko

rm -f "$OUT_ZIP"
(cd "$STAGE" && zip -r9 "$OUT_ZIP" .)

echo "Built: $OUT_ZIP"
sha256sum "$OUT_ZIP"
unzip -l "$OUT_ZIP"
