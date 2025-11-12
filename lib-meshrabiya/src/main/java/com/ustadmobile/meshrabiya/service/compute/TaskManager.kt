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
        val completedAt: Long? = null
    ) {
        enum class State { RUNNING, COMPLETED, FAILED, CANCELLED }
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

    fun completeTask(taskId: UUID, result: Any?) {
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
                        requester = completed.service.author,
                        requirements = completed.requirements
                    )
                    val event = PublishEvent(
                        fileOutput = fileOutput,
                        recipients = listOf(destinationFolder),
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
}