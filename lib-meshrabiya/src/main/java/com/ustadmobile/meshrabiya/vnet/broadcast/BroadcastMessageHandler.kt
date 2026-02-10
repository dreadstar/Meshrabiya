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
import android.content.Context
import android.os.PowerManager
import android.util.Log

/**
 * Centralized handler for broadcast message+file operations
 * Manages both sending and receiving broadcasts
 */
class BroadcastMessageHandler(
    private val virtualNode: VirtualNode,
    private val logger: MNetLogger,
    private val cacheDir: File,
    private val getDropFolderCallback: () -> File?,
    private val context: Context? = null
) {
    
    private val outgoingBroadcasts = ConcurrentHashMap<String, OutgoingBroadcastState>()
    private val incomingBroadcasts = ConcurrentHashMap<String, IncomingBroadcastState>()
    private val executor = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Callbacks for received broadcasts
    private val receiveListeners = mutableListOf<(com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit>()
    
    companion object {
        private const val TAG = "BroadcastMessageHandler"
    }
    
    /**
     * Acquire CPU WakeLock to prevent throttling during broadcasts
     */
    private fun acquireWakeLock() {
        context?.let {
            try {
                val powerManager = it.getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "meshrabiya:broadcast_wakelock"
                ).apply {
                    acquire(10 * 60 * 1000L) // 10 minute timeout
                }
                logger(Log.INFO, "$TAG CPU WakeLock acquired")
            } catch (e: Exception) {
                logger(Log.ERROR, "$TAG Failed to acquire WakeLock", e)
            }
        }
    }
    
    /**
     * Release CPU WakeLock
     */
    private fun releaseWakeLock() {
        wakeLock?.let {
            try {
                if (it.isHeld) {
                    it.release()
                    logger(Log.INFO, "$TAG CPU WakeLock released")
                }
            } catch (e: Exception) {
                logger(Log.ERROR, "$TAG Failed to release WakeLock", e)
            }
            wakeLock = null
        }
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
            acquireWakeLock()  // Acquire CPU WakeLock at start
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
                    filePath = filePath,  // Store full path for NACK resend
                    totalChunks = totalChunks,
                    callback = callback
                )
                outgoingBroadcasts[broadcastId] = state
                
                // Send each chunk
                val batchSize = 100
            val totalBatches = (totalChunks + batchSize - 1) / batchSize

            for (batchNum in 0 until totalBatches) {
                val batchStart = batchNum * batchSize
                val batchEnd = minOf(batchStart + batchSize, totalChunks)
                
                logger(Log.INFO, "$TAG Broadcast $broadcastId: Starting batch ${batchNum + 1}/$totalBatches (chunks $batchStart-${batchEnd - 1})")
                
                // Send all chunks in batch without delay
                for (chunkIndex in batchStart until batchEnd) {
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
                    
                    // new code tmt
                    // val buffer = ByteBuffer.allocateDirect(VirtualPacketHeader.HEADER_SIZE + chunkSize + metadataSize)
                    // buffer.put(headerBytes)
                    // buffer.put(metadataBytes)
                    // buffer.put(fileBytes, startOffset, chunkSize)  // Direct slice, no copy
                    // val packet = VirtualPacket.fromBuffer(buffer)
                    // tmt new code end

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
                    
                    // NEW: DIRECT NEIGHBOR BROADCAST (NO LOOPBACK)
                    // Send directly to all neighbors, no role check required
                    // ANY node (station, hub, router) can originate broadcasts
                    val neighbors = virtualNode.originatingMessageManager.neighbors()
                    
                    if (neighbors.isEmpty()) {
                        logger(Log.WARN, "$TAG Broadcast $broadcastId chunk $chunkIndex: No neighbors found")
                    } else {
                        logger(Log.DEBUG, "$TAG Broadcast $broadcastId chunk $chunkIndex: sending to ${neighbors.size} neighbor(s)")
                        neighbors.forEach { (neighborAddr, lastMsg) ->
                            try {
                                lastMsg.receivedFromSocket.send(
                                    nextHopAddress = lastMsg.lastHopRealInetAddr,
                                    nextHopPort = lastMsg.lastHopRealPort,
                                    virtualPacket = packet
                                )
                                logger(Log.VERBOSE, "$TAG Broadcast $broadcastId chunk $chunkIndex: sent to neighbor $neighborAddr")
                            } catch (e: Exception) {
                                logger(Log.ERROR, "$TAG Broadcast $broadcastId chunk $chunkIndex: failed to send to neighbor $neighborAddr", e)
                            }
                        }
                    }
                    
                    // Do NOT call route() - no loopback
                    // Sender does NOT receive own broadcasts (user requirement)
                    
                    state.chunksSent++
                    
                    // Log milestones only (every 100 chunks or last chunk)
                    if (chunkIndex % 100 == 0 || chunkIndex == totalChunks - 1) {
                        val percentComplete = (chunkIndex * 100) / totalChunks
                        logger(Log.INFO, "$TAG Broadcast $broadcastId: $percentComplete% complete ($chunkIndex/$totalChunks chunks)")
                    }
                    
                    state.chunksSent++
                    
                    // Reduced delay - 1ms instead of 10ms for better throughput
                    Thread.sleep(1)
                }
                
                logger(Log.INFO, "$TAG Broadcast $broadcastId: Batch ${batchNum + 1}/$totalBatches complete")
                
                // Small delay between batches (not between chunks)
                if (batchNum < totalBatches - 1) {
                    Thread.sleep(10)
                }
            } // tmt close
                
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
            } finally {
                releaseWakeLock()  // Always release WakeLock
            }
        }
    }
    
    /**
     * Handle received broadcast packet
     * Called by VirtualNode when broadcast packet arrives (chunk or NACK)
     */
    fun onReceiveBroadcastPacket(packet: VirtualPacket) {
        executor.execute {
            try {
                // Extract payload from packet data
                val payload = packet.data.copyOfRange(
                    packet.payloadOffset, 
                    packet.payloadOffset + packet.header.payloadSize
                )
                
                // Determine packet type and route accordingly
                val packetType = try {
                    BroadcastPacketSerializer.getPacketType(payload)
                } catch (e: Exception) {
                    logger(Log.ERROR, "$TAG Failed to determine packet type: ${e.message}", e)
                    return@execute
                }
                
                when (packetType) {
                    BroadcastPacketSerializer.TYPE_BROADCAST_CHUNK -> {
                        handleBroadcastChunk(packet, payload)
                    }
                    BroadcastPacketSerializer.TYPE_NACK_REQUEST -> {
                        handleNackRequest(packet, payload)
                    }
                    else -> {
                        logger(Log.WARN, "$TAG Unknown packet type: $packetType")
                    }
                }
                
            } catch (e: Exception) {
                logger(Log.ERROR, "$TAG Failed to process broadcast packet: ${e.message}", e)
            }
        }
    }
    
    /**
     * Handle received broadcast chunk packet
     */
    private fun handleBroadcastChunk(packet: VirtualPacket, payload: ByteArray) {
        try {
                // Deserialize payload
                val (broadcastId, messageText, chunkPair) = BroadcastPacketSerializer.deserialize(payload)
                val (metadata, chunkData) = chunkPair
                
                logger(Log.DEBUG, "$TAG Received broadcast chunk: id=$broadcastId, chunk=${metadata.chunkIndex}/${metadata.totalChunks}")
                
                // Get or create incoming state
                val state = incomingBroadcasts.getOrPut(broadcastId) {
                    logger(Log.INFO, "$TAG New incoming broadcast: id=$broadcastId, file=${metadata.fileName}, totalChunks=${metadata.totalChunks}")
                    
                    val newState = IncomingBroadcastState(
                        broadcastId = broadcastId,
                        messageText = messageText,
                        metadata = metadata,
                        senderNodeId = packet.header.fromAddr
                    )
                    
                    // Start timeout monitor for this broadcast
                    startTimeoutMonitor(broadcastId, packet.header.fromAddr)
                    
                    newState
                }
                
                // Store chunk (validate hash)
                val md = MessageDigest.getInstance("SHA-256")
                val actualHash = md.digest(chunkData).joinToString("") { "%02x".format(it) }
                if (actualHash != metadata.hash) {
                    logger(Log.WARN, "$TAG Broadcast $broadcastId chunk ${metadata.chunkIndex}: hash mismatch, discarding")
                    return@handleBroadcastChunk
                }
                
                state.receivedChunks[metadata.chunkIndex] = chunkData
                
                logger(Log.DEBUG, "$TAG Broadcast $broadcastId: ${state.receivedChunks.size}/${metadata.totalChunks} chunks received")
                
                // Check if complete
                if (state.isComplete()) {
                    logger(Log.INFO, "$TAG Broadcast $broadcastId: all chunks received, reassembling")
                    
                    // Reassemble file
                    val fileBytes = state.reassemble()
                    logger(Log.INFO, "$TAG Broadcast $broadcastId: reassembled ${fileBytes.size} bytes")
                    
                    // Try to write to SharedWithMe/ folder
                    var filePath: String?
                    var hasError: Boolean
                    var errorMessage: String? = null
                    try {
                        logger(Log.DEBUG, "$TAG Broadcast $broadcastId: attempting to write file ${state.metadata.fileName}")
                        filePath = writeBroadcastFile(state.metadata.fileName, fileBytes)
                        hasError = false
                        logger(Log.INFO, "$TAG Broadcast $broadcastId: complete, file written to $filePath")
                    } catch (e: IllegalStateException) {
                        // Drop folder not set - create error notification instead
                        filePath = null
                        hasError = true
                        errorMessage = "No storage folder set"
                        logger(Log.ERROR, "$TAG Broadcast $broadcastId: drop folder not set, file cannot be saved", e)
                    } catch (e: Exception) {
                        // Other errors (permission, IO, etc.)
                        filePath = null
                        hasError = true
                        errorMessage = "Failed to save file: ${e.message}"
                        logger(Log.ERROR, "$TAG Broadcast $broadcastId: failed to write file", e)
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
                        errorMessage = errorMessage
                    )
                    
                    synchronized(receiveListeners) {
                        receiveListeners.forEach { it(notification) }
                    }
                    
                    // Cleanup
                    incomingBroadcasts.remove(broadcastId)
                }
                
            } catch (e: Exception) {
                logger(Log.ERROR, "$TAG Failed to process broadcast chunk: ${e.message}", e)
            }
        }
    
    /**
     * Monitor incomplete broadcast and send NACK request if timeout occurs
     * Runs in background thread, waits 60 seconds then checks if broadcast completed
     */
    private fun startTimeoutMonitor(broadcastId: String, senderNodeId: Int) {
        executor.execute {
            try {
                // Wait for timeout period (60 seconds)
                Thread.sleep(60_000)
                
                val state = incomingBroadcasts[broadcastId]
                
                // Check if still incomplete
                if (state != null && !state.isComplete() && state.isTimedOut()) {
                    val missingChunks = state.getMissingChunks()
                    logger(Log.WARN, "$TAG Broadcast $broadcastId: incomplete after 60s, ${missingChunks.size} chunks missing")
                    
                    // Send NACK request to sender
                    sendNackRequest(broadcastId, senderNodeId, missingChunks)
                } else if (state == null) {
                    logger(Log.DEBUG, "$TAG Broadcast $broadcastId: already completed and cleaned up")
                } else if (state.isComplete()) {
                    logger(Log.DEBUG, "$TAG Broadcast $broadcastId: completed before timeout")
                }
            } catch (e: InterruptedException) {
                logger(Log.DEBUG, "$TAG Timeout monitor interrupted for broadcast $broadcastId")
            } catch (e: Exception) {
                logger(Log.ERROR, "$TAG Timeout monitor failed for broadcast $broadcastId", e)
            }
        }
    }
    
    /**
     * Send NACK request packet to original sender
     * Creates and sends a NACK packet requesting specific missing chunks
     */
    private fun sendNackRequest(broadcastId: String, senderNodeId: Int, missingChunks: List<Int>) {
        try {
            logger(Log.INFO, "$TAG Sending NACK for broadcast $broadcastId: requesting ${missingChunks.size} chunks")
            
            // Serialize NACK request
            val nackPayload = BroadcastPacketSerializer.serializeNackRequest(broadcastId, missingChunks)
            
            // Create packet data buffer with header space
            val packetData = ByteArray(nackPayload.size + VirtualPacketHeader.HEADER_SIZE)
            System.arraycopy(nackPayload, 0, packetData, VirtualPacketHeader.HEADER_SIZE, nackPayload.size)
            
            // Create NACK packet addressed to sender (unicast, not broadcast)
            val nackPacket = VirtualPacket.fromHeaderAndPayloadData(
                header = VirtualPacketHeader(
                    toAddr = senderNodeId,  // Direct to sender, not broadcast
                    toPort = 0,
                    fromAddr = virtualNode.addressAsInt,
                    fromPort = 0,
                    lastHopAddr = virtualNode.addressAsInt,
                    hopCount = 0,
                    maxHops = 10,
                    gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
                    payloadSize = nackPayload.size
                ),
                data = packetData,
                payloadOffset = VirtualPacketHeader.HEADER_SIZE
            )
            
            // Route NACK back to sender
            virtualNode.route(nackPacket)
            
            logger(Log.DEBUG, "$TAG NACK sent for broadcast $broadcastId")
        } catch (e: Exception) {
            logger(Log.ERROR, "$TAG Failed to send NACK for broadcast $broadcastId", e)
        }
    }
    
    /**
     * Handle received NACK request packet
     * Called when a receiver requests retransmission of missing chunks
     */
    private fun handleNackRequest(packet: VirtualPacket, payload: ByteArray) {
        try {
            // Deserialize NACK request
            val (broadcastId, missingChunks) = BroadcastPacketSerializer.deserializeNackRequest(payload)
            
            logger(Log.INFO, "$TAG Received NACK for broadcast $broadcastId: ${missingChunks.size} chunks requested by node ${packet.header.fromAddr}")
            
            // Check if we're the sender of this broadcast
            val outgoingState = outgoingBroadcasts[broadcastId]
            if (outgoingState == null) {
                logger(Log.WARN, "$TAG NACK received for unknown broadcast $broadcastId, ignoring")
                return
            }
            
            logger(Log.INFO, "$TAG Resending ${missingChunks.size} missing chunks for broadcast $broadcastId")
            
            // Resend the missing chunks
            resendChunks(broadcastId, outgoingState, missingChunks, packet.header.fromAddr)
            
        } catch (e: Exception) {
            logger(Log.ERROR, "$TAG Failed to process NACK request: ${e.message}", e)
        }
    }
    
    /**
     * Resend specific chunks for a broadcast (NACK retry)
     * Re-reads original file from disk and sends only the requested chunks
     */
    private fun resendChunks(
        broadcastId: String,
        state: OutgoingBroadcastState,
        chunkIndices: List<Int>,
        requestorNodeId: Int
    ) {
        try {
            // Re-read the original file
            val file = File(state.filePath)
            if (!file.exists()) {
                logger(Log.ERROR, "$TAG Cannot resend - original file no longer exists: ${state.filePath}")
                return
            }
            
            val fileBytes = file.readBytes()
            logger(Log.INFO, "$TAG Re-read file ${file.name} (${fileBytes.size} bytes) for chunk resend")
            
            // Resend only the requested chunks
            chunkIndices.forEach { chunkIndex ->
                try {
                    val startOffset = chunkIndex * MeshrabiyaConstants.BROADCAST_CHUNK_SIZE
                    val endOffset = minOf(startOffset + MeshrabiyaConstants.BROADCAST_CHUNK_SIZE, fileBytes.size)
                    val chunkData = fileBytes.sliceArray(startOffset until endOffset)
                    
                    // Calculate hash
                    val md = MessageDigest.getInstance("SHA-256")
                    val hash = md.digest(chunkData).joinToString("") { "%02x".format(it) }
                    
                    // Create metadata
                    val metadata = BroadcastChunkMetadata(
                        chunkId = UUID.randomUUID().toString(),
                        fileId = state.fileId,
                        fileName = state.fileName,
                        chunkIndex = chunkIndex,
                        totalChunks = state.totalChunks,
                        chunkSize = chunkData.size.toLong(),
                        totalFileSize = fileBytes.size.toLong(),
                        hash = hash
                    )
                    
                    // Serialize packet
                    val packetPayload = BroadcastPacketSerializer.serialize(
                        broadcastId = broadcastId,
                        messageText = state.messageText,
                        chunkMetadata = metadata,
                        chunkData = chunkData
                    )
                    
                    // Create packet data buffer
                    val packetData = ByteArray(packetPayload.size + VirtualPacketHeader.HEADER_SIZE)
                    System.arraycopy(packetPayload, 0, packetData, VirtualPacketHeader.HEADER_SIZE, packetPayload.size)
                    
                    // Create packet addressed to requestor (unicast, not broadcast)
                    val packet = VirtualPacket.fromHeaderAndPayloadData(
                        header = VirtualPacketHeader(
                            toAddr = requestorNodeId,  // Direct to requestor, not broadcast
                            toPort = 0,
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
                    
                    // Route packet to requestor
                    virtualNode.route(packet)
                    
                    logger(Log.DEBUG, "$TAG Resent chunk $chunkIndex for broadcast $broadcastId to node $requestorNodeId")
                    
                    // Small delay between chunks (same as original broadcast)
                    Thread.sleep(1)
                    
                } catch (e: Exception) {
                    logger(Log.ERROR, "$TAG Failed to resend chunk $chunkIndex for broadcast $broadcastId", e)
                }
            }
            
            logger(Log.INFO, "$TAG Completed resending ${chunkIndices.size} chunks for broadcast $broadcastId")
            
        } catch (e: Exception) {
            logger(Log.ERROR, "$TAG Failed to resend chunks for broadcast $broadcastId", e)
        }
    }
    
    /**
     * Write received broadcast file to SharedWithMe/ folder in drop folder
     * Creates SharedWithMe/ subfolder if it doesn't exist
     * 
     * @return Absolute path to written file
     * @throws IllegalStateException if drop folder not selected
     */
    private fun writeBroadcastFile(fileName: String, fileBytes: ByteArray): String {
        val dropFolder = getDropFolderCallback() 
            ?: throw IllegalStateException("Drop folder not selected")
        
        logger(Log.INFO, "$TAG Drop folder path: ${dropFolder.absolutePath}")
        
        val sharedFolder = File(dropFolder, "SharedWithMe")
        logger(Log.INFO, "$TAG SharedWithMe folder path: ${sharedFolder.absolutePath}")
        
        if (!sharedFolder.exists()) {
            logger(Log.INFO, "$TAG Creating SharedWithMe folder: ${sharedFolder.absolutePath}")
            val mkdirResult = sharedFolder.mkdirs()
            logger(Log.INFO, "$TAG mkdirs() result: $mkdirResult, exists now: ${sharedFolder.exists()}")
        } else {
            logger(Log.INFO, "$TAG SharedWithMe folder already exists")
        }
        
        require(sharedFolder.isDirectory) { "SharedWithMe path exists but is not a directory: ${sharedFolder.absolutePath}" }
        
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
