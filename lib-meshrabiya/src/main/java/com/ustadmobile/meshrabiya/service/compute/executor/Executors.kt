package com.ustadmobile.meshrabiya.service.compute.executor

import com.ustadmobile.meshrabiya.service.compute.scheduler.*
import com.ustadmobile.meshrabiya.service.compute.model.*

/**
 * Interface for executing Python compute tasks.
 */
interface PythonExecutor {
    suspend fun executeTask(task: ComputeTask.PythonTask): TaskExecutionResult
}

/**
 * Interface for executing LiteRT compute tasks.
 */
// interface LiteRTEngine {
//     suspend fun executeTask(task: ComputeTask.LiteRTTask): TaskExecutionResult
// }

/**
 * Request for executing a compute task remotely.
 */
data class TaskExecutionRequest(
    val taskId: String,
    val taskData: ByteArray,
    val timeoutMs: Long
)

/**
 * Response from a remote compute task execution.
 */
sealed class TaskExecutionResponse {
    data class Success(
        val result: Map<String, Any>,
        val executionTimeMs: Long
    ) : TaskExecutionResponse()

    data class Failed(val error: String) : TaskExecutionResponse()
    object Timeout : TaskExecutionResponse()
}

/**
 * Result of a compute task execution.
 */
sealed class TaskExecutionResult {
    abstract val taskId: String

    data class Success(
        override val taskId: String,
        val result: Map<String, Any>,
        val executionTimeMs: Long,
        val nodeId: String
    ) : TaskExecutionResult()

    data class Failed(
        override val taskId: String,
        val error: String,
        val isCritical: Boolean
    ) : TaskExecutionResult()
}