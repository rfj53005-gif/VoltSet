#!/system/bin/sh
set -eu
PATH=/system/bin
export PATH
umask 077

if [ "${2:-}" = "power_enable" ]; then
    [ "$(id -u)" = 0 ] || { printf 'ERROR 需要 Root 权限\n' >&2; exit 1; }
    qpt_en=/sys/class/powercap/qpt/enabled
    if [ -r "$qpt_en" ]; then
        cur=$(cat "$qpt_en" 2>/dev/null || echo 1)
        if [ "$cur" = "0" ] && [ -w "$qpt_en" ]; then
            printf '1\n' > "$qpt_en" 2>/dev/null || { printf 'ERROR 无法开启 QPT 硬件功耗遥测\n' >&2; exit 1; }
            printf 'QPT_CHANGED=1\n'
        else
            printf 'QPT_CHANGED=0\n'
        fi
        printf 'QPT_ENABLED=%s\n' "$(cat "$qpt_en" 2>/dev/null || echo 0)"
    else
        printf 'QPT_CHANGED=0\nQPT_ENABLED=1\n'
    fi
    exit 0
fi

if [ "${2:-}" = "power_disable" ]; then
    [ "$(id -u)" = 0 ] || { printf 'ERROR 需要 Root 权限\n' >&2; exit 1; }
    qpt_en=/sys/class/powercap/qpt/enabled
    if [ -w "$qpt_en" ]; then
        printf '0\n' > "$qpt_en" 2>/dev/null || true
    fi
    printf 'QPT_ENABLED=%s\n' "$(cat "$qpt_en" 2>/dev/null || echo 0)"
    exit 0
fi

if [ "${2:-}" = "power_sample" ]; then
    [ "$(id -u)" = 0 ] || { printf 'ERROR 需要 Root 权限\n' >&2; exit 1; }
    # Every 1s poll used to fork ~15 `cat`/subshell children, which itself perturbs
    # the idle CPU power it is trying to measure. Use `read` (shell builtin) instead.
    for d in /sys/class/powercap/qpt:0:[0-9]*; do
        [ -r "$d/name" ] && [ -r "$d/energy_uj" ] || continue
        rname=
        renergy=
        read -r rname < "$d/name" 2>/dev/null || rname=
        read -r renergy < "$d/energy_uj" 2>/dev/null || renergy=
        if [ -z "$rname" ] || [ -z "$renergy" ]; then
            continue
        fi
        printf 'QPT_RAIL=%s:%s\n' "$rname" "$renergy"
        if [ -r "$d/power_uw" ]; then
            rpower=
            read -r rpower < "$d/power_uw" 2>/dev/null || rpower=
            if [ -n "$rpower" ]; then
                printf 'QPT_POWER_UW=%s:%s\n' "$rname" "$rpower"
            fi
        fi
    done
    # Pick the two highest-max-frequency cpufreq policies instead of hardcoding
    # policy0/policy6, so other cluster topologies still report mid/large cores.
    large_pol=
    mid_pol=
    large_max=-1
    mid_max=-1
    for d in /sys/devices/system/cpu/cpufreq/policy[0-9]*; do
        [ -r "$d/scaling_cur_freq" ] || continue
        pmax=
        read -r pmax 2>/dev/null < "$d/cpuinfo_max_freq" || pmax=
        case "$pmax" in
            ''|0|*[!0-9]*) read -r pmax 2>/dev/null < "$d/scaling_max_freq" || pmax= ;;
        esac
        case "$pmax" in ''|0|*[!0-9]*) continue ;; esac
        if [ "$pmax" -gt "$large_max" ]; then
            mid_pol=$large_pol
            mid_max=$large_max
            large_pol=$d
            large_max=$pmax
        elif [ "$pmax" -gt "$mid_max" ]; then
            mid_pol=$d
            mid_max=$pmax
        fi
    done
    p0=
    p1=
    if [ -n "$mid_pol" ]; then
        read -r p0 < "$mid_pol/scaling_cur_freq" 2>/dev/null || p0=
    fi
    if [ -n "$large_pol" ]; then
        read -r p1 < "$large_pol/scaling_cur_freq" 2>/dev/null || p1=
    fi
    gpu=
    read -r gpu < /sys/class/kgsl/kgsl-3d0/gpuclk 2>/dev/null || gpu=
    printf 'FREQ_CPU0_KHZ=%s\n' "${p0:-0}"
    printf 'FREQ_CPU1_KHZ=%s\n' "${p1:-0}"
    printf 'FREQ_GPU_HZ=%s\n' "${gpu:-0}"
    max_temp=0
    soc_temp=0
    for tz in /sys/class/thermal/thermal_zone[0-9]*; do
        [ -r "$tz/type" ] && [ -r "$tz/temp" ] || continue
        ztype=
        ztemp=
        read -r ztype < "$tz/type" 2>/dev/null || ztype=
        read -r ztemp < "$tz/temp" 2>/dev/null || ztemp=
        case "$ztemp" in ''|*[!0-9]*) continue ;; esac
        [ "$ztemp" -ge 1000 ] && [ "$ztemp" -le 130000 ] || continue
        case "$ztype" in
            [cC][pP][uU]*)
                [ "$ztemp" -le "$max_temp" ] || max_temp=$ztemp
                ;;
            [sS][oO][cC][dD]*|[nN][sS][pP][sS][sS]*)
                [ "$ztemp" -le "$soc_temp" ] || soc_temp=$ztemp
                ;;
        esac
    done
    [ "$max_temp" -gt 0 ] || max_temp=$soc_temp
    if [ "$max_temp" -gt 0 ]; then
        printf 'TEMP_CPU_MC=%s\n' "$max_temp"
    fi
    exit 0
fi

existing_debugfs_has_adj() {
    local mp hit
    [ -e /sys/kernel/debug/qcom_voltage_adj/get_overrides ] && return 0
    for mp in $(awk '$3 == "debugfs" { print $2 }' /proc/mounts 2>/dev/null || true); do
        [ -e "$mp/qcom_voltage_adj/get_overrides" ] && return 0
    done
    for mp in $(awk '$3 == "debugfs" { print $2 }' /proc/mounts 2>/dev/null || true); do
        hit=$(find "$mp" -maxdepth 2 -name get_overrides 2>/dev/null | head -n 1 || true)
        [ -n "$hit" ] && return 0
    done
    return 1
}

root=/data/adb/voltset
persistent=$root/persistent
session=$root/session
last_set=$persistent/last-set
auto_conf=$persistent/auto-restore.conf
auto_status=$persistent/auto-restore.status
suntuner_baseline=/data/adb/sm8750-tuner/persistent/stock-baseline.csv
modules=/sys/module
boot_file=/proc/sys/kernel/random/boot_id

work=
owned_scmi=
transient_debugfs=0

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
    if [ "$transient_debugfs" = 1 ]; then
        umount /sys/kernel/debug 2>/dev/null || rc=1
    fi
    unload_scmi || {
        printf 'ERROR SCMI 读表模块卸载失败\n' >&2
        rc=1
    }
    [ -z "$work" ] || rm -rf -- "$work" || rc=1
    exit "$rc"
}

trap cleanup EXIT
trap 'exit 143' TERM HUP INT
trap '' PIPE

install_native_helper() {
    local name=$1
    local src="$bundle/bin/$name" dst="$root/$name" tmp="$root/$name.tmp.$$"
    [ -r "$src" ] || return 1
    mkdir -p "$root" && chmod 700 "$root" || return 1
    if [ ! -x "$dst" ] || ! cmp -s "$src" "$dst"; then
        if ! { cp "$src" "$tmp" && chmod 700 "$tmp" && mv -f "$tmp" "$dst"; }; then
            rm -f -- "$tmp"
            return 1
        fi
    fi
    printf '%s\n' "$dst"
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
    pages=$(kernel_page_size) || return 1
    [ "$pages" = 4096 ] || return 1
    if is_sm8850; then
        directory=sm8850/android16-6-4k
        case "$(uname -r)" in
            6.12.*-android16-5-*)
                directory=sm8850/android16-5-4k
                ;;
        esac
    fi
    asset="modules/$directory/qcom-scmi-ro.ko"
    [ -r "$bundle/$asset" ] || return 1
    printf '%s\n' "$asset"
}

