package org.torproject.android.service.compute.scheduler

import org.torproject.android.service.compute.model.*

/**
 * Sealed class representing a compute task in the distributed mesh system.
 * Subclasses represent specific task types and their properties.
 */
sealed class ComputeTask {
    abstract val taskId: String
    abstract val estimatedExecutionMs: Long
    abstract val resourceRequirements: ResourceRequirements
    abstract val dependencies: List<String>

    /**
     * Python compute task.
     */
    data class PythonTask(
        override val taskId: String,
        val scriptCode: String,
        val inputData: Map<String, Any>,
        val libraries: Set<PythonLibrary>,
        override val estimatedExecutionMs: Long,
        override val resourceRequirements: ResourceRequirements,
        override val dependencies: List<String> = emptyList(),
        val outputSchema: OutputSchema
    ) : ComputeTask()

    /**
     * LiteRT compute task.
     */
    data class LiteRTTask(
        override val taskId: String,
        val modelId: String,
        val inputTensors: List<ByteArray>,
        val modelConfig: LiteRTConfig,
        override val estimatedExecutionMs: Long,
        override val resourceRequirements: ResourceRequirements,
        override val dependencies: List<String> = emptyList(),
        val inferenceConfig: InferenceConfig
    ) : ComputeTask()

    /**
     * Hybrid compute task (Python preprocessing, LiteRT inference, Python postprocessing).
     */
    data class HybridTask(
        override val taskId: String,
        val pythonPreprocessing: PythonTask?,
        val liteRTInference: LiteRTTask,
        val pythonPostprocessing: PythonTask?,
        override val estimatedExecutionMs: Long,
        override val resourceRequirements: ResourceRequirements,
        override val dependencies: List<String> = emptyList()
    ) : ComputeTask()

    /**
     * Distributed storage task.
     */
    data class DistributedStorageTask(
        override val taskId: String,
        val operation: StorageOperation,
        val fileId: String,
        val data: ByteArray?,
        val replicationFactor: Int,
        override val estimatedExecutionMs: Long,
        override val resourceRequirements: ResourceRequirements,
        override val dependencies: List<String> = emptyList(),
        val destinationPath: String? = null
    ) : ComputeTask()
}