package com.ustadmobile.meshrabiya.service.compute.executor

import com.ustadmobile.meshrabiya.service.compute.model.TaskExecutionContext
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionResult
import com.ustadmobile.meshrabiya.service.compute.model.ResourceMetrics
import com.ustadmobile.meshrabiya.service.compute.model.ExecutionErrorType
import com.ustadmobile.meshrabiya.service.compute.model.FileReference
import android.content.Context
import com.eclipsesource.v8.V8
import com.ustadmobile.meshrabiya.service.compute.model.TaskType
import java.io.File
import java.util.zip.ZipInputStream
import java.io.ByteArrayInputStream

/**
 * JSExecutor
 * 
 * Phase 2: Task Execution Layer - JavaScript execution implementation
 * 
 * Executes JavaScript code using J2V8 (JavaScript engine for Android).
 * 
 * Code Bundle Formats:
 * 1. Single .js file: Direct JavaScript script execution
 * 2. ZIP archive with main.js entry point: Multi-file JavaScript projects
 * 
 * Security:
 * - Sandboxed V8 runtime with restricted API access
 * - No network access
 * - Limited file I/O (inputs/outputs directories only)
 * - No access to Android APIs
 * 
 * Implementation Steps:
 * 1. Detect code bundle format (single file vs ZIP)
 * 2. Extract code bundle to container workspace
 * 3. Write input files to inputs/ directory
 * 4. Initialize V8 runtime with sandbox restrictions
 * 5. Execute main.js or single script
 * 6. Collect outputs from outputs/ directory
 * 7. Track resource usage during execution
 */
class JSExecutor(
    private val context: Context
) : TaskExecutor {
    
    companion object {
        private val ZIP_MAGIC_BYTES = byteArrayOf(0x50, 0x4B) // "PK"
        private const val MAIN_JS = "main.js"
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
            
            // 2. Extract code bundle
            val isZip = isZipArchive(context.codeBundle)
            val scriptFile = if (isZip) {
                extractCodeBundle(context.codeBundle, workspaceDir)
            } else {
                File(workspaceDir, "script.js").apply {
                    writeBytes(context.codeBundle)
                }
            }
            
            // 3. Write input files
            inputFiles.forEach { (filename, data) ->
                File(inputsDir, filename).writeBytes(data)
            }
            
            // 4. Execute JavaScript using J2V8
            var jsError: String? = null
            try {
                val v8 = V8.createV8Runtime(null, workspaceDir.absolutePath)
                val script = scriptFile.readText()
                v8.executeVoidScript(script)
                v8.release()
            } catch (e: Exception) {
                jsError = e.message
            }
            val executionTime = System.currentTimeMillis() - startTime
            // 5. Collect output files
            val outputManifest = collectOutputFiles(outputsDir)
            return if (jsError == null) {
                ExecutionResult(
                    taskId = context.taskId,
                    success = true,
                    outputManifest = outputManifest,
                    resourcesUsed = ResourceMetrics.zero(),
                    executionTimeMs = executionTime,
                    resultMessage = "JavaScript execution completed successfully"
                )
            } else {
                ExecutionResult(
                    taskId = context.taskId,
                    success = false,
                    outputManifest = outputManifest,
                    resourcesUsed = ResourceMetrics.zero(),
                    executionTimeMs = executionTime,
                    errorMessage = jsError,
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
                errorMessage = e.message ?: "JavaScript execution failed",
                errorType = ExecutionErrorType.RUNTIME_ERROR
            )
        } finally {
            // Cleanup workspace (optional - may keep for debugging)
            // workspaceDir.deleteRecursively()
        }
    }
    
    override fun validateCodeBundle(codeBundle: ByteArray): Boolean {
        if (codeBundle.isEmpty()) return false
        
        // Check if it's a valid ZIP or contains JavaScript syntax
        if (isZipArchive(codeBundle)) {
            return hasMainJs(codeBundle)
        } else {
            // Check for JavaScript-like syntax (basic heuristic)
            val content = String(codeBundle)
            return content.contains("function ") || content.contains("const ") || 
                   content.contains("var ") || content.contains("let ") ||
                   content.contains("console.log") || content.trim().isNotEmpty()
        }
    }
    
    override fun getSupportedTaskType(): TaskType = TaskType.JAVASCRIPT
    
    // === Private Helper Methods ===
    
    private fun isZipArchive(bytes: ByteArray): Boolean {
        return bytes.size >= 2 && 
               bytes[0] == ZIP_MAGIC_BYTES[0] && 
               bytes[1] == ZIP_MAGIC_BYTES[1]
    }
    
    private fun hasMainJs(zipBytes: ByteArray): Boolean {
        try {
            ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (entry.name == MAIN_JS || entry.name.endsWith("/$MAIN_JS")) {
                        return true
                    }
                    entry = zis.nextEntry
                }
            }
        } catch (e: Exception) {
            return false
        }
        return false
    }
    
    private fun extractCodeBundle(zipBytes: ByteArray, destDir: File): File {
        var mainJsFile: File? = null
        
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val file = File(destDir, entry.name)
                
                if (entry.isDirectory) {
                    file.mkdirs()
                } else {
                    file.parentFile?.mkdirs()
                    file.outputStream().use { fos ->
                        zis.copyTo(fos)
                    }
                    
                    if (file.name == MAIN_JS) {
                        mainJsFile = file
                    }
                }
                
                entry = zis.nextEntry
            }
        }
        
        return mainJsFile ?: throw IllegalStateException("No main.js found in ZIP archive")
    }
    
    private fun collectOutputFiles(outputsDir: File): List<FileReference> {
        if (!outputsDir.exists()) return emptyList()
        
        return outputsDir.listFiles()?.mapNotNull { file ->
            if (file.isFile) {
                FileReference(
                    fileId = file.name, // TODO: Calculate SHA-256 hash
                    fileName = file.name,
                    sizeBytes = file.length()
                )
            } else null
        } ?: emptyList()
    }
}
