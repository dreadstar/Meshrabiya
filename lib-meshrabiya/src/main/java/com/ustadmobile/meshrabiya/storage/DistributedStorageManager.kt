package com.ustadmobile.meshrabiya.storage

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.collections.ArrayDeque
import com.ustadmobile.meshrabiya.mmcp.AccessPattern
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel

/**
 * Enhanced distributed storage manager that integrates with mesh network architecture
 * Provides local-first storage with mesh synchronization capabilities
 */
class DistributedStorageManager(
    private val context: Context,
    private val meshNetworkInterface: MeshNetworkInterface,
    private val storageConfig: StorageConfiguration
) {
    companion object {
        private const val TAG = "DistributedStorageManager"
        private const val STORAGE_METADATA_FILE = "distributed_storage.json"
        private const val MAX_CONCURRENT_SYNCS = 3
        private const val SYNC_BATCH_SIZE = 10
        private const val MIN_BATTERY_LEVEL = 20
    }
    
    // Core storage components
    private val stagedSyncManager = StagedSyncManager(context, this, meshNetworkInterface)
    private val storageQuotaManager = StorageQuotaManager(context, storageConfig)
    private val encryptionManager = StorageEncryptionManager()
    
    // BetaTestLogger integration for comprehensive logging
    private val betaLogger = BetaTestLogger.getInstance(context)
    
    // Storage state tracking
    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()
    
    private val _participationEnabled = MutableStateFlow(false)
    val participationEnabled: StateFlow<Boolean> = _participationEnabled.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // File reference tracking for mesh distribution
    private val distributedFiles = ConcurrentHashMap<String, DistributedFileInfo>()
    private val replicationTracker = ReplicationTracker()
    
    init {
        betaLogger.log(LogLevel.DEBUG, "Storage", "DistributedStorageManager initializing")
        loadStorageConfiguration()
        startStorageMonitoring()
        betaLogger.log(LogLevel.INFO, "Storage", "DistributedStorageManager initialized successfully")
    }
    
    // === PUBLIC API ===
    
    /**
     * Store file locally and optionally sync to mesh
     */
    suspend fun storeFile(
        path: String, 
        data: ByteArray, 
        priority: SyncPriority = SyncPriority.NORMAL,
        replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD
    ): FileReference? {
        betaLogger.log(LogLevel.DEBUG, "Storage", "Write operation started: $path (${data.size} bytes)")
        
        return try {
            // Check quota first
            if (!storageQuotaManager.canStoreFile(data.size.toLong())) {
                betaLogger.log(LogLevel.WARN, "Storage", "Storage quota exceeded for file: $path (${data.size} bytes)")
                throw StorageQuotaExceededException("Insufficient storage quota")
            }
            
            // Check if storage is at high utilization (95%+)
            val currentStats = _storageStats.value
            val utilizationPercent = if (currentStats.totalOffered > 0) {
                (currentStats.currentlyUsed.toFloat() / currentStats.totalOffered.toFloat()) * 100f
            } else 0f
            
            if (utilizationPercent >= 95f) {
                betaLogger.log(LogLevel.WARN, "Storage", "Storage limit check: ${utilizationPercent.toInt()}% utilized")
            }
            
            // Store locally first (encrypted)
            betaLogger.log(LogLevel.DEBUG, "DistributedStorage", "Starting encryption for file: $path, size=${data.size}B")
            val encryptStartTime = System.currentTimeMillis()
            val encryptedData = encryptionManager.encrypt(data)
            val encryptDuration = System.currentTimeMillis() - encryptStartTime
            
            betaLogger.log(
                LogLevel.DEBUG, 
                "DistributedStorage", 
                "Encryption complete for $path: ${data.size}B -> ${encryptedData.size}B in ${encryptDuration}ms"
            )
            val localRef = stagedSyncManager.storeFile(path, encryptedData, priority)
            
            if (localRef != null) {
                betaLogger.log(LogLevel.DEBUG, "Storage", "File stored locally: $path")
                
                // Track for mesh distribution
                val fileInfo = DistributedFileInfo(
                    path = path,
                    localReference = localRef,
                    replicationLevel = replicationLevel,
                    priority = priority,
                    createdAt = System.currentTimeMillis(),
                    lastAccessed = System.currentTimeMillis()
                )
                
                distributedFiles[path] = fileInfo
                
                // Queue for mesh sync if participation enabled
                if (_participationEnabled.value) {
                    betaLogger.log(LogLevel.DEBUG, "Storage", "Queuing file for mesh distribution: $path")
                    queueForMeshDistribution(fileInfo)
                }
                
                updateStorageStats()
                betaLogger.log(LogLevel.DEBUG, "Storage", "Write operation completed: $path")
                FileReference(localRef.id, path, data.size.toLong())
            } else {
                betaLogger.log(LogLevel.ERROR, "Storage", "Failed to store file locally: $path")
                null
            }
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, "Storage", "Write operation failed: $path - ${e.message}")
            null
        }
    }
    
    /**
     * Retrieve file from local storage or mesh
     */
    suspend fun retrieveFile(fileRef: FileReference): ByteArray? {
        betaLogger.log(LogLevel.DEBUG, "Storage", "Read operation started: ${fileRef.path}")
        
        return try {
            // Try local first
            val localData = stagedSyncManager.readFile(fileRef.path)
            if (localData != null) {
                betaLogger.log(LogLevel.DEBUG, "Storage", "File retrieved from local storage: ${fileRef.path}")
                
                // Update access time
                distributedFiles[fileRef.path]?.let { fileInfo ->
                    distributedFiles[fileRef.path] = fileInfo.copy(lastAccessed = System.currentTimeMillis())
                }
                
                betaLogger.log(LogLevel.DEBUG, "DistributedStorage", "Starting decryption for local file: ${fileRef.path}, size=${localData.size}B")
                val decryptStartTime = System.currentTimeMillis()
                val decryptedData = encryptionManager.decrypt(localData)
                val decryptDuration = System.currentTimeMillis() - decryptStartTime
                
                betaLogger.log(
                    LogLevel.DEBUG, 
                    "DistributedStorage", 
                    "Local decryption complete for ${fileRef.path}: ${localData.size}B -> ${decryptedData.size}B in ${decryptDuration}ms"
                )
                
                betaLogger.log(LogLevel.DEBUG, "Storage", "Read operation completed: ${fileRef.path}")
                return decryptedData
            }
            
            // Try mesh retrieval if local not available
            if (_participationEnabled.value) {
                betaLogger.log(LogLevel.DEBUG, "Storage", "Attempting mesh retrieval: ${fileRef.path}")
                val meshData = retrieveFromMesh(fileRef)
                if (meshData != null) {
                    betaLogger.log(LogLevel.INFO, "Storage", "File retrieved from mesh: ${fileRef.path}")
                    betaLogger.log(LogLevel.DEBUG, "Storage", "Read operation completed: ${fileRef.path}")
                } else {
                    betaLogger.log(LogLevel.WARN, "Storage", "File not found in mesh: ${fileRef.path}")
                }
                meshData
            } else {
                betaLogger.log(LogLevel.WARN, "Storage", "File not found locally and mesh participation disabled: ${fileRef.path}")
                null
            }
        } catch (e: Exception) {
            betaLogger.log(LogLevel.ERROR, "Storage", "Read operation failed: ${fileRef.path} - ${e.message}")
            null
        }
    }
    
    /**
     * Delete file from local and mesh storage
     */
    suspend fun deleteFile(path: String): Boolean {
        return try {
            val fileInfo = distributedFiles[path]
            
            // Delete from local storage
            val localDeleted = stagedSyncManager.deleteFile(path)
            
            // Queue mesh deletion if file was distributed
            if (fileInfo != null && _participationEnabled.value) {
                stagedSyncManager.queueForMeshDeletion(fileInfo)
            }
            
            distributedFiles.remove(path)
            updateStorageStats()
            
            localDeleted
        } catch (e: Exception) {
            false
        }
    }
    
    /**
     * Configure storage participation settings
     */
    suspend fun configureStorageParticipation(config: StorageParticipationConfig) {
        storageQuotaManager.updateConfiguration(config)
        _participationEnabled.value = config.participationEnabled
        
        // Update mesh capabilities
        if (config.participationEnabled) {
            broadcastStorageCapabilities()
        }
        
        saveStorageConfiguration(config)
    }
    
    /**
     * Get current storage capabilities for role assignment
     */
    fun getStorageCapabilities(): com.ustadmobile.meshrabiya.mmcp.StorageCapabilities {
        val stats = _storageStats.value
        val capabilities = com.ustadmobile.meshrabiya.mmcp.StorageCapabilities(
            totalOffered = stats.totalOffered,
            currentlyUsed = stats.currentlyUsed,
            replicationFactor = storageConfig.defaultReplicationFactor,
            compressionSupported = true,
            encryptionSupported = true,
            accessPatterns = setOf(
                AccessPattern.RANDOM,
                AccessPattern.SEQUENTIAL
            )
        )
        
        // Log storage capabilities that might impact role assignment
        val utilizationPercent = if (stats.totalOffered > 0) {
            (stats.currentlyUsed.toDouble() / stats.totalOffered * 100).toInt()
        } else 0
        
        val roleEligible = stats.totalOffered > 1_000_000L // EmergentRoleManager threshold
        
        betaLogger.log(
            LogLevel.DEBUG, 
            "DistributedStorage",
            "Storage capabilities for role assignment: totalOffered=${stats.totalOffered}B (${stats.totalOffered / 1024 / 1024}MB), " +
                    "currentlyUsed=${stats.currentlyUsed}B (${utilizationPercent}%), " +
                    "replicationFactor=${storageConfig.defaultReplicationFactor}, " +
                    "roleEligible=$roleEligible (requires >1MB offered)"
        )
        
        // Warn if capabilities might negatively impact role assignment
        if (stats.totalOffered <= 1_000_000L && stats.totalOffered > 0) {
            betaLogger.log(
                LogLevel.WARN,
                "DistributedStorage",
                "Storage offering (${stats.totalOffered}B) below threshold for STORAGE_NODE role assignment (requires >1MB)"
            )
        }
        
        return capabilities
    }
    
    // === MESH INTEGRATION ===
    
    private suspend fun queueForMeshDistribution(fileInfo: DistributedFileInfo) {
        scope.launch {
            try {
                // Find optimal storage nodes for replication
                val targetNodes = selectReplicationNodes(fileInfo.replicationLevel)
                
                for (nodeId in targetNodes) {
                    betaLogger.log(
                        LogLevel.DEBUG,
                        "DistributedStorage",
                        "Sending replication request for ${fileInfo.path} to node $nodeId, replicationLevel=${fileInfo.replicationLevel}"
                    )
                    
                    // Send replication request to node
                    meshNetworkInterface.sendStorageRequest(
                        targetNodeId = nodeId,
                        fileInfo = fileInfo,
                        operation = StorageOperation.REPLICATE
                    )
                }
                
                replicationTracker.trackReplication(fileInfo.path, targetNodes)
                
                betaLogger.log(
                    LogLevel.INFO,
                    "DistributedStorage",
                    "Queued replication for ${fileInfo.path} to ${targetNodes.size} nodes: ${targetNodes.joinToString()}, replicationLevel=${fileInfo.replicationLevel}"
                )
            } catch (e: Exception) {
                // Handle replication failure
            }
        }
    }
    
    private suspend fun retrieveFromMesh(fileRef: FileReference): ByteArray? {
        // Query mesh for file availability
        val availableNodes = meshNetworkInterface.queryFileAvailability(fileRef.path)
        
        for (nodeId in availableNodes) {
            try {
                val encryptedData = meshNetworkInterface.requestFileFromNode(nodeId, fileRef.path)
                if (encryptedData != null) {
                    betaLogger.log(LogLevel.DEBUG, "DistributedStorage", "Starting decryption for mesh file: ${fileRef.path} from node $nodeId, size=${encryptedData.size}B")
                    val decryptStartTime = System.currentTimeMillis()
                    val decryptedData = encryptionManager.decrypt(encryptedData)
                    val decryptDuration = System.currentTimeMillis() - decryptStartTime
                    
                    betaLogger.log(
                        LogLevel.DEBUG, 
                        "DistributedStorage", 
                        "Mesh decryption complete for ${fileRef.path}: ${encryptedData.size}B -> ${decryptedData.size}B in ${decryptDuration}ms from node $nodeId"
                    )
                    return decryptedData
                }
            } catch (e: Exception) {
                betaLogger.log(
                    LogLevel.DEBUG, 
                    "DistributedStorage", 
                    "Failed to decrypt file ${fileRef.path} from node $nodeId: ${e.message}, trying next node"
                )
                // Try next node
                continue
            }
        }
        
        return null
    }
    
    private suspend fun selectReplicationNodes(replicationLevel: ReplicationLevel): List<String> {
        val requiredReplicas = when (replicationLevel) {
            ReplicationLevel.MINIMAL -> 1
            ReplicationLevel.STANDARD -> 3
            ReplicationLevel.HIGH -> 5
            ReplicationLevel.CRITICAL -> 7
        }
        
        // Get available storage nodes from mesh intelligence
        val storageNodes = meshNetworkInterface.getAvailableStorageNodes()
        return storageNodes.take(requiredReplicas)
    }
    
    private suspend fun broadcastStorageCapabilities() {
        val capabilities = getStorageCapabilities()
        
        betaLogger.log(
            LogLevel.INFO,
            "DistributedStorage",
            "Broadcasting storage capabilities to mesh - totalOffered=${capabilities.totalOffered}B, " +
                    "currentlyUsed=${capabilities.currentlyUsed}B, " +
                    "replicationFactor=${capabilities.replicationFactor}, " +
                    "compressionSupported=${capabilities.compressionSupported}, " +
                    "encryptionSupported=${capabilities.encryptionSupported}"
        )
        
        try {
            meshNetworkInterface.broadcastStorageAdvertisement(capabilities)
            
            betaLogger.log(
                LogLevel.DEBUG,
                "DistributedStorage", 
                "Successfully broadcast storage capabilities - this may trigger role reassignment across mesh"
            )
        } catch (e: Exception) {
            betaLogger.log(
                LogLevel.ERROR,
                "DistributedStorage",
                "Failed to broadcast storage capabilities: ${e.message} - role assignment may not reflect current storage state"
            )
        }
    }
    
    // === STORAGE MONITORING ===
    
    private fun startStorageMonitoring() {
        scope.launch {
            while (isActive) {
                try {
                    updateStorageStats()
                    cleanupExpiredFiles()
                    optimizeReplication()
                    delay(30_000) // Update every 30 seconds
                } catch (e: Exception) {
                    delay(60_000) // Back off on error
                }
            }
        }
    }
    
    private suspend fun updateStorageStats() {
        val quotaInfo = storageQuotaManager.getQuotaInfo()
        val previousStats = _storageStats.value
        val currentStats = StorageStats(
            totalOffered = quotaInfo.totalQuota,
            currentlyUsed = quotaInfo.usedQuota,
            filesStored = distributedFiles.size,
            replicationHealth = replicationTracker.getOverallHealth()
        )
        
        // Check for role assignment impact changes
        val previousRoleEligible = previousStats.totalOffered > 1_000_000L
        val currentRoleEligible = currentStats.totalOffered > 1_000_000L
        
        val utilizationPercent = if (currentStats.totalOffered > 0) {
            (currentStats.currentlyUsed.toDouble() / currentStats.totalOffered * 100).toInt()
        } else 0
        
        // Log significant changes that could affect role assignment
        if (previousRoleEligible != currentRoleEligible) {
            if (currentRoleEligible) {
                betaLogger.log(
                    LogLevel.INFO,
                    "DistributedStorage",
                    "Storage now eligible for STORAGE_NODE role assignment - totalOffered increased to ${currentStats.totalOffered}B (${currentStats.totalOffered / 1024 / 1024}MB) > 1MB threshold"
                )
            } else {
                betaLogger.log(
                    LogLevel.WARN,
                    "DistributedStorage", 
                    "Storage no longer eligible for STORAGE_NODE role assignment - totalOffered decreased to ${currentStats.totalOffered}B (${currentStats.totalOffered / 1024 / 1024}MB) <= 1MB threshold"
                )
            }
        }
        
        // Log when storage allocation changes significantly (could trigger role reassessment)
        val allocationChangePct = if (previousStats.totalOffered > 0) {
            kotlin.math.abs(currentStats.totalOffered - previousStats.totalOffered).toDouble() / previousStats.totalOffered * 100
        } else if (currentStats.totalOffered > 0) {
            100.0 // First allocation
        } else {
            0.0
        }
        
        if (allocationChangePct > 10) { // Significant change (>10%)
            betaLogger.log(
                LogLevel.INFO,
                "DistributedStorage",
                "Significant storage allocation change: ${previousStats.totalOffered}B -> ${currentStats.totalOffered}B (${if (allocationChangePct > 0) "+" else ""}${allocationChangePct.toInt()}%) - may trigger role reassignment, utilizationNow=${utilizationPercent}%"
            )
        }
        
        _storageStats.value = currentStats
        
        betaLogger.log(
            LogLevel.DEBUG,
            "DistributedStorage",
            "Updated storage stats: totalOffered=${currentStats.totalOffered}B, currentlyUsed=${currentStats.currentlyUsed}B (${utilizationPercent}%), filesStored=${currentStats.filesStored}, roleEligible=$currentRoleEligible"
        )
    }
    
    private suspend fun cleanupExpiredFiles() {
        val expiredFiles = distributedFiles.values.filter { fileInfo ->
            val ageMs = System.currentTimeMillis() - fileInfo.lastAccessed
            val expiry = getExpiryTime(fileInfo.priority)
            ageMs > expiry
        }
        
        for (fileInfo in expiredFiles) {
            deleteFile(fileInfo.path)
        }
    }
    
    private fun getExpiryTime(priority: SyncPriority): Long {
        return when (priority) {
            SyncPriority.CRITICAL -> Long.MAX_VALUE // Never expire
            SyncPriority.HIGH -> 30L * 24 * 60 * 60 * 1000 // 30 days
            SyncPriority.NORMAL -> 7L * 24 * 60 * 60 * 1000 // 7 days  
            SyncPriority.LOW -> 1L * 24 * 60 * 60 * 1000 // 1 day
        }
    }
    
    private suspend fun optimizeReplication() {
        // Check replication health and adjust as needed
        for ((path, fileInfo) in distributedFiles) {
            val health = replicationTracker.getFileHealth(path)
            if (health < 0.5f) { // Less than 50% of replicas available
                queueForMeshDistribution(fileInfo)
            }
        }
    }
    
    // === CONFIGURATION PERSISTENCE ===
    
    private fun loadStorageConfiguration() {
        val prefs = context.getSharedPreferences("distributed_storage", Context.MODE_PRIVATE)
        val participationEnabled = prefs.getBoolean("participation_enabled", false)
        _participationEnabled.value = participationEnabled
    }
    
    private fun saveStorageConfiguration(config: StorageParticipationConfig) {
        val prefs = context.getSharedPreferences("distributed_storage", Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("participation_enabled", config.participationEnabled)
            .putLong("total_quota", config.totalQuota)
            .apply()
    }
    
    fun close() {
        scope.cancel()
        stagedSyncManager.close()
    }
}

