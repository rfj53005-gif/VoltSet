#!/system/bin/sh
SKIPUNZIP=0
[ "$ARCH" = "arm64" ] || abort '仅支持 arm64 架构设备'
[ -n "$MODPATH" ] && [ -d "$MODPATH" ] || abort 'MODPATH 无效'

set_perm "$MODPATH" 0 0 0755
set_perm "$MODPATH/module.prop" 0 0 0644
set_perm "$MODPATH/action.sh" 0 0 0755
set_perm "$MODPATH/boot-completed.sh" 0 0 0755
set_perm "$MODPATH/uninstall.sh" 0 0 0755

if [ -d "$MODPATH/backend" ]; then
    set_perm "$MODPATH/backend" 0 0 0755
    [ -f "$MODPATH/backend/voltset.sh" ] && set_perm "$MODPATH/backend/voltset.sh" 0 0 0755
    if [ -d "$MODPATH/backend/bin" ]; then
        set_perm "$MODPATH/backend/bin" 0 0 0755
        [ -f "$MODPATH/backend/bin/vsetns" ] && set_perm "$MODPATH/backend/bin/vsetns" 0 0 0755
    fi
    if [ -d "$MODPATH/backend/modules" ]; then
        find "$MODPATH/backend/modules" -type d -exec chmod 0755 {} +
        find "$MODPATH/backend/modules" -type f -exec chmod 0644 {} +
    fi
fi

# Leave $MODPATH/webroot permissions/context for KernelSU Manager.
ui_print 'VoltSet SM8850 KSU 模块 (v0.5.4) 安装完成：'
ui_print '- 安装过程完全只读，未修改 SDAM、pdp_cdb 或任何分区。'
ui_print '- 重启激活后，在 KernelSU 管理器 -> 模块 -> 打开 VoltSet WebUI。'
ui_print '- 功能涵盖：CPU Set 1-7 调压、SCMI 电压对比、QPT 硬件实时功耗、CPRH 熔丝检测。'
ui_print '- 可选「每次开机自动写入 Set（boot-completed.sh）」默认关闭，可在 WebUI 首页按需开启。'
