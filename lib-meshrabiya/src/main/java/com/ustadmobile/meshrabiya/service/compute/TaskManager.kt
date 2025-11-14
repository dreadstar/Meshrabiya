package com.ustadmobile.meshrabiya.service.compute

import java.util.UUID
import kotlinx.coroutines.*
import android.content.Context
import android.net.Uri
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import com.ustadmobile.meshrabiya.storage.MeshNetworkInterface
import com.ustadmobile.meshrabiya.storage.StorageConfiguration
import com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy
import com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.StorageAccessPolicy
import com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.StorageOperation
import com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.RetentionPolicy
import com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.AccessScope
import com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.StorageRequest
import com.ustadmobile.meshrabiya.service.MeshServiceCoordinator
import com.ustadmobile.meshrabiya.OrbotApp
import com.ustadmobile.meshrabiya.service.storage.StorageDropFolderManager
import kotlinx.serialization.json.Json

/**
 * TaskManager - Monitors running tasks, handles TaskDataAccessUpdate events,
 * coordinates access profile updates, provides hooks for output publishing,
 * supports service search, smart caching, progress tracking, and result management.
 * Integrates with DistributedStorageManager, MeshEcosystemListener, and IntelligentDistributedComputeService.
 */
object TaskManager {

    // --- Data Models ---

    sealed class TaskExecutionResponse {
        data class Success(
            val result: Map<String, Any>,
            val executionTimeMs: Long
        ) : TaskExecutionResponse()

        data class Failed(val error: String) : TaskExecutionResponse()
        object Timeout : TaskExecutionResponse()
    }

    data class TaskRequest(
        val id: UUID = UUID.randomUUID(),
        val service: ServiceMeta,
        val parameters: Map<String, Any>,
        val requester: String,
        val requiredRole: String? = null,
        val requirements: Map<String, Any>? = null
    )

    data class TaskStatus(
        val id: UUID,
        val service: ServiceMeta,
        val progress: ProgressMetric,
        val state: State,
        val result: Any? = null,
        val requirements: Map<String, Any>? = null,
        val startedAt: Long,
        val completedAt: Long? = null,
        // Phase 1: Execution tracking fields
        val executionStartedAt: Long? = null,
        val executorNodeAddress: String? = null,
        val containerId: String? = null,
        val resourceUsage: Map<String, Any>? = null,
        val executionContext: Map<String, Any>? = null
    ) {
        enum class State { 
            RUNNING, 
            COMPLETED, 
            FAILED, 
            CANCELLED,
            // Phase 1: Additional execution states
            ACCEPTED,
            PREPARING,
            EXECUTING,
            FINALIZING
        }
    }

    data class ProgressMetric(
        val percentComplete: Int,
        val etaSeconds: Int?,
        val currentStage: String
    )

    data class OutputPublishAudit(
        val taskId: UUID,
        val fileName: String,
        val fileCreateTimestamp: Long,
        val taskStart: Long,
        val taskEnd: Long?,
        val recipients: List<String>,
        val owner: String
    )

    // --- Internal State ---

    private val serviceCache = mutableListOf<ServiceSearchResult>()
    private val runningTasks = mutableMapOf<UUID, TaskStatus>()
    private val completedTasks = mutableListOf<TaskStatus>()
    private val outputAuditTrail = mutableListOf<OutputPublishAudit>()

    // Phase 2: Execution state tracking
    data class ExecutionState(
        val taskId: UUID,
        val containerId: String,
        val executorNodeAddress: String,
        val startTime: Long,
        val resourceMetrics: MutableMap<String, Any> = mutableMapOf(),
        val executionContext: Map<String, Any> = emptyMap()
    )

    private val activeExecutions = mutableMapOf<UUID, ExecutionState>()
    private val containerToTask = mutableMapOf<String, UUID>()

    // --- Task Access Update and Output Publishing Hooks ---

    private val accessUpdateHandlers = mutableMapOf<UUID, (List<String>) -> Unit>()
    private val publishOutputHooks = mutableMapOf<UUID, (UUID, TaskRequest, PublishEvent) -> Unit>()

