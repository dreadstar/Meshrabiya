package org.torproject.android.service.compute.model

/**
 * Represents a search result for a service in the mesh ecosystem.
 */
data class ServiceSearchResult(
    val serviceId: String,
    val manifest: ServiceManifest,
    val executionProfile: ExecutionProfile,
    val capabilities: Set<ServiceCapability>,
    val nodeId: String
)

/**
 * Converts a LibraryEntry and nodeId to a ServiceSearchResult.
 */
fun toSearchResult(entry: LibraryEntry, nodeId: String): ServiceSearchResult {
    return ServiceSearchResult(
        serviceId = entry.serviceId,
        manifest = entry.manifest,
        executionProfile = entry.executionProfile,
        capabilities = entry.capabilities,
        nodeId = nodeId
    )
}

/**
 * Sealed class representing a library entry for a distributed compute service.
 * Subclasses represent different service types and their specific properties.
 */
sealed class LibraryEntry {
    abstract val serviceId: String
    abstract val manifest: ServiceManifest
    abstract val executionProfile: ExecutionProfile
    abstract val inputs: List<ServiceInput>
    abstract val outputs: List<ServiceOutput>
    abstract val capabilities: Set<ServiceCapability>

    /**
     * Python service entry.
     */
    data class PythonServiceEntry(
        override val serviceId: String,
        val scriptCode: String,
        val libraries: Set<PythonLibrary>,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    /**
     * LiteRT service entry.
     */
    data class LiteRTServiceEntry(
        override val serviceId: String,
        val modelId: String,
        val modelConfig: LiteRTConfig,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    /**
     * Hybrid service entry.
     */
    data class HybridServiceEntry(
        override val serviceId: String,
        val pythonPreprocessing: PythonServiceEntry?,
        val liteRTInference: LiteRTServiceEntry,
        val pythonPostprocessing: PythonServiceEntry?,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    /**
     * Java service entry.
     */
    data class JavaServiceEntry(
        override val serviceId: String,
        val className: String,
        val jarPath: String,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    /**
     * NDK service entry.
     */
    data class NDKServiceEntry(
        override val serviceId: String,
        val soPath: String,
        val entryFunction: String,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()

    /**
     * Workflow service entry.
     */
    data class WorkflowServiceEntry(
        override val serviceId: String,
        val steps: List<String>,
        override val manifest: ServiceManifest,
        override val executionProfile: ExecutionProfile,
        override val inputs: List<ServiceInput>,
        override val outputs: List<ServiceOutput>,
        override val capabilities: Set<ServiceCapability>
    ) : LibraryEntry()
}

/**
 * Enum representing supported Python libraries.
 */
enum class PythonLibrary {
    OPENCV,
    NUMPY,
    JSON,
    BASE64,
    SCIPY,
    PANDAS,
    TORCH,
    TENSORFLOW
}

/**
 * Configuration for LiteRT model execution.
 */
data class LiteRTConfig(
    val useGPU: Boolean = false,
    val useNNAPI: Boolean = false,
    val numThreads: Int = 1
)

/**
 * Configuration for inference execution.
 */
data class InferenceConfig(
    val batchSize: Int = 1,
    val precision: Precision = Precision.FLOAT32
)

/**
 * Enum representing inference precision.
 */
enum class Precision {
    FLOAT32,
    FLOAT16,
    QUANTIZED
}