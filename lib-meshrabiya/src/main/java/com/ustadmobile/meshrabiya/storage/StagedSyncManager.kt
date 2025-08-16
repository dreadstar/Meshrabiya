package com.ustadmobile.meshrabiya.storage

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayDeque
import android.content.Context
import java.io.File
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel

/**
 * Your excellent StagedSyncManager implementation adapted for mesh integration
 * Maintains local-first approach with intelligent mesh synchronization
 */
class StagedSyncManager(
    private val context: Context,
    private val distributedStorage: DistributedStorageManager,
    private val meshNetwork: MeshNetworkInterface
) {
    companion object {
        private const val MAX_CONCURRENT_SYNCS = 3
        private const val MAX_RETRY_ATTEMPTS = 3
        private const val SYNC_BATCH_SIZE = 10
        private const val BATTERY_THRESHOLD = 20
    }

    private val stagingDir = File(context.filesDir, "staging")
    private val metadataFile = File(context.filesDir, "staged_files.db")
    
    // BetaTestLogger integration for comprehensive sync logging
    private val betaLogger = BetaTestLogger.getInstance(context)
    
    // Thread-safe collections for concurrent access
    private val stagedFiles = ConcurrentHashMap<String, StagedFile>()
    private val syncQueue = ArrayDeque<SyncOperation>()
    private val activeSyncs = ConcurrentHashMap<String, Job>()
    
    private val isMeshAvailable = AtomicBoolean(false)
    private val syncScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // Battery-aware sync control
    private val batteryAwareSync = BatteryAwareSync()
    
    init {
        betaLogger.log(LogLevel.DEBUG, "Storage", "StagedSyncManager initializing")
        stagingDir.mkdirs()
        loadStagedFilesFromDisk()
        startSyncWorker()
        startMeshConnectivityMonitor()
        betaLogger.log(LogLevel.INFO, "Storage", "StagedSyncManager initialized")
    }
    
    // === PUBLIC API ===
    
    suspend fun storeFile(path: String, data: ByteArray, priority: SyncPriority = SyncPriority.NORMAL): LocalFileReference? {
        return try {
            val localPath = saveFileLocally(path, data)
            val stagedFile = StagedFile(
                path = path,
                localPath = localPath,
                size = data.size.toLong(),
                state = FileState.LOCAL_ONLY,
                priority = priority,
                lastModified = System.currentTimeMillis(),
                checksum = calculateChecksum(data)
            )
            
            stagedFiles[path] = stagedFile
            persistMetadata()
            
            // Queue for mesh sync if available
            if (isMeshAvailable.get() && batteryAwareSync.shouldSync()) {
                queueForSync(stagedFile, SyncType.UPLOAD)
            }
            
            LocalFileReference(
                id = generateFileId(path),
                localPath = localPath,
                checksum = stagedFile.checksum
            )
        } catch (e: Exception) {
            null
        }
    }
    
    suspend fun readFile(path: String): ByteArray? {
        val stagedFile = stagedFiles[path] ?: return null
        
        return when (stagedFile.state) {
            FileState.LOCAL_ONLY, FileState.STAGING, FileState.SYNCING, 
            FileState.SYNCED, FileState.SYNC_FAILED -> {
                readFileFromLocal(stagedFile.localPath)
            }
            FileState.MESH_ONLY -> {
                downloadFromMesh(stagedFile)
            }
            FileState.CONFLICT -> {
                // Return local version, let user decide
                readFileFromLocal(stagedFile.localPath)
            }
        }
    }
    
    suspend fun deleteFile(path: String): Boolean {
        val stagedFile = stagedFiles[path] ?: return false
        
        // Delete local copy
        File(stagedFile.localPath).delete()
        
        // Queue mesh deletion if file was synced
        if (stagedFile.state == FileState.SYNCED && isMeshAvailable.get()) {
            queueForSync(stagedFile, SyncType.DELETE)
        }
        
        stagedFiles.remove(path)
        persistMetadata()
        return true
    }
    
    fun getFileState(path: String): FileState? = stagedFiles[path]?.state
    
    fun listFiles(): List<StagedFile> = stagedFiles.values.toList()
    
    fun getPendingSyncCount(): Int = syncQueue.size
    
    fun getActiveSyncCount(): Int = activeSyncs.size
    
    suspend fun forceSyncFile(path: String): SyncResult {
        val stagedFile = stagedFiles[path] ?: return SyncResult.Failure("File not found", false)
        
        if (!isMeshAvailable.get()) {
            return SyncResult.Failure("Mesh not available", true)
        }
        
        val operation = SyncOperation(stagedFile, SyncType.UPLOAD)
        return performSync(operation)
    }
    
    // === MESH CONNECTIVITY MANAGEMENT ===
    
    fun onMeshConnected() {
        isMeshAvailable.set(true)
        syncScope.launch {
            // Queue all LOCAL_ONLY and SYNC_FAILED files for upload
            stagedFiles.values
                .filter { it.state in setOf(FileState.LOCAL_ONLY, FileState.SYNC_FAILED) }
                .forEach { queueForSync(it, SyncType.UPLOAD) }
            
            // Check for mesh updates
            checkForMeshUpdates()
        }
    }
    
    fun onMeshDisconnected() {
        isMeshAvailable.set(false)
        // Cancel active syncs
        activeSyncs.values.forEach { it.cancel() }
        activeSyncs.clear()
    }
    
    // === PRIVATE IMPLEMENTATION ===
    
    private fun saveFileLocally(path: String, data: ByteArray): String {
        val filename = path.replace("/", "_").replace("\\", "_")
        val localFile = File(stagingDir, filename)
        localFile.writeBytes(data)
        return localFile.absolutePath
    }
    
    private fun readFileFromLocal(localPath: String): ByteArray? {
        val file = File(localPath)
        return if (file.exists()) file.readBytes() else null
    }
    
    private fun calculateChecksum(data: ByteArray): String {
        // Simple hash implementation - use SHA-256 in production
        return data.contentHashCode().toString()
    }
    
    private fun generateFileId(path: String): String {
        return "${path.hashCode()}-${System.currentTimeMillis()}"
    }
    
    private suspend fun downloadFromMesh(stagedFile: StagedFile): ByteArray? {
        return try {
            val data = meshNetwork.requestFileFromNode("", stagedFile.path) // Node selection logic needed
            if (data != null) {
                // Cache locally
                val localPath = saveFileLocally(stagedFile.path, data)
                val updatedFile = stagedFile.copy(
                    localPath = localPath,
                    state = FileState.SYNCED
                )
                stagedFiles[stagedFile.path] = updatedFile
                persistMetadata()
            }
            data
        } catch (e: Exception) {
            null
        }
    }
    
    private fun queueForSync(file: StagedFile, syncType: SyncType) {
        val operation = SyncOperation(file, syncType)
        
        synchronized(syncQueue) {
            // Remove existing operations for this file
            syncQueue.removeAll { it.file.path == file.path }
            
            // Insert based on priority
            when (file.priority) {
                SyncPriority.CRITICAL -> syncQueue.addFirst(operation)
                SyncPriority.HIGH -> {
                    val insertIndex = syncQueue.indexOfFirst { 
                        it.file.priority in setOf(SyncPriority.NORMAL, SyncPriority.LOW)
                    }
                    if (insertIndex >= 0) {
                        syncQueue.add(insertIndex, operation)
                    } else {
                        syncQueue.addLast(operation)
                    }
                }
                else -> syncQueue.addLast(operation)
            }
        }
    }
    
    private fun startSyncWorker() {
        syncScope.launch {
            while (isActive) {
                try {
                    if (shouldSkipSync()) {
                        delay(30_000) // Wait 30s before checking again
                        continue
                    }
                    
                    val batch = mutableListOf<SyncOperation>()
                    synchronized(syncQueue) {
                        repeat(minOf(SYNC_BATCH_SIZE, syncQueue.size)) {
                            syncQueue.removeFirstOrNull()?.let { batch.add(it) }
                        }
                    }
                    
                    if (batch.isEmpty()) {
                        delay(5_000) // Wait 5s before checking again
                        continue
                    }
                    
                    // Process batch with concurrency control
                    batch.chunked(MAX_CONCURRENT_SYNCS).forEach { chunk ->
                        chunk.map { operation ->
                            async {
                                val job = launch { 
                                    val result = performSync(operation)
                                    operation.deferred.complete(result)
                                }
                                activeSyncs[operation.file.path] = job
                                
                                try {
                                    job.join()
                                } finally {
                                    activeSyncs.remove(operation.file.path)
                                }
                            }
                        }.awaitAll()
                    }
                    
                } catch (e: Exception) {
                    delay(10_000)
                }
            }
        }
    }
    
    private fun shouldSkipSync(): Boolean {
        return !isMeshAvailable.get() || 
               !batteryAwareSync.shouldSync()
    }
    
    private suspend fun performSync(operation: SyncOperation): SyncResult {
        val file = operation.file
        
        return try {
            when (operation.operation) {
                SyncType.UPLOAD -> uploadToMesh(file)
                SyncType.DOWNLOAD -> downloadFromMeshToLocal(file)
                SyncType.UPDATE -> updateFile(file)
                SyncType.DELETE -> deleteFromMesh(file)
            }
        } catch (e: Exception) {
            val retryable = e !is SecurityException && file.syncAttempts < MAX_RETRY_ATTEMPTS
            
            betaLogger.log(LogLevel.WARN, "Storage", 
                "Sync operation failed for ${file.path}: ${e.message} (attempt ${file.syncAttempts + 1}/$MAX_RETRY_ATTEMPTS)")
            
            if (retryable) {
                betaLogger.log(LogLevel.INFO, "Storage", "Sync operation failed, retrying: ${file.path}")
                // Increment retry count and requeue
                val retryFile = file.copy(syncAttempts = file.syncAttempts + 1)
                stagedFiles[file.path] = retryFile
                
                // Exponential backoff
                delay((1000 * (1 shl file.syncAttempts)).toLong())
                queueForSync(retryFile, operation.operation)
            } else {
                betaLogger.log(LogLevel.ERROR, "Storage", 
                    "Sync operation permanently failed for ${file.path}: ${e.message}")
                // Mark as failed
                val failedFile = file.copy(state = FileState.SYNC_FAILED)
                stagedFiles[file.path] = failedFile
                persistMetadata()
            }
            
            SyncResult.Failure(e.message ?: "Unknown error", retryable)
        }
    }
    
    private suspend fun uploadToMesh(file: StagedFile): SyncResult {
        val data = readFileFromLocal(file.localPath) ?: return SyncResult.Failure("Local file not found", false)
        
        updateFileState(file.path, FileState.SYNCING)
        
        // This would integrate with your mesh network protocol
        // meshNetwork.uploadFile(file.path, data)
        
        val syncedFile = file.copy(
            state = FileState.SYNCED,
            syncAttempts = 0
        )
        
        stagedFiles[file.path] = syncedFile
        persistMetadata()
        
        return SyncResult.Success
    }
    
    private suspend fun downloadFromMeshToLocal(file: StagedFile): SyncResult {
        val data = meshNetwork.requestFileFromNode("", file.path) // Node selection needed
            ?: return SyncResult.Failure("Failed to download from mesh", true)
        
        val localPath = saveFileLocally(file.path, data)
        val downloadedFile = file.copy(
            localPath = localPath,
            state = FileState.SYNCED,
            size = data.size.toLong(),
            syncAttempts = 0
        )
        
        stagedFiles[file.path] = downloadedFile
        persistMetadata()
        
        return SyncResult.Success
    }
    
    private suspend fun updateFile(file: StagedFile): SyncResult {
        return uploadToMesh(file)
    }
    
    private suspend fun deleteFromMesh(file: StagedFile): SyncResult {
        stagedFiles.remove(file.path)
        persistMetadata()
        return SyncResult.Success
    }
    
    private fun updateFileState(path: String, newState: FileState) {
        stagedFiles[path]?.let { file ->
            stagedFiles[path] = file.copy(state = newState)
        }
    }
    
    private suspend fun checkForMeshUpdates() {
        // Implementation for checking mesh file updates
    }
    
    private fun startMeshConnectivityMonitor() {
        syncScope.launch {
            while (isActive) {
                val wasAvailable = isMeshAvailable.get()
                val isNowAvailable = checkMeshConnectivity()
                
                if (!wasAvailable && isNowAvailable) {
                    onMeshConnected()
                } else if (wasAvailable && !isNowAvailable) {
                    onMeshDisconnected()
                }
                
                delay(10_000) // Check every 10 seconds
            }
        }
    }
    
    private suspend fun checkMeshConnectivity(): Boolean {
        return try {
            // Implementation depends on mesh network layer
            true // Placeholder
        } catch (e: Exception) {
            false
        }
    }
    
    // === PERSISTENCE ===
    
    private fun loadStagedFilesFromDisk() {
        try {
            if (!metadataFile.exists()) return
            // Load metadata implementation
        } catch (e: Exception) {
            // Handle corruption gracefully
        }
    }
    
    private fun persistMetadata() {
        syncScope.launch {
            try {
                // Persist metadata implementation
            } catch (e: Exception) {
                // Log error but don't crash
            }
        }
    }
    
    /**
     * Queue file for deletion from mesh network
     */
    internal fun queueForMeshDeletion(fileInfo: DistributedFileInfo) {
        syncScope.launch {
            try {
                // Get the staged file for this path
                val stagedFile = stagedFiles[fileInfo.path]
                if (stagedFile != null) {
                    // Use existing queueForSync function
                    queueForSync(stagedFile, SyncType.DELETE)
                } else {
                    println("File not found in staged files: ${fileInfo.path}")
                }
                
            } catch (e: Exception) {
                // Mark file as sync failed if it exists
                stagedFiles[fileInfo.path]?.let { stagedFile ->
                    stagedFiles[fileInfo.path] = stagedFile.copy(state = FileState.SYNC_FAILED)
                }
                println("Failed to queue mesh deletion: ${e.message}")
            }
        }
    }
    
    fun close() {
        syncScope.cancel()
    }
}

