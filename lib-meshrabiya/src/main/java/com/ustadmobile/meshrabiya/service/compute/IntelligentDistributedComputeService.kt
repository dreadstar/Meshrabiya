package com.ustadmobile.meshrabiya.service.compute

import kotlinx.coroutines.*
import com.ustadmobile.meshrabiya.service.compute.model.*
import com.ustadmobile.meshrabiya.service.compute.mesh.*
import com.ustadmobile.meshrabiya.service.compute.scheduler.*
import com.ustadmobile.meshrabiya.service.compute.executor.*
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.vnet.MeshNetworkInterface
import com.ustadmobile.meshrabiya.service.MeshEcosystemListener
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.service.MeshGossipService.TaskDataAccessUpdateMessage
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import android.content.Context
import androidx.appcompat.app.AlertDialog
import android.widget.ArrayAdapter
// import com.ustadmobile.meshrabiya.service.storage.StorageDropFolderManager
import java.util.concurrent.ConcurrentHashMap
// UNUSED SCHEDULER IMPORT - Commented 2025-11-12
// import com.ustadmobile.meshrabiya.service.compute.scheduler.ExecutionPlan

/**
 * Main service class for intelligent distributed compute.
 * Integrates mesh intelligence, task scheduling, and execution.
 * Uses MeshConnectionPool for chunk/file transfer.
 */
