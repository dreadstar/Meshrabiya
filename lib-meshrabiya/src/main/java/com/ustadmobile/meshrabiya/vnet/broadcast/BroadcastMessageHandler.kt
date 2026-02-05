package com.ustadmobile.meshrabiya.vnet.broadcast

import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.api.model.BroadcastResultDto
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.VirtualNode
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.VirtualPacketHeader
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import android.util.Log

/**
 * Centralized handler for broadcast message+file operations
 * Manages both sending and receiving broadcasts
 */
class BroadcastMessageHandler(
    private val virtualNode: VirtualNode,
    private val logger: MNetLogger,
    private val cacheDir: File,
    private val getDropFolderCallback: () -> File?
) {
    
    private val outgoingBroadcasts = ConcurrentHashMap<String, OutgoingBroadcastState>()
    private val incomingBroadcasts = ConcurrentHashMap<String, IncomingBroadcastState>()
    private val executor = Executors.newSingleThreadExecutor()
    
    // Callbacks for received broadcasts
    private val receiveListeners = mutableListOf<(com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit>()
    
    companion object {
        private const val TAG = "BroadcastMessageHandler"
    }
    
    /**
     * Register a listener for received broadcasts
     */
    fun addReceiveListener(listener: (com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit) {
        synchronized(receiveListeners) {
            receiveListeners.add(listener)
        }
    }
    
    /**
     * Unregister a listener
     */
    fun removeReceiveListener(listener: (com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit) {
        synchronized(receiveListeners) {
            receiveListeners.remove(listener)
        }
    }
    
    /**
     * Send a broadcast message+file to all mesh nodes
     * 
     * @param messageText The text message to broadcast
     * @param filePath Path to the file to broadcast
     * @param callback Completion callback with result
     */
    fun sendBroadcast(
        messageText: String,
        filePath: String,
        callback: (Result<BroadcastResultDto>) -> Unit
    ) {
        executor.execute {
            try {
                logger(Log.INFO, "$TAG Starting broadcast: message='$messageText', file='$filePath'")
                
                val file = File(filePath)
                require(file.exists()) { "File does not exist: $filePath" }
                require(file.canRead()) { "Cannot read file: $filePath" }
                
                val broadcastId = UUID.randomUUID().toString()
                val fileId = UUID.randomUUID().toString()
                val fileBytes = file.readBytes()
                
                // Calculate chunks
                val totalChunks = (fileBytes.size + MeshrabiyaConstants.BROADCAST_CHUNK_SIZE - 1) / 
                                 MeshrabiyaConstants.BROADCAST_CHUNK_SIZE
                
                logger(Log.DEBUG, "$TAG Broadcast $broadcastId: file size=${fileBytes.size}, chunks=$totalChunks")
                
                // Register outgoing state
                val state = OutgoingBroadcastState(
                    broadcastId = broadcastId,
                    messageText = messageText,
                    fileId = fileId,
                    fileName = file.name,
                    totalChunks = totalChunks,
                    callback = callback
                )
                outgoingBroadcasts[broadcastId] = state
                
                // Send each chunk
                for (chunkIndex in 0 until totalChunks) {
                    val startOffset = chunkIndex * MeshrabiyaConstants.BROADCAST_CHUNK_SIZE
                    val endOffset = minOf(startOffset + MeshrabiyaConstants.BROADCAST_CHUNK_SIZE, fileBytes.size)
                    val chunkData = fileBytes.sliceArray(startOffset until endOffset)
                    
                    // Calculate hash for this chunk
                    val md = MessageDigest.getInstance("SHA-256")
                    val hash = md.digest(chunkData).joinToString("") { "%02x".format(it) }
                    
                    // Create chunk metadata
                    val metadata = BroadcastChunkMetadata(
                        chunkId = UUID.randomUUID().toString(),
                        fileId = fileId,
                        fileName = file.name,
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        chunkSize = chunkData.size.toLong(),
                        totalFileSize = fileBytes.size.toLong(),
                        hash = hash
                    )
                    
                    // Serialize packet payload
                    val packetPayload = BroadcastPacketSerializer.serialize(
                        broadcastId = broadcastId,
                        messageText = messageText,
                        chunkMetadata = metadata,
                        chunkData = chunkData
                    )
                    
                    // Create packet data buffer with header space
                    val packetData = ByteArray(packetPayload.size + VirtualPacketHeader.HEADER_SIZE)
                    System.arraycopy(packetPayload, 0, packetData, VirtualPacketHeader.HEADER_SIZE, packetPayload.size)
                    
                    // Create VirtualPacket with broadcast addressing
                    val packet = VirtualPacket.fromHeaderAndPayloadData(
                        header = VirtualPacketHeader(
                            toAddr = VirtualPacket.ADDR_BROADCAST,
                            toPort = 0,  // MMCP port
                            fromAddr = virtualNode.addressAsInt,
                            fromPort = 0,
                            lastHopAddr = virtualNode.addressAsInt,
                            hopCount = 0,
                            maxHops = 10,
                            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
                            payloadSize = packetPayload.size
                        ),
                        data = packetData,
                        payloadOffset = VirtualPacketHeader.HEADER_SIZE
                    )
                    
                    // Send via VirtualNode
                    virtualNode.route(packet)
                    
                    state.chunksSent++
                    
                    logger(Log.DEBUG, "$TAG Broadcast $broadcastId: sent chunk $chunkIndex/$totalChunks")
                    
                    // Small delay between chunks to avoid overwhelming network
                    Thread.sleep(10)
                }
                
                // All chunks sent - return success
                logger(Log.INFO, "$TAG Broadcast $broadcastId: complete, all $totalChunks chunks sent")
                
                val result = BroadcastResultDto(
                    broadcastId = broadcastId,
                    messageText = messageText,
                    fileId = fileId,
                    fileName = file.name,
                    totalChunks = totalChunks,
                    successNodeIds = emptyList(),  // Best effort broadcast, no ACKs
                    failedNodeIds = emptyList(),
                    timestamp = System.currentTimeMillis()
                )
                
                callback(Result.success(result))
                outgoingBroadcasts.remove(broadcastId)
                
            } catch (e: Exception) {
                logger(Log.ERROR, "$TAG Broadcast send failed: ${e.message}", e)
                callback(Result.failure(e))
            }
        }
    }
    
    /**
     * Handle received broadcast packet
     * Called by VirtualNode when broadcast packet arrives
     */
    fun onReceiveBroadcastPacket(packet: VirtualPacket) {
        executor.execute {
            try {
                // Extract payload from packet data
                val payload = packet.data.copyOfRange(
                    packet.payloadOffset, 
                    packet.payloadOffset + packet.header.payloadSize
                )
                
                // Deserialize payload
                val (broadcastId, messageText, chunkPair) = BroadcastPacketSerializer.deserialize(payload)
                val (metadata, chunkData) = chunkPair
                
                logger(Log.DEBUG, "$TAG Received broadcast chunk: id=$broadcastId, chunk=${metadata.chunkIndex}/${metadata.totalChunks}")
                
                // Get or create incoming state
                val state = incomingBroadcasts.getOrPut(broadcastId) {
                    logger(Log.INFO, "$TAG New incoming broadcast: id=$broadcastId, file=${metadata.fileName}, totalChunks=${metadata.totalChunks}")
                    
                    IncomingBroadcastState(
                        broadcastId = broadcastId,
                        messageText = messageText,
                        metadata = metadata,
                        senderNodeId = packet.header.fromAddr
                    )
                }
                
                // Store chunk (validate hash)
                val md = MessageDigest.getInstance("SHA-256")
                val actualHash = md.digest(chunkData).joinToString("") { "%02x".format(it) }
                if (actualHash != metadata.hash) {
                    logger(Log.WARN, "$TAG Broadcast $broadcastId chunk ${metadata.chunkIndex}: hash mismatch, discarding")
                    return@execute
                }
                
                state.receivedChunks[metadata.chunkIndex] = chunkData
                
                logger(Log.DEBUG, "$TAG Broadcast $broadcastId: ${state.receivedChunks.size}/${metadata.totalChunks} chunks received")
                
                // Check if complete
                if (state.isComplete()) {
                    logger(Log.INFO, "$TAG Broadcast $broadcastId: all chunks received, reassembling")
                    
                    // Reassemble file
                    val fileBytes = state.reassemble()
                    
                    // Try to write to Shared/ folder
                    var filePath: String?
                    var hasError: Boolean
                    try {
                        filePath = writeBroadcastFile(state.metadata.fileName, fileBytes)
                        hasError = false
                        logger(Log.INFO, "$TAG Broadcast $broadcastId: complete, file written to $filePath")
                    } catch (e: IllegalStateException) {
                        // Drop folder not set - create error notification instead
                        filePath = null
                        hasError = true
                        logger(Log.WARN, "$TAG Broadcast $broadcastId: drop folder not set, file cannot be saved", e)
                    }
                    
                    // Notify listeners (with or without file path)
                    val notification = com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto(
                        broadcastId = broadcastId,
                        messageText = state.messageText,
                        fileId = state.metadata.fileId,
                        fileName = state.metadata.fileName,
                        filePath = filePath ?: "",  // Empty string if error
                        senderNodeId = state.senderNodeId,
                        receivedAt = System.currentTimeMillis(),
                        hasError = hasError,
                        errorMessage = if (hasError) "No storage folder set" else null
                    )
                    
                    synchronized(receiveListeners) {
                        receiveListeners.forEach { it(notification) }
                    }
                    
                    // Cleanup
                    incomingBroadcasts.remove(broadcastId)
                }
                
            } catch (e: Exception) {
                logger(Log.ERROR, "$TAG Failed to process broadcast packet: ${e.message}", e)
            }
        }
    }
    
    /**
     * Write received broadcast file to Shared/ folder in drop folder
     * Creates Shared/ subfolder if it doesn't exist
     * 
     * @return Absolute path to written file
     * @throws IllegalStateException if drop folder not selected
     */
    private fun writeBroadcastFile(fileName: String, fileBytes: ByteArray): String {
        val dropFolder = getDropFolderCallback() 
            ?: throw IllegalStateException("Drop folder not selected")
        
        val sharedFolder = File(dropFolder, "Shared")
        if (!sharedFolder.exists()) {
            logger(Log.INFO, "$TAG Creating Shared folder: ${sharedFolder.absolutePath}")
            sharedFolder.mkdirs()
        }
        
        require(sharedFolder.isDirectory) { "Shared path exists but is not a directory" }
        
        val outputFile = File(sharedFolder, fileName)
        
        // Handle duplicate filenames
        var finalFile = outputFile
        var counter = 1
        while (finalFile.exists()) {
            val nameWithoutExt = fileName.substringBeforeLast(".")
            val ext = fileName.substringAfterLast(".", "")
            finalFile = File(sharedFolder, "${nameWithoutExt}_$counter${if (ext.isNotEmpty()) ".$ext" else ""}")
            counter++
        }
        
        finalFile.writeBytes(fileBytes)
        
        logger(Log.INFO, "$TAG Wrote broadcast file: ${finalFile.absolutePath} (${fileBytes.size} bytes)")
        
        return finalFile.absolutePath
    }
    
    /**
     * Cleanup incomplete broadcasts older than timeout
     */
    fun cleanupStaleTransfers() {
        executor.execute {
            val now = System.currentTimeMillis()
            
            incomingBroadcasts.entries.removeIf { (id, state) ->
                if (now - state.startTime > MeshrabiyaConstants.BROADCAST_TIMEOUT_MS) {
                    logger(Log.WARN, "$TAG Broadcast $id timed out, received ${state.receivedChunks.size}/${state.metadata.totalChunks} chunks")
                    true
                } else {
                    false
                }
            }
        }
    }
    
    /**
     * Shutdown executor
     */
    fun shutdown() {
        executor.shutdown()
        executor.awaitTermination(5, TimeUnit.SECONDS)
    }
}
