package com.hank.flow.open.llm

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.hank.flow.open.BuildConfig
import com.hank.flow.open.settings.FlowSettings

object InferenceBackendRuntime {

    fun resolve(
        context: Context,
        settings: FlowSettings,
        bridge: LlamaBridge = JniLlamaBridge,
    ): InferenceBackendAvailability {
        if (!settings.llmAccelerationEnabled) {
            return InferenceBackendAvailability.cpu(InferenceBackendPreference.Cpu)
        }
        return detector(context, bridge).resolve(settings.llmInferenceBackend)
    }

    fun detector(
        context: Context,
        bridge: LlamaBridge = JniLlamaBridge,
    ): InferenceBackendDetector = InferenceBackendDetector(
        build = InferenceBackendBuildSupport(
            vulkan = BuildConfig.OPENFLOW_ENABLE_VULKAN,
            openCl = BuildConfig.OPENFLOW_ENABLE_OPENCL,
        ),
        android = AndroidGpuSupport(
            vulkanRuntime = context.packageManager.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_LEVEL),
            socManufacturer = Build.SOC_MANUFACTURER.orEmpty(),
            socModel = Build.SOC_MODEL.orEmpty(),
        ),
        native = NativeBackendSupport(
            supportsGpuOffload = bridge.supportsGpuOffload(),
            devices = bridge.listBackendDevices()
                .lineSequence()
                .mapNotNull(::parseDevice)
                .toList(),
        ),
    )

    fun parseDevice(record: String): NativeBackendDevice? {
        val parts = record.split('\t', limit = 3)
        if (parts.size != 3) return null
        return NativeBackendDevice(
            name = parts[0],
            description = parts[1],
            type = parseType(parts[2]),
        )
    }

    private fun parseType(value: String): NativeBackendDeviceType =
        when (value.lowercase()) {
            "cpu" -> NativeBackendDeviceType.Cpu
            "gpu" -> NativeBackendDeviceType.Gpu
            "igpu", "integrated_gpu", "integratedgpu" -> NativeBackendDeviceType.IntegratedGpu
            "accel", "accelerator" -> NativeBackendDeviceType.Accelerator
            "meta" -> NativeBackendDeviceType.Meta
            else -> NativeBackendDeviceType.Unknown
        }
}
