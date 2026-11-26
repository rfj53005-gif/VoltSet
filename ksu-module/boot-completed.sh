#!/system/bin/sh
set -eu
PATH=/system/bin
export PATH
umask 077

MODDIR=${0%/*}
BUNDLE="$MODDIR/backend"
SH="$BUNDLE/voltset.sh"
PERSISTENT=/data/adb/voltset/persistent
CONF="$PERSISTENT/auto-restore.conf"
STATUS="$PERSISTENT/auto-restore.status"
BOOT_FILE=/proc/sys/kernel/random/boot_id

dry_run=0
if [ "${1:-}" = "--dry-run" ]; then
    dry_run=1
fi

[ -r "$SH" ] || exit 0
[ -r "$CONF" ] || exit 0

enabled=0
target_set=
while IFS='=' read -r k v; do
    case "$k" in
        enabled) enabled=$(printf '%s' "$v" | tr -cd '0-9') ;;
        target_set) target_set=$(printf '%s' "$v" | tr -cd '0-9') ;;
    esac
done < "$CONF"

[ "$enabled" = 1 ] || exit 0
case "$target_set" in
    [2-7]) ;;
    *) exit 0 ;;
esac

boot_id=$(cat "$BOOT_FILE" 2>/dev/null || true)
case "$boot_id" in
    ????????-????-????-????-????????????) ;;
    *) exit 0 ;;
esac

if [ "$dry_run" = 0 ] && [ -r "$STATUS" ]; then
    prev_boot=$(awk -F= '$1 == "boot_id" { print $2; exit }' "$STATUS" 2>/dev/null || true)
    if [ "$prev_boot" = "$boot_id" ]; then
        exit 0
    fi
fi

tmp_status=$(mktemp /data/local/tmp/voltset-boot.XXXXXXXX 2>/dev/null || mktemp)
cleanup() {
    rm -f -- "$tmp_status" 2>/dev/null || true
}
trap cleanup EXIT

write_status() {
    local action=$1 observed=$2 detail=$3 now tmp
    [ "$dry_run" = 0 ] || {
        printf 'DRY_RUN action=%s target=%s observed=%s detail=%s\n' "$action" "$target_set" "$observed" "$detail"
        return 0
    }
    now=$(date '+%Y-%m-%d %H:%M:%S' 2>/dev/null || echo unknown)
    mkdir -p "$PERSISTENT" 2>/dev/null || return 0
    tmp="$STATUS.tmp.$$"
    {
        printf 'boot_id=%s\n' "$boot_id"
        printf 'time=%s\n' "$now"
        printf 'action=%s\n' "$action"
        printf 'target_set=%s\n' "$target_set"
        printf 'observed_set=%s\n' "$observed"
        printf 'detail=%s\n' "$detail"
    } > "$tmp" 2>/dev/null && mv -f "$tmp" "$STATUS" 2>/dev/null || rm -f -- "$tmp" 2>/dev/null || true
}

if ! /system/bin/sh "$SH" "$BUNDLE" status > "$tmp_status" 2>/dev/null; then
    write_status "error" "" "开机读取设备状态失败"
    exit 0
fi

adj_avail=$(awk -F= '$1 == "ADJ_AVAILABLE" { print $2; exit }' "$tmp_status" 2>/dev/null || true)
adj_set=$(awk -F= '$1 == "ADJ_SET_NODE" { print $2; exit }' "$tmp_status" 2>/dev/null || true)
sdam_cpuss=$(awk -F= '$1 == "SDAM_CPUSS_IDX" { print $2; exit }' "$tmp_status" 2>/dev/null || true)
sdam_global=$(awk -F= '$1 == "SDAM_GLOBAL_IDX" { print $2; exit }' "$tmp_status" 2>/dev/null || true)

if [ "$adj_avail" != 1 ] || [ "$adj_set" != 1 ]; then
    write_status "skipped_unavailable" "${sdam_cpuss:-?}" "调压写入接口不可用，已跳过自动写入"
    exit 0
fi

case "$sdam_cpuss:$sdam_global" in
    [0-7]:[0-7]) ;;
    *)
        write_status "skipped_unavailable" "${sdam_cpuss:-?}" "SDAM 档位读回无效，已跳过自动写入"
        exit 0
        ;;
esac

if [ "$dry_run" = 1 ]; then
    write_status "dry_run_would_write" "$sdam_cpuss" "dry-run 模拟将在本次开机写入 Set $target_set（当前读回 Set $sdam_cpuss）"
    exit 0
fi

if err_out=$(/system/bin/sh "$SH" "$BUNDLE" set_override "$boot_id" "$target_set" "$sdam_cpuss" "$sdam_global" 2>&1); then
    if [ "$sdam_cpuss" = "$target_set" ]; then
        write_status "refreshed" "$sdam_cpuss" "本次开机已重新写入并回读确认 Set $target_set（原值已为 Set $target_set）"
    else
        write_status "written" "$sdam_cpuss" "开机检测原值为 Set $sdam_cpuss，已自动写入 Set $target_set（下次重启生效，不会自动重启）"
    fi
else
    clean_err=$(printf '%s' "$err_out" | tr '\n' ' ' | tr -s ' ')
    write_status "error" "$sdam_cpuss" "开机自动写入 Set $target_set 失败：$clean_err"
fi