read_scmi_module_to_file() {
    local out_file=$1 check_file=${2:-} asset driver snap
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
    if [ -n "$check_file" ] && is_sm8850 && ! cat "$snap" > "$check_file"; then
        rm -f -- "$check_file"
    fi
    unload_scmi || {
        printf '卸载 SCMI 读表模块失败\n' >&2
        return 1
    }
    return 0
}

find_energy_model_dir() {
    local hint_dbg=${1:-} mp
    if [ -n "$hint_dbg" ] && [ -d "$hint_dbg/energy_model" ]; then
        printf '%s\n' "$hint_dbg/energy_model"
        return 0
    fi
    if [ -d /sys/kernel/debug/energy_model ]; then
        printf '%s\n' /sys/kernel/debug/energy_model
        return 0
    fi
    awk '$3 == "debugfs" { print $2 }' /proc/mounts 2>/dev/null > "$work/debugfs_mounts" || : > "$work/debugfs_mounts"
    while read -r mp; do
        [ -n "$mp" ] || continue
        if [ -d "$mp/energy_model" ]; then
            printf '%s\n' "$mp/energy_model"
            return 0
        fi
    done < "$work/debugfs_mounts"
    return 1
}

read_energy_model_snapshot() {
    local em_dir=$1 out_file=$2
    local dom=0 cpu cdir flags count max_pwr d

    : > "$out_file"
    {
        printf '# source=debugfs_energy_model\n'
        printf 'domain,query_index,perf_value,estimated_power_uW,latency_us,indicative_freq_kHz,level_index\n'
    } > "$out_file"

    for cpu in $(for d in "$em_dir"/cpu[0-9]*; do [ -d "$d" ] && basename "$d" || true; done | awk '{ sub(/^cpu/, ""); print }' | sort -n); do
        cdir="$em_dir/cpu$cpu"
        flags=
        if [ -r "$cdir/flags" ]; then
            flags=$(awk 'NF { print $1; exit }' "$cdir/flags" 2>/dev/null || true)
        fi
        if [ -n "$flags" ]; then
            case "$flags" in
                0[xX][0-9a-fA-F]*|[0-9]*)
                    if [ $(( flags & 1 )) -eq 0 ] || [ $(( flags & 4 )) -ne 0 ]; then
                        continue
                    fi
                    ;;
            esac
        fi

        set -- "$cdir"/ps:*/frequency
        [ -e "$1" ] || continue
        awk '
            {
                p = FILENAME
                sub(/^.*\//, "", p)
                dir = FILENAME
                sub(/\/[^\/]+$/, "", dir)
                if (p == "frequency") freq[dir] = $1
                else if (p == "power") pwr[dir] = $1
            }
            END {
                for (d in freq) {
                    if (freq[d] + 0 > 0 && pwr[d] + 0 > 0) {
                        printf "%d %d\n", freq[d], pwr[d]
                    }
                }
            }
        ' "$cdir"/ps:*/frequency "$cdir"/ps:*/power 2>/dev/null | sort -n -k1,1 > "$work/em_sorted" || : > "$work/em_sorted"

        count=$(awk 'END { print NR }' "$work/em_sorted" 2>/dev/null || echo 0)
        [ "$count" -ge 2 ] || continue
        if [ -z "$flags" ]; then
            max_pwr=$(awk '$2 > m { m = $2 } END { printf "%d\n", m + 0 }' "$work/em_sorted")
            [ "$max_pwr" -gt 50000 ] || continue
        fi
        awk -v dom="$dom" '{ i = NR - 1; printf "%d,%d,%s,%s,0,%s,%d\n", dom, i, $1, $2, $1, i }' "$work/em_sorted" >> "$out_file"
        dom=$((dom + 1))
    done

    grep -q '^0,' "$out_file" && grep -q '^1,' "$out_file"
}

scmi_source=none

read_scmi_to_file() {
    local out_file=$1 check_file=${2:-} hint_dbg=${3:-}
    local em_dir= mod_err=
    scmi_source=none
    em_dir=$(find_energy_model_dir "$hint_dbg" 2>/dev/null || true)
    if [ -n "$em_dir" ] && read_energy_model_snapshot "$em_dir" "$out_file"; then
        if [ -n "$check_file" ] && is_sm8850; then
            read_energy_model_snapshot "$em_dir" "$check_file" 2>/dev/null || rm -f -- "$check_file"
        fi
        scmi_source=energy_model
        return 0
    fi
    if read_scmi_module_to_file "$out_file" "$check_file" 2> "$work/scmi_mod.err"; then
        scmi_source=module
        return 0
    fi
    mod_err=$(tr '\n' ' ' < "$work/scmi_mod.err" 2>/dev/null | tr -s ' ' || true)
    if [ -n "$em_dir" ]; then
        printf 'debugfs/energy_model 未找到有效的微瓦双簇功耗表，且回退内核模块失败：%s\n' "${mod_err:-未知错误}" >&2
    else
        printf '未挂载或缺少 debugfs/energy_model，且回退内核模块失败：%s\n' "${mod_err:-未知错误}" >&2
    fi
    return 1
}

first_debugfs_mount() {
    local mp
    mp=$(awk '$3 == "debugfs" { print $2; exit }' /proc/mounts 2>/dev/null || true)
    if [ -n "$mp" ]; then
        printf '%s\n' "$mp"
        return 0
    fi
    if [ -d /sys/kernel/debug/regmap ] || [ -d /sys/kernel/debug/qcom_voltage_adj ] || [ -d /sys/kernel/debug/energy_model ]; then
        printf '%s\n' /sys/kernel/debug
        return 0
    fi
    return 1
}

dbg=
adj_dir=

find_adj_pair() {
    local mp hit
    dbg=
    adj_dir=
    awk '$3 == "debugfs" { print $2 }' /proc/mounts 2>/dev/null > "$work/debugfs_mounts" || : > "$work/debugfs_mounts"
    while read -r mp; do
        [ -n "$mp" ] || continue
        if [ -e "$mp/qcom_voltage_adj/get_overrides" ]; then
            dbg=$mp
            adj_dir=$mp/qcom_voltage_adj
            return 0
        fi
    done < "$work/debugfs_mounts"
    if [ -e /sys/kernel/debug/qcom_voltage_adj/get_overrides ]; then
        dbg=/sys/kernel/debug
        adj_dir=/sys/kernel/debug/qcom_voltage_adj
        return 0
    fi
    while read -r mp; do
        [ -n "$mp" ] || continue
        hit=$(find "$mp" -maxdepth 2 -name get_overrides 2>/dev/null | head -n 1 || true)
        if [ -n "$hit" ]; then
            dbg=$mp
            adj_dir=$(dirname "$hit")
            return 0
        fi
    done < "$work/debugfs_mounts"
    if [ -d /sys/kernel/debug ]; then
        hit=$(find /sys/kernel/debug -maxdepth 2 -name get_overrides 2>/dev/null | head -n 1 || true)
        if [ -n "$hit" ]; then
            dbg=/sys/kernel/debug
            adj_dir=$(dirname "$hit")
            return 0
        fi
    fi
    return 1
}

vadj_platform_dev=
vadj_driver=
vadj_spmi_parent=
vadj_dt_path=

discover_vadj_platform() {
    local cand= p hit real pdir
    vadj_platform_dev=
    vadj_driver=
    vadj_spmi_parent=
    vadj_dt_path=
    for p in /sys/bus/platform/devices/*voltage*adj* /sys/bus/platform/devices/*vadj*; do
        if [ -e "$p" ]; then
            cand=$p
            break
        fi
    done
    if [ -z "$cand" ]; then
        hit=$(grep -lE 'OF_COMPATIBLE_[0-9]+=qcom,(vadj|voltage-adj)|^DRIVER=qcom_voltage_adj$' /sys/bus/platform/devices/*/uevent 2>/dev/null | head -n 1 || true)
        [ -n "$hit" ] && cand=${hit%/uevent}
    fi
    [ -n "$cand" ] || return 0
    vadj_platform_dev=$(basename "$cand")
    if [ -r "$cand/uevent" ]; then
        vadj_driver=$(awk -F= '$1 == "DRIVER" { print $2; exit }' "$cand/uevent" 2>/dev/null || true)
        vadj_dt_path=$(awk -F= '$1 == "OF_FULLNAME" { print $2; exit }' "$cand/uevent" 2>/dev/null || true)
    fi
    [ -n "$vadj_driver" ] || vadj_driver=unbound
    real=$(readlink -f "$cand" 2>/dev/null || true)
    if [ -n "$real" ]; then
        pdir=$(basename "$(dirname "$real")" 2>/dev/null || true)
        case "$pdir" in
            [0-9]*-[0-9a-fA-F]*|spmi*) vadj_spmi_parent=$pdir ;;
        esac
    fi
}

