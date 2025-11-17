package com.ustadmobile.meshrabiya.service.compute.model

import com.ustadmobile.meshrabiya.service.compute.ResourceRequirements
import com.ustadmobile.meshrabiya.service.compute.CPUIntensity
import com.ustadmobile.meshrabiya.vnet.hardware.ThermalState  // Use canonical ThermalState

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

// Note: ResourceRequirements, CPUIntensity, and ThermalState are now imported from SupportTypes.kt
// to avoid redeclaration errors. Import them as needed:
// import com.ustadmobile.meshrabiya.service.compute.ResourceRequirements
// import com.ustadmobile.meshrabiya.service.compute.CPUIntensity
// import com.ustadmobile.meshrabiya.service.compute.ThermalState
