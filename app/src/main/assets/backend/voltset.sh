#!/system/bin/sh
set -eu
PATH=/system/bin
export PATH
umask 077

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

if [ -z "${VOLTSET_PRIVATE_NS:-}" ] && [ "$(id -u)" = 0 ] && ! existing_debugfs_has_adj; then
    helper_src="${1:-}/bin/vsetns"
    if [ -n "${1:-}" ] && [ -r "$helper_src" ]; then
        mkdir -p /data/adb/voltset 2>/dev/null || true
        chmod 700 /data/adb/voltset 2>/dev/null || true
        helper_bin=/data/adb/voltset/vsetns
        if [ ! -x "$helper_bin" ] || ! cmp -s "$helper_src" "$helper_bin" 2>/dev/null; then
            helper_tmp="$helper_bin.tmp.$$"
            if cp "$helper_src" "$helper_tmp" 2>/dev/null &&
                chmod 700 "$helper_tmp" 2>/dev/null &&
                mv -f "$helper_tmp" "$helper_bin" 2>/dev/null; then
                :
            else
                rm -f -- "$helper_tmp" 2>/dev/null || true
            fi
        fi
        if [ -x "$helper_bin" ]; then
            VOLTSET_PRIVATE_NS=1
            export VOLTSET_PRIVATE_NS
            exec "$helper_bin" /system/bin/sh "$0" "$@"
        fi
    fi
fi

transient_debugfs=0
if [ -z "${VOLTSET_PRIVATE_NS:-}" ] && [ "$(id -u)" = 0 ] && ! existing_debugfs_has_adj; then
    if mount -t debugfs -o nosuid,nodev,noexec debugfs /sys/kernel/debug 2>/dev/null; then
        transient_debugfs=1
    fi
fi

bundle=$1
action=$2
shift 2

root=/data/adb/voltset
persistent=$root/persistent
session=$root/session
last_set=$persistent/last-set
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
    if [ "${transient_debugfs:-0}" = 1 ]; then
        umount /sys/kernel/debug 2>/dev/null || rc=1
    fi
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

