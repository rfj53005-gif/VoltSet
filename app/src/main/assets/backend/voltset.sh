#!/system/bin/sh
set -eu
PATH=/system/bin
export PATH
umask 077

bundle=$1
action=$2
shift 2

root=/data/adb/voltset
persistent=$root/persistent
session=$root/session
suntuner_baseline=/data/adb/sm8750-tuner/persistent/stock-baseline.csv
modules=/sys/module
boot_file=/proc/sys/kernel/random/boot_id

work=
owned_scmi=

die() {
    printf 'ERROR %s\n' "$*" >&2
    exit 1
}

unload_scmi() {
    [ -n "$owned_scmi" ] || return 0
    if [ -d "$modules/$owned_scmi" ]; then
        rmmod "$owned_scmi" || return 1
    fi
    owned_scmi=
}

cleanup() {
    rc=$?
    trap - EXIT
    unload_scmi || {
        printf 'ERROR SCMI 读表模块卸载失败\n' >&2
        rc=1
    }
    [ -z "$work" ] || rm -rf -- "$work" || rc=1
    exit "$rc"
}

is_sm8850() {
    case "$(getprop ro.soc.model 2>/dev/null || true)" in
        [sS][mM]8850|[sS][mM]8850[pP]) return 0 ;;
        *) return 1 ;;
    esac
}

kernel_page_size() {
    local bytes
    bytes=$(getconf PAGESIZE 2>/dev/null) || bytes=
    case "$bytes" in
        ''|*[!0-9]*)
            awk '
                /^KernelPageSize:/ && $2 > 0 { print $2 * 1024; found = 1; exit }
                END { if (!found) exit 1 }
            ' /proc/self/smaps
            ;;
        *) printf '%s\n' "$bytes" ;;
    esac
}

scmi_module_asset() {
    local directory=sm8750 pages asset
    if is_sm8850; then
        directory=sm8850/android16-6-4k
        case "$(uname -r)" in
            6.12.*-android16-5-*)
                pages=$(kernel_page_size) || return 1
                [ "$pages" = 4096 ] || return 1
                directory=sm8850/android16-5-4k
                ;;
        esac
    fi
    asset="modules/$directory/qcom-scmi-ro.ko"
    [ -r "$bundle/$asset" ] || return 1
    printf '%s\n' "$asset"
}

