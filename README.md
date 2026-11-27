# VoltSet · SM8850 OEM Set + Adreno GPU 降压与稳定性工具 (`v0.6.0`)

面向 **Qualcomm Snapdragon 8 Elite Gen 5 (`SM8850` / 6.12 Android 16)** 的纯本地 Root 降压、硬件限位分析、功耗监测与稳定性验证工具，同时提供 **Android 客户端 (Jetpack Compose)** 与 **KernelSU WebUI 模块 (`voltset-sm8850-ksu`)**。

> [!CAUTION]
> **仅限个人调试与研究。** 降压、超频或修改 `vendor_boot` GPU 频率电压表存在黑屏、死机、重启或无法开机的风险；**GPU 修改强烈建议先导出原厂备份，并确认设备已解除或处理好 AVB (`vbmeta`) 校验。**

---

## 核心功能 (`v0.6.0`)

### 1. CPU OEM Set 1–7 切换与双核簇实时频压解析
- 通过 `XBL/PDP -> PMK8850 SDAM2 (0x7151) -> pdp_cdb OEM Set 1–7` 实现免刷 `pdp` 分区的官方降压档位切换（切换需重启生效）。
- 自动提取并解码：
  - **`pdp_cdb`**：7 组 OEM Set 规则（`ALL` / `FREQ` / `PSTATE`、`cx_mv` / `mx_mv`）及 `OVERRIDE` 锁定检测与 SHA-256 指纹。
  - **`pdp` (`CFDB v3`)**：自动按本机 `Set 1` 基线匹配双核簇 PDP0 / PDP1 的硬件 `floor` / `ceil` 限位表，预估各 Set 在本机芯片体质下的实际有效降压范围。
  - **SCMI 计划电压读取**：优先直读内核 `debugfs/energy_model`（零 `.ko`、全内核通用），不支持时自动切换匹配 `kmi` / 4K/16K 页大小的内嵌内核模块。
  - **`cdt` Key 7 检测**：识别工业测试档 (`SLT+ACD`)，防止与 OEM Set 叠加造成过度欠压。
  - **CPRH 老化熔丝校准 (`QFPROM`)**：结合 `socinfo` 与老化熔丝位判断芯片长期老化裕量与飘移趋势。

### 2. CPU 稳定性压测与异常重启诊断 (`CpuStress`)
- 应用内置 **CPU 确定性压测引擎**：
  - **单核峰值 (`SINGLE_CORE`)**：锁定单线程高压验证大核/峰值频点。
  - **全核持续 (`ALL_CORES`)**：多线程并发计算与浮点/位运算校验和比对，捕捉静默计算错误。
  - **突发变频 (`BURST_SWITCH`)**：计算与短暂空闲交替触发高频↔低频电压瞬变。
- 支持 **温度保护**（`PowerManager.THERMAL_STATUS_SEVERE` 自动熔断）与 **QPT 功耗联动统计**。
- 自动采集并警告上次启动 **`boot_reason`**（如 `kernel_panic` / `watchdog`）与 **`/sys/fs/pstore` 崩溃日志**，帮助快速定位极限降压导致的异常重启。

### 3. QPT 原生流式功耗监测与温度追踪 (`vsetqpt`)
- 内嵌 `arm64-v8a` 原生 C 采样器 **`vsetqpt`**（基于 `CLOCK_BOOTTIME` 与内核 sysfs）：
  - 毫秒级流式采集 `cpu-m` / `cpu-l` / `gpu` / `nsp` 电源轨功耗（能量寄存器优先，`power_uw` 自动兜底）。
  - 同步采集 **CPU 双簇实时频率** 与 **SoC/CPU 热敏温区温度 (`TEMP_CPU_MC`)**。
  - Android 客户端与 KernelSU WebUI 均支持 **实时功耗曲线 (Sparkline)** 与 **双端互通的 Set 1–7 历史功耗记录**（12 列 CSV 持久化，向后兼容旧 9 列格式）。