class IntelligentDistributedComputeService(
    private val meshNetwork: MeshNetworkInterface,
    // private val gossipProtocol: EnhancedGossipProtocol,
    // private val quorumManager: QuorumManager,
    private val resourceManager: ResourceManager,
    private val pythonExecutor: PythonExecutor,
    // private val liteRTEngine: LiteRTEngine,
    private val emergentRoleManager: com.ustadmobile.meshrabiya.vnet.EmergentRoleManager,
    private val betaLogger: BetaTestLogger? = null
) {
    // --- Event Handlers ---
    // UNUSED SCHEDULER CALLBACK - Commented 2025-11-12
    // var onTaskCompleted: ((taskId: String, result: ExecutionPlan) -> Unit)? = null
    var onTaskFailed: ((taskId: String, error: Throwable) -> Unit)? = null
    private var meshEcosystemListener: MeshEcosystemListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // UNUSED FIELDS - SCHEDULER INFRASTRUCTURE (Commented 2025-11-12)
    // These fields were part of the IntelligentTaskScheduler infrastructure which is unused.
    // The scheduler (line 46) is declared but NEVER called - codebase search shows 0 method invocations.
    // Phase 3-4 implementation uses direct ML-capable node selection (processTaskRequest → handleComputeNodeResponses → assignTaskToNode)
    // without scheduler decomposition. Keeping as comments for future scheduler reactivation if needed.
    
    // private val activeJobs = ConcurrentHashMap<String, DistributedJob>()  // Only .size used at line 306 (queueDepth calculation)
    // private val nodeCapabilities = ConcurrentHashMap<String, NodeCapabilitySnapshot>()  // UNUSED - Never accessed
    // private val networkTopology = NetworkTopologyTracker()  // UNUSED - Never accessed
    // private val taskScheduler = IntelligentTaskScheduler()  // UNUSED - Never called (scheduler infrastructure unused)
    
    // Replaced activeJobs with simple counter for queue depth calculation (used in handleIncomingComputeTaskRequest line ~306)
    private val activeJobCount = java.util.concurrent.atomic.AtomicInteger(0)
    
    private val connectionPool = MeshConnectionPool(meshNetwork, poolSize = 8)

    fun registerWithEcosystemListener(listener: MeshEcosystemListener) {
        meshEcosystemListener = listener
        listener.registerComputeService(this)
        registerEventHandlers(listener)
    }

    private fun registerEventHandlers(listener: MeshEcosystemListener) {
        // If MeshGossipService supports registering listeners for TaskDataAccessUpdate, do so here.
        // Otherwise, this is a placeholder for future event handler registration.
        // Example:
        // listener.meshGossipService.registerTaskDataAccessUpdateListener { updateMsg ->
        //     handleTaskDataAccessUpdate(updateMsg)
        // }
    }

    fun handleTaskDataAccessUpdate(updateMsg: TaskDataAccessUpdateMessage) {
        betaLogger?.log(LogLevel.INFO, "ComputeService", "Received TaskDataAccessUpdate: $updateMsg")
        // TODO: Implement actual permission update and job trigger logic
    }

    // ============================================================================
    // CLIENT-SIDE TASK REQUEST TRACKING & SELECTION
    // ============================================================================
    
    /**
     * Tracks client-side compute task requests throughout their lifecycle.
     * Manages request state, collected responses, retry count, and selection results.
     */
    data class TrackedRequest(
        val localRequest: LocalComputeTaskRequest,
        val responses: MutableList<ComputeNodeResponse> = mutableListOf(),
        var selectedNodeAddress: Int? = null,
        var retryCount: Int = 0,
        var status: RequestStatus = RequestStatus.PENDING,
        val createdAt: Long = System.currentTimeMillis(),
        var lastUpdated: Long = System.currentTimeMillis()
    )
    
    enum class RequestStatus {
        PENDING,        // Waiting for responses (broadcast sent)
        COLLECTING,     // Within timeout window collecting responses
        SELECTING,      // Evaluating responses after timeout
        ASSIGNED,       // Task assigned to node
        EXECUTING,      // Task running on selected node
        COMPLETED,      // Task finished successfully
        FAILED          // Task failed or timeout
    }
    
    private val activeRequests = ConcurrentHashMap<String, TrackedRequest>()
    
    /**
     * Process client-side task request: broadcast, collect responses, select best node.
     * Implements timeout-driven collection and retry logic.
     */
    fun processTaskRequest(localRequest: LocalComputeTaskRequest) {
        val taskId = localRequest.mmcpRequest.taskId
        activeRequests[taskId] = TrackedRequest(localRequest)
        
        scope.launch {
            betaLogger?.log(LogLevel.INFO, "ComputeService", 
                "Broadcasting compute task request $taskId (timeout=${MeshrabiyaConstants.getTimeoutMs()}ms)")
            
            val responses = meshNetwork.meshGossipService.broadcastComputeTaskRequestSync(
                localRequest.mmcpRequest,
                MeshrabiyaConstants.getTimeoutMs()
            )
            
            handleComputeNodeResponses(localRequest, responses)
        }
    }

    /**
     * Handle compute node responses after timeout expires.
     * Filters, ranks, and selects best node based on capabilities, load, and latency.
     * Implements retry logic for no responses or capability mismatches.
     */
    fun handleComputeNodeResponses(
        localRequest: LocalComputeTaskRequest, 
        responses: List<ComputeNodeResponse>
    ) {
        val taskId = localRequest.mmcpRequest.taskId
        val tracked = activeRequests[taskId] ?: return
        
        tracked.responses.addAll(responses)
        tracked.status = RequestStatus.SELECTING
        tracked.lastUpdated = System.currentTimeMillis()
        
        betaLogger?.log(LogLevel.INFO, "ComputeService",
            "Received ${responses.size} responses for task $taskId")
        
        if (responses.isEmpty()) {
            betaLogger?.log(LogLevel.WARN, "ComputeService",
                "No responses for task $taskId - triggering retry")
            retryTaskRequest(localRequest)
            return
        }
        
        // Filter and rank responses:
        // 1. Available nodes only
        // 2. Has required ML Kit capabilities
        // 3. Sort by: currentLoad (asc) → estimatedLatencyMs (asc) → mlKitFeatures.size (desc)
        val rankedNodes = responses
            .filter { it.available }
            .filter { hasRequiredMLCapabilities(it, localRequest) }
            .sortedWith(
                compareBy<ComputeNodeResponse> { it.currentLoad }
                    .thenBy { it.estimatedLatencyMs }
                    .thenByDescending { it.mlKitFeatures.size }
            )
        
        if (rankedNodes.isEmpty()) {
            betaLogger?.log(LogLevel.WARN, "ComputeService",
                "No capable nodes for task $taskId (${responses.size} responded, 0 capable) - retrying")
            retryTaskRequest(localRequest)
            return
        }
        
        // Select best node (highest ranked)
        val selectedNode = rankedNodes.first()
        betaLogger?.log(LogLevel.INFO, "ComputeService",
            "Selected node ${selectedNode.nodeAddress} for task $taskId " +
            "(load=${selectedNode.currentLoad}, latency=${selectedNode.estimatedLatencyMs}ms, " +
            "mlFeatures=${selectedNode.mlKitFeatures})")
        
        assignTaskToNode(localRequest, selectedNode)
    }
    
    /**
     * Check if response has required ML Kit capabilities for the task.
     * Returns true if no specific requirements or all requirements met.
     */
    private fun hasRequiredMLCapabilities(
        response: ComputeNodeResponse,
        request: LocalComputeTaskRequest
    ): Boolean {
        // TODO: Extract required ML features from mmcpRequest
        // For now, accept any node with ML Kit features if custom support needed
        // This will be enhanced when MmcpComputeTaskRequest schema includes ML requirements
        return true
    }
    
    /**
     * Retry task request with exponential backoff.
     * Checks MeshrabiyaConstants.getMaxRetries() limit before retrying.
     */
    private fun retryTaskRequest(localRequest: LocalComputeTaskRequest) {
        val taskId = localRequest.mmcpRequest.taskId
        val tracked = activeRequests[taskId] ?: return
        val maxRetries = MeshrabiyaConstants.getMaxRetries()
        
        if (tracked.retryCount >= maxRetries) {
            betaLogger?.log(LogLevel.ERROR, "ComputeService",
                "Task $taskId failed: max retries ($maxRetries) exceeded")
            
            tracked.status = RequestStatus.FAILED
            tracked.lastUpdated = System.currentTimeMillis()
            
            onTaskFailed?.invoke(taskId, 
                Exception("No compute nodes responded after $maxRetries retries"))
            
            activeRequests.remove(taskId)
            return
        }
        
        tracked.retryCount++
        tracked.lastUpdated = System.currentTimeMillis()
        
        // Exponential backoff: 1s, 2s, 4s, 8s, capped at 30s
        val backoffMs = minOf(1000L * (1 shl tracked.retryCount), 30000L)
        
        betaLogger?.log(LogLevel.INFO, "ComputeService",
            "Retrying task $taskId (attempt ${tracked.retryCount}/$maxRetries) after ${backoffMs}ms")
        
        scope.launch {
            delay(backoffMs)
            
            betaLogger?.log(LogLevel.INFO, "ComputeService",
                "Re-broadcasting task $taskId (retry ${tracked.retryCount}/$maxRetries)")
            
            val responses = meshNetwork.meshGossipService.broadcastComputeTaskRequestSync(
                localRequest.mmcpRequest,
                MeshrabiyaConstants.getTimeoutMs()
            )
            
            handleComputeNodeResponses(localRequest, responses)
        }
    }
    
    /**
     * Assign task to selected node: send assignment message, update status, invoke callback.
     * TODO: Implement actual task assignment message sending when MeshGossipService supports it.
     */
    private fun assignTaskToNode(
        localRequest: LocalComputeTaskRequest,
        selectedNode: ComputeNodeResponse
    ) {
        val taskId = localRequest.mmcpRequest.taskId
        val tracked = activeRequests[taskId] ?: return
        
        tracked.selectedNodeAddress = selectedNode.nodeAddress
        tracked.status = RequestStatus.ASSIGNED
        tracked.lastUpdated = System.currentTimeMillis()
        
        betaLogger?.log(LogLevel.INFO, "ComputeService",
            "Task $taskId assigned to node ${selectedNode.nodeAddress}")
        
        // TODO: Send actual task assignment message to selected node
        // This will be implemented when task execution protocol is defined
        // For now, just mark as completed
        scope.launch {
            // Placeholder: simulate task completion
            delay(100)
            tracked.status = RequestStatus.COMPLETED
            tracked.lastUpdated = System.currentTimeMillis()
            
            // onTaskCompleted?.invoke(taskId, executionResult)
            betaLogger?.log(LogLevel.INFO, "ComputeService",
                "Task $taskId completed on node ${selectedNode.nodeAddress}")
            
            activeRequests.remove(taskId)
        }
    }

    // ============================================================================
    // COMPUTE NODE SIDE - PHASE 4: INCOMING TASK REQUEST HANDLER
    // ============================================================================
    
    /**
     * Handle incoming compute task request from another node (compute node side).
     * Evaluates local ML capabilities, availability, and load to generate response.
     * Sends ComputeNodeResponse back to requester with ML Kit features and availability.
     * 
     * Phase 4 Implementation:
     * - Retrieves local ML capabilities via EmergentRoleManager.getLocalMLCapabilitiesForResponse()
     * - Checks resource availability and current load
     * - Estimates latency based on current workload
     * - Generates and sends ComputeNodeResponse with mlKitFeatures and mlKitCustomSupport
     */
    fun handleIncomingComputeTaskRequest(
        requestId: String,
        requesterNodeAddress: Int,
        request: MeshEcosystemMessage.ComputeTaskRequestMessage
    ) {
        betaLogger?.log(LogLevel.INFO, "ComputeService",
            "Received compute task request $requestId from node $requesterNodeAddress " +
            "(taskId=${request.taskId}, serviceId=${request.serviceId})")
        
        scope.launch {
            try {
                // Get local ML capabilities from EmergentRoleManager
                val (mlKitFeatures, mlKitCustomSupport) = emergentRoleManager.getLocalMLCapabilitiesForResponse()
                
                // Check availability based on current load and resources
                val currentLoad = resourceManager.getCurrentLoad()
                val available = currentLoad < 0.8 && resourceManager.hasAvailableResources()
                
                // Estimate latency based on active jobs and queue depth
                val queueDepth = activeJobCount.get()
                val estimatedLatencyMs = when {
                    queueDepth == 0 -> 100L
                    queueDepth < 3 -> 500L
                    queueDepth < 6 -> 1000L
                    else -> 2000L
                }
                
                val response = ComputeNodeResponse(
                    nodeAddress = meshNetwork.getLocalNodeAddress(),
                    available = available,
                    currentLoad = currentLoad,
                    estimatedLatencyMs = estimatedLatencyMs,
                    mlKitFeatures = mlKitFeatures,
                    mlKitCustomSupport = mlKitCustomSupport
                )
                
                betaLogger?.log(LogLevel.INFO, "ComputeService",
                    "Sending compute response for request $requestId: " +
                    "available=$available, load=$currentLoad, latency=${estimatedLatencyMs}ms, " +
                    "mlFeatures=$mlKitFeatures, mlCustom=$mlKitCustomSupport")
                
                // Send response back to requester
                meshNetwork.sendComputeNodeResponse(requesterNodeAddress, requestId, response)
                
            } catch (e: Exception) {
                betaLogger?.log(LogLevel.ERROR, "ComputeService",
                    "Failed to handle compute task request $requestId: ${e.message}")
            }
        }
    }

    val builtinLibraryEntries: List<LibraryEntry> = listOf(
        LibraryEntry.PythonServiceEntry(
            serviceId = "builtin_image_preprocessing",
            scriptCode = generateImagePreprocessingScript(),
            libraries = setOf(PythonLibrary.OPENCV, PythonLibrary.NUMPY, PythonLibrary.JSON, PythonLibrary.BASE64),
            manifest = ServiceManifest(
                serviceType = ServiceType.PYTHON,
                version = "1.0.0",
                author = "Orbot Team",
                signature = null,
                resourceRequirements = ResourceRequirements(
                    minRAMMB = 512,
                    preferredRAMMB = 1024,
                    cpuIntensity = CPUIntensity.MODERATE
                ),
                builtin = true
            ),
            executionProfile = ExecutionProfile(deterministic = true),
            inputs = listOf(ServiceInput("images", "List<Base64Image>", true)),
            outputs = listOf(ServiceOutput("processed_tensors", "List<Base64Tensor>")),
            capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
        ),
        // LibraryEntry.LiteRTServiceEntry(
        //     serviceId = "builtin_mobilenet_v3_inference",
        //     modelId = "mobilenet_v3_quantized",
        //     modelConfig = LiteRTConfig(useGPU = true, useNNAPI = true, numThreads = 2),
        //     manifest = ServiceManifest(
        //         serviceType = ServiceType.LITERT,
        //         version = "1.0.0",
        //         author = "Orbot Team",
        //         signature = null,
        //         resourceRequirements = ResourceRequirements(
        //             minRAMMB = 256,
        //             preferredRAMMB = 512,
        //             cpuIntensity = CPUIntensity.LIGHT,
        //             requiresGPU = true
        //         ),
        //         builtin = true
        //     ),
        //     executionProfile = ExecutionProfile(deterministic = true),
        //     inputs = listOf(ServiceInput("input_tensors", "List<Base64Tensor>", true)),
        //     outputs = listOf(ServiceOutput("inference_results", "List<FloatArray>")),
        //     capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
        // ),
        LibraryEntry.HybridServiceEntry(
            serviceId = "builtin_hybrid_image_pipeline",
            pythonPreprocessing = null,
            // liteRTInference = LibraryEntry.LiteRTServiceEntry(
            //     serviceId = "builtin_mobilenet_v3_inference",
            //     modelId = "mobilenet_v3_quantized",
            //     modelConfig = LiteRTConfig(useGPU = true, useNNAPI = true, numThreads = 2),
            //     manifest = ServiceManifest(
            //         serviceType = ServiceType.LITERT,
            //         version = "1.0.0",
            //         author = "Orbot Team",
            //         signature = null,
            //         resourceRequirements = ResourceRequirements(
            //             minRAMMB = 256,
            //             preferredRAMMB = 512,
            //             cpuIntensity = CPUIntensity.LIGHT,
            //             requiresGPU = true
            //         ),
            //         builtin = true
            //     ),
            //     executionProfile = ExecutionProfile(deterministic = true),
            //     inputs = listOf(ServiceInput("input_tensors", "List<Base64Tensor>", true)),
            //     outputs = listOf(ServiceOutput("inference_results", "List<FloatArray>")),
            //     capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
            // ),
            pythonPostprocessing = null,
            manifest = ServiceManifest(
                serviceType = ServiceType.HYBRID,
                version = "1.0.0",
                author = "Orbot Team",
                signature = null,
                resourceRequirements = ResourceRequirements(
                    minRAMMB = 512,
                    preferredRAMMB = 1024,
                    cpuIntensity = CPUIntensity.MODERATE
                ),
                builtin = true
            ),
            executionProfile = ExecutionProfile(deterministic = true),
            inputs = listOf(ServiceInput("images", "List<Base64Image>", true)),
            outputs = listOf(ServiceOutput("inference_results", "List<FloatArray>")),
            capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
        )
    )

    // Helper for chunk/file transfer using connection pool and async I/O
    suspend fun <T> withConnectionForTransfer(action: suspend (MeshConnectionPool.Connection) -> T): T? {
        val connection = try {
            connectionPool.acquireConnection(timeoutMs = 5000)
        } catch (e: Exception) {
            null
        }
        return if (connection != null) {
            try {
                action(connection)
            } finally {
                connectionPool.releaseConnection(connection)
            }
        } else {
            betaLogger?.log(LogLevel.ERROR, "ComputeService", "No available connection for transfer")
            null
        }
    }

    // Example usage for remote compute task execution (non-blocking)
    suspend fun executeRemoteComputeTask(nodeId: String, request: TaskExecutionRequest): TaskExecutionResponse? {
        return try {
            val response = withConnectionForTransfer { connection ->
                connection.meshNetworkInterface.executeRemoteTask(nodeId, request)
            }
            // If response indicates completion, invoke callback
            // onTaskCompleted?.invoke(taskId, result) // Uncomment and adapt if ExecutionPlan/result available
            response
        } catch (e: Exception) {
            // onTaskFailed?.invoke(taskId, e) // Uncomment and adapt if taskId available
            null
        }
    }

    fun showFolderPickerDialog(
        context: Context,
        initialPath: String = "/DropFolder",
        onFolderSelected: (String) -> Unit
    ) {
        val dialogBuilder = AlertDialog.Builder(context)
        dialogBuilder.setTitle("Select Destination Folder")
        val folderList = getDropFolderSubfolders(initialPath)
        val adapter = object : ArrayAdapter<String>(context, android.R.layout.simple_list_item_1, folderList) {}
        dialogBuilder.setAdapter(adapter) { _, which ->
            val selectedFolder = folderList[which]
            onFolderSelected(selectedFolder)
        }
        dialogBuilder.setNegativeButton("Cancel", null)
        dialogBuilder.show()
    }

    // TODO refactor dropfolder logic
    // private fun getDropFolderSubfolders(path: String): List<String> {
    //     return StorageDropFolderManager.getSubfolders(path)
    // }

    fun pickDestinationFolder(
        context: Context,
        onPathSelected: (String) -> Unit
    ) {
        showFolderPickerDialog(context) { selectedPath ->
            onPathSelected(selectedPath)
        }
    }

    private fun generateImagePreprocessingScript(): String = """
import numpy as np
import cv2
import json
import base64

def preprocess_images(input_data):
    images = input_data.get('images', [])
    processed = []
    
    for img_b64 in images:
        img_data = base64.b64decode(img_b64)
        img_array = np.frombuffer(img_data, np.uint8)
        img = cv2.imdecode(img_array, cv2.IMREAD_COLOR)
        img_resized = cv2.resize(img, (224, 224))
        img_normalized = img_resized.astype(np.float32) / 255.0
        tensor_data = img_normalized.tobytes()
        processed.append(base64.b64encode(tensor_data).decode())
    return {
        'processed_tensors': processed,
        'tensor_shape': [224, 224, 3],
        'count': len(processed)
    }

result = preprocess_images(globals().get('input_data', {}))
print(json.dumps(result))
"""

    // The IntelligentTaskScheduler inner class and other logic are now imported from scheduler package.
}