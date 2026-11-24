# Kernel Modules Notice & Attribution

The read-only SCMI PERF query kernel module source (`kernel/src/qcom-scmi-ro.c`) and prebuilt `.ko` binaries in `kernel/prebuilt/` originate from **washyda**'s open-source project [**SunTuner**](https://github.com/washyda/SunTuner) and are redistributed under the **GNU General Public License v2.0 only** (`GPL-2.0-only`, see [COPYING](COPYING)).

## Included Prebuilts

| Platform / Kernel ABI | Path |
| --- | --- |
| SM8750 | `prebuilt/sm8750/qcom-scmi-ro.ko` |
| SM8850 Android 16 GKI gen 5 (4 KB pages) | `prebuilt/sm8850/android16-5-4k/qcom-scmi-ro.ko` |
| SM8850 Android 16 GKI gen 6 (4 KB pages) | `prebuilt/sm8850/android16-6-4k/qcom-scmi-ro.ko` |

## Rebuilding from Source

Point `KDIR` at the matching prepared ARM64 kernel build tree and `SCMI_PROTOCOL_DIR` at the kernel's `drivers/firmware/arm_scmi` directory:

```sh
make -C "$KDIR" M="$PWD/kernel/src" ARCH=arm64 LLVM=1 \
  SUN_TUNER_PLATFORM=sm8850 \
  SCMI_PROTOCOL_DIR="$KERNEL_SOURCE/drivers/firmware/arm_scmi" modules
```

Omit `SUN_TUNER_PLATFORM=sm8850` when building for SM8750.