    // --- Service Search and Smart Caching ---

    fun searchServices(query: String): List<ServiceSearchResult> =
        serviceCache.filter {
            val manifest = it.manifest
            manifest.author.contains(query, ignoreCase = true) ||
            manifest.version.contains(query, ignoreCase = true) ||
            manifest.serviceType.name.contains(query, ignoreCase = true) ||
            manifest.resourceRequirements.toString().contains(query, ignoreCase = true) ||
            it.capabilities.any { cap -> cap.name.contains(query, ignoreCase = true) }
        }.take(5)

    fun updateServiceCache(result: ServiceSearchResult) {
        serviceCache.removeAll { it.serviceId == result.serviceId && it.manifest.version == result.manifest.version && it.nodeId == result.nodeId }
        serviceCache.add(result)
        val grouped = serviceCache.groupBy { Triple(it.serviceId, it.manifest.version, it.nodeId) }
        serviceCache.clear()
        grouped.values.forEach { group ->
            serviceCache.addAll(group.take(5))
        }
    }

    // --- Task Lifecycle Management ---

    fun createTaskWithParams(service: ServiceMeta, params: Map<String, Any>): UUID {
        val destinationFolder = params["destinationFolder"] as? String
        val request = TaskRequest(
            id = UUID.randomUUID(),
            service = service,
            parameters = params,
            requester = "local",
            requirements = if (destinationFolder != null) mapOf("destinationFolder" to destinationFolder) else null
        )
        createTaskRequest(request)
        return request.id
    }

    fun createTaskRequest(request: TaskRequest): TaskStatus {
        val missingInputs = request.service.inputs.map { it.name }.filter { !request.parameters.containsKey(it) }
        if (missingInputs.isNotEmpty()) {
            throw IllegalArgumentException("Missing required inputs: ${missingInputs.joinToString()}")
        }
        val status = TaskStatus(
            id = request.id,
            service = request.service,
            progress = ProgressMetric(0, null, "Pending"),
            state = TaskStatus.State.RUNNING,
            requirements = request.requirements,
            startedAt = System.currentTimeMillis()
        )
        runningTasks[request.id] = status
        return status
    }

    fun updateTaskProgress(taskId: UUID, percent: Int, eta: Int?, stage: String) {
        runningTasks[taskId]?.let {
            runningTasks[taskId] = it.copy(progress = ProgressMetric(percent, eta, stage))
        }
    }

    fun completeTask(
        taskId: UUID, 
        result: Any?,
        owner: String? = null,
        recipients: List<String>? = null
    ) {
        runningTasks[taskId]?.let {
            val mappedResult = if (result is Map<*, *>) {
                val outputMap = mutableMapOf<String, Any?>()
                for (output in it.service.outputs) {
                    outputMap[output.name] = result[output.name]
                }
                outputMap
            } else result
            val completed = it.copy(
                state = TaskStatus.State.COMPLETED,
                result = mappedResult,
                completedAt = System.currentTimeMillis()
            )
            completedTasks.add(completed)
            runningTasks.remove(taskId)

            // Output publishing hook (file streaming to Distributed Storage)
            val destinationFolder = it.requirements?.get("destinationFolder") as? String
            if (destinationFolder != null && mappedResult is Map<*, *>) {
                val fileOutput = mappedResult.values.find { v -> v is java.io.File || v is Uri }
                if (fileOutput != null) {
                    val taskConfig = TaskRequest(
                        id = completed.id,
                        service = completed.service,
                        parameters = completed.service.inputs.associate { inp -> inp.name to (result as? Map<*, *>)?.get(inp.name) },
                        requester = owner ?: completed.service.author,
                        requirements = completed.requirements
                    )
                    val event = PublishEvent(
                        fileOutput = fileOutput,
                        recipients = recipients ?: listOf(destinationFolder),
                        destinationFolder = destinationFolder,
                        additionalMetadata = completed.requirements
                    )
                    publishOutputHooks[taskId]?.invoke(taskId, taskConfig, event)
                }
            }
        }
    }

