package com.ustadmobile.meshrabiya.service.compute

import kotlinx.coroutines.*
import org.torproject.android.service.compute.model.*
import org.torproject.android.service.compute.mesh.*
import org.torproject.android.service.compute.scheduler.*
import org.torproject.android.service.compute.executor.*
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
import org.torproject.android.service.storage.StorageDropFolderManager
import java.util.concurrent.ConcurrentHashMap

/**
 * Main service class for intelligent distributed compute.
 * Integrates mesh intelligence, task scheduling, and execution.
 * Uses MeshConnectionPool for chunk/file transfer.
 */
class IntelligentDistributedComputeService(
    private val meshNetwork: MeshNetworkInterface,
    private val gossipProtocol: EnhancedGossipProtocol,
    private val quorumManager: QuorumManager,
    private val resourceManager: ResourceManager,
    private val pythonExecutor: PythonExecutor,
    private val liteRTEngine: LiteRTEngine,
    private val betaLogger: BetaTestLogger? = null
) {
    // --- Event Handlers ---
    var onTaskCompleted: ((taskId: String, result: ExecutionPlan) -> Unit)? = null
    var onTaskFailed: ((taskId: String, error: Throwable) -> Unit)? = null
    private var meshEcosystemListener: MeshEcosystemListener? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val activeJobs = ConcurrentHashMap<String, DistributedJob>()
    private val nodeCapabilities = ConcurrentHashMap<String, NodeCapabilitySnapshot>()
    private val networkTopology = NetworkTopologyTracker()
    private val taskScheduler = IntelligentTaskScheduler()
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

    fun processTaskRequest(localRequest: LocalComputeTaskRequest) {
        ClientTaskRequestTracker.add(localRequest) // Add to client-side request list
        scope.launch {
            val responses = meshNetwork.meshGossipService.broadcastComputeTaskRequestSync(
                localRequest.mmcpRequest,
                MeshrabiyaConstants.getTimeoutMs()
            )
            handleComputeNodeResponses(localRequest, responses)
        }
    }

    fun handleComputeNodeResponses(localRequest: LocalComputeTaskRequest, responses: List<ComputeNodeResponse>) {
        // Select nodes, update client-side request status
        ClientTaskRequestTracker.updateWithResponses(localRequest, responses)
        // Optionally trigger remote execution
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
        LibraryEntry.LiteRTServiceEntry(
            serviceId = "builtin_mobilenet_v3_inference",
            modelId = "mobilenet_v3_quantized",
            modelConfig = LiteRTConfig(useGPU = true, useNNAPI = true, numThreads = 2),
            manifest = ServiceManifest(
                serviceType = ServiceType.LITERT,
                version = "1.0.0",
                author = "Orbot Team",
                signature = null,
                resourceRequirements = ResourceRequirements(
                    minRAMMB = 256,
                    preferredRAMMB = 512,
                    cpuIntensity = CPUIntensity.LIGHT,
                    requiresGPU = true
                ),
                builtin = true
            ),
            executionProfile = ExecutionProfile(deterministic = true),
            inputs = listOf(ServiceInput("input_tensors", "List<Base64Tensor>", true)),
            outputs = listOf(ServiceOutput("inference_results", "List<FloatArray>")),
            capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
        ),
        LibraryEntry.HybridServiceEntry(
            serviceId = "builtin_hybrid_image_pipeline",
            pythonPreprocessing = null,
            liteRTInference = LibraryEntry.LiteRTServiceEntry(
                serviceId = "builtin_mobilenet_v3_inference",
                modelId = "mobilenet_v3_quantized",
                modelConfig = LiteRTConfig(useGPU = true, useNNAPI = true, numThreads = 2),
                manifest = ServiceManifest(
                    serviceType = ServiceType.LITERT,
                    version = "1.0.0",
                    author = "Orbot Team",
                    signature = null,
                    resourceRequirements = ResourceRequirements(
                        minRAMMB = 256,
                        preferredRAMMB = 512,
                        cpuIntensity = CPUIntensity.LIGHT,
                        requiresGPU = true
                    ),
                    builtin = true
                ),
                executionProfile = ExecutionProfile(deterministic = true),
                inputs = listOf(ServiceInput("input_tensors", "List<Base64Tensor>", true)),
                outputs = listOf(ServiceOutput("inference_results", "List<FloatArray>")),
                capabilities = setOf(ServiceCapability.ML, ServiceCapability.CV)
            ),
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

    private fun getDropFolderSubfolders(path: String): List<String> {
        return StorageDropFolderManager.getSubfolders(path)
    }

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