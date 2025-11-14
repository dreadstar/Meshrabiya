package com.ustadmobile.meshrabiya.storage

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import com.ustadmobile.meshrabiya.mmcp.AccessPattern
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.vnet.MeshNetworkInterface
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.service.MeshEcosystemListener
import com.ustadmobile.meshrabiya.service.MeshEcosystemMessage
import com.ustadmobile.meshrabiya.vnet.StorageNodeRequest
import com.ustadmobile.meshrabiya.vnet.StorageNodeResponse
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.storage.StorageDataStore
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageBufferPacker
import org.msgpack.core.MessageUnpacker
import org.bouncycastle.openpgp.PGPPublicKey
import com.ustadmobile.meshrabiya.service.security.SandboxStorageProxy.AccessScope
import kotlinx.serialization.Serializable

// === Storage Types ===

/**
 * File reference for tracking stored files in distributed storage.
 */
data class FileReference(
    val id: String,        // File ID (SHA-256 hash)
    val path: String,      // Original file path
    val size: Long         // File size in bytes
)

/**
 * Replication level for file storage across mesh network.
 */
enum class ReplicationLevel {
    MINIMAL,    // 1 replica
    STANDARD,   // 3 replicas (default)
    HIGH,       // 5 replicas
    CRITICAL    // 7 replicas
}

/**
 * Priority level for sync operations.
 */
enum class SyncPriority {
    LOW,        // Background sync only
    NORMAL,     // Standard sync when battery allows
    HIGH,       // Priority sync
    CRITICAL    // Always sync immediately
}

/**
 * File metadata with permission information
 * 
 * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md Section 2.4
 */
@Serializable
data class FileMetadata(
    val fileId: String,
    val path: String,
    val sizeBytes: Long,
    val owner: String,              // Task requester node public key
    val recipients: List<String>,   // Authorized nodes
    val accessScope: AccessScope,
    val createdAt: Long,
    val lastAccessedBy: String? = null,
    val encryptionKeyId: String? = null
)