    // --- Access Update Event Handling ---

    fun registerAccessUpdateHandler(taskId: UUID, handler: (List<String>) -> Unit) {
        accessUpdateHandlers[taskId] = handler
    }

    fun unregisterAccessUpdateHandler(taskId: UUID) {
        accessUpdateHandlers.remove(taskId)
    }

    fun handleTaskDataAccessUpdate(taskId: UUID, newAccessList: List<String>) {
        accessUpdateHandlers[taskId]?.invoke(newAccessList)
    }

    // --- Output Publishing Hook ---

    data class PublishEvent(
        val fileOutput: Any,
        val recipients: List<String>,
        val destinationFolder: String,
        val additionalMetadata: Map<String, Any>? = null
    )

    fun registerPublishOutputHook(
        taskId: UUID,
        taskConfig: TaskRequest,
        hook: (UUID, TaskRequest, PublishEvent) -> Unit
    ) {
        publishOutputHooks[taskId] = hook
    }

    fun unregisterPublishOutputHook(taskId: UUID) {
        publishOutputHooks.remove(taskId)
    }

    /**
     * Standardized publishOutputHook implementation.
     * This should be used by all tasks to publish output files.
     */
    val standardPublishOutputHook: (UUID, TaskRequest, PublishEvent) -> Unit = { taskId, taskConfig, event ->
        publishOutputFile(taskId, event.fileOutput, event.destinationFolder, event.recipients, taskConfig)
    }

    // --- File Transfer Logic ---