sdam_nvmem_path=
sdam_nvmem_dev=
sdam_nvmem_pmic=
sdam_byte=
sdam_hex=

discover_sdam2_nvmem() {
    local d real dev_dir pmic_dir dev_drv dev_of pmic_spmi pmic_name matched_pmic
    sdam_nvmem_path=
    sdam_nvmem_dev=
    sdam_nvmem_pmic=
    for d in /sys/bus/nvmem/devices/*sdam*; do
        [ -e "$d/nvmem" ] || continue
        real=$(readlink -f "$d" 2>/dev/null || true)
        [ -n "$real" ] || continue
        dev_dir=$(dirname "$real")
        [ -r "$dev_dir/uevent" ] || continue
        dev_drv=$(awk -F= '$1 == "DRIVER" { print $2; exit }' "$dev_dir/uevent" 2>/dev/null || true)
        dev_of=$(awk -F= '$1 == "OF_FULLNAME" { print $2; exit }' "$dev_dir/uevent" 2>/dev/null || true)
        [ "$dev_drv" = "qcom,spmi-sdam" ] || continue
        case "$dev_of" in
            */sdam@7100) ;;
            *) continue ;;
        esac
        pmic_dir=$(dirname "$dev_dir")
        pmic_spmi=$(basename "$pmic_dir")
        pmic_name=
        if [ -r "$pmic_dir/uevent" ]; then
            pmic_name=$(awk -F= '$1 == "OF_NAME" { print $2; exit }' "$pmic_dir/uevent" 2>/dev/null || true)
        fi
        matched_pmic=0
        case "$pmic_name" in
            [pP][mM][kK]8850*) matched_pmic=1 ;;
        esac
        if [ "$matched_pmic" = 0 ] && [ -n "$vadj_spmi_parent" ] && [ "$pmic_spmi" = "$vadj_spmi_parent" ]; then
            matched_pmic=1
        fi
        [ "$matched_pmic" = 1 ] || continue
        sdam_nvmem_path="$d/nvmem"
        sdam_nvmem_dev=$(basename "$d")
        sdam_nvmem_pmic=${pmic_name:-$pmic_spmi}
        break
    done
}

read_sdam2_nvmem_byte() {
    local path=$1 val
    sdam_byte=
    sdam_hex=
    [ -r "$path" ] || return 1
    val=$(dd if="$path" bs=1 skip=81 count=1 2>/dev/null | od -An -tu1 | awk 'NF { print $1; exit }' || true)
    case "$val" in
        ''|*[!0-9]*) return 1 ;;
    esac
    [ "$val" -ge 0 ] && [ "$val" -le 255 ] || return 1
    sdam_byte=$val
    sdam_hex=$(printf '%02x' "$val")
    return 0
}

read_sdam2_nvmem_overrides() {
    read_sdam2_nvmem_byte "$1" || return 1
    [ $(( sdam_byte & 136 )) -eq 136 ] || return 2
    cpuss_idx=$(( (sdam_byte >> 4) & 7 ))
    global_idx=$(( sdam_byte & 7 ))
    case "$cpuss_idx:$global_idx" in
        [1-7]:[0-7]) return 0 ;;
        *) return 2 ;;
    esac
}

write_sdam2_nvmem_overrides() {
    local path=$1 target_cpuss=$2 target_global=$3 byte_val oct
    byte_val=$(( (target_cpuss << 4) | target_global | 136 ))
    oct=$(printf '%03o' "$byte_val")
    printf "\\$oct" | dd of="$path" bs=1 seek=81 count=1 conv=notrunc 2>/dev/null
}

reg_7151=unavailable
regmap_node=none

