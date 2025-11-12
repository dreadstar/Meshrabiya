package com.ustadmobile.meshrabiya.service.compute.model

/**
 * Represents the manifest for a distributed compute service.
 * Describes service type, version, author, signature, runtime requirements, device profile,
 * resource requirements, platform support, files, and whether the service is builtin.
 */
data class ServiceManifest(
    val serviceType: ServiceType,
    val version: String,
    val author: String,
    val signature: String?,
    val runtimeRequired: List<String> = emptyList(),
    val runtimeOptional: List<String> = emptyList(),
    val deviceProfile: String? = null,
    val resourceRequirements: ResourceRequirements,
    val platformSupport: List<String> = emptyList(),
    val files: List<String> = emptyList(),
    val builtin: Boolean = false
)

/**
 * Enum representing the type of service.
 */
enum class ServiceType {
    PYTHON,
    // LITERT,
    HYBRID,
    JAVA,
    NDK,
    WORKFLOW,
    STORAGE
}

/**
 * Execution profile for a service.
 * Indicates if execution is deterministic, requires zero-knowledge proof, and access level.
 */
data class ExecutionProfile(
    val deterministic: Boolean = false,
    val zkpRequired: Boolean = false,
    val accessLevel: String = "user"
)

/**
 * Metadata for a service, including manifest, execution profile, inputs, outputs, and capabilities.
 */
data class ServiceMeta(
    val serviceId: String,
    val manifest: ServiceManifest,
    val executionProfile: ExecutionProfile,
    val inputs: List<ServiceInput> = emptyList(),
    val outputs: List<ServiceOutput> = emptyList(),
    val capabilities: Set<ServiceCapability> = emptySet()
)

/**
 * Represents an input to a service.
 */
data class ServiceInput(
    val name: String,
    val type: String,
    val required: Boolean = true
)

/**
 * Represents an output from a service.
 */
data class ServiceOutput(
    val name: String,
    val type: String
)

/**
 * Enum representing the capabilities of a service.
 */
enum class ServiceCapability {
    ML,
    CV,
    NLP,
    STORAGE,
    AUDIO,
    SIGNAL,
    CRYPTO,
    WORKFLOW,
    JAVA,
    NDK
}

/**
 * Resource requirements for a service.
 * This is referenced by ServiceManifest.
 */
data class ResourceRequirements(
    val minRAMMB: Int,
    val preferredRAMMB: Int,
    val cpuIntensity: CPUIntensity,
    val requiresGPU: Boolean = false,
    val requiresNPU: Boolean = false,
    val requiresStorage: Boolean = false,
    val minStorageGB: Float = 0f,
    val minBatteryLevel: Int = 0,
    val thermalConstraints: Set<ThermalState> = setOf(ThermalState.NORMAL),
    val maxNetworkLatencyMs: Int = 1000
)

/**
 * Enum representing CPU intensity for resource requirements.
 */
enum class CPUIntensity {
    LIGHT,
    MODERATE,
    HEAVY,
    BURST
}

/**
 * Enum representing device thermal state.
 */
enum class ThermalState {
    NORMAL,
    WARNING,
    CRITICAL
}