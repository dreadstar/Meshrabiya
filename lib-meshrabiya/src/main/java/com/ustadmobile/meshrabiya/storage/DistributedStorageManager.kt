
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
import com.ustadmobile.meshrabiya.vnet.StorageNodeRequest
import com.ustadmobile.meshrabiya.vnet.StorageNodeResponse
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.vnet.FileReference
import com.ustadmobile.meshrabiya.vnet.ReplicationLevel
import com.ustadmobile.meshrabiya.vnet.SyncPriority
import com.ustadmobile.meshrabiya.vnet.StorageOperation
import com.ustadmobile.meshrabiya.storage.StorageDataStore
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import org.msgpack.core.MessagePack
import org.msgpack.core.MessageBufferPacker
import org.msgpack.core.MessageUnpacker
import org.bouncycastle.openpgp.PGPPublicKey

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
    }

    private val stagedSyncManager = StagedSyncManager(context, this, meshNetworkInterface)
    private val storageQuotaManager = StorageQuotaManager(context, storageConfig)
    private val encryptionManager = StorageEncryptionManager()
    private val betaLogger = BetaTestLogger.getInstance(context)

    private val _storageStats = MutableStateFlow(StorageStats())
    val storageStats: StateFlow<StorageStats> = _storageStats.asStateFlow()
    private val _participationEnabled = MutableStateFlow(false)
    val participationEnabled: StateFlow<Boolean> = _participationEnabled.asStateFlow()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val distributedFiles = ConcurrentHashMap<String, DistributedFileInfo>()
    private val replicationTracker = ReplicationTracker()
    private val chunkReplicaTracker = ConcurrentHashMap<String, MutableSet<String>>()
    private val storageDataStore: StorageDataStore = StorageDataStore.getInstance(context)

    // Ecosystem listener integration
    private var meshEcosystemListener: MeshEcosystemListener? = null

    // Track outstanding storage node requests for broadcast/response lifecycle
    private val pendingStorageNodeRequests = ConcurrentLinkedQueue<PendingStorageNodeRequest>()
    private val pendingChunkRetrievals = ConcurrentHashMap<String, MutableList<ChunkRetrievalResponse>>()
    private val pendingReplicaResponses = ConcurrentHashMap<String, MutableList<ReplicaResponse>>()
    private val pendingPermissionConfirmations = ConcurrentHashMap<String, MutableList<MeshGossipService.FilePermissionUpdateConfirmation>>()

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
    fun handleChunkRetrievalResponse(senderId: Int, response: ChunkRetrievalResponse) {
        val chunkId = response.chunkId
        pendingChunkRetrievals.computeIfAbsent(chunkId) { mutableListOf() }.add(response)
        // Optionally: trigger retrieval continuation or update state
    }

    /**
     * Handles replica responses.
     * Used to track replica state and health for files/chunks.
     */
    fun handleReplicaResponse(senderId: Int, response: ReplicaResponse) {
        val fileId = response.fileId
        pendingReplicaResponses.computeIfAbsent(fileId) { mutableListOf() }.add(response)
        replicationTracker.updateReplicaState(fileId, response)
    }

    /**
     * Handles permission update confirmations.
     * Used to track confirmation status for permission updates.
     */
    fun handlePermissionUpdateConfirmation(confirmation: MeshGossipService.FilePermissionUpdateConfirmation) {
        val fileId = confirmation.fileId
        pendingPermissionConfirmations.computeIfAbsent(fileId) { mutableListOf() }.add(confirmation)
        // Optionally: update permission state or trigger next lifecycle step
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

    // === MessagePack Serialization Helpers ===

    private fun serializeChunkTransfer(chunk: MeshChunk, chunkBytes: ByteArray): ByteArray {
        val packer: MessageBufferPacker = MessagePack.newDefaultBufferPacker()
        packer.packString("ChunkTransfer")
        packer.packString(chunk.chunkId)
        packer.packString(chunk.fileId)
        packer.packInt(chunk.chunkIndex)
        packer.packInt(chunk.totalChunks)
        packer.packString(chunk.fileName)
        packer.packString(chunk.relativePath)
        packer.packBinaryHeader(chunkBytes.size)
        packer.writePayload(chunkBytes)
        packer.packString(chunk.hash)
        packer.close()
        return packer.toByteArray()
    }

    private fun deserializeChunkTransfer(bytes: ByteArray): MeshGossipService.ChunkTransferMessage? {
        val unpacker: MessageUnpacker = MessagePack.newDefaultUnpacker(bytes)
        val type = unpacker.unpackString()
        if (type != "ChunkTransfer") {
            unpacker.close()
            return null
        }
        val chunkId = unpacker.unpackString()
        val fileId = unpacker.unpackString()
        val chunkIndex = unpacker.unpackInt()
        val totalChunks = unpacker.unpackInt()
        val fileName = unpacker.unpackString()
        val relativePath = unpacker.unpackString()
        val chunkSize = unpacker.unpackBinaryHeader()
        val chunkBytes = ByteArray(chunkSize)
        unpacker.readPayload(chunkBytes)
        val hash = unpacker.unpackString()
        unpacker.close()
        return MeshGossipService.ChunkTransferMessage(
            chunkId, fileId, chunkIndex, totalChunks, fileName, relativePath, chunkBytes, hash
        )
    }

    // === PUBLIC API ===

    suspend fun storeFile(
        path: String,
        data: ByteArray,
        priority: SyncPriority = SyncPriority.NORMAL,
        replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD
    ): FileReference? = coroutineScope {
        betaLogger.log(LogLevel.DEBUG, "Storage", "Write operation started: $path (${data.size} bytes)")

        if (!storageQuotaManager.canStoreFile(data.size.toLong())) {
            betaLogger.log(LogLevel.WARN, "Storage", "Storage quota exceeded for file: $path (${data.size} bytes)")
            throw StorageQuotaExceededException("Insufficient storage quota")
        }

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

            val file = File(path)
            val fileId = sha256File(file)
            val chunkSize = MeshrabiyaConstants.getChunkSizeKb() * 1024
            val chunks = chunkFile(file, fileId, chunkSize)
            val desiredReplicas = when (replicationLevel) {
                ReplicationLevel.MINIMAL -> 1
                ReplicationLevel.STANDARD -> 3
                ReplicationLevel.HIGH -> 5
                ReplicationLevel.CRITICAL -> 7
            }
            chunkReplicaTracker.clear()
            val jobs = chunks.map { chunk ->
                async {
                    val candidateNodes = findBestStorageNodesForChunk(chunk, fileId, desiredReplicas)
                    chunkReplicaTracker[chunk.chunkId] = mutableSetOf()
                    val msgPackBytes = serializeChunkTransfer(chunk, readChunkBytes(file, chunk))
                    candidateNodes.forEach { nodeId ->
                        val connection = try {
                            connectionPool.acquireConnection(timeoutMs = RESPONSE_TIMEOUT_MS)
                        } catch (e: Exception) {
                            null
                        }
                        if (connection != null) {
                            try {
                                connection.meshNetworkInterface.sendChunkToNode(nodeId, chunk, msgPackBytes)
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

            val fileInfo = DistributedFileInfo(
                path = path,
                localReference = localRef,
                replicationLevel = replicationLevel,
                priority = priority,
                createdAt = System.currentTimeMillis(),
                lastAccessed = System.currentTimeMillis(),
                meshReferences = chunkReplicaTracker.values.flatten().toList()
            )
            distributedFiles[path] = fileInfo

            if (_participationEnabled.value) {
                betaLogger.log(LogLevel.DEBUG, "Storage", "Queuing file for mesh distribution: $path")
                queueForMeshDistribution(fileInfo)
            }

            updateStorageStats()
            betaLogger.log(LogLevel.DEBUG, "Storage", "Write operation completed: $path")
            onFileStored?.invoke(fileId, file)
            FileReference(localRef.id, path, data.size.toLong())
        } else {
            betaLogger.log(LogLevel.ERROR, "Storage", "Failed to store file locally: $path")
            null
        }
    }

    private fun readChunkBytes(file: File, chunk: MeshChunk): ByteArray {
        val fis = FileInputStream(file)
        fis.skip(chunk.chunkIndex * chunk.chunkSize)
        val buffer = ByteArray(chunk.chunkSize.toInt())
        val bytesRead = fis.read(buffer)
        fis.close()
        return if (bytesRead < buffer.size) buffer.copyOf(bytesRead) else buffer
    }

    suspend fun retrieveFile(fileRef: FileReference): ByteArray? = coroutineScope {
        betaLogger.log(LogLevel.DEBUG, "Storage", "Read operation started: ${fileRef.path}")

        val localData = stagedSyncManager.readFile(fileRef.path)
        if (localData != null) {
            betaLogger.log(LogLevel.DEBUG, "Storage", "File retrieved from local storage: ${fileRef.path}")
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
                                    val chunkMsg = deserializeChunkTransfer(msgPackBytes)
                                    if (chunkMsg != null && chunkMsg.chunkId == chunk.chunkId) {
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
        return distributedFiles[fileId]?.meshReferences?.mapIndexed { idx, _ ->
            MeshChunk(
                chunkId = "$fileId-chunk$idx",
                fileId = fileId,
                chunkIndex = idx,
                totalChunks = distributedFiles[fileId]?.meshReferences?.size ?: 0,
                chunkSize = MeshrabiyaConstants.getChunkSizeKb() * 1024L,
                fileName = File(distributedFiles[fileId]?.path ?: "").name,
                relativePath = "",
                hash = "$fileId-chunk$idx"
            )
        } ?: emptyList()
    }

    fun addMeshChunk(chunk: MeshChunk) = storageDataStore.addMeshChunk(chunk)
    fun getMeshChunk(chunkId: String): MeshChunk? = storageDataStore.getMeshChunk(chunkId)
    fun getAllMeshChunks(): List<MeshChunk> = storageDataStore.getAllMeshChunks()

    /**
     * Handles inbound chunk/file transfer events using connection pool.
     * Called by MeshEcosystemListener.
     */
    fun handleIncomingChunkTransfer(senderId: Int, chunk: MeshGossipService.ChunkTransferMessage) {
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

    // ...existing code for permission updates, stats, monitoring, etc...

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

    data class DistributedFileInfo(
        val path: String,
        val localReference: FileReference,
        val replicationLevel: ReplicationLevel,
        val priority: SyncPriority,
        val createdAt: Long,
        val lastAccessed: Long,
        val meshReferences: List<String> = emptyList()
    )

    class StorageQuotaExceededException(message: String) : Exception(message)
}