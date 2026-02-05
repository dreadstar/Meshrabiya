package com.ustadmobile.meshrabiya.vnet.broadcast

import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import com.ustadmobile.meshrabiya.ext.BroadcastChunkMetadata
import com.ustadmobile.meshrabiya.ext.BroadcastResultDto
import com.ustadmobile.meshrabiya.log.LogLine
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
import android.util.Log as AndroidLog

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
    private val receiveListeners = mutableListOf<(com.ustadmobile.meshrabiya.ext.BroadcastReceivedDto) -> Unit>()
    
    companion object {
        private const val TAG = "BroadcastMessageHandler"
    }
    
    /**
     * Register a listener for received broadcasts
     */
    fun addReceiveListener(listener: (com.ustadmobile.meshrabiya.ext.BroadcastReceivedDto) -> Unit) {
        synchronized(receiveListeners) {
            receiveListeners.add(listener)
        }
    }
    
    /**
     * Unregister a listener
     */
    fun removeReceiveListener(listener: (com.ustadmobile.meshrabiya.ext.BroadcastReceivedDto) -> Unit) {
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
                logger(LogLine(
                    timestamp = System.currentTimeMillis(),
                    severity = AndroidLog.INFO,
                    tag = TAG,
                    message = "Starting broadcast: message='$messageText', file='$filePath'"
                ))
                
                val file = File(filePath)
                require(file.exists()) { "File does not exist: $filePath" }
                require(file.canRead()) { "Cannot read file: $filePath" }
                
                val broadcastId = UUID.randomUUID().toString()
                val fileId = UUID.randomUUID().toString()
                val fileBytes = file.readBytes()
                
                // Calculate chunks
                val totalChunks = (fileBytes.size + MeshrabiyaConstants.BROADCAST_CHUNK_SIZE - 1) / 
                                 MeshrabiyaConstants.BROADCAST_CHUNK_SIZE
                
                logger(LogLine(
                    timestamp = System.currentTimeMillis(),
                    severity = AndroidLog.DEBUG,
                    tag = TAG,
                    message = "Broadcast $broadcastId: file size=${fileBytes.size}, chunks=$totalChunks"
                ))
                
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
                    val payload = BroadcastPacketSerializer.serialize(
                        broadcastId = broadcastId,
                        messageText = messageText,
                        chunkMetadata = metadata,
                        chunkData = chunkData
                    )
                    
                    // Create VirtualPacket with broadcast addressing
                    val packet = VirtualPacket(
                        header = VirtualPacketHeader(
                            fromAddr = virtualNode.address,
                            toAddr = VirtualPacket.ADDR_BROADCAST,
                            fromPort = 0,
                            toPort = 0,  // MMCP port
                            hopCount = 0,
                            flags = 0,
                            packetId = System.nanoTime()
                        ),
                        payload = payload
                    )
                    
                    // Send via VirtualNode
                    virtualNode.route(packet)
                    
                    state.chunksSent++
                    
                    logger(LogLine(
                        timestamp = System.currentTimeMillis(),
                        severity = AndroidLog.DEBUG,
                        tag = TAG,
                        message = "Broadcast $broadcastId: sent chunk $chunkIndex/$totalChunks"
                    ))
                    
                    // Small delay between chunks to avoid overwhelming network
                    Thread.sleep(10)
                }
                
                // All chunks sent - return success
                logger(LogLine(
                    timestamp = System.currentTimeMillis(),
                    severity = AndroidLog.INFO,
                    tag = TAG,
                    message = "Broadcast $broadcastId: complete, all $totalChunks chunks sent"
                ))
                
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
                logger(LogLine(
                    timestamp = System.currentTimeMillis(),
                    severity = AndroidLog.ERROR,
                    tag = TAG,
                    message = "Broadcast send failed: ${e.message}"
                ))
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
                // Deserialize payload
                val (broadcastId, messageText, chunkPair) = BroadcastPacketSerializer.deserialize(packet.payload)
                val (metadata, chunkData) = chunkPair
                
                logger(LogLine(
                    timestamp = System.currentTimeMillis(),
                    severity = AndroidLog.DEBUG,
                    tag = TAG,
                    message = "Received broadcast chunk: id=$broadcastId, chunk=${metadata.chunkIndex}/${metadata.totalChunks}"
                ))
                
                // Get or create incoming state
                val state = incomingBroadcasts.getOrPut(broadcastId) {
                    logger(LogLine(
                        timestamp = System.currentTimeMillis(),
                        severity = AndroidLog.INFO,
                        tag = TAG,
                        message = "New incoming broadcast: id=$broadcastId, file=${metadata.fileName}, totalChunks=${metadata.totalChunks}"
                    ))
                    
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
                    logger(LogLine(
                        timestamp = System.currentTimeMillis(),
                        severity = AndroidLog.WARN,
                        tag = TAG,
                        message = "Broadcast $broadcastId chunk ${metadata.chunkIndex}: hash mismatch, discarding"
                    ))
                    return@execute
                }
                
                state.receivedChunks[metadata.chunkIndex] = chunkData
                
                logger(LogLine(
                    timestamp = System.currentTimeMillis(),
                    severity = AndroidLog.DEBUG,
                    tag = TAG,
                    message = "Broadcast $broadcastId: ${state.receivedChunks.size}/${metadata.totalChunks} chunks received"
                ))
                
                // Check if complete
                if (state.isComplete()) {
                    logger(LogLine(
                        timestamp = System.currentTimeMillis(),
                        severity = AndroidLog.INFO,
                        tag = TAG,
                        message = "Broadcast $broadcastId: all chunks received, reassembling"
                    ))
                    
                    // Reassemble file
                    val fileBytes = state.reassemble()
                    
                    // Write to Shared/ folder
                    val filePath = writeBroadcastFile(state.metadata.fileName, fileBytes)
                    
                    // Notify listeners
                    val notification = com.ustadmobile.meshrabiya.ext.BroadcastReceivedDto(
                        broadcastId = broadcastId,
                        messageText = state.messageText,
                        fileId = state.metadata.fileId,
                        fileName = state.metadata.fileName,
                        filePath = filePath,
                        senderNodeId = state.senderNodeId,
                        receivedAt = System.currentTimeMillis()
                    )
                    
                    synchronized(receiveListeners) {
                        receiveListeners.forEach { it(notification) }
                    }
                    
                    logger(LogLine(
                        timestamp = System.currentTimeMillis(),
                        severity = AndroidLog.INFO,
                        tag = TAG,
                        message = "Broadcast $broadcastId: complete, file written to $filePath"
                    ))
                    
                    // Cleanup
                    incomingBroadcasts.remove(broadcastId)
                }
                
            } catch (e: Exception) {
                logger(LogLine(
                    timestamp = System.currentTimeMillis(),
                    severity = AndroidLog.ERROR,
                    tag = TAG,
                    message = "Failed to process broadcast packet: ${e.message}"
                ))
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
            logger(LogLine(
                timestamp = System.currentTimeMillis(),
                severity = AndroidLog.INFO,
                tag = TAG,
                message = "Creating Shared folder: ${sharedFolder.absolutePath}"
            ))
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
        
        logger(LogLine(
            timestamp = System.currentTimeMillis(),
            severity = AndroidLog.INFO,
            tag = TAG,
            message = "Wrote broadcast file: ${finalFile.absolutePath} (${fileBytes.size} bytes)"
        ))
        
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
                    logger(LogLine(
                        timestamp = System.currentTimeMillis(),
                        severity = AndroidLog.WARN,
                        tag = TAG,
                        message = "Broadcast $id timed out, received ${state.receivedChunks.size}/${state.metadata.totalChunks} chunks"
                    ))
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
