package com.ustadmobile.meshrabiya.storage

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.service.MeshEcosystemListener
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.storage.StorageDataStore
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import kotlinx.serialization.Serializable
import com.ustadmobile.meshrabiya.service.security.AccessScope


/**
 * DistributedStorageManager: Core manager for distributed storage operations.
 * 
 * Architecture:
 * - Provides common utilities and shared state for client/server operations
 * - Delegates client-side workflows to DistributedStorageClient
 * - Delegates server-side workflows to DistributedStorageServer
 * - Manages service keypair for chunk encryption/decryption
 * - Tracks metadata and storage statistics
 */
class DistributedStorageManager(
    private val context: Context,
    private val virtualNode: VirtualNode,
    private val meshGossipService: MeshGossipService,
    private val coreGossipBroadcastService: CoreGossipBroadcastService,
    val storageConfig: StorageConfiguration,
    private val connectionPool: MeshConnectionPool
) {
    companion object {
        private const val TAG = "DistributedStorageManager"
        
        @Volatile
        private var instance: DistributedStorageManager? = null
        
        fun getInstance(context: Context): DistributedStorageManager {
            return instance ?: throw IllegalStateException(
                "DistributedStorageManager not initialized. Must be initialized through VirtualNode or explicitly via initialize()."
            )
        }
        
        fun initialize(
            context: Context,
            virtualNode: VirtualNode,
            meshGossipService: MeshGossipService,
            coreGossipBroadcastService: CoreGossipBroadcastService,
            storageConfig: StorageConfiguration,
            connectionPool: MeshConnectionPool
        ): DistributedStorageManager {
            return instance ?: synchronized(this) {
                instance ?: DistributedStorageManager(
                    context,
                    virtualNode,
                    meshGossipService,
                    coreGossipBroadcastService,
                    storageConfig,
                    connectionPool
                ).also { instance = it }
            }
        }
    }

    // === Core Components ===
    
    /**
     * Update the storage participation configuration (called by StorageParticipationManager)
     */
    /**
     * Update the storage participation configuration (called from UI/API)
     * - Updates quota manager, participation state, and recalculates stats
     */
    fun configureStorageParticipation(config: StorageParticipationConfig) {
        // Update quota manager with new allowed directories and quota
        storageQuotaManager.updateConfiguration(config)

        // Update participation enabled state
        _participationEnabled.value = config.participationEnabled

        // Optionally update encryption requirement (if used elsewhere)
        // This example assumes encryptionRequired is handled by the quota manager or elsewhere

        // Log the change for auditability
        betaLogger.log(LogLevel.INFO, TAG, "Storage participation updated: enabled=${config.participationEnabled}, totalQuota=${config.totalQuota}, dirs=${config.allowedDirectories}, encryptionRequired=${config.encryptionRequired}")

        // Recalculate storage stats to reflect new limits
        updateStorageStats()
    }

    /**
     * Data class for user storage participation configuration (copied from .md)
     */
    data class StorageParticipationConfig(
        val participationEnabled: Boolean,
        val totalQuota: Long,
        val allowedDirectories: List<String>,
        val encryptionRequired: Boolean = true
    )
    val stagedSyncManager = StagedSyncManager(
        context = context,
        onSyncComplete = { fileId, replicaCount -> 
            betaLogger.log(LogLevel.DEBUG, TAG, "StagedSyncManager synced file: $fileId with $replicaCount replicas")
        },
        onSyncFailed = { fileId, error ->
            betaLogger.log(LogLevel.ERROR, TAG, "StagedSyncManager sync failed for $fileId: $error")
        }
    )
    
    val storageQuotaManager = StorageQuotaManager(context, storageConfig)
    val encryptionManager = StorageEncryptionManager()
    val betaLogger = BetaTestLogger.getInstance(context)
    val storageDataStore: StorageDataStore = StorageDataStore.getInstance(context)
    
    // === Service Keypair (Canonical Workflow Step 0) ===
    
    private val serviceKeypair: Pair<ByteArray, ByteArray> by lazy {
        encryptionManager.generateServiceKeypair()
    }
    
    val servicePublicKey: ByteArray
        get() = serviceKeypair.first
        
    val servicePrivateKey: ByteArray
        get() = serviceKeypair.second
    
    // === Shared State ===
    
    val fileMetadataStore = ConcurrentHashMap<String, FileMetadata>()
    val chunkReplicaTracker = ConcurrentHashMap<String, MutableSet<String>>()
    
    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()
    
    private val _participationEnabled = MutableStateFlow(false)
    val participationEnabled: StateFlow<Boolean> = _participationEnabled.asStateFlow()
    
    val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // === Client and Server Components ===
    
    private lateinit var client: DistributedStorageClient
    private lateinit var server: DistributedStorageServer
    
    init {
        client = DistributedStorageClient(this, virtualNode, coreGossipBroadcastService, connectionPool)
        server = DistributedStorageServer(this, virtualNode, context)
    }
    
    // === Event Handlers ===
    
    var onFileStored: ((fileId: String, file: File) -> Unit)? = null
    var onFileRetrieved: ((fileId: String, file: File) -> Unit)? = null
    
    // === Ecosystem Listener Integration ===
    
    private var meshEcosystemListener: MeshEcosystemListener? = null

    fun registerWithEcosystemListener(listener: MeshEcosystemListener) {
        meshEcosystemListener = listener
        listener.registerStorageManager(this)
    }

    fun unregisterFromEcosystemListener(listener: MeshEcosystemListener) {
        if (meshEcosystemListener == listener) {
            meshEcosystemListener = null
        }
    }
    
    // === Public API - Delegates to Client ===
    
    /**
     * Store file with permission parameters.
     * Delegates to DistributedStorageClient.
     */
    /**
     * Store file with permission parameters and explicit accessScope.
     * Delegates to DistributedStorageClient.
     * Hybrid encryption is enforced based on accessScope and recipients.
     */
    suspend fun storeFile(
        path: String,
        data: ByteArray,
        priority: SyncPriority = SyncPriority.NORMAL,
        replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
        owner: String? = null,
        recipients: List<RecipientEntry>? = null,
        accessScope: AccessScope = AccessScope.TASK_ISOLATED
    ): FileReference? {
        // Hybrid encryption: encrypt data for each recipient using their public key, then encrypt symmetric key with service keypair
        // This logic is enforced in the client layer, but we document and check here
        if (accessScope == AccessScope.TASK_ISOLATED && (recipients == null || recipients.isEmpty())) {
            throw IllegalArgumentException("Task-isolated storage requires at least one recipient for hybrid encryption.")
        }
        // Pass accessScope to client for correct encryption handling
        return client.storeFile(path, data, priority, replicationLevel, owner, recipients, accessScope)
    }
    
    /**
     * Retrieve file from distributed storage.
     * Delegates to DistributedStorageClient.
     */
    suspend fun retrieveFile(fileRef: FileReference): ByteArray? {
        return client.retrieveFile(fileRef)
    }
    
    /**
     * Update file access permissions.
     * Delegates to DistributedStorageClient.
     */
    suspend fun updateFileAccess(
        fileId: String,
        addRecipients: List<RecipientEntry> = emptyList(),
        removeRecipients: List<String> = emptyList()
    ): Boolean {
        return client.updateFileAccess(fileId, addRecipients, removeRecipients)
    }
    
    // === Message Handlers - Delegates to Client/Server ===
    
    fun handleStorageNodeResponse(requestId: String, senderId: Int, response: com.ustadmobile.meshrabiya.service.StorageNodeResponse) {
        client.handleStorageNodeResponse(requestId, senderId, response)
    }

    fun handleChunkRetrievalResponse(requestId: String, senderId: Int, response: com.ustadmobile.meshrabiya.service.ChunkRetrievalResponse) {
        client.handleChunkRetrievalResponse(requestId, senderId, response)
    }

    fun handleReplicaResponse(requestId: String, senderId: Int, response: com.ustadmobile.meshrabiya.service.ReplicaResponse) {
        client.handleReplicaResponse(requestId, senderId, response)
    }
    
    fun handlePermissionUpdateConfirmation(confirmation: com.ustadmobile.meshrabiya.service.FilePermissionUpdateConfirmationMessage) {
        client.handlePermissionUpdateConfirmation(confirmation)
    }
    
    fun handleIncomingChunkTransfer(senderId: Int, chunkTransfer: com.ustadmobile.meshrabiya.service.ChunkTransferMessage) {
        server.handleIncomingChunkTransfer(senderId, chunkTransfer)
    }
    
    // === Common Utilities ===
    
    /**
     * Chunk file into MeshChunk objects.
     * Used by both client (for initial storage) and server (for replication).
     */
    fun chunkFile(file: File, fileId: String, chunkSize: Int): List<MeshChunk> {
        val chunks = mutableListOf<MeshChunk>()
        val fileName = file.name

        if (MeshrabiyaConstants.getNoChunking()) {
            val fileBytes = file.readBytes()
            val chunkId = sha256(fileBytes)
            chunks.add(
                MeshChunk(
                    chunkId = chunkId,
                    fileId = fileId,
                    chunkIndex = 0,
                    totalChunks = 1,
                    chunkSize = fileBytes.size.toLong(),
                    fileName = fileName,
                    relativePath = "",
                    hash = chunkId
                )
            )
        } else {
            val actualChunkSize = MeshrabiyaConstants.getChunkSizeKb() * 1024
            val totalChunks = ((file.length() + actualChunkSize - 1) / actualChunkSize).toInt()
            FileInputStream(file).use { fis ->
                var chunkIndex = 0
                var bytesRead: Int
                val buffer = ByteArray(actualChunkSize)
                while (fis.read(buffer).also { bytesRead = it } > 0) {
                    val chunkBytes = if (bytesRead < actualChunkSize) buffer.copyOf(bytesRead) else buffer.clone()
                    val chunkId = sha256(chunkBytes)
                    chunks.add(
                        MeshChunk(
                            chunkId = chunkId,
                            fileId = fileId,
                            chunkIndex = chunkIndex,
                            totalChunks = totalChunks,
                            chunkSize = bytesRead.toLong(),
                            fileName = fileName,
                            relativePath = "",
                            hash = chunkId
                        )
                    )
                    chunkIndex++
                }
            }
        }
        return chunks
    }
    
    /**
     * Read specific chunk bytes from file.
     */
    fun readChunkBytes(file: File, chunk: MeshChunk): ByteArray {
        val fis = FileInputStream(file)
        fis.skip(chunk.chunkIndex * chunk.chunkSize)
        val buffer = ByteArray(chunk.chunkSize.toInt())
        val bytesRead = fis.read(buffer)
        fis.close()
        return if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
    }
    
    /**
     * Compute SHA-256 hash of byte array.
     */
    fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }
    
    /**
     * Compute SHA-256 hash of file.
     */
    fun sha256File(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } > 0) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    
    // === Storage Data Store Access ===
    
    fun addMeshChunk(chunk: MeshChunk) = storageDataStore.addMeshChunk(chunk)
    fun getMeshChunk(chunkId: String): MeshChunk? = storageDataStore.getMeshChunk(chunkId)
    fun getAllMeshChunks(): List<MeshChunk> = storageDataStore.getAllMeshChunks()
    
    // === Metadata Access ===
    
    fun getFileMetadata(fileId: String): FileMetadata? {
        return fileMetadataStore[fileId]
    }
    
    // === Storage Stats ===
    
    fun updateStorageStats() {
        val allChunks = storageDataStore.getAllMeshChunks()
        val totalUsed = allChunks.sumOf { it.chunkSize }
        val filesCount = fileMetadataStore.size
        
        val replicationHealth = if (allChunks.isEmpty()) 1.0f else {
            val healthyChunks = allChunks.count { chunk ->
                val replicas = chunkReplicaTracker[chunk.chunkId]?.size ?: 0
                replicas >= storageConfig.defaultReplicationFactor
            }
            healthyChunks.toFloat() / allChunks.size
        }
        
        _storageStats.value = StorageStats(
            totalOffered = storageConfig.defaultQuota,
            currentlyUsed = totalUsed,
            filesStored = filesCount,
            replicationHealth = replicationHealth
        )
    }
    
    // === Data Classes ===
    
    data class StorageConfiguration(
        val defaultReplicationFactor: Int = 3,
        val encryptionEnabled: Boolean = true,
        val compressionEnabled: Boolean = true,
        val maxFileSize: Long = 100L * 1024 * 1024,
        val defaultQuota: Long = 1L * 1024 * 1024 * 1024
    )

    data class StorageStats(
        val totalOffered: Long = 0L,
        val currentlyUsed: Long = 0L,
        val filesStored: Int = 0,
        val replicationHealth: Float = 1.0f
    )
    
    class StorageQuotaExceededException(message: String) : Exception(message)
}

// === Supporting Data Classes ===

data class FileReference(
    val id: String,
    val path: String,
    val size: Long
)

enum class ReplicationLevel {
    MINIMAL,
    STANDARD,
    HIGH,
    CRITICAL
}

enum class SyncPriority {
    LOW,
    NORMAL,
    HIGH,
    CRITICAL
}

@Serializable
data class FileMetadata(
    val fileId: String,
    val path: String,
    val sizeBytes: Long,
    val owner: String,
    val recipients: List<RecipientEntry>,
    val createdAt: Long,
    val lastAccessedBy: String? = null,
    val encryptionKeyId: String? = null
) {
    fun getActiveRecipients(): List<RecipientEntry> {
        return recipients.filter { !it.isExpired() }
    }
    
    fun getUserRecipients(): List<RecipientEntry> {
        return recipients.filter { it.recipientType == RecipientType.USER }
    }
    
    fun getTaskRecipients(): List<RecipientEntry> {
        return recipients.filter { it.recipientType == RecipientType.TASK }
    }
    
    fun hasTaskAccess(taskId: String): Boolean {
        return recipients.any { 
            it.recipientType == RecipientType.TASK && 
            it.taskId == taskId && 
            !it.isExpired() 
        }
    }
}