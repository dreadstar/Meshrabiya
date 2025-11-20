package com.ustadmobile.meshrabiya.storage

import kotlinx.coroutines.*
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.MeshConnectionPool
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import com.ustadmobile.meshrabiya.service.StorageNodeRequest
import com.ustadmobile.meshrabiya.service.ChunkTransferMessage
import com.ustadmobile.meshrabiya.vnet.MeshChunk
import com.ustadmobile.meshrabiya.service.StorageNodeResponse
import com.ustadmobile.meshrabiya.service.ChunkRetrievalResponse
import com.ustadmobile.meshrabiya.service.ReplicaResponse
import com.ustadmobile.meshrabiya.MeshrabiyaConstants

/**
 * DistributedStorageClient: Client-side workflows for distributed storage.
 * 
 * Responsibilities:
 * - Initiate file storage (Workflow 1)
 * - Initiate file retrieval (Workflow 2)
 * - Initiate permission updates (Workflow 3)
 * - Broadcast requests and collect responses
 * - Select storage nodes
 * - Prepare and encrypt chunks
 */
class DistributedStorageClient(
    private val manager: DistributedStorageManager,
    private val virtualNode: VirtualNode,
    private val coreGossipBroadcastService: CoreGossipBroadcastService,
    private val connectionPool: MeshConnectionPool
) {
    companion object {
        private const val TAG = "DistributedStorageClient"
        private const val RESPONSE_TIMEOUT_MS = 5000L
        private const val RETRY_DELAY_MS = 10000L
        private const val MAX_RETRIES = 3
    }
    
    // === Request Tracking ===
    
    private val pendingStorageNodeRequests = ConcurrentLinkedQueue<PendingStorageNodeRequest>()
    private val pendingChunkRetrievals = ConcurrentHashMap<String, MutableList<ChunkRetrievalResponse>>()
    private val pendingReplicaResponses = ConcurrentHashMap<String, MutableList<ReplicaResponse>>()
    private val pendingPermissionConfirmations = ConcurrentHashMap<String, MutableList<com.ustadmobile.meshrabiya.service.FilePermissionUpdateConfirmationMessage>>()
    
    data class PendingStorageNodeRequest(
        val request: StorageNodeRequest,
        val chunk: MeshChunk,
        val fileId: String,
        val desiredReplicas: Int,
        val responses: MutableList<StorageNodeResponse> = mutableListOf(),
        var retries: Int = 0,
        val completion: CompletableDeferred<List<StorageNodeResponse>> = CompletableDeferred()
    )
    
    // === Response Handlers ===
    
    fun handleStorageNodeResponse(requestId: String, senderId: Int, response: StorageNodeResponse) {
        pendingStorageNodeRequests.forEach { pending ->
            if (pending.request.fileId == response.fileId) {
                pending.responses.add(response)
            }
        }
        
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received storage node response for request $requestId from node $senderId (fileId=${response.fileId})"
        )
    }
    
    fun handleChunkRetrievalResponse(requestId: String, senderId: Int, response: ChunkRetrievalResponse) {
        val chunkId = response.chunkId
        pendingChunkRetrievals.computeIfAbsent(chunkId) { mutableListOf() }.add(response)
        
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received chunk retrieval response for request $requestId from node $senderId (chunkId=$chunkId)"
        )
    }
    
    fun handleReplicaResponse(requestId: String, senderId: Int, response: ReplicaResponse) {
        val fileId = response.fileId
        pendingReplicaResponses.computeIfAbsent(fileId) { mutableListOf() }.add(response)
        
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received replica response for request $requestId from node $senderId (fileId=$fileId)"
        )
    }
    
    fun handlePermissionUpdateConfirmation(confirmation: com.ustadmobile.meshrabiya.service.FilePermissionUpdateConfirmationMessage) {
        val fileId = confirmation.fileId
        pendingPermissionConfirmations.computeIfAbsent(fileId) { mutableListOf() }.add(confirmation)
    }
    
    // === Client Workflows ===
    
    /**
     * Store file with permission parameters.
     * Canonical workflow Step 1: Client-side file preparation and distribution.
     */
    suspend fun storeFile(
        path: String,
        data: ByteArray,
        priority: SyncPriority = SyncPriority.NORMAL,
        replicationLevel: ReplicationLevel = ReplicationLevel.STANDARD,
        owner: String? = null,
        recipients: List<RecipientEntry>? = null
    ): FileReference? = coroutineScope {
        manager.betaLogger.log(LogLevel.DEBUG, TAG, "Write operation started: $path (${data.size} bytes)")

        if (!manager.storageQuotaManager.canStoreFile(data.size.toLong())) {
            manager.betaLogger.log(LogLevel.WARN, TAG, "Storage quota exceeded for file: $path (${data.size} bytes)")
            throw DistributedStorageManager.StorageQuotaExceededException("Insufficient storage quota")
        }

        // Canonical workflow: Prepare permission metadata
        val effectiveOwner = owner ?: virtualNode.addressAsInt.toString()
        val effectiveRecipients = recipients ?: listOf(
            RecipientEntry(
                publicKey = effectiveOwner,
                recipientType = RecipientType.USER
            )
        )
        
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Starting file storage: $path, size=${data.size}B, owner=$effectiveOwner, recipients=${effectiveRecipients.size}"
        )

        // Store unencrypted data temporarily for chunking
        val file = File(path)
        file.parentFile?.mkdirs()
        FileOutputStream(file).use { it.write(data) }
        
        val fileId = manager.sha256File(file)
        val chunkSize = MeshrabiyaConstants.getChunkSizeKb() * 1024
        val chunks = manager.chunkFile(file, fileId, chunkSize)
        
        val desiredReplicas = when (replicationLevel) {
            ReplicationLevel.MINIMAL -> MeshrabiyaConstants.getMinimalReplicaCount()
            ReplicationLevel.STANDARD -> MeshrabiyaConstants.getStandardReplicaCount()
            ReplicationLevel.HIGH -> MeshrabiyaConstants.getHighReplicaCount()
            ReplicationLevel.CRITICAL -> MeshrabiyaConstants.getCriticalReplicaCount()
        }
        
        manager.chunkReplicaTracker.clear()
        
        // Process each chunk with single-node selection and targeted encryption
        val jobs = chunks.map { chunk ->
            async {
                // Step 1: Broadcast StorageNodeRequest
                val storageNodeResponses = broadcastStorageNodeRequest(chunk, fileId, desiredReplicas)
                
                // Step 2: Select single best storage node
                val selectedNode = selectBestStorageNode(storageNodeResponses)
                
                if (selectedNode != null) {
                    // Step 3: Read chunk bytes
                    val chunkBytes = manager.readChunkBytes(file, chunk)
                    
                    // Step 4: Encrypt chunk including storage node's service public key
                    val allRecipients = effectiveRecipients + RecipientEntry(
                        publicKey = selectedNode.servicePublicKey.toString(Charsets.ISO_8859_1),
                        recipientType = RecipientType.USER
                    )
                    
                    manager.betaLogger.log(
                        LogLevel.DEBUG,
                        TAG,
                        "Encrypting chunk ${chunk.chunkId} for ${allRecipients.size} recipients (including storage node)"
                    )
                    
                    val encryptedChunkBytes = manager.encryptionManager.encryptWithRecipients(
                        data = chunkBytes,
                        owner = effectiveOwner,
                        recipients = allRecipients.map { it.publicKey }
                    )
                    
                    // Step 5: Create ChunkTransferMessage
                    val chunkMsg = ChunkTransferMessage(
                        chunkId = chunk.chunkId,
                        fileId = chunk.fileId,
                        chunkIndex = chunk.chunkIndex,
                        totalChunks = chunk.totalChunks,
                        fileName = chunk.fileName,
                        relativePath = chunk.relativePath,
                        chunkBytes = encryptedChunkBytes,
                        hash = chunk.hash,
                        replicaCount = 0,
                        recipientKeyIds = allRecipients.map { it.publicKey.hashCode().toLong() },
                        sessionKeys = emptyMap(),
                        desiredReplicas = desiredReplicas
                    )
                    
                    // Step 6: Send chunk to selected storage node
                    // TODO: Implement actual message sending
                    manager.chunkReplicaTracker[chunk.chunkId] = mutableSetOf()
                    manager.chunkReplicaTracker[chunk.chunkId]?.add(selectedNode.nodeId)
                    
                    manager.betaLogger.log(
                        LogLevel.INFO,
                        TAG,
                        "Chunk ${chunk.chunkId} prepared for node ${selectedNode.nodeId} (replica 0/${desiredReplicas})"
                    )
                } else {
                    manager.betaLogger.log(LogLevel.WARN, TAG, "No storage node available for chunk ${chunk.chunkId}")
                }
            }
        }
        jobs.forEach { it.await() }

        // Register with StagedSyncManager
        val targetReplicaCount = when (replicationLevel) {
            ReplicationLevel.MINIMAL -> MeshrabiyaConstants.getMinimalReplicaCount()
            ReplicationLevel.STANDARD -> MeshrabiyaConstants.getStandardReplicaCount()
            ReplicationLevel.HIGH -> MeshrabiyaConstants.getHighReplicaCount()
            ReplicationLevel.CRITICAL -> MeshrabiyaConstants.getCriticalReplicaCount()
        }
        
        manager.stagedSyncManager.registerForSync(
            filePath = path,
            fileId = fileId,
            size = data.size.toLong(),
            priority = priority,
            targetReplicaCount = targetReplicaCount
        )
        
        manager.stagedSyncManager.updateChunkIds(path, chunks.map { it.chunkId })
        manager.stagedSyncManager.updateMeshNodeIds(path, manager.chunkReplicaTracker.values.flatten().toList())

        // Store metadata
        val fileMetadata = FileMetadata(
            fileId = fileId,
            path = path,
            sizeBytes = data.size.toLong(),
            owner = effectiveOwner,
            recipients = effectiveRecipients,
            createdAt = System.currentTimeMillis()
        )
        manager.fileMetadataStore[fileId] = fileMetadata
        
        manager.updateStorageStats()
        manager.betaLogger.log(LogLevel.DEBUG, TAG, "Write operation completed: $path")
        manager.onFileStored?.invoke(fileId, file)
        
        FileReference(fileId, path, data.size.toLong())
    }
    
    /**
     * Retrieve file from distributed storage.
     * Canonical workflow Step 2: Client-side file retrieval.
     */
    suspend fun retrieveFile(fileRef: FileReference): ByteArray? = coroutineScope {
        manager.betaLogger.log(LogLevel.DEBUG, TAG, "Read operation started: ${fileRef.path}")

        // Try local file first
        val file = File(fileRef.path)
        val localData = if (file.exists()) file.readBytes() else null
        
        if (localData != null) {
            manager.betaLogger.log(LogLevel.DEBUG, TAG, "File retrieved from local storage: ${fileRef.path}")
            manager.stagedSyncManager.updateLastAccessed(fileRef.path)
            
            val decryptedData = manager.encryptionManager.decrypt(localData)
            manager.betaLogger.log(LogLevel.DEBUG, TAG, "Read operation completed: ${fileRef.path}")
            manager.onFileRetrieved?.invoke(fileRef.id, File(fileRef.path))
            return@coroutineScope decryptedData
        }

        if (manager.participationEnabled.value) {
            manager.betaLogger.log(LogLevel.DEBUG, TAG, "Attempting mesh retrieval: ${fileRef.path}")
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
                            // TODO: Implement chunk retrieval via broadcast
                        } finally {
                            connectionPool.releaseConnection(connection)
                        }
                    }
                }
            }
            jobs.forEach { it.await() }

            if (retrievedChunks.any { it == null }) {
                manager.betaLogger.log(LogLevel.ERROR, TAG, "Failed to retrieve all chunks for file ${fileRef.id}")
                return@coroutineScope null
            }

            val fileBytes = retrievedChunks.filterNotNull().reduce { acc, bytes -> acc + bytes }
            manager.betaLogger.log(LogLevel.INFO, TAG, "File ${fileRef.id} reassembled from mesh")
            return@coroutineScope fileBytes
        } else {
            manager.betaLogger.log(LogLevel.WARN, TAG, "File not found locally and mesh participation disabled: ${fileRef.path}")
            return@coroutineScope null
        }
    }
    
    /**
     * Update file access permissions.
     * Canonical workflow Step 3: Client-side permission update initiation.
     */
    suspend fun updateFileAccess(
        fileId: String,
        addRecipients: List<RecipientEntry> = emptyList(),
        removeRecipients: List<String> = emptyList()
    ): Boolean = withContext(Dispatchers.IO) {
        val metadata = manager.fileMetadataStore[fileId] ?: return@withContext false
        
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Updating file access for $fileId: +${addRecipients.size} recipients, -${removeRecipients.size} recipients"
        )
        
        // Build updated recipient list
        val updatedRecipients = metadata.recipients
            .filter { it.publicKey !in removeRecipients }
            .toMutableList()
            .apply { addAll(addRecipients) }
        
        // Update metadata
        val updatedMetadata = metadata.copy(recipients = updatedRecipients)
        manager.fileMetadataStore[fileId] = updatedMetadata
        
        manager.betaLogger.log(
            LogLevel.INFO,
            TAG,
            "File access updated for $fileId: ${updatedRecipients.size} total recipients"
        )
        
        // TODO: Broadcast FilePermissionUpdateMessage to storage nodes
        
        true
    }
    
    // === Helper Methods ===
    
    private suspend fun broadcastStorageNodeRequest(
        chunk: MeshChunk,
        fileId: String,
        desiredReplicas: Int
    ): List<StorageNodeResponse> = coroutineScope {
        val requestId = "${fileId}_${chunk.chunkIndex}_${System.currentTimeMillis()}"
        val request = StorageNodeRequest(
            requestId = requestId,
            chunkId = chunk.chunkId,
            chunkIndex = chunk.chunkIndex,
            fileId = fileId,
            desiredReplicas = desiredReplicas,
            chunkSizeBytes = chunk.chunkSize,
            replicaCount = 0,
            requiredSpace = chunk.chunkSize,
            fileName = chunk.fileName,
            senderId = virtualNode.addressAsInt.toString()
        )
        
        val pending = PendingStorageNodeRequest(
            request = request,
            chunk = chunk,
            fileId = fileId,
            desiredReplicas = desiredReplicas
        )
        pendingStorageNodeRequests.add(pending)
        
        coreGossipBroadcastService.sendStorageNodeRequest(request)
        
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Broadcasted StorageNodeRequest for chunk ${chunk.chunkId} (requestId=$requestId)"
        )
        
        // Wait for responses
        val startTime = System.currentTimeMillis()
        while (System.currentTimeMillis() - startTime < RESPONSE_TIMEOUT_MS) {
            if (pending.responses.isNotEmpty()) {
                break
            }
            delay(100)
        }
        
        pendingStorageNodeRequests.remove(pending)
        
        manager.betaLogger.log(
            LogLevel.DEBUG,
            TAG,
            "Received ${pending.responses.size} StorageNodeResponses for chunk ${chunk.chunkId}"
        )
        
        pending.responses.toList()
    }
    
    private fun selectBestStorageNode(
        responses: List<StorageNodeResponse>
    ): StorageNodeResponse? {
        if (responses.isEmpty()) {
            manager.betaLogger.log(LogLevel.WARN, TAG, "No storage node responses available")
            return null
        }
        
        val suitableNodes = responses.filter { response ->
            response.availableSpace > 0 && response.systemState == "HEALTHY"
        }
        
        if (suitableNodes.isEmpty()) {
            manager.betaLogger.log(LogLevel.WARN, TAG, "No suitable storage nodes (all full or unhealthy)")
            return null
        }
        
        val bestNode = suitableNodes.maxWithOrNull(
            compareBy<StorageNodeResponse> { it.fitnessScore }
                .thenBy { -it.latency }
        )
        
        bestNode?.let {
            manager.betaLogger.log(
                LogLevel.INFO,
                TAG,
                "Selected storage node ${it.nodeId}: fitness=${it.fitnessScore}, latency=${it.latency}ms"
            )
        }
        
        return bestNode
    }
    
    private fun getFileChunks(fileId: String): List<MeshChunk> {
        val syncedFile = manager.stagedSyncManager.getSyncedFileByFileId(fileId)
        if (syncedFile == null) {
            manager.betaLogger.log(LogLevel.WARN, TAG, "No synced file found for fileId: $fileId")
            return emptyList()
        }
        
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
}