read_reg_7151() {
    local hint_dbg=${1:-} spmi_hint=${2:-}
    local candidates rn candidate mp val
    reg_7151=unavailable
    regmap_node=none
    if [ -n "$spmi_hint" ] && [ "$spmi_hint" != "0-00" ]; then
        candidates="$spmi_hint 0-00"
    else
        candidates="0-00"
    fi
    awk '$3 == "debugfs" { print $2 }' /proc/mounts 2>/dev/null > "$work/debugfs_mounts" || : > "$work/debugfs_mounts"
    for rn in $candidates; do
        if [ -n "$hint_dbg" ]; then
            candidate="$hint_dbg/regmap/$rn/registers"
            if [ -r "$candidate" ]; then
                val=$(awk -F: '$1 == "7151" { gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit }' "$candidate" 2>/dev/null || true)
                if [ -n "$val" ]; then
                    reg_7151=$val
                    regmap_node=$rn
                    return 0
                fi
            fi
        fi
        candidate="/sys/kernel/debug/regmap/$rn/registers"
        if [ -r "$candidate" ]; then
            val=$(awk -F: '$1 == "7151" { gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit }' "$candidate" 2>/dev/null || true)
            if [ -n "$val" ]; then
                reg_7151=$val
                regmap_node=$rn
                return 0
            fi
        fi
        while read -r mp; do
            [ -n "$mp" ] || continue
            candidate="$mp/regmap/$rn/registers"
            if [ -r "$candidate" ]; then
                val=$(awk -F: '$1 == "7151" { gsub(/^[ \t]+|[ \t]+$/, "", $2); print $2; exit }' "$candidate" 2>/dev/null || true)
                if [ -n "$val" ]; then
                    reg_7151=$val
                    regmap_node=$rn
                    return 0
                fi
            fi
        done < "$work/debugfs_mounts"
    done
    if [ -n "$sdam_nvmem_path" ] && [ -r "$sdam_nvmem_path" ] && read_sdam2_nvmem_byte "$sdam_nvmem_path"; then
        reg_7151=$sdam_hex
        regmap_node="nvmem:$sdam_nvmem_dev"
        return 0
    fi
    return 0
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

verify_overrides() {
    read_overrides_indices "$1" || die "无法核对当前 SDAM 档位，请刷新后重试"
    [ "$cpuss_idx:$global_idx" = "$2:$3" ] || die "SDAM 档位已变化，请刷新后重新确认"
}

probe_adjustment() {
    adj_backend=none
    adj_available=0
    adj_set_node=0
    adj_error=
    if find_adj_pair; then
        adj_backend=debugfs
        [ ! -e "$adj_dir/set_overrides" ] || adj_set_node=1
        if read_overrides_indices "$adj_dir/get_overrides"; then
            adj_available=1
        else
            adj_error="无法解析 get_overrides 输出"
        fi
    else
        dbg=$(first_debugfs_mount 2>/dev/null || true)
        if [ "${1:-}" = quick ]; then
            discover_vadj_platform
            discover_sdam2_nvmem
        fi
        if [ -n "$sdam_nvmem_path" ] && [ -r "$sdam_nvmem_path" ]; then
            if read_sdam2_nvmem_overrides "$sdam_nvmem_path"; then
                adj_backend=nvmem
                adj_available=1
                [ ! -w "$sdam_nvmem_path" ] || adj_set_node=1
            else
                adj_error="未找到 debugfs/qcom_voltage_adj；虽检测到 $sdam_nvmem_path (0x7151=0x${sdam_hex:-??})，但读回未通过安全校验，为安全起见未启用 nvmem 兜底"
            fi
        fi
    fi
    if [ "$adj_available" = 1 ] && [ ! -f "$session/boot-cpuss-idx" ]; then
        printf '%s\n' "$cpuss_idx" > "$session/boot-cpuss-idx"
    fi
}

emit_adjustment_status() {
    local private_ns=0 path=$adj_dir
    [ "${VOLTSET_PRIVATE_NS:-0}" != 1 ] || private_ns=1
    [ "$adj_backend" != nvmem ] || path=$sdam_nvmem_path
    printf 'ADJ_BACKEND=%s\n' "$adj_backend"
    printf 'DEBUGFS=%s\n' "${dbg:-none}"
    printf 'DEBUGFS_PRIVATE_NS=%s\n' "$private_ns"
    printf 'ADJ_DIR=%s\n' "${path:-none}"
    printf 'ADJ_SET_NODE=%s\n' "$adj_set_node"
    printf 'ADJ_AVAILABLE=%s\n' "$adj_available"
    if [ "$adj_available" = 1 ]; then
        printf 'SDAM_CPUSS_IDX=%s\n' "$cpuss_idx"
        printf 'SDAM_GLOBAL_IDX=%s\n' "$global_idx"
    elif [ -n "$adj_error" ]; then
        printf 'ADJ_ERROR=%s\n' "$adj_error"
    fi
}

find_partition_dev() {
    local target=$1 path blk
    for path in \
        "/dev/block/by-name/$target" \
        "/dev/block/bootdevice/by-name/$target" \
        /dev/block/platform/*/by-name/"$target" \
        /dev/block/platform/*/*/by-name/"$target"; do
        if [ -b "$path" ]; then
            printf '%s\n' "$path"
            return 0
        fi
    done
    blk=$(awk -F= -v t="$target" '
        FNR == 1 {
            n = split(FILENAME, path, "/")
            b = path[n - 1]
        }
        $1 == "PARTNAME" && $2 == t {
            print b
            exit
        }
    ' /sys/class/block/*/uevent 2>/dev/null || true)
    if [ -n "$blk" ] && [ -b "/dev/block/$blk" ]; then
        printf '/dev/block/%s\n' "$blk"
        return 0
    fi
    return 1
}

emit_gpu_corners() {
    local mp db gfx
    for mp in /sys/kernel/debug $(awk '$3 == "debugfs" { print $2 }' /proc/mounts); do
        db="$mp/cmd-db"
        [ -r "$db" ] || continue
        gfx=$(awk '$2 == "gfx.lvl" { print "gfx=" $0 }' "$db" 2>/dev/null) || continue
        [ -n "$gfx" ] || continue
        printf '%s\n' "$gfx"
        return 0
    done
    return 1
}

emit_gpu_info() {
    local corners=${1:-0} slot source bytes file acd helper
    [ "$#" -le 1 ] && { [ "$corners" = 0 ] || [ "$corners" = 1 ]; } || die "gpu_info 参数无效"
    slot=$(getprop ro.boot.slot_suffix)
    case "$slot" in _a|_b) ;; *) die "无法识别当前 A/B 启动槽位" ;; esac
    source=$(find_partition_dev "vendor_boot$slot") || die "找不到当前槽位的 vendor_boot"
    bytes=$(blockdev --getsize64 "$source") || die "无法读取 vendor_boot 分区大小"
    printf 'slot=%s\nsource=%s\n' "$slot" "$source"
    printf 'device=%s\n' "$(getprop ro.product.device)"
    printf 'build=%s\n' "$(getprop ro.build.version.incremental)"
    printf 'index=%s\n' "$(getprop ro.boot.dtb_idx)"
    printf 'boot=%s\n' "$(cat "$boot_file")"
    printf 'bytes=%s\n' "$bytes"
    for file in /sys/class/kgsl/kgsl-3d0/gpu_available_frequencies /sys/class/kgsl/kgsl-3d0/devfreq/available_frequencies; do
        if [ -r "$file" ]; then
            printf 'frequencies=%s\n' "$(cat "$file")"
            break
        fi
    done
    if acd=$(cat /sys/class/kgsl/kgsl-3d0/acd 2>/dev/null); then
        printf 'acd=%s\n' "$acd"
    fi
    if find_partition_dev "dtbo$slot" >/dev/null 2>&1 || find_partition_dev dtbo >/dev/null 2>&1; then
        printf 'dtbo_present=1\n'
    else
        printf 'dtbo_present=0\n'
    fi
    if [ "$corners" = 1 ]; then
        printf 'soc_model=%s\n' "$(getprop ro.soc.model 2>/dev/null || true)"
        printf 'soc_id=%s\n' "$(cat /sys/devices/soc0/soc_id 2>/dev/null || getprop ro.vendor.qti.soc_id 2>/dev/null || true)"
        if ! emit_gpu_corners && helper=$(install_native_helper vsetns 2>/dev/null); then
            "$helper" /system/bin/sh "$0" "$bundle" gpu_corners 2>/dev/null || true
        fi
    fi
}

find_pdp_cdb() {
    local slot
    slot=$(getprop ro.boot.slot_suffix 2>/dev/null || true)
    if [ -n "$slot" ] && find_partition_dev "pdp_cdb$slot"; then
        return 0
    fi
    find_partition_dev pdp_cdb
}

find_pdp_fw() {
    local slot
    slot=$(getprop ro.boot.slot_suffix 2>/dev/null || true)
    if [ -n "$slot" ] && find_partition_dev "pdp$slot"; then
        return 0
    fi
    find_partition_dev pdp
}

read_partition_prefix() {
    # dd stops at EOF on smaller partitions. Both frontends accept up to 4 MiB.
    dd if="$1" of="$2" bs=4096 count=1024 2>/dev/null && [ -s "$2" ]
}

partition_fingerprint() {
    local raw_file=$1 hash
    hash=$(sha256sum "$raw_file" 2>/dev/null) || return 1
    hash=${hash%% *}
    valid_fingerprint "$hash" || return 1
    printf '%s\n' "$hash"
}

valid_fingerprint() {
    [ "${#1}" = 64 ] || return 1
    case "$1" in *[!0-9a-f]*) return 1 ;; esac
}

read_pdp_cdb_fingerprint() {
    local dev raw_file="$work/pdp-cdb.raw"
    dev=$(find_pdp_cdb) && read_partition_prefix "$dev" "$raw_file" || return 1
    partition_fingerprint "$raw_file"
}

emit_partition_block() {
    local dev=$1 gz_tag=$2 raw_tag=$3 err_key=$4 err_msg=$5 hash
    local raw_file="$work/partition.raw" gz_file="$work/partition.gz" b64_file="$work/partition.b64"
    local tag=$gz_tag
    if ! read_partition_prefix "$dev" "$raw_file"; then
        printf '%s=%s\n' "$err_key" "$err_msg"
        return
    fi
    if [ "$raw_tag" = "PDP_CDB_B64" ] && hash=$(partition_fingerprint "$raw_file"); then
        printf 'PDP_CDB_SHA256=%s\n' "$hash"
    fi
    if ! { gzip -c "$raw_file" > "$gz_file" 2>/dev/null &&
        base64 -w 0 "$gz_file" > "$b64_file" && [ -s "$b64_file" ]; }; then
        tag=$raw_tag
        if ! base64 -w 0 "$raw_file" > "$b64_file" || [ ! -s "$b64_file" ]; then
            printf '%s=%s\n' "$err_key" "$err_msg"
            return
        fi
    fi
    printf '%s_BEGIN\n' "$tag"
    cat "$b64_file"
    printf '\n%s_END\n' "$tag"
    rm -f -- "$raw_file" "$gz_file" "$b64_file"
}

find_cdt() {
    local slot
    slot=$(getprop ro.boot.slot_suffix 2>/dev/null || true)
    if [ -n "$slot" ] && find_partition_dev "cdt$slot"; then
        return 0
    fi
    find_partition_dev cdt
}

