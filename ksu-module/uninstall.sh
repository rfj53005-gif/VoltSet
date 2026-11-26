#!/system/bin/sh
# 仅清理临时会话与锁文件，保留 /data/adb/voltset/persistent（Set 1 原厂基线与功耗对比记录）
# 且绝不修改 PMK8850 SDAM2 (0x7151) 档位。
rm -rf /data/adb/voltset/session /data/adb/voltset/lock 2>/dev/null || true
