package com.holos.reader.tts

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceCapabilityScannerTest {

    @Test
    fun flagshipRecommendsHolosFp32() {
        val (tier, model) = DeviceCapabilityScanner.classifyHardware(8, 3200, 12.0, true, true)
        assertEquals(HardwareTier.HIGH, tier)
        assertEquals(TtsModelId.HOLOS_FP32, model)
    }

    @Test
    fun midRangeRecommendsSupertonic3() {
        val (tier, model) = DeviceCapabilityScanner.classifyHardware(8, 2100, 6.0, true, true)
        assertEquals(HardwareTier.MEDIUM, tier)
        assertEquals(TtsModelId.SUPERTONIC_3, model)
    }

    @Test
    fun budgetRecommendsHolosInt8() {
        val (tier, model) = DeviceCapabilityScanner.classifyHardware(4, 1800, 3.0, true, false)
        assertEquals(HardwareTier.ENTRY, tier)
        assertEquals(TtsModelId.HOLOS_INT8, model)
    }

    @Test
    fun thirtyTwoBitAlwaysRecommendsHolosInt8() {
        val (tier, model) = DeviceCapabilityScanner.classifyHardware(8, 2800, 8.0, false, true)
        assertEquals(HardwareTier.ENTRY, tier)
        assertEquals(TtsModelId.HOLOS_INT8, model)
    }

    @Test
    fun restrictedSysfsCpuFreqFallsBackUsingRamAndVulkan() {
        val (highTier, highModel) = DeviceCapabilityScanner.classifyHardware(8, 0, 8.0, true, true)
        assertEquals(HardwareTier.HIGH, highTier)
        assertEquals(TtsModelId.HOLOS_FP32, highModel)

        val (midTier, midModel) = DeviceCapabilityScanner.classifyHardware(6, 0, 4.0, true, false)
        assertEquals(HardwareTier.MEDIUM, midTier)
        assertEquals(TtsModelId.SUPERTONIC_3, midModel)
    }
}
