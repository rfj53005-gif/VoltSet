#!/system/bin/sh
MODDIR=${0%/*}
BUNDLE="$MODDIR/backend"
SH="$BUNDLE/voltset.sh"
PERSISTENT=/data/adb/voltset/persistent
CONF="$PERSISTENT/auto-restore.conf"
STATUS="$PERSISTENT/auto-restore.status"

if [ ! -r "$SH" ]; then
    echo "VoltSet 后端脚本不存在：$SH"
    exit 1
fi

echo "=== VoltSet 状态摘要（只读） ==="
/system/bin/sh "$SH" "$BUNDLE" status | awk '
    {
        eq = index($0, "=")
        if (eq > 1) {
            k = substr($0, 1, eq - 1)
            v = substr($0, eq + 1)
            if (k == "DEVICE_MODEL") model = v
            else if (k == "SOC_MODEL") soc = v
            else if (k == "SDAM_CPUSS_IDX") cpuss = v
            else if (k == "SDAM_GLOBAL_IDX") global = v
            else if (k == "REG_7151") reg7151 = v
            else if (k == "FIRST_SDAM_IDX") first_idx = v
            else if (k == "PREVIOUS_TARGET_IDX") prev_idx = v
            else if (k == "SCMI_SOURCE") scmi_src = v
            else if (k == "BASELINE_SOURCE") base_src = v
            else if (k == "socinfo") socinfo = v
            else if (k == "qfprom_aging") qfprom = v
            else if (k == "qpt_rails") qpt = v
        }
    }
    $0 == "SCMI_BEGIN" { in_scmi = 1; next }
    $0 == "SCMI_END" { in_scmi = 0; next }
    in_scmi && /^[01],/ { scmi_cnt++ }
    END {
        printf "设备: %s (%s)\n", (model ? model : "?"), (soc ? soc : "?")
        printf "SDAM: CPUSS=Set %s / Global=%s (0x7151=0x%s)\n", (cpuss != "" ? cpuss : "?"), (global != "" ? global : "?"), (reg7151 ? reg7151 : "--")
        if (first_idx != "") printf "开机首次读回: Set %s", first_idx
        if (prev_idx != "") printf " | 上次设定目标: Set %s", prev_idx
        if (first_idx != "" || prev_idx != "") printf "\n"
        printf "SCMI: %d 个频点 (来源 %s) | 原厂基线: %s\n", scmi_cnt + 0, (scmi_src ? scmi_src : "none"), (base_src ? base_src : "none")
        if (socinfo != "") printf "硅片: %s\n", socinfo
        if (qfprom != "") printf "老化熔丝: %s\n", qfprom
        if (qpt != "") printf "QPT 功耗轨: %s\n", qpt
    }
'

auto_en=0
auto_target=
auto_fingerprint=
if [ -r "$CONF" ]; then
    while IFS='=' read -r k v; do
        case "$k" in
            enabled) auto_en=$v ;;
            target_set) auto_target=$v ;;
            pdp_cdb_sha256) auto_fingerprint=$v ;;
        esac
    done < "$CONF"
fi
case "$auto_fingerprint" in *[!0-9a-f]*) auto_fingerprint= ;; esac
case "$auto_en:$auto_target" in
    1:[2-7])
        if [ "${#auto_fingerprint}" = 64 ]; then
            printf '开机自动写 Set: 已开启 (目标 Set %s)\n' "$auto_target"
        else
            printf '开机自动写 Set: 待确认 (目标 Set %s，缺少固件指纹)\n' "$auto_target"
        fi
        ;;
    *) printf '开机自动写 Set: 已关闭\n' ;;
esac

if [ -r "$STATUS" ]; then
    last_time=$(awk -F= '$1 == "time" { print $2; exit }' "$STATUS" 2>/dev/null || true)
    last_detail=$(awk -F= '$1 == "detail" { sub(/^detail=/, ""); print; exit }' "$STATUS" 2>/dev/null || true)
    if [ -n "$last_detail" ]; then
        printf '上次开机执行: [%s] %s\n' "${last_time:-?}" "$last_detail"
    fi
fi
echo "请在 KernelSU 管理器打开 VoltSet WebUI 进行完整查看与调压。"
