#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/.." && pwd)
KSU_SRC="$ROOT_DIR/ksu-module"
STAGE="$ROOT_DIR/build/ksu/stage"
OUT_DIR="$ROOT_DIR/build/ksu"
VERSION=$(awk -F= '$1 == "version" { print $2; exit }' "$KSU_SRC/module.prop" | tr -d '\r\n')
OUT_ZIP="$OUT_DIR/VoltSet-KSU-${VERSION}.zip"

rm -rf "$STAGE"
mkdir -p "$STAGE/webroot" "$STAGE/backend/bin" "$STAGE/backend/modules/sm8750" \
    "$STAGE/backend/modules/sm8850/android16-5-4k" "$STAGE/backend/modules/sm8850/android16-6-4k"

cp "$KSU_SRC/module.prop" "$STAGE/"
cp "$KSU_SRC/customize.sh" "$STAGE/"
cp "$KSU_SRC/action.sh" "$STAGE/"
cp "$KSU_SRC/boot-completed.sh" "$STAGE/"
cp "$KSU_SRC/uninstall.sh" "$STAGE/"
cp "$KSU_SRC/webroot/index.html" "$STAGE/webroot/"
cp "$KSU_SRC/webroot/app.js" "$STAGE/webroot/"
cp "$KSU_SRC/webroot/core.js" "$STAGE/webroot/"

cp "$ROOT_DIR/app/src/main/assets/backend/voltset.sh" "$STAGE/backend/voltset.sh"
cp "$ROOT_DIR/native/vsetns/prebuilt/arm64-v8a/vsetns" "$STAGE/backend/bin/vsetns"
cp "$ROOT_DIR/kernel/prebuilt/sm8750/qcom-scmi-ro.ko" "$STAGE/backend/modules/sm8750/qcom-scmi-ro.ko"
cp "$ROOT_DIR/kernel/prebuilt/sm8850/android16-5-4k/qcom-scmi-ro.ko" "$STAGE/backend/modules/sm8850/android16-5-4k/qcom-scmi-ro.ko"
cp "$ROOT_DIR/kernel/prebuilt/sm8850/android16-6-4k/qcom-scmi-ro.ko" "$STAGE/backend/modules/sm8850/android16-6-4k/qcom-scmi-ro.ko"

chmod 0755 "$STAGE/customize.sh" "$STAGE/action.sh" "$STAGE/boot-completed.sh" "$STAGE/uninstall.sh" \
    "$STAGE/backend/voltset.sh" "$STAGE/backend/bin/vsetns"
chmod 0644 "$STAGE/module.prop" "$STAGE/webroot/"* "$STAGE/backend/modules/"*/*.ko "$STAGE/backend/modules/"*/*/*.ko

rm -f "$OUT_ZIP"
(cd "$STAGE" && zip -r9 "$OUT_ZIP" .)

echo "Built: $OUT_ZIP"
sha256sum "$OUT_ZIP"
unzip -l "$OUT_ZIP"