// === DATA CLASSES ===

data class StorageConfiguration(
    val defaultReplicationFactor: Int = 3,
    val encryptionEnabled: Boolean = true,
    val compressionEnabled: Boolean = true,
    val maxFileSize: Long = 100L * 1024 * 1024, // 100MB default
    val defaultQuota: Long = 1L * 1024 * 1024 * 1024 // 1GB default
)

data class StorageParticipationConfig(
    val participationEnabled: Boolean,
    val totalQuota: Long,
    val allowedDirectories: List<String>,
    val encryptionRequired: Boolean = true
)

data class StorageStats(
    val totalOffered: Long = 0L,
    val currentlyUsed: Long = 0L,
    val filesStored: Int = 0,
    val replicationHealth: Float = 1.0f
)

data class DistributedFileInfo(
    val path: String,
    val localReference: LocalFileReference,
    val replicationLevel: ReplicationLevel,
    val priority: SyncPriority,
    val createdAt: Long,
    val lastAccessed: Long,
    val meshReferences: List<String> = emptyList()
)

data class FileReference(
    val id: String,
    val path: String,
    val size: Long
)

data class LocalFileReference(
    val id: String,
    val localPath: String,
    val checksum: String
)

enum class ReplicationLevel {
    MINIMAL,    // 1 replica
    STANDARD,   // 3 replicas
    HIGH,       // 5 replicas
    CRITICAL    // 7 replicas
}

enum class SyncPriority {
    LOW,        // Background sync
    NORMAL,     // Standard sync
    HIGH,       // User-requested immediate sync
    CRITICAL    // System-critical files
}

enum class StorageOperation {
    REPLICATE,
    RETRIEVE,
    DELETE,
    VERIFY
}

class StorageQuotaExceededException(message: String) : Exception(message)

// === INTERFACES ===

interface MeshNetworkInterface {
    suspend fun sendStorageRequest(targetNodeId: String, fileInfo: DistributedFileInfo, operation: StorageOperation)
    suspend fun queryFileAvailability(path: String): List<String>
    suspend fun requestFileFromNode(nodeId: String, path: String): ByteArray?
    suspend fun getAvailableStorageNodes(): List<String>
    suspend fun broadcastStorageAdvertisement(capabilities: com.ustadmobile.meshrabiya.mmcp.StorageCapabilities)
}
