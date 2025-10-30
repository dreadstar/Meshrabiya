package org.torproject.android.service.compute.model

/**
 * Enum representing the type of distributed job.
 */
enum class JobType {
    IMAGE_PROCESSING,
    DATA_ANALYSIS,
    ML_PIPELINE,
    SENSOR_FUSION,
    COLLABORATIVE_FILTERING,
    DISTRIBUTED_STORAGE
}

/**
 * Enum representing the priority of a distributed job.
 */
enum class JobPriority {
    BACKGROUND,
    NORMAL,
    HIGH,
    CRITICAL
}

/**
 * Enum representing aggregation strategies for distributed compute results.
 */
enum class AggregationStrategy {
    SIMPLE_CONCAT,
    MAJORITY_VOTE,
    WEIGHTED_AVERAGE,
    ENSEMBLE_COMBINE
}

/**
 * Enum representing specialized capabilities of a node.
 */
enum class SpecializedCapability {
    IMAGE_PROCESSING,
    AUDIO_PROCESSING,
    NLP,
    COMPUTER_VISION,
    SIGNAL_PROCESSING,
    CRYPTOGRAPHY,
    SCIENTIFIC_COMPUTING,
    DISTRIBUTED_STORAGE
}

/**
 * Enum representing supported Python libraries for distributed compute.
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