// === SUPPORTING CLASSES ===

data class StagedFile(
    val path: String,
    val localPath: String,
    val size: Long,
    val state: FileState,
    val priority: SyncPriority,
    val lastModified: Long,
    val syncAttempts: Int = 0,
    val checksum: String,
    val metadata: Map<String, String> = emptyMap()
)

data class SyncOperation(
    val file: StagedFile,
    val operation: SyncType,
    val timestamp: Long = System.currentTimeMillis(),
    val deferred: CompletableDeferred<SyncResult> = CompletableDeferred()
)

enum class FileState {
    LOCAL_ONLY,      // File exists only locally
    STAGING,         // Queued for mesh sync
    SYNCING,         // Currently syncing to mesh
    SYNCED,          // Available on both local and mesh
    MESH_ONLY,       // Cached from mesh, not local original
    SYNC_FAILED,     // Sync attempted but failed
    CONFLICT         // Local and mesh versions differ
}

enum class SyncType {
    UPLOAD,          // Local → Mesh
    DOWNLOAD,        // Mesh → Local
    UPDATE,          // Sync newer version
    DELETE           // Remove from mesh/local
}

sealed class SyncResult {
    object Success : SyncResult()
    data class Failure(val error: String, val retryable: Boolean) : SyncResult()
    data class Conflict(val localVersion: StagedFile, val meshVersion: String) : SyncResult()
}

/**
 * Battery-aware sync management based on your excellent design
 */
class BatteryAwareSync {
    fun shouldSync(): Boolean {
        val batteryLevel = getBatteryLevel()
        val isCharging = isCharging()
        
        return when {
            batteryLevel < 15 -> false
            batteryLevel < 30 && !isCharging -> false // Only critical sync
            batteryLevel > 50 || isCharging -> true
            else -> true
        }
    }
    
    private fun getBatteryLevel(): Int {
        // Implementation using BatteryManager
        return 50 // Placeholder
    }
    
    private fun isCharging(): Boolean {
        // Implementation using BatteryManager
        return false // Placeholder
    }
}
