package io.voltset.sm8850

import io.voltset.sm8850.core.GpuAcdCalibration

internal data class GpuFactoryTables(
    val voltages: Map<Long?, Map<Long, Long>>,
    val acd: Map<Long, Map<Long, GpuAcdCalibration>>,
)

internal object GpuFactoryReference {
    // Xiaomi full OTA, verified against the payload manifest's vendor_boot SHA-256.
    // https://cdnorg.d.miui.com/OS4.0.6.0.XPBCNXM/popsicle-ota_full-OS4.0.6.0.XPBCNXM-user-17.0-13c9e292db.zip
    // DTB 2. Speed bins in this image share the same voltage requests at common frequencies.
    private val popsicle406Voltages = mapOf(
        1_300_000_000L to 452L,
        1_200_000_000L to 448L,
        1_100_000_000L to 432L,
        1_050_000_000L to 416L,
        1_000_000_000L to 384L,
        902_000_000L to 256L,
        826_000_000L to 224L,
        726_000_000L to 192L,
        646_000_000L to 144L,
        578_000_000L to 128L,
        500_000_000L to 80L,
        461_000_000L to 76L,
        422_000_000L to 64L,
        382_000_000L to 60L,
        342_000_000L to 56L,
        282_000_000L to 54L,
        222_000_000L to 52L,
        191_000_000L to 51L,
        160_000_000L to 50L,
    )

    private val popsicle406Acd = mapOf(
        252L to mapOf(
            282_000_000L to 0xca2e5ffdL,
            342_000_000L to 0xe22a5ffdL,
            382_000_000L to 0xa22c5ffdL,
            422_000_000L to 0xa22c5ffdL,
            461_000_000L to 0xe82e5ffdL,
            500_000_000L to 0xe82c5ffdL,
            578_000_000L to 0xc02c5ffdL,
            646_000_000L to 0xc02c5ffdL,
            726_000_000L to 0x882f5ffdL,
            826_000_000L to 0xa82c5ffdL,
            902_000_000L to 0xa82b5ffdL,
            1_000_000_000L to 0x882a5ffdL,
            1_050_000_000L to 0x88295ffdL,
            1_100_000_000L to 0x88295ffdL,
            1_200_000_000L to 0xa02e5ffdL,
        ).mapValues { (hz, code) -> GpuAcdCalibration(code, popsicle406Voltages.getValue(hz)) },
    )

    private val popsicle406 = GpuFactoryTables(
        voltages = mapOf(
            null to popsicle406Voltages,
            221L to (popsicle406Voltages - setOf(1_100_000_000L, 1_200_000_000L, 1_300_000_000L)),
            252L to (popsicle406Voltages - 1_300_000_000L),
            273L to popsicle406Voltages,
        ),
        acd = popsicle406Acd,
    )

    fun forSource(source: GpuImageSource): GpuFactoryTables? =
        if (source.device == "popsicle" && source.build == "OS4.0.6.0.XPBCNXM" && source.dtbIndex == 2) {
            popsicle406
        } else null
}
