package com.hank.flow.open.llm

enum class InferenceBackendPreference(val id: String) {
    Auto("auto"),
    Cpu("cpu"),
    Vulkan("vulkan"),
    OpenCl("opencl"),
    ;

    companion object {
        fun fromId(id: String?): InferenceBackendPreference =
            entries.firstOrNull { it.id == id } ?: Auto
    }
}

enum class InferenceBackend(val nativeName: String?) {
    Cpu(null),
    Vulkan("vulkan"),
    OpenCl("opencl"),
}

enum class NativeBackendDeviceType {
    Cpu,
    Gpu,
    IntegratedGpu,
    Accelerator,
    Meta,
    Unknown,
}

data class NativeBackendDevice(
    val name: String,
    val description: String,
    val type: NativeBackendDeviceType,
)

data class InferenceBackendBuildSupport(
    val vulkan: Boolean,
    val openCl: Boolean,
)

data class AndroidGpuSupport(
    val vulkanRuntime: Boolean,
    val socManufacturer: String,
    val socModel: String,
)

data class NativeBackendSupport(
    val supportsGpuOffload: Boolean,
    val devices: List<NativeBackendDevice>,
)

enum class InferenceBackendUnavailableReason {
    BuildMissing,
    RuntimeMissing,
    NativeBackendMissing,
    DeviceUnsupported,
}

data class InferenceBackendAvailability(
    val requested: InferenceBackendPreference,
    val resolved: InferenceBackend,
    val supported: Boolean,
    val nGpuLayers: Int,
    val unavailableReason: InferenceBackendUnavailableReason? = null,
) {
    companion object {
        fun cpu(requested: InferenceBackendPreference): InferenceBackendAvailability =
            InferenceBackendAvailability(
                requested = requested,
                resolved = InferenceBackend.Cpu,
                supported = true,
                nGpuLayers = 0,
            )

        fun unavailable(
            requested: InferenceBackendPreference,
            reason: InferenceBackendUnavailableReason,
        ): InferenceBackendAvailability =
            InferenceBackendAvailability(
                requested = requested,
                resolved = InferenceBackend.Cpu,
                supported = false,
                nGpuLayers = 0,
                unavailableReason = reason,
            )
    }
}

class InferenceBackendDetector(
    private val build: InferenceBackendBuildSupport,
    private val android: AndroidGpuSupport,
    private val native: NativeBackendSupport,
) {
    fun resolve(preference: InferenceBackendPreference): InferenceBackendAvailability =
        when (preference) {
            InferenceBackendPreference.Cpu -> InferenceBackendAvailability.cpu(preference)
            InferenceBackendPreference.Vulkan -> resolveVulkan(preference)
            InferenceBackendPreference.OpenCl -> resolveOpenCl(preference)
            InferenceBackendPreference.Auto -> resolveAuto()
        }

    private fun resolveAuto(): InferenceBackendAvailability {
        val openCl = resolveOpenCl(InferenceBackendPreference.Auto)
        if (openCl.supported) return openCl
        val vulkan = resolveVulkan(InferenceBackendPreference.Auto)
        if (vulkan.supported) return vulkan
        return InferenceBackendAvailability.unavailable(
            requested = InferenceBackendPreference.Auto,
            reason = openCl.unavailableReason
                ?: vulkan.unavailableReason
                ?: InferenceBackendUnavailableReason.NativeBackendMissing,
        )
    }

    private fun resolveVulkan(requested: InferenceBackendPreference): InferenceBackendAvailability {
        if (!build.vulkan) {
            return InferenceBackendAvailability.unavailable(
                requested,
                InferenceBackendUnavailableReason.BuildMissing,
            )
        }
        if (!android.vulkanRuntime) {
            return InferenceBackendAvailability.unavailable(
                requested,
                InferenceBackendUnavailableReason.RuntimeMissing,
            )
        }
        if (!native.supportsGpuOffload) {
            return InferenceBackendAvailability.unavailable(
                requested,
                InferenceBackendUnavailableReason.NativeBackendMissing,
            )
        }
        val devices = native.devices.filter { it.isVulkanCandidate() }
        if (devices.isEmpty()) {
            return InferenceBackendAvailability.unavailable(
                requested,
                InferenceBackendUnavailableReason.NativeBackendMissing,
            )
        }
        if (!matchesSnapdragonPolicy(devices)) {
            return InferenceBackendAvailability.unavailable(
                requested,
                InferenceBackendUnavailableReason.DeviceUnsupported,
            )
        }
        return InferenceBackendAvailability(
            requested = requested,
            resolved = InferenceBackend.Vulkan,
            supported = true,
            nGpuLayers = GPU_ALL_LAYERS,
        )
    }

    private fun resolveOpenCl(requested: InferenceBackendPreference): InferenceBackendAvailability {
        if (!build.openCl) {
            return InferenceBackendAvailability.unavailable(
                requested,
                InferenceBackendUnavailableReason.BuildMissing,
            )
        }
        if (!native.supportsGpuOffload) {
            return InferenceBackendAvailability.unavailable(
                requested,
                InferenceBackendUnavailableReason.NativeBackendMissing,
            )
        }
        val devices = native.devices.filter { it.isOpenClCandidate() }
        if (devices.isEmpty()) {
            return InferenceBackendAvailability.unavailable(
                requested,
                InferenceBackendUnavailableReason.NativeBackendMissing,
            )
        }
        if (devices.none { it.isQualcommAdreno() }) {
            return InferenceBackendAvailability.unavailable(
                requested,
                InferenceBackendUnavailableReason.DeviceUnsupported,
            )
        }
        return InferenceBackendAvailability(
            requested = requested,
            resolved = InferenceBackend.OpenCl,
            supported = true,
            nGpuLayers = GPU_ALL_LAYERS,
        )
    }

    private fun matchesSnapdragonPolicy(devices: List<NativeBackendDevice>): Boolean =
        android.socManufacturer.containsIgnoreCase("qualcomm") ||
            android.socModel.containsIgnoreCase("snapdragon") ||
            android.socModel.containsIgnoreCase("sm8") ||
            devices.any { it.isQualcommAdreno() }

    private fun NativeBackendDevice.isVulkanCandidate(): Boolean =
        name.containsIgnoreCase("vulkan") || description.containsIgnoreCase("vulkan") || isGpu()

    private fun NativeBackendDevice.isOpenClCandidate(): Boolean =
        name.containsIgnoreCase("opencl") || description.containsIgnoreCase("opencl")

    private fun NativeBackendDevice.isQualcommAdreno(): Boolean =
        name.containsIgnoreCase("adreno") ||
            name.containsIgnoreCase("qualcomm") ||
            description.containsIgnoreCase("adreno") ||
            description.containsIgnoreCase("qualcomm")

    private fun NativeBackendDevice.isGpu(): Boolean =
        type == NativeBackendDeviceType.Gpu || type == NativeBackendDeviceType.IntegratedGpu

    private fun String.containsIgnoreCase(value: String): Boolean =
        contains(value, ignoreCase = true)

    companion object {
        const val GPU_ALL_LAYERS = -1
    }
}