# Mirrors core/CdtInspector.kt for known formats. Ambiguous data blocks auto-restore.
cdt_key7() {
    local dev raw="$work/cdt.raw"
    dev=$(find_cdt) && [ -r "$dev" ] || {
        printf 'unavailable\n'
        return 0
    }
    dd if="$dev" of="$raw" bs=1024 count=1 2>/dev/null &&
        od -An -v -tu1 "$raw" > "$work/cdt-bytes" || {
        printf 'unknown\n'
        return 0
    }
    awk '
        { for (i = 1; i <= NF; i++) { b[n++] = $i + 0; if ($i != 0) nonzero = 1 } }
        END {
            if (n < 18) { print "unknown"; exit }
            if (!nonzero) { print "off"; exit }
            if (b[0] != 67 || b[1] != 68 || b[2] != 84 || b[3] != 0) { print "unknown"; exit }
            off = b[14] + 256 * b[15]
            size = b[16] + 256 * b[17]
            if (off < 18 || size < 6 || off + size > n) { print "unknown"; exit }
            ver = b[off]
            if (ver == 6 && size == 9) { print "off"; exit }
            if (ver < 3 || ver > 6) { print "unknown"; exit }
            hl = (ver == 3) ? 6 : ((ver >= 6) ? 10 : 7)
            if (size < hl) { print "unknown"; exit }
            cnt = b[off + hl - 1]
            if (hl + cnt * 2 > size) { print "unknown"; exit }
            for (i = 0; i < cnt; i++) {
                if (b[off + hl + i * 2] == 7 && b[off + hl + i * 2 + 1] != 0) {
                    print b[off + hl + i * 2 + 1]
                    exit
                }
            }
            print "off"
        }
    ' "$work/cdt-bytes" 2>/dev/null || printf 'unknown\n'
}

join_lines_csv() {
    awk 'NF { printf "%s%s", (c++ ? "," : ""), $0 } END { if (!c) printf "none"; printf "\n" }'
}

