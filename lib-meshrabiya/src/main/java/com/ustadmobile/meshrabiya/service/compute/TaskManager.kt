package com.ustadmobile.meshrabiya.service.compute

import java.util.UUID
import kotlinx.coroutines.*
import android.content.Context
import android.net.Uri
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
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
import com.ustadmobile.meshrabiya.service.compute.model.MeshComputeDataDefinitions.*
import com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutor
import com.ustadmobile.meshrabiya.service.security.StrangersSafeComputeEngine

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
        val taskContext: TaskExecutionContext,
        val requesterNodeId: String,
        val callbackAddress: String,
        // Phase 2.2: Resource monitoring fields
        val resourceMetrics: ResourceMetrics = ResourceMetrics.zero(),
        val lastMetricUpdate: Long = 0L
    )

    private val activeExecutions = mutableMapOf<UUID, ExecutionState>()
    private val containerToTask = mutableMapOf<String, UUID>()
    
    // Phase 2.2: Resource monitoring state
    private var resourceMonitoringJob: Job? = null
    private val peakMetrics = mutableMapOf<String, ResourceMetrics>()

    // Phase 4.2: Task keypair management
    // Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md Section 5
    
    /**
     * Keypair entry for per-task encryption.
     * 
     * @property publicKey PGP public key (PEM format, Base64-encoded)
     * @property privateKey PGP private key (PEM format, Base64-encoded)
     * @property createdAt Timestamp when keypair was generated (milliseconds since epoch)
     * @property expiresAt Timestamp when keypair expires (milliseconds since epoch)
     */
    data class KeypairEntry(
        val publicKey: String,
        val privateKey: String,
        val createdAt: Long,
        val expiresAt: Long
    ) {
        /**
         * Check if this keypair is expired.
         */
        fun isExpired(): Boolean {
            return System.currentTimeMillis() > expiresAt
        }
        
        /**
         * Get remaining lifetime in milliseconds.
         */
        fun getRemainingLifetimeMs(): Long {
            return maxOf(0L, expiresAt - System.currentTimeMillis())
        }
    }
    
    /**
     * In-memory keypair registry: taskId → KeypairEntry
     * Stores ephemeral task keypairs for task-level data isolation.
     */
    private val keypairRegistry = mutableMapOf<String, KeypairEntry>()
    
    /**
     * Background cleanup job for expired keypairs.
     * Runs every 15 minutes to remove expired entries.
     */
    private var keypairCleanupJob: Job? = null

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
                taskContext = context,
                requesterNodeId = requesterNodeId,
                callbackAddress = callbackAddress,
                resourceMetrics = ResourceMetrics.zero(),
                lastMetricUpdate = System.currentTimeMillis()
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

    /**
     * Phase 2.2: Resource Monitoring
     * Ensures the background resource monitoring loop is running
     */
    private suspend fun ensureResourceMonitoringActive() {
        if (resourceMonitoringJob?.isActive == true) return
        
        resourceMonitoringJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    updateResourceMetrics()
                    checkResourceLimitViolations()
                    delay(1000) // Poll every second
                } catch (e: Exception) {
                    // Log error and continue monitoring
                }
            }
        }
    }
    
    /**
     * Phase 2.2: Resource Monitoring
     * Updates resource metrics for all active containers
     */
    private suspend fun updateResourceMetrics() {
        val context = getAppContext() ?: return
        val computeEngine = StrangersSafeComputeEngine.getInstance(context)
        
        for ((taskId, execution) in activeExecutions) {
            try {
                val metrics = computeEngine.getContainerMetrics(execution.containerId)
                
                // Update execution state with latest metrics
                activeExecutions[taskId] = execution.copy(
                    resourceMetrics = metrics,
                    lastMetricUpdate = System.currentTimeMillis()
                )
                
                // Update peak metrics
                peakMetrics[execution.containerId] = ResourceMetrics(
                    ramUsedBytes = maxOf(
                        peakMetrics[execution.containerId]?.ramUsedBytes ?: 0L,
                        metrics.ramUsedBytes
                    ),
                    cpuUsedPercent = maxOf(
                        peakMetrics[execution.containerId]?.cpuUsedPercent ?: 0.0,
                        metrics.cpuUsedPercent
                    ),
                    diskUsedBytes = maxOf(
                        peakMetrics[execution.containerId]?.diskUsedBytes ?: 0L,
                        metrics.diskUsedBytes
                    ),
                    networkSentBytes = metrics.networkSentBytes,
                    networkReceivedBytes = metrics.networkReceivedBytes
                )
                
            } catch (e: Exception) {
                // Log error, continue to next container
            }
        }
    }
    
    /**
     * Phase 2.2: Resource Monitoring
     * Checks all active tasks for resource limit violations and terminates violators
     */
    private suspend fun checkResourceLimitViolations() {
        val tasksToTerminate = mutableListOf<UUID>()
        
        for ((taskId, execution) in activeExecutions) {
            val limits = execution.taskContext.resourceLimits
            val metrics = execution.resourceMetrics
            
            // Check memory limit
            if (metrics.ramUsedBytes > limits.maxMemoryBytes) {
                tasksToTerminate.add(taskId)
                continue
            }
            
            // Check CPU limit (average over monitoring window)
            if (metrics.cpuUsedPercent > limits.maxCpuPercent) {
                tasksToTerminate.add(taskId)
                continue
            }
            
            // Check disk limit
            if (metrics.diskUsedBytes > limits.maxDiskBytes) {
                tasksToTerminate.add(taskId)
                continue
            }
            
            // Check execution time limit
            val executionTime = System.currentTimeMillis() - execution.startTime
            if (executionTime > limits.maxExecutionTimeMs) {
                tasksToTerminate.add(taskId)
                continue
            }
        }
        
        // Terminate violating tasks
        for (taskId in tasksToTerminate) {
            terminateTask(taskId, ExecutionErrorType.OUT_OF_MEMORY) // Or appropriate error type
        }
    }
    
    /**
     * Phase 2.2: Resource Monitoring
     * Forcefully terminates a task due to resource violations
     */
    private suspend fun terminateTask(taskId: UUID, errorType: ExecutionErrorType) {
        val execution = activeExecutions[taskId] ?: return
        val context = getAppContext() ?: return
        val computeEngine = StrangersSafeComputeEngine.getInstance(context)
        
        try {
            // Kill the container
            computeEngine.killContainer(execution.containerId)
            
            // Create error result
            val result = ExecutionResult(
                taskId = taskId.toString(),
                success = false,
                outputManifest = emptyList(),
                resourcesUsed = execution.resourceMetrics,
                executionTimeMs = System.currentTimeMillis() - execution.startTime,
                errorMessage = when (errorType) {
                    ExecutionErrorType.TIMEOUT -> "Task execution exceeded time limit"
                    ExecutionErrorType.OUT_OF_MEMORY -> "Task exceeded memory limit"
                    ExecutionErrorType.DISK_FULL -> "Task exceeded disk limit"
                    else -> "Task terminated due to resource violation"
                },
                errorType = errorType
            )
            
            // Update task status
            val task = taskStatuses[taskId]
            if (task != null) {
                taskStatuses[taskId] = task.copy(
                    state = TaskStatus.State.FAILED,
                    completedAt = System.currentTimeMillis()
                )
            }
            
            // Send failure notification
            sendCompletionNotification(
                taskId,
                execution.requesterNodeId,
                execution.callbackAddress,
                result
            )
            
            // Cleanup
            cleanupExecution(taskId)
            
        } catch (e: Exception) {
            // Log error
        }
    }
    
    /**
     * Phase 2.2: Resource Monitoring
     * Public API: Get current total resource load across all executing tasks
     */
    fun getTotalLoad(): ResourceMetrics {
        var totalRam = 0L
        var maxCpu = 0.0
        var totalDisk = 0L
        var totalNetSent = 0L
        var totalNetRecv = 0L
        
        for (execution in activeExecutions.values) {
            totalRam += execution.resourceMetrics.ramUsedBytes
            maxCpu = maxOf(maxCpu, execution.resourceMetrics.cpuUsedPercent)
            totalDisk += execution.resourceMetrics.diskUsedBytes
            totalNetSent += execution.resourceMetrics.networkSentBytes
            totalNetRecv += execution.resourceMetrics.networkReceivedBytes
        }
        
        return ResourceMetrics(
            ramUsedBytes = totalRam,
            cpuUsedPercent = maxCpu,
            diskUsedBytes = totalDisk,
            networkSentBytes = totalNetSent,
            networkReceivedBytes = totalNetRecv
        )
    }
    
    /**
     * Phase 2.2: Resource Monitoring
     * Public API: Get resource metrics for a specific task
     */
    fun getTaskMetrics(taskId: UUID): ResourceMetrics? {
        return activeExecutions[taskId]?.resourceMetrics
    }
    
    /**
     * Phase 2.2: Resource Monitoring
     * Public API: Get peak resource metrics for a container
     */
    fun getPeakMetrics(containerId: String): ResourceMetrics? {
        return peakMetrics[containerId]
    }

    /**
     * Phase 3: Runtime Management
     * Load appropriate executor for task type with runtime validation
     */
    private suspend fun loadExecutor(
        taskType: com.ustadmobile.meshrabiya.service.compute.model.TaskType
    ): TaskExecutor {
        val context = getAppContext() ?: throw IllegalStateException("Context not available")
        val registry = com.ustadmobile.meshrabiya.service.compute.runtime.RuntimeRegistry.getInstance(context)
        
        // Check if runtime is available
        if (!registry.isRuntimeAvailable(taskType)) {
            throw IllegalStateException("Runtime not available for task type: ${taskType.name}")
        }
        
        // Load appropriate executor
        return when (taskType) {
            com.ustadmobile.meshrabiya.service.compute.model.TaskType.PYTHON -> 
                com.ustadmobile.meshrabiya.service.compute.executor.PythonExecutor(context)
            com.ustadmobile.meshrabiya.service.compute.model.TaskType.JVM,
            com.ustadmobile.meshrabiya.service.compute.model.TaskType.JAVA ->
                com.ustadmobile.meshrabiya.service.compute.executor.JVMExecutor(context)
            com.ustadmobile.meshrabiya.service.compute.model.TaskType.JAVASCRIPT ->
                com.ustadmobile.meshrabiya.service.compute.executor.JSExecutor(context)
            com.ustadmobile.meshrabiya.service.compute.model.TaskType.ML_NATIVE ->
                com.ustadmobile.meshrabiya.service.compute.executor.MLNativeExecutor(context)
            com.ustadmobile.meshrabiya.service.compute.model.TaskType.WORKFLOW -> {
                // WorkflowExecutor needs an executor factory
                val factory: (com.ustadmobile.meshrabiya.service.compute.model.TaskType) -> TaskExecutor? = { type ->
                    try {
                        loadExecutor(type)
                    } catch (e: Exception) {
                        null
                    }
                }
                com.ustadmobile.meshrabiya.service.compute.executor.WorkflowExecutor(context, factory)
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

    // ========== Phase 4.2: Task Keypair Management ==========
    // Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md Section 5
    
    /**
     * Generate a new PGP keypair for a task.
     * 
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md Section 5.2
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART4.md Section 11 (Performance: 287ms on Pixel 5)
     * 
     * @param taskId Unique task identifier
     * @param lifetimeMs Keypair lifetime in milliseconds (default: 24 hours)
     * @return KeypairEntry with public and private keys
     */
    suspend fun generateTaskKeypair(
        taskId: String,
        lifetimeMs: Long = 24 * 60 * 60 * 1000L // 24 hours default
    ): KeypairEntry = withContext(Dispatchers.IO) {
        val createdAt = System.currentTimeMillis()
        val expiresAt = createdAt + lifetimeMs
        
        // Generate PGP keypair using BouncyCastle
        val identity = "task-$taskId"
        val (publicKey, privateKey) = com.ustadmobile.meshrabiya.service.compute.security.PGPKeypairGenerator
            .generateKeypair(identity, passphrase = null)
        
        val keypairEntry = KeypairEntry(
            publicKey = publicKey,
            privateKey = privateKey,
            createdAt = createdAt,
            expiresAt = expiresAt
        )
        
        // Register in keypair registry
        keypairRegistry[taskId] = keypairEntry
        
        // Start cleanup job if not already running
        if (keypairCleanupJob == null || keypairCleanupJob?.isActive != true) {
            startKeypairCleanup()
        }
        
        keypairEntry
    }
    
    /**
     * Retrieve task public key.
     * 
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md Section 5.3
     * 
     * @param taskId Task identifier
     * @return Public key string or null if not found/expired
     */
    fun getTaskPublicKey(taskId: String): String? {
        val entry = keypairRegistry[taskId] ?: return null
        if (entry.isExpired()) {
            keypairRegistry.remove(taskId)
            return null
        }
        return entry.publicKey
    }
    
    /**
     * Retrieve task private key.
     * 
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md Section 5.3
     * 
     * @param taskId Task identifier
     * @return Private key string or null if not found/expired
     */
    fun getTaskPrivateKey(taskId: String): String? {
        val entry = keypairRegistry[taskId] ?: return null
        if (entry.isExpired()) {
            keypairRegistry.remove(taskId)
            return null
        }
        return entry.privateKey
    }
    
    /**
     * Remove a task keypair from the registry.
     * 
     * @param taskId Task identifier
     */
    fun removeTaskKeypair(taskId: String) {
        keypairRegistry.remove(taskId)
    }
    
    /**
     * Start background cleanup task for expired keypairs.
     * Runs every 15 minutes.
     * 
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md Section 5.4
     */
    private fun startKeypairCleanup() {
        keypairCleanupJob?.cancel()
        
        keypairCleanupJob = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) {
                delay(15 * 60 * 1000L) // 15 minutes
                cleanupExpiredKeypairs()
            }
        }
    }
    
    /**
     * Clean up expired keypairs from the registry.
     * 
     * Ref: TASK_KEYPAIR_ENHANCEMENT_PLAN_PART1.md Section 5.4
     */
    fun cleanupExpiredKeypairs() {
        val expiredTaskIds = keypairRegistry
            .filter { (_, entry) -> entry.isExpired() }
            .map { (taskId, _) -> taskId }
        
        expiredTaskIds.forEach { taskId ->
            keypairRegistry.remove(taskId)
            // TODO: Secure memory zeroing of private keys
        }
        
        if (expiredTaskIds.isNotEmpty()) {
            println("TaskManager: Cleaned up ${expiredTaskIds.size} expired task keypairs")
        }
    }
    
    /**
     * Stop the keypair cleanup background job.
     */
    fun stopKeypairCleanup() {
        keypairCleanupJob?.cancel()
        keypairCleanupJob = null
    }
    
    /**
     * Get all active task keypairs (for debugging/monitoring).
     * 
     * @return Map of taskId to remaining lifetime in milliseconds
     */
    fun getActiveKeypairs(): Map<String, Long> {
        return keypairRegistry
            .filter { (_, entry) -> !entry.isExpired() }
            .mapValues { (_, entry) -> entry.getRemainingLifetimeMs() }
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