class DistributedStorageManager(
    private val context: Context,
    private val meshNetworkInterface: MeshNetworkInterface,
    private val meshGossipService: MeshGossipService,
    private val storageConfig: StorageConfiguration,
    private val connectionPool: MeshConnectionPool
) {
    // --- Event Handlers ---
    var onFileStored: ((fileId: String, file: File) -> Unit)? = null
    var onFileRetrieved: ((fileId: String, file: File) -> Unit)? = null

    companion object {
        private const val TAG = "DistributedStorageManager"
        private const val RETRY_DELAY_MS = 10000L
        private const val RESPONSE_TIMEOUT_MS = 5000L
        private const val MAX_RETRIES = 3
        
        @Volatile
        private var instance: DistributedStorageManager? = null
        
        fun getInstance(context: Context): DistributedStorageManager {
            return instance ?: synchronized(this) {
                instance ?: throw IllegalStateException(
                    "DistributedStorageManager not initialized. Must be initialized through VirtualNode or explicitly via initialize()."
                )
            }
        }
        
        fun initialize(
            context: Context,
            meshNetworkInterface: MeshNetworkInterface,
            meshGossipService: MeshGossipService,
            storageConfig: StorageConfiguration,
            connectionPool: MeshConnectionPool
        ): DistributedStorageManager {
            return instance ?: synchronized(this) {
                instance ?: DistributedStorageManager(
                    context,
                    meshNetworkInterface,
                    meshGossipService,
                    storageConfig,
                    connectionPool
                ).also { instance = it }
            }
        }
    }

    private val stagedSyncManager = StagedSyncManager(
        context = context,
        meshNetwork = meshNetworkInterface,
        onSyncComplete = { fileId, replicaCount -> 
            betaLogger.log(LogLevel.DEBUG, TAG, "StagedSyncManager synced file: $fileId with $replicaCount replicas")
        },
        onSyncFailed = { fileId, error ->
            betaLogger.log(LogLevel.ERROR, TAG, "StagedSyncManager sync failed for $fileId: $error")
        }
    )
    private val storageQuotaManager = StorageQuotaManager(context, storageConfig)
    private val encryptionManager = StorageEncryptionManager()
    private val betaLogger = BetaTestLogger.getInstance(context)
    
    // In-memory metadata store (TODO: Persist to disk for production)
    private val fileMetadataStore = ConcurrentHashMap<String, FileMetadata>()

    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()
    private val _participationEnabled = MutableStateFlow(false)
    val participationEnabled: StateFlow<Boolean> = _participationEnabled.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // Removed: distributedFiles - now using StagedSyncManager.syncedFiles
    private val replicationTracker = ReplicationTracker()
    private val chunkReplicaTracker = ConcurrentHashMap<String, MutableSet<String>>()
    private val storageDataStore: StorageDataStore = StorageDataStore.getInstance(context)

    // Ecosystem listener integration
    private var meshEcosystemListener: MeshEcosystemListener? = null

    // Track outstanding storage node requests for broadcast/response lifecycle
    private val pendingStorageNodeRequests = ConcurrentLinkedQueue<PendingStorageNodeRequest>()
    private val pendingChunkRetrievals = ConcurrentHashMap<String, MutableList<com.ustadmobile.meshrabiya.vnet.ChunkRetrievalResponse>>()
    private val pendingReplicaResponses = ConcurrentHashMap<String, MutableList<com.ustadmobile.meshrabiya.vnet.ReplicaResponse>>()
    private val pendingPermissionConfirmations = ConcurrentHashMap<String, MutableList<MeshEcosystemMessage.FilePermissionUpdateConfirmationMessage>>()

    data class PendingStorageNodeRequest(
        val request: StorageNodeRequest,
        val chunk: MeshChunk,
        val fileId: String,
        val desiredReplicas: Int,
        val responses: MutableList<StorageNodeResponse> = mutableListOf(),
        var retries: Int = 0,
        val completion: CompletableDeferred<List<StorageNodeResponse>> = CompletableDeferred()
    )

    fun registerWithEcosystemListener(listener: MeshEcosystemListener) {
        meshEcosystemListener = listener
        listener.registerStorageManager(this)
    }

    fun unregisterFromEcosystemListener(listener: MeshEcosystemListener) {
        if (meshEcosystemListener == listener) {
            meshEcosystemListener = null
            // Optionally: remove this manager from listener's registry
        }
    }

    /**
     * Handles responses from storage node broadcast.
     * Evaluates responses, retries if needed, and continues lifecycle for chunk distribution.
     */
    fun handleStorageNodeResponse(senderId: Int, response: StorageNodeResponse) {
        pendingStorageNodeRequests.forEach { pending ->
            if (pending.request.fileId == response.fileId) {
                pending.responses.add(response)
            }
        }
        // Optionally: update replication tracker or trigger next lifecycle step
        replicationTracker.updateNodeAvailability(response.nodeId, response.availableSpace, response.systemState)
    }

    /**
     * Handles chunk retrieval responses.
     * Used to aggregate responses for ongoing retrieval requests.
     */
    fun handleChunkRetrievalResponse(senderId: Int, response: com.ustadmobile.meshrabiya.vnet.ChunkRetrievalResponse) {
        val chunkId = response.chunkId
        pendingChunkRetrievals.computeIfAbsent(chunkId) { mutableListOf() }.add(response)
        // Optionally: trigger retrieval continuation or update state
    }

    /**
     * Handles replica responses.
     * Used to track replica state and health for files/chunks.
     */
    fun handleReplicaResponse(senderId: Int, response: com.ustadmobile.meshrabiya.vnet.ReplicaResponse) {
        val fileId = response.fileId
        pendingReplicaResponses.computeIfAbsent(fileId) { mutableListOf() }.add(response)
        replicationTracker.updateReplicaState(fileId, response)
    }

    /**
     * Handles permission update confirmations.
     * Used to track confirmation status for permission updates.
     */
    fun handlePermissionUpdateConfirmation(confirmation: MeshEcosystemMessage.FilePermissionUpdateConfirmationMessage) {
        val fileId = confirmation.fileId
        pendingPermissionConfirmations.computeIfAbsent(fileId) { mutableListOf() }.add(confirmation)
        // Optionally: update permission state or trigger next lifecycle step
    }

    /**
     * Handles inbound chunk/file transfer events using connection pool.
     * Called by MeshEcosystemListener.
     */
    fun handleIncomingChunkTransfer(senderId: Int, chunk: MeshEcosystemMessage.ChunkTransferMessage) {
        scope.launch {
            val connection = try {
                connectionPool.acquireConnection(timeoutMs = RESPONSE_TIMEOUT_MS)
            } catch (e: Exception) {
                null
            }
            if (connection != null) {
                try {
                    val sharedStorageDir = File(context.filesDir, "shared_storage/${chunk.fileId}/${chunk.relativePath}")
                    if (!sharedStorageDir.exists()) sharedStorageDir.mkdirs()
                    val chunkFile = File(sharedStorageDir, "${chunk.chunkId}.chunk")
                    FileOutputStream(chunkFile).use { it.write(chunk.chunkBytes) }
                    addMeshChunk(
                        MeshChunk(
                            chunkId = chunk.chunkId,
                            fileId = chunk.fileId,
                            chunkIndex = chunk.chunkIndex,
                            totalChunks = chunk.totalChunks,
                            chunkSize = chunk.chunkBytes.size.toLong(),
                            fileName = chunk.fileName,
                            relativePath = chunk.relativePath,
                            hash = chunk.hash
                        )
                    )
                } finally {
                    connectionPool.releaseConnection(connection)
                }
            } else {
                betaLogger.log(LogLevel.ERROR, TAG, "No available connection for inbound chunk transfer")
            }
        }
    }

    // === CHUNKING ===

    private fun chunkFile(file: File, fileId: String, chunkSize: Int): List<MeshChunk> {
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

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashBytes = digest.digest(data)
        return hashBytes.joinToString("") { "%02x".format(it) }
    }

    private fun sha256File(file: File): String {
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

    // === PUBLIC API ===

    /**
     * Store file with permission parameters
     * 
     * Ref: TASK_EXECUTION_LAYER_IMPLEMENTATION_PLAN.md Section 2.2
     * 
     * @param path File path
     * @param data File data (will be encrypted)
     * @param priority Sync priority level
     * @param replicationLevel Number of replicas across mesh
     * @param accessScope Permission model (TASK_ISOLATED, SERVICE_SHARED, MESH_GLOBAL)
     * @param owner Task requester node public key (null = local node)
     * @param recipients Authorized nodes that can access the file (null = owner only)
     */
    suspend fun storeFile(
        path: String,
        data: ByteArray,
        priority: SyncPriority = SyncPriority.NORMAL,
        replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
        accessScope: AccessScope = AccessScope.TASK_ISOLATED,
        owner: String? = null,
        recipients: List<String>? = null
    ): FileReference? = coroutineScope {
        betaLogger.log(LogLevel.DEBUG, "Storage", "Write operation started: $path (${data.size} bytes)")

        if (!storageQuotaManager.canStoreFile(data.size.toLong())) {
            betaLogger.log(LogLevel.WARN, "Storage", "Storage quota exceeded for file: $path (${data.size} bytes)")
            throw StorageQuotaExceededException("Insufficient storage quota")
        }

        // === NEW: Prepare permission metadata ===
        val effectiveOwner = owner ?: meshNetworkInterface.getLocalNodeId()
        val effectiveRecipients = when (accessScope) {
            AccessScope.TASK_ISOLATED -> recipients ?: listOf(effectiveOwner)
            AccessScope.SERVICE_SHARED -> recipients ?: emptyList() // Service members loaded separately
            AccessScope.MESH_GLOBAL -> emptyList() // Public access (no specific recipients)
        }
        
        betaLogger.log(
            LogLevel.DEBUG,
            "DistributedStorage",
            "Starting file storage: $path, size=${data.size}B, owner=$effectiveOwner, " +
            "accessScope=$accessScope, recipients=${effectiveRecipients.size}"
        )

        // === NEW: Hybrid encryption with per-recipient key encryption ===
        betaLogger.log(LogLevel.DEBUG, "DistributedStorage", "Starting encryption for file: $path, size=${data.size}B")
        val encryptStartTime = System.currentTimeMillis()
        val encryptedData = encryptionManager.encryptWithRecipients(
            data = data,
            owner = effectiveOwner,
            recipients = effectiveRecipients
        )
        val encryptDuration = System.currentTimeMillis() - encryptStartTime
        betaLogger.log(
            LogLevel.DEBUG,
            "DistributedStorage",
            "Encryption complete for $path: ${data.size}B -> ${encryptedData.size}B in ${encryptDuration}ms"
        )
        
        // Store encrypted data to local file
        val file = File(path)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(encryptedData) }
        
        val fileId = sha256File(file)
        val chunkSize = MeshrabiyaConstants.getChunkSizeKb() * 1024
        val chunks = chunkFile(file, fileId, chunkSize)
            val desiredReplicas = when (replicationLevel) {
                ReplicationLevel.MINIMAL -> MeshrabiyaConstants.getMinimalReplicaCount()
                ReplicationLevel.STANDARD -> MeshrabiyaConstants.getStandardReplicaCount()
                ReplicationLevel.HIGH -> MeshrabiyaConstants.getHighReplicaCount()
                ReplicationLevel.CRITICAL -> MeshrabiyaConstants.getCriticalReplicaCount()
            }
            chunkReplicaTracker.clear()
            val jobs = chunks.map { chunk ->
                async {
                    val candidateNodes = findBestStorageNodesForChunk(chunk, fileId, desiredReplicas)
                    chunkReplicaTracker[chunk.chunkId] = mutableSetOf()
                    val chunkBytes = readChunkBytes(file, chunk)
                    val chunkMsg = MeshEcosystemMessage.ChunkTransferMessage(
                        chunkId = chunk.chunkId,
                        fileId = chunk.fileId,
                        chunkIndex = chunk.chunkIndex,
                        totalChunks = chunk.totalChunks,
                        fileName = chunk.fileName,
                        relativePath = chunk.relativePath,
                        chunkBytes = chunkBytes,
                        hash = chunk.hash
                    )
                    candidateNodes.forEach { nodeId ->
                        val connection = try {
                            connectionPool.acquireConnection(timeoutMs = RESPONSE_TIMEOUT_MS)
                        } catch (e: Exception) {
                            null
                        }
                        if (connection != null) {
                            try {
                                connection.meshNetworkInterface.sendChunkToNode(nodeId, chunk, chunkMsg.toBytes())
                                chunkReplicaTracker[chunk.chunkId]?.add(nodeId)
                                betaLogger.log(LogLevel.INFO, TAG, "Chunk ${chunk.chunkId} stored on node $nodeId")
                            } finally {
                                connectionPool.releaseConnection(connection)
                            }
                        } else {
                            betaLogger.log(LogLevel.ERROR, TAG, "No available connection for chunk transfer to $nodeId")
                        }
                    }
                }
            }
            jobs.forEach { it.await() }

            // Register with StagedSyncManager for battery-aware sync orchestration
            val targetReplicaCount = when (replicationLevel) {
                ReplicationLevel.MINIMAL -> MeshrabiyaConstants.getMinimalReplicaCount()
                ReplicationLevel.STANDARD -> MeshrabiyaConstants.getStandardReplicaCount()
                ReplicationLevel.HIGH -> MeshrabiyaConstants.getHighReplicaCount()
                ReplicationLevel.CRITICAL -> MeshrabiyaConstants.getCriticalReplicaCount()
            }
            
            stagedSyncManager.registerForSync(
                filePath = path,
                fileId = fileId,
                size = data.size.toLong(),
                priority = priority,
                targetReplicaCount = targetReplicaCount
            )
            
            // Update chunk and node tracking in StagedSyncManager
            stagedSyncManager.updateChunkIds(path, chunks.map { it.chunkId })
            stagedSyncManager.updateMeshNodeIds(path, chunkReplicaTracker.values.flatten().toList())

            // === NEW: Store metadata with permissions ===
            val fileMetadata = FileMetadata(
                fileId = fileId,
                path = path,
                sizeBytes = data.size.toLong(),
                owner = effectiveOwner,
                recipients = effectiveRecipients,
                accessScope = accessScope,
                createdAt = System.currentTimeMillis()
            )
            fileMetadataStore[fileId] = fileMetadata
            betaLogger.log(
                LogLevel.DEBUG,
                "DistributedStorage",
                "File metadata stored for $fileId: owner=$effectiveOwner, " +
                "accessScope=$accessScope, recipients=${effectiveRecipients.size}"
            )

            updateStorageStats()
            betaLogger.log(LogLevel.DEBUG, "Storage", "Write operation completed: $path")
            onFileStored?.invoke(fileId, file)
            
            // Return FileReference for compatibility
            FileReference(fileId, path, data.size.toLong())
    }

    private fun readChunkBytes(file: File, chunk: MeshChunk): ByteArray {
        val fis = FileInputStream(file)
        fis.skip(chunk.chunkIndex * chunk.chunkSize)
        val buffer = ByteArray(chunk.chunkSize.toInt())
        val bytesRead = fis.read(buffer)
        fis.close()
        return if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
    }

    suspend fun retrieveFile(fileRef: FileReference): ByteArray? = coroutinescape {
        betaLogger.log(LogLevel.DEBUG, "Storage", "Read operation started: ${fileRef.path}")

        // Try reading from local file first
        val file = File(fileRef.path)
        val localData = if (file.exists()) file.readBytes() else null
        
        if (localData != null) {
            betaLogger.log(LogLevel.DEBUG, "Storage", "File retrieved from local storage: ${fileRef.path}")
            
            // Update last accessed time in StagedSyncManager
            stagedSyncManager.updateLastAccessed(fileRef.path)
            
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
            onFileRetrieved?.invoke(fileRef.id, File(fileRef.path))
            return@coroutineScope decryptedData
        }

        if (_participationEnabled.value) {
            betaLogger.log(LogLevel.DEBUG, "Storage", "Attempting mesh retrieval: ${fileRef.path}")
            val chunks = getFileChunks(fileRef.id)
            val retrievedChunks = Array<ByteArray?>(chunks.size) { null }
            val jobs = chunks.map { chunk ->
                async {
                    val connection = try {
                        connectionPool.acquireConnection(timeoutMs = RESPONSE_TIMEOUT_MS)
                    } catch (e: Exception) {
                        null
                    }
                    if (connection != null) {
                        try {
                            val chunkInfoList = pendingChunkRetrievals[chunk.chunkId] ?: emptyList()
                            val nodeIds = chunkInfoList.map { it.nodeId }
                            for (nodeId in nodeIds) {
                                val msgPackBytes = connection.meshNetworkInterface.requestChunkFromNode(nodeId, chunk.chunkId)
                                if (msgPackBytes != null) {
                                    val chunkMsg = MeshEcosystemMessage.fromBytes(msgPackBytes)
                                    if (chunkMsg is MeshEcosystemMessage.ChunkTransferMessage && chunkMsg.chunkId == chunk.chunkId) {
                                        retrievedChunks[chunk.chunkIndex] = chunkMsg.chunkBytes
                                        break
                                    }
                                }
                            }
                        } finally {
                            connectionPool.releaseConnection(connection)
                        }
                    } else {
                        betaLogger.log(LogLevel.ERROR, TAG, "No available connection for chunk retrieval")
                    }
                }
            }
            jobs.forEach { it.await() }

            if (retrievedChunks.any { it == null }) {
                betaLogger.log(LogLevel.ERROR, TAG, "Failed to retrieve all chunks for file ${fileRef.id}")
                return@coroutineScope null
            }

            val fileBytes = retrievedChunks.filterNotNull().reduce { acc, bytes -> acc + bytes }
            betaLogger.log(LogLevel.INFO, TAG, "File ${fileRef.id} reassembled from mesh")
            return@coroutineScope fileBytes
        } else {
            betaLogger.log(LogLevel.WARN, "Storage", "File not found locally and mesh participation disabled: ${fileRef.path}")
            return@coroutineScope null
        }
    }

    private fun getFileChunks(fileId: String): List<MeshChunk> {
        // Get file metadata from StagedSyncManager
        val syncedFile = stagedSyncManager.getSyncedFileByFileId(fileId)
        if (syncedFile == null) {
            betaLogger.log(LogLevel.WARN, TAG, "No synced file found for fileId: $fileId")
            return emptyList()
        }
        
        // Use stored chunkIds from StagedSyncManager
        return syncedFile.chunkIds.mapIndexed { idx, chunkId ->
            MeshChunk(
                chunkId = chunkId,
                fileId = fileId,
                chunkIndex = idx,
                totalChunks = syncedFile.chunkIds.size,
                chunkSize = MeshrabiyaConstants.getChunkSizeKb() * 1024L,
                fileName = File(syncedFile.filePath).name,
                relativePath = "",
                hash = chunkId
            )
        }
    }

    fun addMeshChunk(chunk: MeshChunk) = storageDataStore.addMeshChunk(chunk)
    fun getMeshChunk(chunkId: String): MeshChunk? = storageDataStore.getMeshChunk(chunkId)
    fun getAllMeshChunks(): List<MeshChunk> = storageDataStore.getAllMeshChunks()

    data class StorageConfiguration(
        val defaultReplicationFactor: Int = 3,
        val encryptionEnabled: Boolean = true,
        val compressionEnabled: Boolean = true,
        val maxFileSize: Long = 100L * 1024 * 1024,
        val defaultQuota: Long = 1L * 1024 * 1024 * 1024
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

    // Removed: DistributedFileInfo - now using StagedSyncManager.SyncedFile
    
    /**
     * Get file metadata by fileId
     * 
     * @param fileId File ID (SHA-256 hash)
     * @return FileMetadata if exists, null otherwise
     */
    fun getFileMetadata(fileId: String): FileMetadata? {
        return fileMetadataStore[fileId]
    }
    
    /**
     * DTO for network transport of file metadata.
     * Used by interop layer for serialization only.
     */
    data class FileTransportDTO(
        val path: String,
        val fileId: String,
        val replicationLevel: ReplicationLevel,
        val priority: SyncPriority,
        val createdAt: Long,
        val lastAccessed: Long,
        val meshReferences: List<String> = emptyList(),
        val checksum: String = ""
    )

    class StorageQuotaExceededException(message: String) : Exception(message)

    // TODO: Implement findBestStorageNodesForChunk, updateStorageStats, ReplicationTracker, StorageQuotaManager, StorageEncryptionManager, StorageDataStore, etc.
}