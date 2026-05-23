package com.hank.flow.open.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InferenceBackendDetectorTest {

    @Test
    fun preferenceIdUnknownFallsBackToAuto() {
        assertEquals(InferenceBackendPreference.Auto, InferenceBackendPreference.fromId(null))
        assertEquals(InferenceBackendPreference.Auto, InferenceBackendPreference.fromId("unknown"))
        assertEquals(InferenceBackendPreference.OpenCl, InferenceBackendPreference.fromId("opencl"))
    }

    @Test
    fun nativeDeviceRecordParsesTabSeparatedFormat() {
        val device = InferenceBackendRuntime.parseDevice("GPUOpenCL\tQualcomm Adreno 750\tgpu")

        assertEquals(NativeBackendDevice("GPUOpenCL", "Qualcomm Adreno 750", NativeBackendDeviceType.Gpu), device)
    }

    @Test
    fun cpuPreferenceAlwaysResolvesToCpu() {
        val result = detector(build = InferenceBackendBuildSupport(vulkan = false, openCl = false))
            .resolve(InferenceBackendPreference.Cpu)

        assertTrue(result.supported)
        assertEquals(InferenceBackend.Cpu, result.resolved)
        assertEquals(0, result.nGpuLayers)
        assertNull(result.unavailableReason)
    }

    @Test
    fun vulkanIsUnavailableWhenBuildFlagIsMissing() {
        val result = detector(build = InferenceBackendBuildSupport(vulkan = false, openCl = true))
            .resolve(InferenceBackendPreference.Vulkan)

        assertFalse(result.supported)
        assertEquals(InferenceBackend.Cpu, result.resolved)
        assertEquals(0, result.nGpuLayers)
        assertEquals(InferenceBackendUnavailableReason.BuildMissing, result.unavailableReason)
    }

    @Test
    fun vulkanRequiresAndroidRuntimeAndSnapdragonPolicy() {
        val noRuntime = detector(
            android = AndroidGpuSupport(
                vulkanRuntime = false,
                socManufacturer = "Qualcomm",
                socModel = "SM8650",
            ),
        ).resolve(InferenceBackendPreference.Vulkan)
        assertEquals(InferenceBackendUnavailableReason.RuntimeMissing, noRuntime.unavailableReason)

        val unsupportedSoc = detector(
            android = AndroidGpuSupport(
                vulkanRuntime = true,
                socManufacturer = "MediaTek",
                socModel = "Dimensity",
            ),
            native = NativeBackendSupport(
                supportsGpuOffload = true,
                devices = listOf(NativeBackendDevice("Vulkan0", "Mali", NativeBackendDeviceType.Gpu)),
            ),
        ).resolve(InferenceBackendPreference.Vulkan)
        assertEquals(InferenceBackendUnavailableReason.DeviceUnsupported, unsupportedSoc.unavailableReason)
    }

    @Test
    fun vulkanAvailableOnSnapdragonWithNativeGpuDevice() {
        val result = detector().resolve(InferenceBackendPreference.Vulkan)

        assertTrue(result.supported)
        assertEquals(InferenceBackend.Vulkan, result.resolved)
        assertEquals(-1, result.nGpuLayers)
        assertNull(result.unavailableReason)
    }

    @Test
    fun openClIsOnlyAvailableForAdrenoNativeDevice() {
        val nonAdreno = detector(
            native = NativeBackendSupport(
                supportsGpuOffload = true,
                devices = listOf(NativeBackendDevice("GPUOpenCL", "Mali", NativeBackendDeviceType.Gpu)),
            ),
        ).resolve(InferenceBackendPreference.OpenCl)
        assertEquals(InferenceBackendUnavailableReason.DeviceUnsupported, nonAdreno.unavailableReason)

        val adreno = detector().resolve(InferenceBackendPreference.OpenCl)
        assertTrue(adreno.supported)
        assertEquals(InferenceBackend.OpenCl, adreno.resolved)
        assertEquals(-1, adreno.nGpuLayers)
    }

    @Test
    fun autoPrefersOpenClThenVulkanThenReportsUnsupported() {
        val withBoth = detector().resolve(InferenceBackendPreference.Auto)
        assertEquals(InferenceBackend.OpenCl, withBoth.resolved)

        val vulkanOnly = detector(
            build = InferenceBackendBuildSupport(vulkan = true, openCl = false),
        ).resolve(InferenceBackendPreference.Auto)
        assertTrue(vulkanOnly.supported)
        assertEquals(InferenceBackend.Vulkan, vulkanOnly.resolved)

        val none = detector(
            build = InferenceBackendBuildSupport(vulkan = false, openCl = false),
        ).resolve(InferenceBackendPreference.Auto)
        assertFalse(none.supported)
        assertEquals(InferenceBackend.Cpu, none.resolved)
        assertEquals(InferenceBackendUnavailableReason.BuildMissing, none.unavailableReason)
    }

    private fun detector(
        build: InferenceBackendBuildSupport = InferenceBackendBuildSupport(vulkan = true, openCl = true),
        android: AndroidGpuSupport = AndroidGpuSupport(
            vulkanRuntime = true,
            socManufacturer = "Qualcomm",
            socModel = "SM8650",
        ),
        native: NativeBackendSupport = NativeBackendSupport(
            supportsGpuOffload = true,
            devices = listOf(
                NativeBackendDevice("GPUOpenCL", "Qualcomm Adreno 750", NativeBackendDeviceType.Gpu),
                NativeBackendDevice("Vulkan0", "Qualcomm Adreno 750", NativeBackendDeviceType.Gpu),
            ),
        ),
    ): InferenceBackendDetector = InferenceBackendDetector(build, android, native)
}
