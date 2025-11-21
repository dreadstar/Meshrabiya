package com.ustadmobile.meshrabiya.service.compute.executor

import com.ustadmobile.meshrabiya.service.compute.model.TaskExecutionContext
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionResult
import com.ustadmobile.meshrabiya.service.compute.model.TaskType

/**
 * TaskExecutor Interface
 * 
 * Phase 2: Task Execution Layer - Executor abstraction
 * 
 * Defines the contract for all task executor implementations.
 * Each executor handles a specific TaskType (PYTHON, JVM, JAVASCRIPT, ML_NATIVE, WORKFLOW)
 * and is responsible for executing code bundles in sandboxed containers.
 */
interface TaskExecutor {
    
    /**
     * Execute a task in a sandboxed container.
     * 
     * @param context Complete task execution context including task type, code bundle, limits
     * @param inputFiles Map of filename to file contents (inputs retrieved from storage)
     * @param containerId Sandbox container ID for execution isolation
     * @return ExecutionResult with success status, outputs, metrics, and any errors
     */
    suspend fun execute(
        context: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult
    
    /**
     * Validate that the code bundle is properly formatted for this executor.
     * 
     * @param codeBundle The raw code bundle bytes
     * @return true if valid, false otherwise
     */
    fun validateCodeBundle(codeBundle: ByteArray): Boolean
    
    /**
     * Get the task type supported by this executor.
     * 
     * @return TaskType enum value
     */
    fun getSupportedTaskType(): TaskType
}