read_scmi_to_file() {
    local out_file=$1 asset driver snap
    [ -d /sys/bus/scmi_protocol ] || {
        printf '缺少 /sys/bus/scmi_protocol 接口\n' >&2
        return 1
    }
    asset=$(scmi_module_asset) || {
        printf '缺少对应内核 (%s) 的 SCMI 读表模块\n' "$(uname -r)" >&2
        return 1
    }
    if [ -d "$modules/qcom_scmi_ro" ] || [ -d "$modules/sm8750_scmi_plan_ro" ]; then
        printf 'SCMI 读表模块正在被其他程序占用\n' >&2
        return 1
    fi
    cp "$bundle/$asset" "$work/qcom-scmi-ro.ko" || {
        printf '复制 SCMI 模块失败\n' >&2
        return 1
    }
    insmod "$work/qcom-scmi-ro.ko" || {
        printf '加载 qcom-scmi-ro 失败（内核 %s）\n' "$(uname -r)" >&2
        return 1
    }
    if [ -d "$modules/qcom_scmi_ro" ]; then
        owned_scmi=qcom_scmi_ro
        driver=qcom-scmi-ro
    elif [ -d "$modules/sm8750_scmi_plan_ro" ]; then
        owned_scmi=sm8750_scmi_plan_ro
        driver=sm8750-scmi-plan-ro
    else
        printf '加载后未发现 SCMI 模块节点\n' >&2
        return 1
    fi

    set -- /sys/bus/scmi_protocol/drivers/"$driver"/*/snapshot
    if [ "$#" -lt 1 ] || [ ! -r "$1" ]; then
        unload_scmi || true
        printf 'SCMI PERF 读表驱动未绑定\n' >&2
        return 1
    fi
    snap=$1
    if ! cat "$snap" > "$out_file"; then
        unload_scmi || true
        printf '读取 SCMI snapshot 失败\n' >&2
        return 1
    fi
    unload_scmi || {
        printf '卸载 SCMI 读表模块失败\n' >&2
        return 1
    }
    return 0
}

find_debugfs() {
    local mp
    awk '$3 == "debugfs" { print $2 }' /proc/mounts 2>/dev/null > "$work/debugfs_mounts" || : > "$work/debugfs_mounts"
    while read -r mp; do
        [ -n "$mp" ] || continue
        if [ -e "$mp/qcom_voltage_adj/get_overrides" ]; then
            printf '%s\n' "$mp"
            return 0
        fi
    done < "$work/debugfs_mounts"
    if [ -e /sys/kernel/debug/qcom_voltage_adj/get_overrides ]; then
        printf '%s\n' /sys/kernel/debug
        return 0
    fi
    return 1
}

read_reg_7151() {
    local hint_dbg=${1:-} candidate mp val
    for candidate in "$hint_dbg/regmap/0-00/registers" /sys/kernel/debug/regmap/0-00/registers; do
        if [ -n "$candidate" ] && [ -r "$candidate" ]; then
            val=$(awk -F: '$1 == "7151" { gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit }' "$candidate" 2>/dev/null || true)
            if [ -n "$val" ]; then
                printf '%s\n' "$val"
                return 0
            fi
        fi
    done
    awk '$3 == "debugfs" { print $2 }' /proc/mounts 2>/dev/null > "$work/debugfs_mounts" || : > "$work/debugfs_mounts"
    while read -r mp; do
        [ -n "$mp" ] || continue
        candidate="$mp/regmap/0-00/registers"
        if [ -r "$candidate" ]; then
            val=$(awk -F: '$1 == "7151" { gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit }' "$candidate" 2>/dev/null || true)
            if [ -n "$val" ]; then
                printf '%s\n' "$val"
                return 0
            fi
        fi
    done < "$work/debugfs_mounts"
    printf '%s\n' unavailable
}

read_overrides_indices() {
    local node=$1 raw
    raw=$(cat "$node") || return 1
    cpuss_idx=$(printf '%s\n' "$raw" | awk -F: '/CPUSS rails index/ { gsub(/[^0-9]/, "", $2); print $2; exit }')
    global_idx=$(printf '%s\n' "$raw" | awk -F: '/Global rails index/ { gsub(/[^0-9]/, "", $2); print $2; exit }')
    case "$cpuss_idx:$global_idx" in
        [0-7]:[0-7]) return 0 ;;
        *) return 1 ;;
    esac
}

find_partition_dev() {
    local path
    for path in "/dev/block/by-name/$1" "/dev/block/bootdevice/by-name/$1"; do
        if [ -e "$path" ]; then
            printf '%s\n' "$path"
            return 0
        fi
    done
    return 1
}

find_pdp_cdb() {
    local slot
    slot=$(getprop ro.boot.slot_suffix 2>/dev/null || true)
    if [ -n "$slot" ] && find_partition_dev "pdp_cdb$slot"; then
        return 0
    fi
    find_partition_dev pdp_cdb
}

find_cdt() {
    find_partition_dev cdt
}

[ "$(id -u)" = 0 ] || die "需要 Root 权限"
boot=$(cat "$boot_file" 2>/dev/null || true)
case "$boot" in
    ????????-????-????-????-????????????) ;;
    *) die "无法确认启动 ID" ;;
esac

mkdir -p "$root" "$persistent"
chmod 700 "$root" "$persistent"
exec 9>"$root/lock"
flock 9 9>&9 || die "无法获取 Root 操作锁"

if [ "$(cat "$session/boot" 2>/dev/null || true)" != "$boot" ]; then
    rm -rf -- "$session"
    mkdir -p "$session"
    printf '%s\n' "$boot" > "$session/boot"
fi

work=$(mktemp -d "$root/work.XXXXXXXX")
trap cleanup EXIT
trap 'exit 143' TERM HUP INT
trap '' PIPE

case "$action" in
    status)
        [ "$#" = 0 ] || die "status 参数无效"
        soc_model=$(getprop ro.soc.model 2>/dev/null || printf 'unknown')
        device_model=$(getprop ro.product.model 2>/dev/null || printf 'unknown')
        device_name=$(getprop ro.product.device 2>/dev/null || printf 'unknown')
        slot=$(getprop ro.boot.slot_suffix 2>/dev/null || printf '')
        kernel=$(uname -r)
        page_size=$(kernel_page_size 2>/dev/null || printf 'unknown')
        scmi_asset=$(scmi_module_asset 2>/dev/null || printf 'none')

        printf 'BOOT_ID=%s\n' "$boot"
        printf 'SOC_MODEL=%s\n' "$soc_model"
        printf 'DEVICE_MODEL=%s\n' "$device_model"
        printf 'DEVICE_NAME=%s\n' "$device_name"
        printf 'SLOT=%s\n' "$slot"
        printf 'KERNEL=%s\n' "$kernel"
        printf 'PAGE_SIZE=%s\n' "$page_size"
        printf 'SCMI_MODULE_ASSET=%s\n' "$scmi_asset"

        dbg=
        if dbg=$(find_debugfs); then
            printf 'DEBUGFS=%s\n' "$dbg"
            if [ -e "$dbg/qcom_voltage_adj/set_overrides" ]; then
                printf 'ADJ_SET_NODE=1\n'
            else
                printf 'ADJ_SET_NODE=0\n'
            fi
            if read_overrides_indices "$dbg/qcom_voltage_adj/get_overrides"; then
                printf 'ADJ_AVAILABLE=1\n'
                if [ ! -f "$session/boot-cpuss-idx" ]; then
                    printf '%s\n' "$cpuss_idx" > "$session/boot-cpuss-idx"
                fi
                boot_cpuss_idx=$(cat "$session/boot-cpuss-idx" 2>/dev/null || printf '%s' "$cpuss_idx")
                case "$boot_cpuss_idx" in [0-7]) ;; *) boot_cpuss_idx=$cpuss_idx ;; esac
                printf 'BOOT_CPUSS_IDX=%s\n' "$boot_cpuss_idx"
                printf 'SDAM_CPUSS_IDX=%s\n' "$cpuss_idx"
                printf 'SDAM_GLOBAL_IDX=%s\n' "$global_idx"
            else
                printf 'ADJ_AVAILABLE=0\n'
                printf 'ADJ_ERROR=无法解析 get_overrides 输出\n'
            fi
        else
            printf 'DEBUGFS=none\n'
            printf 'ADJ_SET_NODE=0\n'
            printf 'ADJ_AVAILABLE=0\n'
            printf 'ADJ_ERROR=未在 debugfs 找到 qcom_voltage_adj 接口\n'
        fi

        reg_7151=$(read_reg_7151 "$dbg")
        printf 'REG_7151=%s\n' "$reg_7151"

        if pdp_dev=$(find_pdp_cdb); then
            printf 'PDP_CDB_PATH=%s\n' "$pdp_dev"
            if dd if="$pdp_dev" bs=4096 count=64 2>/dev/null | base64 | tr -d '\n' > "$work/pdp.b64" && [ -s "$work/pdp.b64" ]; then
                printf 'PDP_CDB_B64_BEGIN\n'
                cat "$work/pdp.b64"
                printf '\nPDP_CDB_B64_END\n'
            else
                printf 'PDP_CDB_ERROR=读取 pdp_cdb 分区内容失败\n'
            fi
        else
            printf 'PDP_CDB_PATH=none\n'
        fi

        if cdt_dev=$(find_cdt); then
            printf 'CDT_PATH=%s\n' "$cdt_dev"
            if dd if="$cdt_dev" bs=1024 count=1 2>/dev/null | base64 | tr -d '\n' > "$work/cdt.b64" && [ -s "$work/cdt.b64" ]; then
                printf 'CDT_B64_BEGIN\n'
                cat "$work/cdt.b64"
                printf '\nCDT_B64_END\n'
            else
                printf 'CDT_ERROR=读取 cdt 分区内容失败\n'
            fi
        else
            printf 'CDT_PATH=none\n'
        fi

        if scmi_err=$(read_scmi_to_file "$work/scmi.csv" 2>&1); then
            printf 'SCMI_STATUS=ok\n'
            printf 'SCMI_BEGIN\n'
            awk '1' "$work/scmi.csv"
            printf 'SCMI_END\n'
        else
            printf 'SCMI_STATUS=error\n'
            printf 'SCMI_ERROR=%s\n' "$(printf '%s' "$scmi_err" | tr '\n' ' ' | tr -s ' ')"
        fi

        if [ -s "$persistent/stock-baseline.csv" ]; then
            printf 'BASELINE_SOURCE=voltset\n'
            printf 'BASELINE_BEGIN\n'
            awk '1' "$persistent/stock-baseline.csv"
            printf 'BASELINE_END\n'
        elif [ -r "$suntuner_baseline" ] && [ -s "$suntuner_baseline" ]; then
            printf 'BASELINE_SOURCE=suntuner\n'
            printf 'BASELINE_BEGIN\n'
            awk '1' "$suntuner_baseline"
            printf 'BASELINE_END\n'
        else
            printf 'BASELINE_SOURCE=none\n'
        fi
        ;;

    set_override)
        [ "$#" = 3 ] || die "set_override 参数数量无效"
        [ "$1" = "$boot" ] || die "启动 ID 已改变，请刷新状态后重试"
        target_cpuss=$2
        target_global=$3
        case "$target_cpuss" in [1-7]) ;; *) die "CPUSS 档位必须为 1..7" ;; esac
        case "$target_global" in [0-7]) ;; *) die "Global 档位必须为 0..7" ;; esac

        dbg=$(find_debugfs) || die "未找到 debugfs/qcom_voltage_adj 接口"
        [ -e "$dbg/qcom_voltage_adj/set_overrides" ] || die "缺少 qcom_voltage_adj/set_overrides 节点"
        [ -r "$dbg/qcom_voltage_adj/get_overrides" ] || die "缺少 qcom_voltage_adj/get_overrides 节点"

        read_overrides_indices "$dbg/qcom_voltage_adj/get_overrides" || die "写入前读取 get_overrides 失败"
        if [ ! -f "$session/boot-cpuss-idx" ]; then
            printf '%s\n' "$cpuss_idx" > "$session/boot-cpuss-idx"
        fi
        boot_cpuss_idx=$(cat "$session/boot-cpuss-idx" 2>/dev/null || printf '%s' "$cpuss_idx")

        dec=$(( (target_cpuss << 4) | target_global ))
        printf '%s\n' "$dec" > "$dbg/qcom_voltage_adj/set_overrides" || die "写入 set_overrides 失败"

        read_overrides_indices "$dbg/qcom_voltage_adj/get_overrides" || die "写入后回读 get_overrides 失败"
        [ "$cpuss_idx" = "$target_cpuss" ] && [ "$global_idx" = "$target_global" ] ||
            die "写入校验失败：读回为 CPUSS=$cpuss_idx Global=$global_idx（期望 $target_cpuss/$target_global）"

        reg_7151=$(read_reg_7151 "$dbg")
        printf 'BOOT_ID=%s\n' "$boot"
        printf 'BOOT_CPUSS_IDX=%s\n' "$boot_cpuss_idx"
        printf 'SDAM_CPUSS_IDX=%s\n' "$cpuss_idx"
        printf 'SDAM_GLOBAL_IDX=%s\n' "$global_idx"
        printf 'REG_7151=%s\n' "$reg_7151"
        printf 'WRITTEN_DEC=%s\n' "$dec"
        ;;

    save_baseline)
        [ "$#" = 1 ] || die "save_baseline 参数无效"
        [ "$1" = "$boot" ] || die "启动 ID 已改变，请刷新状态"

        if dbg=$(find_debugfs) && read_overrides_indices "$dbg/qcom_voltage_adj/get_overrides"; then
            boot_idx=$(cat "$session/boot-cpuss-idx" 2>/dev/null || printf '%s' "$cpuss_idx")
            [ "$boot_idx" = 1 ] || die "当前并非以 Set 1 启动（Set $boot_idx），不能保存为原厂基线"
        fi

        scmi_err=$(read_scmi_to_file "$work/scmi.csv" 2>&1) || die "$scmi_err"
        grep -q '^0,' "$work/scmi.csv" && grep -q '^1,' "$work/scmi.csv" ||
            die "SCMI 快照缺少 CPU 电压表数据"

        tmp="$persistent/stock-baseline.csv.tmp"
        {
            printf '# boot_id=%s\n' "$boot"
            grep '^[01],' "$work/scmi.csv"
        } > "$tmp" || {
            rm -f -- "$tmp"
            die "写入原厂基线文件失败"
        }
        mv "$tmp" "$persistent/stock-baseline.csv" || {
            rm -f -- "$tmp"
            die "保存原厂基线文件失败"
        }

        printf 'BASELINE_SOURCE=voltset\n'
        printf 'BASELINE_BEGIN\n'
        awk '1' "$persistent/stock-baseline.csv"
        printf 'BASELINE_END\n'
        ;;

    reboot)
        [ "$#" = 1 ] || die "reboot 参数无效"
        [ "$1" = "$boot" ] || die "启动 ID 已改变，请刷新状态"
        /system/bin/svc power reboot --no-wait
        ;;

    *)
        die "未知操作: $action"
        ;;
esac