find_partition_dev() {
    local target=$1 path blk
    for path in \
        "/dev/block/by-name/$target" \
        "/dev/block/bootdevice/by-name/$target" \
        /dev/block/platform/*/by-name/"$target" \
        /dev/block/platform/*/*/by-name/"$target"; do
        if [ -e "$path" ]; then
            printf '%s\n' "$path"
            return 0
        fi
    done
    blk=$(awk -F= -v t="$target" '
        FNR == 1 {
            b = FILENAME
            sub(/^\/sys\/class\/block\//, "", b)
            sub(/\/uevent$/, "", b)
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

emit_partition_block() {
    local dev=$1 blocks=$2 gz_tag=$3 raw_tag=$4 err_key=$5 err_msg=$6
    local raw_file="$work/$raw_tag.raw"
    local b64_file="$work/$raw_tag.b64"
    if ! dd if="$dev" bs=4096 count="$blocks" of="$raw_file" 2>/dev/null || [ ! -s "$raw_file" ]; then
        rm -f -- "$raw_file"
        printf '%s=%s\n' "$err_key" "$err_msg"
        return 0
    fi
    if command -v gzip >/dev/null 2>&1 &&
        gzip -c "$raw_file" > "$raw_file.gz" 2>/dev/null &&
        [ -s "$raw_file.gz" ] &&
        base64 < "$raw_file.gz" | tr -d '\n' > "$b64_file" &&
        [ -s "$b64_file" ]; then
        printf '%s_BEGIN\n' "$gz_tag"
        cat "$b64_file"
        printf '\n%s_END\n' "$gz_tag"
    elif base64 < "$raw_file" | tr -d '\n' > "$b64_file" && [ -s "$b64_file" ]; then
        printf '%s_BEGIN\n' "$raw_tag"
        cat "$b64_file"
        printf '\n%s_END\n' "$raw_tag"
    else
        printf '%s=%s\n' "$err_key" "$err_msg"
    fi
    rm -f -- "$raw_file" "$raw_file.gz" "$b64_file"
}

find_cdt() {
    local slot
    slot=$(getprop ro.boot.slot_suffix 2>/dev/null || true)
    if [ -n "$slot" ] && find_partition_dev "cdt$slot"; then
        return 0
    fi
    find_partition_dev cdt
}

join_lines_csv() {
    awk 'NF { printf "%s%s", (c++ ? "," : ""), $0 } END { if (!c) printf "none"; printf "\n" }'
}

emit_system_probe() {
    local hint_dbg=${1:-} mod_loaded=${2:-0} mod_files=${3:-}
    local debugfs_mounts debugfs_volt_nodes pdp_cdt_partitions by_name_dirs scmi_drivers scmi_count cpufreq_policies
    local energy_model_domains em_dir socinfo_dir socinfo_str pair f k v cpu cdir cnt
    local d p n c mn mx

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
    printf 'PROBE_END\n'
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
flock -n 9 9>&9 || die "另一项 Root 操作正在进行，请稍后重试"

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
        if [ -r "$session/previous-target-idx" ]; then
            printf 'PREVIOUS_TARGET_IDX=%s\n' "$(cat "$session/previous-target-idx")"
        fi
        if [ -n "$last_set_idx" ] && [ "$last_set_boot" = "$boot" ]; then
            printf 'STAGED_THIS_BOOT_IDX=%s\n' "$last_set_idx"
        fi

        discover_vadj_platform
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

        if find_adj_pair; then
            printf 'DEBUGFS=%s\n' "$dbg"
            printf 'ADJ_DIR=%s\n' "$adj_dir"
            if [ "${VOLTSET_PRIVATE_NS:-0}" = 1 ]; then
                printf 'DEBUGFS_PRIVATE_NS=1\n'
            else
                printf 'DEBUGFS_PRIVATE_NS=0\n'
            fi
            if [ -e "$adj_dir/set_overrides" ]; then
                printf 'ADJ_SET_NODE=1\n'
            else
                printf 'ADJ_SET_NODE=0\n'
            fi
            if read_overrides_indices "$adj_dir/get_overrides"; then
                printf 'ADJ_AVAILABLE=1\n'
                if [ ! -f "$session/boot-cpuss-idx" ]; then
                    printf '%s\n' "$cpuss_idx" > "$session/boot-cpuss-idx"
                fi
                printf 'SDAM_CPUSS_IDX=%s\n' "$cpuss_idx"
                printf 'SDAM_GLOBAL_IDX=%s\n' "$global_idx"
            else
                printf 'ADJ_AVAILABLE=0\n'
                printf 'ADJ_ERROR=无法解析 get_overrides 输出\n'
            fi
        else
            dbg=$(first_debugfs_mount 2>/dev/null || true)
            printf 'DEBUGFS=%s\n' "${dbg:-none}"
            printf 'ADJ_DIR=none\n'
            if [ "${VOLTSET_PRIVATE_NS:-0}" = 1 ]; then
                printf 'DEBUGFS_PRIVATE_NS=1\n'
            else
                printf 'DEBUGFS_PRIVATE_NS=0\n'
            fi
            printf 'ADJ_SET_NODE=0\n'
            printf 'ADJ_AVAILABLE=0\n'
            if [ "$vadj_mod_loaded" = 1 ]; then
                printf 'ADJ_ERROR=内核已加载 qcom_voltage_adj 驱动，但无法挂载或访问 debugfs 节点（请检查内核 CONFIG_DEBUG_FS 或 SELinux 限制）\n'
            elif [ -n "$vadj_mod_files" ]; then
                printf 'ADJ_ERROR=在系统目录发现模块（%s）但未加载到内核，且未找到 debugfs 节点\n' "$vadj_mod_files"
            elif [ -n "$vadj_platform_dev" ]; then
                printf 'ADJ_ERROR=设备树存在 %s 节点，但内核未内置或未加载 qcom_voltage_adj 驱动\n' "$vadj_platform_dev"
            else
                printf 'ADJ_ERROR=未在 debugfs、内核模块目录或设备树中发现 qcom_voltage_adj 接口（当前机型/内核可能未启用该功能）\n'
            fi
        fi

        if [ -r "$session/boot-cpuss-idx" ]; then
            printf 'FIRST_SDAM_IDX=%s\n' "$(cat "$session/boot-cpuss-idx")"
        fi

        read_reg_7151 "$dbg" "$vadj_spmi_parent"
        printf 'REGMAP_NODE=%s\n' "$regmap_node"
        printf 'REG_7151=%s\n' "$reg_7151"

        if pdp_dev=$(find_pdp_cdb); then
            printf 'PDP_CDB_PATH=%s\n' "$pdp_dev"
            emit_partition_block "$pdp_dev" 256 PDP_CDB_GZB64 PDP_CDB_B64 PDP_CDB_ERROR "读取 pdp_cdb 分区内容失败"
        else
            printf 'PDP_CDB_PATH=none\n'
        fi

        if pdp_fw_dev=$(find_pdp_fw); then
            printf 'PDP_FW_PATH=%s\n' "$pdp_fw_dev"
            emit_partition_block "$pdp_fw_dev" 256 PDP_FW_GZB64 PDP_FW_B64 PDP_FW_ERROR "读取 pdp 分区内容失败"
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

    set_override)
        [ "$#" = 4 ] || die "set_override 参数数量无效"
        [ "$1" = "$boot" ] || die "启动 ID 已改变，请刷新状态后重试"
        target_cpuss=$2
        expected_cpuss=$3
        target_global=$4
        case "$target_cpuss" in [1-7]) ;; *) die "CPUSS 档位必须为 1..7" ;; esac
        case "$target_global" in [0-7]) ;; *) die "Global 档位必须为 0..7" ;; esac

        find_adj_pair || die "未找到 debugfs/qcom_voltage_adj 接口"
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

        discover_vadj_platform
        read_reg_7151 "$dbg" "$vadj_spmi_parent"
        printf 'SDAM_CPUSS_IDX=%s\n' "$cpuss_idx"
        printf 'SDAM_GLOBAL_IDX=%s\n' "$global_idx"
        printf 'REG_7151=%s\n' "$reg_7151"
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
        find_adj_pair || die "未找到 debugfs/qcom_voltage_adj 接口"
        verify_overrides "$adj_dir/get_overrides" "$2" "$3"
        /system/bin/svc power reboot --no-wait
        ;;

    *)
        die "未知操作: $action"
        ;;
esac
