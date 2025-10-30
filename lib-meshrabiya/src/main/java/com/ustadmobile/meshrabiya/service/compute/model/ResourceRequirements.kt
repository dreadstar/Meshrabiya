package org.torproject.android.service.compute.model

/**
 * Resource requirements for a distributed compute service or task.
 * Specifies RAM, CPU, storage, battery, thermal, and network constraints.
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

/**
 * Output schema for a compute task.
 * Describes format, expected size, and field schema.
 */
data class OutputSchema(
    val format: OutputFormat,
    val expectedSizeBytes: Long,
    val schema: Map<String, String>
)

/**
 * Enum representing output format for a compute task.
 */
enum class OutputFormat {
    JSON,
    BINARY,
    IMAGE,
    TENSOR,
    CSV
}

/**
 * Enum representing storage operations for distributed storage tasks.
 */
enum class StorageOperation {
    STORE,
    RETRIEVE,
    DELETE,
    REPLICATE,
    VERIFY
}