package com.ustadmobile.meshrabiya.service.compute.executor

import com.ustadmobile.meshrabiya.service.compute.model.TaskExecutionContext
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionResult
import com.ustadmobile.meshrabiya.service.compute.model.ResourceMetrics
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionErrorType
import com.ustadmobile.meshrabiya.service.compute.model.FileReference
import java.io.File
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * MLNativeExecutor
 * 
 * Phase 2: Task Execution Layer - ML model inference implementation
 * 
 * Executes machine learning model inference using TensorFlow Lite.
 * 
 * Code Bundle Format:
 * - Single .tflite model file
 * 
 * Input Format:
 * - Binary tensor data (raw bytes)
 * - Input manifest specifies tensor shapes and types
 * 
 * Output Format:
 * - Binary tensor data (inference results)
 * - Output manifest with result tensors
 * 
 * Implementation Steps:
 * 1. Validate .tflite model file format
 * 2. Load model into TensorFlow Lite interpreter
 * 3. Prepare input tensors from input files
 * 4. Run inference
 * 5. Extract output tensors
 * 6. Write output tensors to outputs/ directory
 * 7. Track resource usage during inference
 */
class MLNativeExecutor(
    private val context: Context
) : TaskExecutor {
    
    companion object {
        private val TFLITE_MAGIC_BYTES = byteArrayOf(0x54, 0x46, 0x4C, 0x33) // "TFL3"
        private const val MODEL_FILE = "model.tflite"
    }
    
    override suspend fun execute(
        context: TaskExecutionContext,
        inputFiles: Map<String, ByteArray>,
        containerId: String
    ): ExecutionResult {
        val startTime = System.currentTimeMillis()
        val workspaceDir = File(this.context.filesDir, "containers/$containerId")
        
        try {
            // 1. Setup workspace
            workspaceDir.mkdirs()
            val inputsDir = File(workspaceDir, "inputs")
            val outputsDir = File(workspaceDir, "outputs")
            inputsDir.mkdirs()
            outputsDir.mkdirs()
            
            // 2. Save model file
            val modelFile = File(workspaceDir, MODEL_FILE)
            modelFile.writeBytes(context.codeBundle)
            
            // 3. Write input files (tensor data)
            inputFiles.forEach { (filename, data) ->
                File(inputsDir, filename).writeBytes(data)
            }
            
            // 4. Run inference using TensorFlow Lite
            var mlError: String? = null
            try {
                val interpreter = Interpreter(modelFile)
                // For demonstration, assume single input/output tensor, float32
                val inputTensor = inputFiles.values.firstOrNull()?.let { bytesToFloatArray(it) }
                val outputTensor = FloatArray(inputTensor?.size ?: 1)
                if (inputTensor != null) {
                    interpreter.run(inputTensor, outputTensor)
                    // Write output tensor to outputs dir
                    val outFile = File(outputsDir, "output_tensor.bin")
                    outFile.writeBytes(floatArrayToBytes(outputTensor))
                }
                interpreter.close()
            } catch (e: Exception) {
                mlError = e.message
            }
            val executionTime = System.currentTimeMillis() - startTime
            // 5. Collect output files (tensor results)
            val outputManifest = collectOutputFiles(outputsDir)
            return if (mlError == null) {
                ExecutionResult(
                    taskId = context.taskId,
                    success = true,
                    outputManifest = outputManifest,
                    resourcesUsed = ResourceMetrics.zero(), // TODO: Actual metrics
                    executionTimeMs = executionTime,
                    resultMessage = "ML inference completed successfully"
                )
            } else {
                ExecutionResult(
                    taskId = context.taskId,
                    success = false,
                    outputManifest = outputManifest,
                    resourcesUsed = ResourceMetrics.zero(),
                    executionTimeMs = executionTime,
                    errorMessage = mlError,
                    errorType = ExecutionErrorType.RUNTIME_ERROR
                )
            }
            
        } catch (e: Exception) {
            val executionTime = System.currentTimeMillis() - startTime
            return ExecutionResult(
                taskId = context.taskId,
                success = false,
                outputManifest = emptyList(),
                resourcesUsed = ResourceMetrics.zero(),
                executionTimeMs = executionTime,
                errorMessage = e.message ?: "ML inference failed",
                errorType = ExecutionErrorType.RUNTIME_ERROR
            )
        } finally {
            // Cleanup workspace (optional - may keep for debugging)
            // workspaceDir.deleteRecursively()
        }
    }
    
    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        if (codeBundle.size < 4) return false
        
        // Check TFLite magic bytes
        return codeBundle[0] == TFLITE_MAGIC_BYTES[0] &&
               codeBundle[1] == TFLITE_MAGIC_BYTES[1] &&
               codeBundle[2] == TFLITE_MAGIC_BYTES[2] &&
               codeBundle[3] == TFLITE_MAGIC_BYTES[3]
    }
    
    override fun getSupportedTaskType(): TaskType = TaskType.ML_NATIVE
    
    // === Private Helper Methods ===
    
    private fun collectOutputFiles(outputsDir: File): List<FileReference> {
        if (!outputsDir.exists()) return emptyList()
        
        return outputsDir.listFiles()?.mapNotNull { file ->
            if (file.isFile) {
                FileReference(
                    fileId = calculateSha256Hash(file),
                    fileName = file.name,
                    sizeBytes = file.length(),
                    mimeType = "application/octet-stream" // Binary tensor data
                )
            } else null
        } ?: emptyList()
    }

    private fun calculateSha256Hash(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val inputStream = file.inputStream()
        val buffer = ByteArray(8192)
        var read: Int
        while (inputStream.read(buffer).also { read = it } > 0) {
            digest.update(buffer, 0, read)
        }
        inputStream.close()
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    }
    
    /**
     * Helper to convert byte array to FloatArray for tensor input
     */
    private fun bytesToFloatArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.nativeOrder())
        val floatArray = FloatArray(bytes.size / 4)
        buffer.asFloatBuffer().get(floatArray)
        return floatArray
    }
    
    /**
     * Helper to convert FloatArray to byte array for tensor output
     */
    private fun floatArrayToBytes(floats: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.nativeOrder())
        buffer.asFloatBuffer().put(floats)
        return buffer.array()
    }
}