emit_system_probe() {
    local hint_dbg=${1:-} mod_loaded=${2:-0} mod_files=${3:-}
    local debugfs_mounts debugfs_volt_nodes pdp_cdt_partitions by_name_dirs scmi_drivers scmi_count cpufreq_policies
    local energy_model_domains em_dir socinfo_dir socinfo_str pair f k v cpu cdir cnt
    local d p n c mn mx
    local qa10 qa20_pair qfprom_aging qpt_rails rn

    debugfs_mounts=$(awk '$3 == "debugfs" { print $2 }' /proc/mounts 2>/dev/null | join_lines_csv)
    if [ -n "$hint_dbg" ] && [ -d "$hint_dbg" ]; then
        debugfs_volt_nodes=$(find "$hint_dbg" -maxdepth 2 \( -iname '*voltage_adj*' -o -iname '*vadj*' -o -name 'get_overrides' -o -name 'set_overrides' -o -iname '*cpr*' \) 2>/dev/null | sort -u | head -n 12 | join_lines_csv)
    else
        debugfs_volt_nodes=none
    fi
    pdp_cdt_partitions=$(awk -F= '
        FNR == 1 {
            b = FILENAME
            sub(/^\/sys\/class\/block\//, "", b)
            sub(/\/uevent$/, "", b)
        }
        $1 == "PARTNAME" && tolower($2) ~ /^(pdp|cdt)/ {
            printf "%s=/dev/block/%s\n", $2, b
        }
    ' /sys/class/block/*/uevent 2>/dev/null | sort | head -n 16 | join_lines_csv)
    by_name_dirs=$(
        for d in /dev/block/by-name /dev/block/bootdevice/by-name /dev/block/platform/*/by-name /dev/block/platform/*/*/by-name; do
            [ -d "$d" ] && printf '%s\n' "$d" || true
        done | sort -u | join_lines_csv
    )
    scmi_drivers=$(
        for d in /sys/bus/scmi_protocol/drivers/*; do
            [ -e "$d" ] && basename "$d" || true
        done | sort | join_lines_csv
    )
    scmi_count=$(set -- /sys/bus/scmi_protocol/devices/*; [ -e "$1" ] && printf '%s\n' "$#" || printf '0\n')
    cpufreq_policies=$(
        for p in /sys/devices/system/cpu/cpufreq/policy*; do
            [ -d "$p" ] || continue
            n=$(basename "$p")
            c=$(awk '{ if (NF > 1) printf "%s-%s", $1, $NF; else if (NF == 1) printf "%s", $1 }' "$p/affected_cpus" 2>/dev/null || true)
            mn=$(cat "$p/cpuinfo_min_freq" 2>/dev/null || echo 0)
            mx=$(cat "$p/cpuinfo_max_freq" 2>/dev/null || echo 0)
            case "$mn" in ''|*[!0-9]*) mn=0 ;; esac
            case "$mx" in ''|*[!0-9]*) mx=0 ;; esac
            printf '%s(%s,%d-%dMHz)\n' "$n" "${c:-?}" "$((mn / 1000))" "$((mx / 1000))"
        done | join_lines_csv
    )
    em_dir=$(find_energy_model_dir "$hint_dbg" 2>/dev/null || true)
    if [ -n "$em_dir" ]; then
        energy_model_domains=$(
            for cpu in $(for d in "$em_dir"/cpu[0-9]*; do [ -d "$d" ] && basename "$d" || true; done | awk '{ sub(/^cpu/, ""); print }' | sort -n); do
                cdir="$em_dir/cpu$cpu"
                c=$(awk '{ if (NF > 1) printf "%s-%s", $1, $NF; else if (NF == 1) printf "%s", $1 }' "$cdir/cpus" 2>/dev/null || true)
                set -- "$cdir"/ps:*
                if [ -d "$1" ]; then cnt=$#; else cnt=0; fi
                printf 'cpu%s(%s,%d)\n' "$cpu" "${c:-?}" "$cnt"
            done | join_lines_csv
        )
    else
        energy_model_domains=none
    fi
    if [ -n "$hint_dbg" ] && [ -d "$hint_dbg/qcom_socinfo" ]; then
        socinfo_dir="$hint_dbg/qcom_socinfo"
    elif [ -d /sys/kernel/debug/qcom_socinfo ]; then
        socinfo_dir=/sys/kernel/debug/qcom_socinfo
    else
        socinfo_dir=
    fi
    if [ -n "$socinfo_dir" ]; then
        socinfo_str=
        for pair in "chip_id:chip" "foundry_id:foundry" "feature_code:feature" "pcode:pcode" "oem_variant:oem" "pmic_die_rev:pmic_rev"; do
            f=${pair%%:*}
            k=${pair#*:}
            if [ -r "$socinfo_dir/$f" ]; then
                v=$(awk 'NF { gsub(/^[ \t]+|[ \t]+$/, ""); print; exit }' "$socinfo_dir/$f" 2>/dev/null || true)
                if [ -n "$v" ]; then
                    socinfo_str="${socinfo_str:+$socinfo_str }$k=$v"
                fi
            fi
        done
        socinfo_str=${socinfo_str:-none}
    else
        socinfo_str=none
    fi

    if [ -r /sys/bus/nvmem/devices/qfprom0/nvmem ]; then
        qa10=$(dd if=/sys/bus/nvmem/devices/qfprom0/nvmem bs=4 skip=644 count=1 2>/dev/null | od -An -tx4 | awk 'NF { print $1; exit }' || true)
        qa20_pair=$(dd if=/sys/bus/nvmem/devices/qfprom0/nvmem bs=4 skip=648 count=2 2>/dev/null | od -An -tx4 | awk '{ for (i=1; i<=NF; i++) printf "%s%s", (c++ ? "," : ""), $i }' || true)
        case "$qa10:$qa20_pair" in
            [0-9a-fA-F]*:[0-9a-fA-F]*,[0-9a-fA-F]*) qfprom_aging="$qa10,$qa20_pair" ;;
            *) qfprom_aging=none ;;
        esac
    else
        qfprom_aging=none
    fi
    qpt_rails=$(
        for d in /sys/class/powercap/qpt:0:[0-9]*; do
            [ -r "$d/name" ] && [ -r "$d/energy_uj" ] || continue
            rn=$(cat "$d/name" 2>/dev/null || true)
            case "$rn" in
                ''|debug-*) continue ;;
                *) printf '%s\n' "$rn" ;;
            esac
        done | join_lines_csv
    )
    boot_reason=$(
        {
            getprop ro.boot.bootreason 2>/dev/null || true
            getprop sys.boot.reason 2>/dev/null || true
        } | awk 'NF && !seen[$0]++ { print }' | join_lines_csv
    )
    pstore_crash=none
    for pf in /sys/fs/pstore/console-ramoops-0 /sys/fs/pstore/console-ramoops /sys/fs/pstore/dmesg-ramoops-0; do
        [ -r "$pf" ] && [ -s "$pf" ] || continue
        hit=$(grep -iE 'Kernel panic|Watchdog|BUG:|Oops|Internal error|Subsystem restart' "$pf" 2>/dev/null |
            tail -n 1 | tr '\r\n\t' '   ' | tr -cd '[:print:]' |
            awk '{ gsub(/^[ \t]+|[ \t]+$/, ""); print substr($0, 1, 120); exit }' || true)
        if [ -n "$hit" ]; then
            pstore_crash="$(basename "$pf"): $hit"
        else
            pstore_crash="$(basename "$pf") (present)"
        fi
        break
    done

    printf 'PROBE_BEGIN\n'
    printf 'vadj_platform_dev=%s\n' "${vadj_platform_dev:-none}"
    printf 'vadj_driver=%s\n' "${vadj_driver:-none}"
    printf 'vadj_spmi_parent=%s\n' "${vadj_spmi_parent:-none}"
    printf 'vadj_dt_path=%s\n' "${vadj_dt_path:-none}"
    printf 'vadj_module_loaded=%s\n' "$mod_loaded"
    printf 'vadj_module_files=%s\n' "${mod_files:-none}"
    printf 'debugfs_mounts=%s\n' "$debugfs_mounts"
    printf 'debugfs_volt_nodes=%s\n' "$debugfs_volt_nodes"
    printf 'pdp_cdt_partitions=%s\n' "$pdp_cdt_partitions"
    printf 'by_name_dirs=%s\n' "$by_name_dirs"
    printf 'scmi_drivers=%s\n' "$scmi_drivers"
    printf 'scmi_device_count=%s\n' "$scmi_count"
    printf 'cpufreq_policies=%s\n' "$cpufreq_policies"
    printf 'energy_model_domains=%s\n' "$energy_model_domains"
    printf 'socinfo=%s\n' "$socinfo_str"
    printf 'qfprom_aging=%s\n' "$qfprom_aging"
    printf 'qpt_rails=%s\n' "$qpt_rails"
    printf 'sdam2_nvmem=%s\n' "${sdam_nvmem_path:-none}"
    printf 'sdam2_pmic=%s\n' "${sdam_nvmem_pmic:-none}"
    printf 'boot_reason=%s\n' "${boot_reason:-none}"
    printf 'pstore_crash=%s\n' "${pstore_crash:-none}"
    printf 'PROBE_END\n'
}


write_override() {
    local target_cpuss=$1 expected_cpuss=$2 target_global=$3 dec
    if [ -n "$adj_dir" ]; then
        [ -e "$adj_dir/set_overrides" ] || die "缺少 qcom_voltage_adj/set_overrides 节点"
        [ -r "$adj_dir/get_overrides" ] || die "缺少 qcom_voltage_adj/get_overrides 节点"

        verify_overrides "$adj_dir/get_overrides" "$expected_cpuss" "$target_global"
        if [ ! -f "$session/boot-cpuss-idx" ]; then
            printf '%s\n' "$cpuss_idx" > "$session/boot-cpuss-idx"
        fi
        printf '%s %s\n' "$boot" "$target_cpuss" > "$work/last-set" || die "无法创建档位记录"

        dec=$(( (target_cpuss << 4) | target_global ))
        printf '%s\n' "$dec" > "$adj_dir/set_overrides" || die "写入 set_overrides 失败"

        read_overrides_indices "$adj_dir/get_overrides" || die "写入后回读 get_overrides 失败"
        [ "$cpuss_idx" = "$target_cpuss" ] && [ "$global_idx" = "$target_global" ] ||
            die "写入校验失败：读回为 CPUSS=$cpuss_idx Global=$global_idx（期望 $target_cpuss/$target_global）"
        mv "$work/last-set" "$last_set" || die "SDAM 已写入，但无法保存重启核对记录"

        if [ "$action" = auto_restore ]; then
            return 0
        fi
        read_reg_7151 "$dbg" "$vadj_spmi_parent"
        printf 'SDAM_CPUSS_IDX=%s\n' "$cpuss_idx"
        printf 'SDAM_GLOBAL_IDX=%s\n' "$global_idx"
        printf 'REG_7151=%s\n' "$reg_7151"
    elif [ -n "$sdam_nvmem_path" ] && [ -r "$sdam_nvmem_path" ] && [ -w "$sdam_nvmem_path" ]; then
        read_sdam2_nvmem_overrides "$sdam_nvmem_path" || die "未找到 debugfs，且 $sdam_nvmem_path 当前 0x7151 状态未通过安全校验"
        [ "$cpuss_idx:$global_idx" = "$expected_cpuss:$target_global" ] || die "SDAM 档位已变化，请刷新后重新确认"
        if [ ! -f "$session/boot-cpuss-idx" ]; then
            printf '%s\n' "$cpuss_idx" > "$session/boot-cpuss-idx"
        fi
        printf '%s %s\n' "$boot" "$target_cpuss" > "$work/last-set" || die "无法创建档位记录"

        write_sdam2_nvmem_overrides "$sdam_nvmem_path" "$target_cpuss" "$target_global" || die "写入 $sdam_nvmem_path (0x7151) 失败"

        read_sdam2_nvmem_overrides "$sdam_nvmem_path" || die "写入后回读 $sdam_nvmem_path 失败"
        [ "$cpuss_idx" = "$target_cpuss" ] && [ "$global_idx" = "$target_global" ] ||
            die "写入校验失败：读回为 CPUSS=$cpuss_idx Global=$global_idx（期望 $target_cpuss/$target_global）"
        mv "$work/last-set" "$last_set" || die "SDAM 已写入，但无法保存重启核对记录"

        printf 'SDAM_CPUSS_IDX=%s\n' "$cpuss_idx"
        printf 'SDAM_GLOBAL_IDX=%s\n' "$global_idx"
        printf 'REG_7151=%s\n' "$sdam_hex"
    else
        die "未找到 debugfs/qcom_voltage_adj 或可用的 spmi_sdam2/nvmem 接口"
    fi
}

write_auto_restore_config() {
    local enabled=$1 target=$2 hash=$3 tmp="$work/auto-restore.conf"
    {
        printf 'enabled=%s\ntarget_set=%s\n' "$enabled" "$target"
        if [ -n "$hash" ]; then
            printf 'pdp_cdb_sha256=%s\n' "$hash"
        fi
    } > "$tmp" && mv -f "$tmp" "$auto_conf"
}

set_auto_restore() {
    local enabled=$1 target=$2 hash current
    case "$enabled:$target" in
        0:|0:[2-7]) write_auto_restore_config 0 "$target" "" || die "保存开机自动写入配置失败" ;;
        1:[2-7])
            [ "$#" = 4 ] && [ "$3" = "$boot" ] || die "启动状态已变化，请刷新后重试"
            hash=$4
            valid_fingerprint "$hash" || die "缺少有效的 pdp_cdb 指纹，请刷新后重试"
            [ "$(cdt_key7)" = off ] || die "无法确认 CDT Key 7 已关闭"
            current=$(read_pdp_cdb_fingerprint) || die "读取 pdp_cdb 指纹失败"
            [ "$current" = "$hash" ] || die "pdp_cdb 已变化，请刷新并重新确认档位"
            write_auto_restore_config 1 "$target" "$hash" || die "保存开机自动写入配置失败"
            ;;
        *) die "开机自动写入配置无效" ;;
    esac
}

write_auto_restore_status() {
    local result=$1 observed=$2 detail=$3 tmp="$work/auto-restore.status"
    if [ "$dry_run" = 1 ]; then
        printf 'DRY_RUN action=%s target=%s observed=%s detail=%s\n' "$result" "$target_set" "$observed" "$detail"
        return 0
    fi
    {
        printf 'boot_id=%s\ntime=%s\n' "$boot" "$(date '+%Y-%m-%d %H:%M:%S')"
        printf 'action=%s\ntarget_set=%s\nobserved_set=%s\ndetail=%s\n' "$result" "$target_set" "$observed" "$detail"
    } > "$tmp" && mv -f "$tmp" "$auto_status"
}

restore_on_boot() {
    local dry_run=0 enabled=0 target_set= saved_hash= k v reason key7 current err
    [ "${1:-}" != "--dry-run" ] || dry_run=1
    [ -r "$auto_conf" ] || return 0
    while IFS='=' read -r k v || [ -n "$k$v" ]; do
        case "$k" in
            enabled) enabled=$v ;;
            target_set) target_set=$v ;;
            pdp_cdb_sha256) saved_hash=$v ;;
        esac
    done < "$auto_conf"
    [ "$enabled" = 1 ] || return 0
    case "$target_set" in [2-7]) ;; *) return 0 ;; esac
    if [ "$dry_run" = 0 ] && [ -r "$auto_status" ] && grep -qx "boot_id=$boot" "$auto_status"; then
        return 0
    fi

    reason=$(
        { getprop ro.boot.bootreason; getprop sys.boot.reason; } 2>/dev/null |
            tr '\n[:upper:]' ' [:lower:]'
    )
    case "$reason" in
        *watchdog*|*wdog*|*wdt*|*panic*|*crash*|*lockup*|*fatal*)
            if [ "$dry_run" = 0 ] && ! write_auto_restore_config 0 "$target_set" ""; then
                write_auto_restore_status skipped_bootreason "" "检测到异常重启（$reason），本次已跳过，关闭自动写入配置失败"
            else
                write_auto_restore_status skipped_bootreason "" "检测到异常重启（$reason），自动写入暂停，需在 WebUI 重新开启"
            fi
            return
            ;;
    esac
    if ! valid_fingerprint "$saved_hash"; then
        write_auto_restore_status skipped_firmware "" "缺少 pdp_cdb 指纹，请在 WebUI 关闭后重新开启自动写入"
        return
    fi
    key7=$(cdt_key7)
    case "$key7" in
        off) ;;
        unavailable|unknown)
            write_auto_restore_status skipped_cdt "" "无法确认 CDT Key 7 已关闭，已跳过自动写入"
            return
            ;;
        *)
            write_auto_restore_status skipped_cdt "" "CDT Key 7=$key7 已开启，已跳过自动写入"
            return
            ;;
    esac
    current=$(read_pdp_cdb_fingerprint) || current=
    if [ "$current" != "$saved_hash" ]; then
        write_auto_restore_status skipped_firmware "" "pdp_cdb 已变化或无法读取，请刷新设备状态并重新确认自动写入"
        return
    fi
    probe_adjustment quick
    if [ "$adj_available:$adj_set_node" != 1:1 ]; then
        write_auto_restore_status skipped_unavailable "" "调压写入接口不可用，已跳过自动写入"
        return
    fi
    if [ "$cpuss_idx" = "$target_set" ]; then
        write_auto_restore_status skipped_already_target "$cpuss_idx" "SDAM 已是 Set $target_set，无需重复写入"
    elif [ "$dry_run" = 1 ]; then
        write_auto_restore_status dry_run_would_write "$cpuss_idx" "当前为 Set $cpuss_idx，正常开机会写入 Set $target_set"
    elif err=$(write_override "$target_set" "$cpuss_idx" "$global_idx" 2>&1); then
        write_auto_restore_status written "$cpuss_idx" "开机检测原值为 Set $cpuss_idx，已写入并回读确认 Set $target_set（下次重启生效）"
    else
        err=$(printf '%s' "$err" | tr '\n' ' ')
        write_auto_restore_status error "$cpuss_idx" "开机自动写入 Set $target_set 失败：$err"
    fi
}

[ "$(id -u)" = 0 ] || die "需要 Root 权限"
[ "$#" -ge 2 ] || die "缺少后端操作参数"
bundle=$1
action=$2
shift 2

needs_debugfs=0
case "$action" in
    gpu_info) emit_gpu_info "$@"; exit ;;
    gpu_corners) emit_gpu_corners; exit ;;
    power_stream)
        [ "$#" -le 1 ] || die "power_stream 参数无效"
        helper=$(install_native_helper vsetqpt) || die "无法部署 QPT 采样程序"
        exec "$helper" "$root/qpt.lock" "$@"
        ;;
    status|quick_status|set_override|save_baseline|reboot) needs_debugfs=1 ;;
    set_auto_restore)
        case "$#:${1:-}" in 2:0|4:1) ;; *) die "set_auto_restore 参数无效" ;; esac
        ;;
    read_power_records|read_auto_restore)
        [ "$#" = 0 ] || die "$action 参数无效"
        ;;
    write_power_records)
        [ "$#" = 1 ] || die "write_power_records 参数无效"
        ;;
    auto_restore)
        case "$#:${1:-}" in 0:|1:--dry-run) ;; *) die "auto_restore 参数无效" ;; esac
        [ -r "$auto_conf" ] && grep -qx 'enabled=1' "$auto_conf" || exit 0
        needs_debugfs=1
        ;;
    *) die "未知操作: $action" ;;
esac

if [ "$needs_debugfs" = 1 ] && [ "${VOLTSET_PRIVATE_NS:-0}" != 1 ] &&
    ! existing_debugfs_has_adj && helper=$(install_native_helper vsetns 2>/dev/null); then
    VOLTSET_PRIVATE_NS=1
    export VOLTSET_PRIVATE_NS
    exec "$helper" /system/bin/sh "$0" "$bundle" "$action" "$@"
fi

boot=$(cat "$boot_file" 2>/dev/null || true)
case "$boot" in
    ????????-????-????-????-????????????) ;;
    *) die "无法确认启动 ID" ;;
esac

mkdir -p "$root" "$persistent"
chmod 700 "$root" "$persistent"
exec 9>"$root/lock"
if [ "$action" = auto_restore ]; then
    flock 9 9>&9 || die "开机自动写入无法获取 Root 锁"
else
    flock -n 9 9>&9 || die "另一项 Root 操作正在进行，请稍后重试"
fi

# A shared mount, if needed, is owned by this locked operation and covered by cleanup.
if [ "$needs_debugfs" = 1 ] && [ "${VOLTSET_PRIVATE_NS:-0}" != 1 ] && ! first_debugfs_mount >/dev/null &&
    mount -t debugfs -o nosuid,nodev,noexec debugfs /sys/kernel/debug 2>/dev/null; then
    transient_debugfs=1
fi

last_set_boot=
last_set_idx=
if [ -r "$last_set" ]; then
    read -r last_set_boot last_set_idx < "$last_set" || last_set_idx=
    case "$last_set_boot:$last_set_idx" in
        ????????-????-????-????-????????????:[1-7]) ;;
        *) last_set_idx= ;;
    esac
fi

if [ "$(cat "$session/boot" 2>/dev/null || true)" != "$boot" ]; then
    rm -rf -- "$session"
    mkdir -p "$session"
    if [ -n "$last_set_idx" ] && [ "$last_set_boot" != "$boot" ]; then
        printf '%s\n' "$last_set_idx" > "$session/previous-target-idx"
    fi
    printf '%s\n' "$boot" > "$session/boot"
fi

work=$(mktemp -d "$root/work.XXXXXXXX")

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
        if [ -r "$session/previous-target-idx" ]; then
            printf 'PREVIOUS_TARGET_IDX=%s\n' "$(cat "$session/previous-target-idx")"
        fi
        if [ -n "$last_set_idx" ] && [ "$last_set_boot" = "$boot" ]; then
            printf 'STAGED_THIS_BOOT_IDX=%s\n' "$last_set_idx"
        fi

        discover_vadj_platform
        discover_sdam2_nvmem
        vadj_mod_loaded=0
        if grep -qE '(^|[[:space:]])qcom_voltage_adj([[:space:]]|$)' /proc/modules 2>/dev/null ||
            [ -d /sys/module/qcom_voltage_adj ] ||
            [ -d /sys/bus/platform/drivers/qcom_voltage_adj ]; then
            vadj_mod_loaded=1
        fi
        vadj_mod_files=$(
            for d in /vendor_dlkm/lib/modules /vendor/lib/modules /system_dlkm/lib/modules /lib/modules; do
                [ -d "$d" ] && find "$d" -maxdepth 3 -iname '*voltage*adj*.ko*' 2>/dev/null || true
            done | sort -u | head -n 8 | awk 'NF { printf "%s%s", (c++ ? "," : ""), $0 }'
        )

        probe_adjustment
        if [ "$adj_available" = 0 ] && [ -z "$adj_error" ]; then
            if [ "$vadj_mod_loaded" = 1 ]; then
                adj_error="内核已加载 qcom_voltage_adj 驱动，但无法挂载或访问 debugfs 节点（请检查内核 CONFIG_DEBUG_FS 或 SELinux 限制）"
            elif [ -n "$vadj_mod_files" ]; then
                adj_error="在系统目录发现模块（$vadj_mod_files）但未加载到内核，且未找到 debugfs 节点"
            elif [ -n "$vadj_platform_dev" ]; then
                adj_error="设备树存在 $vadj_platform_dev 节点，但内核未内置或未加载 qcom_voltage_adj 驱动"
            else
                adj_error="未在 debugfs、内核模块目录或设备树中发现 qcom_voltage_adj 接口（当前机型/内核可能未启用该功能）"
            fi
        fi
        emit_adjustment_status

        if [ -r "$session/boot-cpuss-idx" ]; then
            printf 'FIRST_SDAM_IDX=%s\n' "$(cat "$session/boot-cpuss-idx")"
        fi

        read_reg_7151 "$dbg" "$vadj_spmi_parent"
        printf 'REGMAP_NODE=%s\n' "$regmap_node"
        printf 'REG_7151=%s\n' "$reg_7151"

        if pdp_dev=$(find_pdp_cdb); then
            printf 'PDP_CDB_PATH=%s\n' "$pdp_dev"
            emit_partition_block "$pdp_dev" PDP_CDB_GZB64 PDP_CDB_B64 PDP_CDB_ERROR "读取 pdp_cdb 分区内容失败"
        else
            printf 'PDP_CDB_PATH=none\n'
        fi

        if pdp_fw_dev=$(find_pdp_fw); then
            printf 'PDP_FW_PATH=%s\n' "$pdp_fw_dev"
            emit_partition_block "$pdp_fw_dev" PDP_FW_GZB64 PDP_FW_B64 PDP_FW_ERROR "读取 pdp 分区内容失败"
        else
            printf 'PDP_FW_PATH=none\n'
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

        if read_scmi_to_file "$work/scmi.csv" "$work/scmi-recheck.csv" "$dbg" 2> "$work/scmi.err"; then
            printf 'SCMI_STATUS=ok\n'
            printf 'SCMI_SOURCE=%s\n' "$scmi_source"
            printf 'SCMI_BEGIN\n'
            awk '1' "$work/scmi.csv"
            printf 'SCMI_END\n'
            if [ -s "$work/scmi-recheck.csv" ]; then
                printf 'SCMI_RECHECK_BEGIN\n'
                awk '1' "$work/scmi-recheck.csv"
                printf 'SCMI_RECHECK_END\n'
            fi
        else
            printf 'SCMI_STATUS=error\n'
            printf 'SCMI_SOURCE=none\n'
            printf 'SCMI_ERROR=%s\n' "$(tr '\n' ' ' < "$work/scmi.err" | tr -s ' ')"
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

        emit_system_probe "$dbg" "$vadj_mod_loaded" "$vadj_mod_files"
        ;;

    quick_status)
        [ "$#" = 0 ] || die "quick_status 参数无效"
        printf 'BOOT_ID=%s\n' "$boot"
        probe_adjustment quick
        emit_adjustment_status
        ;;

    set_auto_restore)
        set_auto_restore "$@"
        ;;

    read_power_records)
        power_file=$persistent/power-records.csv
        if [ -r "$power_file" ] && [ -s "$power_file" ]; then
            printf 'POWER_RECORDS_BEGIN\n'
            awk '1' "$power_file"
            printf 'POWER_RECORDS_END\n'
        else
            printf 'POWER_RECORDS_EMPTY=1\n'
        fi
        ;;

    write_power_records)
        power_file=$persistent/power-records.csv
        b64=$1
        if [ -z "$b64" ]; then
            rm -f -- "$power_file"
            exit 0
        fi
        [ "${#b64}" -le 65536 ] || die "功耗记录数据超出限制"
        printf '%s' "$b64" | base64 -d > "$work/power-records.raw" 2>/dev/null || die "功耗记录解码失败"
        awk '
            NF == 0 { next }
            /^[1-7],[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+,[0-9]+(,[0-9]+,[0-9]+,-?[0-9]+)?$/ { print; next }
            { bad = 1; exit 1 }
            END { if (bad) exit 1 }
        ' "$work/power-records.raw" > "$work/power-records.clean" || die "功耗记录格式无效"
        if [ -s "$work/power-records.clean" ]; then
            mv -f "$work/power-records.clean" "$power_file" || die "保存功耗记录失败"
        else
            rm -f -- "$power_file"
        fi
        ;;

    read_auto_restore)
        if [ -r /data/adb/modules/voltset/boot-completed.sh ] ||
            [ -r /data/adb/modules_update/voltset/boot-completed.sh ]; then
            printf 'KSU_MODULE_INSTALLED=1\n'
        else
            printf 'KSU_MODULE_INSTALLED=0\n'
        fi
        if [ -r "$auto_conf" ]; then
            printf 'AUTO_RESTORE_CONF_BEGIN\n'
            awk '1' "$auto_conf"
            printf 'AUTO_RESTORE_CONF_END\n'
        fi
        if [ -r "$auto_status" ]; then
            printf 'AUTO_RESTORE_STATUS_BEGIN\n'
            awk '1' "$auto_status"
            printf 'AUTO_RESTORE_STATUS_END\n'
        fi
        ;;

    auto_restore)
        restore_on_boot "$@"
        ;;

    set_override)
        [ "$#" = 4 ] || die "set_override 参数数量无效"
        [ "$1" = "$boot" ] || die "启动 ID 已改变，请刷新状态后重试"
        target_cpuss=$2
        expected_cpuss=$3
        target_global=$4
        case "$target_cpuss" in [1-7]) ;; *) die "CPUSS 档位必须为 1..7" ;; esac
        case "$target_global" in [0-7]) ;; *) die "Global 档位必须为 0..7" ;; esac

        discover_vadj_platform
        discover_sdam2_nvmem
        find_adj_pair || true
        write_override "$target_cpuss" "$expected_cpuss" "$target_global"
        ;;

    save_baseline)
        [ "$#" = 1 ] || die "save_baseline 参数无效"
        [ "$1" = "$boot" ] || die "启动 ID 已改变，请刷新状态"

        boot_idx=$(cat "$session/boot-cpuss-idx" 2>/dev/null || true)
        prev_idx=$(cat "$session/previous-target-idx" 2>/dev/null || true)
        [ "$boot_idx" = 1 ] && { [ -z "$prev_idx" ] || [ "$prev_idx" = 1 ]; } ||
            die "无法确认本次开机为 Set 1，不能保存为原厂基线"

        em_dbg=$(first_debugfs_mount 2>/dev/null || true)
        read_scmi_to_file "$work/scmi.csv" "" "$em_dbg" 2> "$work/scmi.err" || die "$(cat "$work/scmi.err")"
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
        [ "$#" = 3 ] || die "reboot 参数无效"
        [ "$1" = "$boot" ] || die "启动 ID 已改变，请刷新状态"
        discover_vadj_platform
        discover_sdam2_nvmem
        if find_adj_pair; then
            verify_overrides "$adj_dir/get_overrides" "$2" "$3"
        elif [ -n "$sdam_nvmem_path" ] && [ -r "$sdam_nvmem_path" ]; then
            read_sdam2_nvmem_overrides "$sdam_nvmem_path" || die "无法核对 $sdam_nvmem_path 当前 SDAM 档位，请刷新后重试"
            [ "$cpuss_idx:$global_idx" = "$2:$3" ] || die "SDAM 档位已变化，请刷新后重新确认"
        else
            die "未找到 debugfs/qcom_voltage_adj 或可用的 spmi_sdam2/nvmem 接口"
        fi
        /system/bin/svc power reboot --no-wait
        ;;

    *)
        die "未知操作: $action"
        ;;
esac