### 4. Adreno GPU `vendor_boot` 编辑器与 GLES3 压测 (`GpuEditor`)
- 解析并修改 `vendor_boot` 中的设备树 `qcom,gpu-pwrlevels-*` 频率、Corner Voltage Level 与 ACD 校准档位：
  - **按机型/DTB 保存与恢复本机原厂基线 (`GpuBaselineStore`)**，与参考数据库共同对照。
  - **单档与批量降压 (`shiftBinVoltage`)**：支持全频段或仅高频段整体升降 Corner 阶梯，并自动维持电压单调递减与 Corner 边界钳位。
  - **安全导出与一键备份原厂分区**：导出前检测 **AVB (`AVB0`) 签名脚注** 与目标分区 / 存储剩余空间，并展示导出镜像的 **SHA-256 校验值**。
  - **内置 GLES3 Vulkan/Shader 重载压测 (`GpuStress`)** 与热状态监测。

### 5. KernelSU WebUI 模块与双端互通
- 提供独立可用的 KernelSU / APatch WebUI 模块（`ksu-module`）：
  - **开机自动恢复与双保险熔断 (`boot-completed.sh`)**：校验 `pdp_cdb` SHA-256 指纹与异常 `boot_reason` 计数；若检测到异常重启（如 `panic` / `watchdog` / `tsens`）或连续超限失败，自动熔断退回安全状态（`Set 1` / 关闭自动恢复）。
  - Android 与 WebUI 共用 `/data/adb/voltset/persistent/` 下的 `auto-restore.conf` 与 `power-records.csv`。
  - 支持在应用内一键导出脱敏 **诊断信息 ZIP 包 (`DiagnosticExporter`)**。

---

## 目录结构

- [app/](app/) — Android 客户端源码（Kotlin + Jetpack Compose + Unit Tests）
- [app/src/main/assets/backend/voltset.sh](app/src/main/assets/backend/voltset.sh) — 核心 Root POSIX `/system/bin/sh` 后端脚本
- [native/qpt/](native/qpt/) — 原生 `vsetqpt` 功耗/频率/温度流式采样器
- [native/vsetns/](native/vsetns/) — 原生 Root mount-namespace debugfs 工具
- [ksu-module/](ksu-module/) — KernelSU WebUI 模块（`webroot/` + `boot-completed.sh` + Node 测试）
- [shared-fixtures/](shared-fixtures/) — Android Kotlin 与 WebUI JS 跨语言黄金契约测试样例
- [scripts/build-ksu-module.sh](scripts/build-ksu-module.sh) — KernelSU ZIP 打包脚本（统一读取 [version.properties](version.properties)）

---

## 构建、测试与打包

版本统一由根目录 [version.properties](version.properties) 管理。

### 1. 编译原生 `arm64-v8a` 二进制
需要设置 `ANDROID_NDK_HOME`（或 `ANDROID_HOME` 下的 NDK）：
```bash
ANDROID_NDK_HOME=/path/to/Android/Sdk/ndk/<version> make -C native/qpt -B
ANDROID_NDK_HOME=/path/to/Android/Sdk/ndk/<version> make -C native/vsetns -B
```

### 2. 运行全套验证测试
```bash
# 1) Shell 后端与 KernelSU 脚本语法检查
sh -n app/src/main/assets/backend/voltset.sh
sh -n ksu-module/boot-completed.sh ksu-module/action.sh ksu-module/customize.sh ksu-module/uninstall.sh
bash -n scripts/build-ksu-module.sh

# 2) Node.js 跨端核心一致性 + 后端行为 + WebUI DOM 测试
node --test ksu-module/test/core.test.mjs ksu-module/test/backend.test.mjs

# 3) Android JVM 单元测试、Release APK 构建与 Release Lint
./gradlew :app:testDebugUnitTest :app:assembleRelease :app:lintRelease --console=plain
```

### 3. 打包 KernelSU WebUI 模块
```bash
./scripts/build-ksu-module.sh
# 输出产物：build/ksu/VoltSet-KSU-v0.6.0.zip（文件名随 version.properties 自动更新）
```