    private fun publishOutputFile(
        taskId: UUID,
        fileOutput: Any,
        destinationFolder: String,
        recipients: List<String>,
        taskConfig: TaskRequest
    ) {
        val context = getAppContext() ?: return
        val fileBytes: ByteArray? = when (fileOutput) {
            is java.io.File -> fileOutput.readBytes()
            is Uri -> context.contentResolver.openInputStream(fileOutput)?.use { it.readBytes() }
            else -> null
        }
        val fileCreateTimestamp = System.currentTimeMillis()
        if (fileBytes != null) {
            val dropFolderManager = StorageDropFolderManager.getInstance(context)
            val dropFolderPath = dropFolderManager.getSelectedFolderPath() ?: destinationFolder
            val distributedStorageManager = DistributedStorageManager.getInstance(context)
            val sandboxPolicy = StorageAccessPolicy(
                allowedOperations = setOf(StorageOperation.WRITE, StorageOperation.READ),
                retentionPolicy = RetentionPolicy.PERSISTENT,
                accessScope = AccessScope.TASK_ISOLATED
            )
            val sandboxProxy = SandboxStorageProxy(
                distributedStorageManager,
                sandboxPolicy
            )
            val uniqueFileName = "output_${fileCreateTimestamp}.bin"
            val request = StorageRequest.Store(
                requestId = UUID.randomUUID().toString(),
                taskId = taskId.toString(),
                fileName = uniqueFileName,
                data = java.util.Base64.getEncoder().encodeToString(fileBytes),
                metadata = mapOf("destinationFolder" to dropFolderPath),
                retentionPolicy = RetentionPolicy.PERSISTENT
            )
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val payload = Json.encodeToString(request)
                    sandboxProxy.processStorageRequest(payload, taskId.toString(), "default")
                    // Audit trail entry
                    val status = runningTasks[taskId] ?: completedTasks.find { it.id == taskId }
                    outputAuditTrail.add(
                        OutputPublishAudit(
                            taskId = taskId,
                            fileName = uniqueFileName,
                            fileCreateTimestamp = fileCreateTimestamp,
                            taskStart = status?.startedAt ?: 0L,
                            taskEnd = status?.completedAt,
                            recipients = recipients,
                            owner = taskConfig.requester
                        )
                    )
                    // TODO: Explicitly grant access to recipients (update permissions, send notification, etc.)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    // --- Utility Methods ---

    private fun getAppContext(): Context? {
        return OrbotApp.instance.applicationContext
    }

    private fun getMeshNetworkInterface(): MeshNetworkInterface {
        val ctx = getAppContext() ?: throw IllegalStateException("Application context unavailable")
        val coordinator = MeshServiceCoordinator.getInstance(ctx)
            ?: throw IllegalStateException("MeshServiceCoordinator not available; ensure the mesh adapter is initialized before using TaskManager")
        return coordinator.provideMeshNetworkInterface()
            ?: throw IllegalStateException("Mesh network adapter not provided by MeshServiceCoordinator; storage operations require an explicit mesh adapter")
    }

    private fun getStorageConfiguration(): StorageConfiguration {
        return try {
            StorageConfiguration()
        } catch (e: Exception) {
            StorageConfiguration(defaultReplicationFactor = 1)
        }
    }

    fun getRunningTasks(): List<TaskStatus> = runningTasks.values.toList()
    fun getCompletedTasks(): List<TaskStatus> = completedTasks
    fun getTaskProgress(taskId: UUID): ProgressMetric? = runningTasks[taskId]?.progress
    fun getOutputAuditTrail(): List<OutputPublishAudit> = outputAuditTrail

    // === Phase 2: Task Execution Layer ===

    /**
     * Main task execution entry point - orchestrates the complete execution lifecycle:
     * 1. Verify runtime availability
     * 2. Retrieve input files from distributed storage
     * 3. Create sandbox container
     * 4. Load executor for task type
     * 5. Execute task in sandbox
     * 6. Monitor resource usage
     * 7. Store result files with proper permissions
     * 8. Notify requester of completion
     * 9. Update task status
     * 10. Cleanup execution resources
     */
    suspend fun executeTask(
        taskId: UUID,
        taskType: com.ustadmobile.meshrabiya.service.compute.model.TaskType,
        jobType: com.ustadmobile.meshrabiya.service.compute.model.JobType,
        codeBundle: ByteArray,
        inputManifest: List<com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.FileReference>,
        resourceLimits: com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.ResourceLimits,
        deadlineMs: Long,
        requesterNodeId: String,
        callbackAddress: String,
        accessScope: AccessScope = AccessScope.TASK_ISOLATED
    ): com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.ExecutionResult = coroutineScope {
        
        updateTaskProgress(taskId, 0, null, "Task accepted, preparing for execution")
        
        try {
            // 1. Create execution context
            val context = com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.TaskExecutionContext(
                taskId = taskId.toString(),
                taskType = taskType,
                jobType = jobType,
                codeBundle = codeBundle,
                inputManifest = inputManifest,
                resourceLimits = resourceLimits,
                deadlineMs = deadlineMs,
                requesterNodeId = requesterNodeId,
                callbackAddress = callbackAddress,
                accessScope = accessScope
            )
            
            // 2. Retrieve input files
            updateTaskProgress(taskId, 10, null, "Retrieving input files")
            val inputFiles = retrieveInputFiles(inputManifest)
            
            // 3. Create sandbox container
            updateTaskProgress(taskId, 30, null, "Creating sandbox container")
            val containerId = createSandboxContainer(context)
            
            // 4. Register execution
            val executionState = ExecutionState(
                taskId = taskId,
                containerId = containerId,
                executorNodeAddress = "local", // TODO: Get actual node address
                startTime = System.currentTimeMillis(),
                resourceMetrics = mutableMapOf(),
                executionContext = mapOf(
                    "taskType" to taskType.name,
                    "jobType" to jobType.name,
                    "requesterNodeId" to requesterNodeId
                )
            )
            activeExecutions[taskId] = executionState
            containerToTask[containerId] = taskId
            
            // 5. Start resource monitoring if not already running
            ensureResourceMonitoringActive()
            
            // 6. Load executor
            updateTaskProgress(taskId, 50, null, "Loading executor")
            val executor = loadExecutor(taskType)
            
            // 7. Execute task
            updateTaskProgress(taskId, 60, null, "Executing task")
            val result = executor.execute(context, inputFiles, containerId)
            
            // 8. Store result files
            if (result.success && result.outputManifest.isNotEmpty()) {
                updateTaskProgress(taskId, 80, null, "Storing result files")
                storeResultFiles(
                    taskId = taskId,
                    outputManifest = result.outputManifest,
                    owner = requesterNodeId,
                    recipients = listOf(requesterNodeId), // Task requester can access results
                    accessScope = accessScope
                )
            }
            
            // 9. Send completion notification
            updateTaskProgress(taskId, 90, null, "Sending completion notification")
            sendCompletionNotification(
                taskId = taskId,
                requesterNodeId = requesterNodeId,
                callbackAddress = callbackAddress,
                result = result
            )
            
            // 10. Update status
            if (result.success) {
                completeTask(
                    taskId = taskId,
                    result = result.resultMessage,
                    owner = requesterNodeId,
                    recipients = listOf(requesterNodeId)
                )
            }
            
            result
            
        } catch (e: Exception) {
            val errorResult = com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.ExecutionResult(
                taskId = taskId.toString(),
                success = false,
                outputManifest = emptyList(),
                resourcesUsed = com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.ResourceMetrics.zero(),
                executionTimeMs = System.currentTimeMillis() - (activeExecutions[taskId]?.startTime ?: 0),
                errorMessage = e.message ?: "Unknown error",
                errorType = com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.ExecutionErrorType.UNKNOWN
            )
            
            runningTasks[taskId]?.let {
                runningTasks[taskId] = it.copy(state = TaskStatus.State.FAILED)
            }
            errorResult
            
        } finally {
            // 11. Cleanup
            cleanupExecution(taskId)
        }
    }

    // === Helper Methods ===

    private suspend fun retrieveInputFiles(
        inputManifest: List<com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.FileReference>
    ): Map<String, ByteArray> {
        val context = getAppContext() ?: throw IllegalStateException("Application context unavailable")
        val storageManager = DistributedStorageManager.getInstance(context)
        
        return inputManifest.associate { fileRef ->
            // TODO: Implement retrieveFile() in DistributedStorageManager
            fileRef.fileName to ByteArray(0) // Placeholder
        }
    }

    private suspend fun createSandboxContainer(
        context: com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.TaskExecutionContext
    ): String {
        // TODO: Integrate with StrangersSafeComputeEngine
        return "container_${context.taskId}"
    }

    private suspend fun ensureResourceMonitoringActive() {
        // TODO: Implement resource monitoring loop
    }

    private suspend fun loadExecutor(
        taskType: com.ustadmobile.meshrabiya.service.compute.model.TaskType
    ): TaskExecutor {
        // TODO: Implement executor loading
        return object : TaskExecutor {
            override suspend fun execute(
                context: com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.TaskExecutionContext,
                inputFiles: Map<String, ByteArray>,
                containerId: String
            ): com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.ExecutionResult {
                return com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.ExecutionResult(
                    taskId = context.taskId,
                    success = true,
                    outputManifest = emptyList(),
                    resourcesUsed = com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.ResourceMetrics.zero(),
                    executionTimeMs = 0
                )
            }
        }
    }

    private suspend fun storeResultFiles(
        taskId: UUID,
        outputManifest: List<com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.FileReference>,
        owner: String,
        recipients: List<String>,
        accessScope: AccessScope
    ) {
        val context = getAppContext() ?: return
        val storageManager = DistributedStorageManager.getInstance(context)
        
        // TODO: Store each output file with proper permissions
    }

    private suspend fun sendCompletionNotification(
        taskId: UUID,
        requesterNodeId: String,
        callbackAddress: String,
        result: com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.ExecutionResult
    ) {
        // TODO: Send TaskCompletedMessage to requester
    }

    private suspend fun cleanupExecution(taskId: UUID) {
        activeExecutions[taskId]?.let { execution ->
            containerToTask.remove(execution.containerId)
        }
        activeExecutions.remove(taskId)
    }

    // TaskExecutor interface
    interface TaskExecutor {
        suspend fun execute(
            context: com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.TaskExecutionContext,
            inputFiles: Map<String, ByteArray>,
            containerId: String
        ): com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.ExecutionResult
    }
}