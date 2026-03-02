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


import com.ustadmobile.meshrabiya.vnet.AndroidVirtualNode   
import androidx.documentfile.provider.DocumentFile

/**
 * Centralized handler for broadcast message+file operations
 * Manages both sending and receiving broadcasts
 */
class BroadcastMessageHandler(
    private val virtualNode: VirtualNode,
    private val logger: (Int, String) -> Unit,
    private val cacheDir: File,
    private val getDropFolderCallback: () -> DocumentFile?,  // Changed from File? to DocumentFile?
    private val context: Context? = null
) {
    
    private val outgoingBroadcasts = ConcurrentHashMap<String, OutgoingBroadcastState>()
    private val incomingBroadcasts = ConcurrentHashMap<String, IncomingBroadcastState>()
    // private val executor = Executors.newSingleThreadExecutor()
    private var wakeLock: PowerManager.WakeLock? = null
    
    // Callbacks for received broadcasts
    private val receiveListeners = mutableListOf<(com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto) -> Unit>()
    
    companion object {
        private const val TAG = "BroadcastMessageHandler"
    }
    
    /**
     * Helper to create broadcast-specific log tag
     */
    private fun broadcastTag(broadcastId: String): String {
        val shortId = broadcastId.take(8)
        return "$TAG[$shortId]"
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
                Log.e(TAG, "Failed to acquire WakeLock", e)
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
                Log.e(TAG, "Failed to release WakeLock", e)
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
        latitude: Double? = null,
        longitude: Double? = null,
        callback: (Result<BroadcastResultDto>) -> Unit
    ) {
        virtualNode.connectionExecutor.execute {
            acquireWakeLock()  // Acquire CPU WakeLock at start
            try {
                logger(Log.INFO, "$TAG Starting broadcast: message='$messageText', file='$filePath', lat=$latitude, lon=$longitude")
                
                // Handle file if provided, otherwise text-only
                val hasFile = filePath.isNotEmpty()
                val file: File?
                val fileBytes: ByteArray
                val fileName: String
                val fileId = UUID.randomUUID().toString()
                
                if (hasFile) {
                    // File broadcast
                    file = File(filePath)
                    require(file.exists()) { "File does not exist: $filePath" }
                    require(file.canRead()) { "Cannot read file: $filePath" }
                    fileBytes = file.readBytes()
                    fileName = file?.name ?: ""
                    logger(Log.DEBUG, "$TAG File broadcast: ${fileBytes.size} bytes, file=$fileName")
                } else {
                    // Text-only broadcast
                    file = null
                    fileBytes = ByteArray(0)
                    fileName = ""
                    logger(Log.DEBUG, "$TAG Text-only broadcast (no file)")
                }
                
                val broadcastId = UUID.randomUUID().toString()
                
                // Calculate chunks (0 for text-only)
                val totalChunks = if (hasFile) {
                    (fileBytes.size + MeshrabiyaConstants.BROADCAST_CHUNK_SIZE - 1) / 
                    MeshrabiyaConstants.BROADCAST_CHUNK_SIZE
                } else {
                    0  // Text-only broadcasts have 0 chunks
                }
                
                logger(Log.DEBUG, "$TAG Broadcast $broadcastId: file size=${fileBytes.size}, chunks=$totalChunks, hasFile=$hasFile")
                
                // Register outgoing state
                val state = OutgoingBroadcastState(
                    broadcastId = broadcastId,
                    messageText = messageText,
                    fileId = fileId,
                    fileName = fileName,
                    filePath = filePath,  // Can be empty for text-only
                    totalChunks = totalChunks,
                    latitude = latitude,
                    longitude = longitude,
                    callback = callback
                )
                outgoingBroadcasts[broadcastId] = state
                
                // If text-only (no chunks), complete immediately
                if (!hasFile) {
                    logger(Log.INFO, "$TAG Text-only broadcast $broadcastId: sending metadata packet to neighbors")
                    
                    // Send single metadata packet (no chunks)
                    val metadataOnly = BroadcastChunkMetadata(
                        chunkId = UUID.randomUUID().toString(),
                        fileId = fileId,
                        fileName = "",
                        chunkIndex = 0,
                        totalChunks = 0,
                        chunkSize = 0L,
                        totalFileSize = 0L,
                        hash = "",
                        latitude = latitude,
                        longitude = longitude
                    )
                    
                    val packetPayload = BroadcastPacketSerializer.serialize(
                        broadcastId = broadcastId,
                        messageText = messageText,
                        chunkMetadata = metadataOnly,
                        chunkData = ByteArray(0)
                    )
                    
                    val packetData = ByteArray(packetPayload.size + VirtualPacketHeader.HEADER_SIZE)
                    System.arraycopy(packetPayload, 0, packetData, VirtualPacketHeader.HEADER_SIZE, packetPayload.size)
                    
                    val packet = VirtualPacket.fromHeaderAndPayloadData(
                        header = VirtualPacketHeader(
                            toAddr = VirtualPacket.ADDR_BROADCAST,
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
                    
                    // Send to all neighbors (same as file broadcast)
                    val neighbors = virtualNode.originatingMessageManager.neighbors()
                    if (neighbors.isEmpty()) {
                        logger(Log.WARN, "$TAG Text-only broadcast $broadcastId: No neighbors found")
                    } else {
                        logger(Log.DEBUG, "$TAG Text-only broadcast $broadcastId: sending to ${neighbors.size} neighbor(s)")
                        neighbors.forEach { (neighborAddr, lastMsg) ->
                            try {
                                lastMsg.receivedFromSocket.send(
                                    nextHopAddress = lastMsg.lastHopRealInetAddr,
                                    nextHopPort = lastMsg.lastHopRealPort,
                                    virtualPacket = packet
                                )
                                logger(Log.VERBOSE, "$TAG Text-only broadcast $broadcastId: sent to neighbor $neighborAddr")
                            } catch (e: Exception) {
                                Log.e(TAG, "Text-only broadcast $broadcastId: failed to send to neighbor $neighborAddr", e)
                            }
                        }
                    }
                    
                    logger(Log.INFO, "$TAG Text-only broadcast $broadcastId sent to all neighbors")
                    
                    // Complete immediately
                    val result = BroadcastResultDto(
                        broadcastId = broadcastId,
                        messageText = messageText,
                        fileId = fileId,
                        fileName = fileName,
                        totalChunks = 0,
                        latitude = latitude,
                        longitude = longitude,
                        successNodeIds = emptyList(),
                        failedNodeIds = emptyList(),
                        timestamp = System.currentTimeMillis()
                    )
                    
                    callback(Result.success(result))
                    outgoingBroadcasts.remove(broadcastId)
                    releaseWakeLock()
                    return@execute
                }
                
                // File broadcast - send chunks in batches
                val batchSize = 100
                val totalBatches = (totalChunks + batchSize - 1) / batchSize
                
                // Register outgoing state
                // val state = OutgoingBroadcastState(
                //     broadcastId = broadcastId,
                //     messageText = messageText,
                //     fileId = fileId,
                //     fileName = file.name,
                //     filePath = filePath,  // Store full path for NACK resend
                //     totalChunks = totalChunks,
                //     callback = callback
                // )
                // outgoingBroadcasts[broadcastId] = state
                
                // Send each chunk
                

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
                        fileName = file?.name ?: "",
                        chunkIndex = chunkIndex,
                        totalChunks = totalChunks,
                        chunkSize = chunkData.size.toLong(),
                        totalFileSize = fileBytes.size.toLong(),
                        hash = hash,
                        latitude = latitude,
                        longitude = longitude
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
                                Log.e(TAG, "Broadcast $broadcastId chunk $chunkIndex: failed to send to neighbor $neighborAddr", e)
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
                    
                    // Delay between chunks within batch
                    Thread.sleep(MeshrabiyaConstants.BROADCAST_CHUNK_DELAY_MS)
                }
                
                logger(Log.INFO, "$TAG Broadcast $broadcastId: Batch ${batchNum + 1}/$totalBatches complete")
                
                // Delay between batches to allow receiver processing
                if (batchNum < totalBatches - 1) {
                    Thread.sleep(MeshrabiyaConstants.BROADCAST_BATCH_DELAY_MS)
                }
            } // tmt close
                
                // All chunks sent - return success
                logger(Log.INFO, "$TAG Broadcast $broadcastId: complete, all $totalChunks chunks sent")
                
                val result = BroadcastResultDto(
                    broadcastId = broadcastId,
                    messageText = messageText,
                    fileId = fileId,
                    fileName = file?.name ?: "",
                    totalChunks = totalChunks,
                    latitude = latitude,
                    longitude = longitude,
                    successNodeIds = emptyList(),  // Best effort broadcast, no ACKs
                    failedNodeIds = emptyList(),
                    timestamp = System.currentTimeMillis()
                )
                
                callback(Result.success(result))
                outgoingBroadcasts.remove(broadcastId)
                
            } catch (e: Exception) {
                Log.e(TAG, "Broadcast send failed: ${e.message}", e)
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
                Log.e(TAG, "Failed to determine packet type: ${e.message}", e)
                return
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
            Log.e(TAG, "Failed to process broadcast packet: ${e.message}", e)
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
                logger(Log.INFO, "$TAG New incoming broadcast: id=$broadcastId, file=${metadata.fileName}, totalChunks=${metadata.totalChunks}, isTextOnly=${metadata.totalChunks == 0}")
                
                val newState = IncomingBroadcastState(
                    broadcastId = broadcastId,
                    messageText = messageText,
                    metadata = metadata,
                    senderNodeId = packet.header.fromAddr
                )
                
                // Start timeout monitor (unless text-only, which completes immediately)
                if (metadata.totalChunks > 0) {
                    startTimeoutMonitor(broadcastId, packet.header.fromAddr)
                }
                
                newState
            }
            
            // If text-only broadcast (totalChunks=0), complete immediately
            if (metadata.totalChunks == 0) {
                logger(Log.INFO, "${broadcastTag(broadcastId)} ✅ Text-only broadcast received, message='$messageText'")
                onTextOnlyBroadcastComplete(broadcastId, messageText, packet.header.fromAddr, metadata.latitude, metadata.longitude)
                incomingBroadcasts.remove(broadcastId)
                return
            }
            
            // File broadcast - store chunk and continue as before
            val md = MessageDigest.getInstance("SHA-256")
            val actualHash = md.digest(chunkData).joinToString("") { "%02x".format(it) }
            if (actualHash != metadata.hash) {
                logger(Log.WARN, "$TAG Broadcast $broadcastId chunk ${metadata.chunkIndex}: hash mismatch, discarding")
                return
            }
            
            state.receivedChunks[metadata.chunkIndex] = chunkData
                
                logger(Log.DEBUG, "$TAG Broadcast $broadcastId: ${state.receivedChunks.size}/${metadata.totalChunks} chunks received")
                
                // COMPREHENSIVE DEBUG: Check completion status
                logger(Log.DEBUG, "$TAG [BROADCAST_COMPLETE_CHECK] broadcastId=$broadcastId, receivedChunks=${state.receivedChunks.size}, totalChunks=${metadata.totalChunks}, isComplete=${state.isComplete()}")
                
                // Check if complete
                if (state.isComplete()) {
                    logger(Log.INFO, "${broadcastTag(broadcastId)} ✅ [BROADCAST_COMPLETE] All ${metadata.totalChunks} chunks received, reassembling")
                    
                    // Reassemble file
                    val fileBytes = state.reassemble()
                    logger(Log.INFO, "${broadcastTag(broadcastId)} [RECONSTITUTE] Reassembled ${fileBytes.size} bytes from ${metadata.totalChunks} chunks")
                    
                    // Try to write to SharedWithMe/ folder
                    var filePath: String?
                    var hasError: Boolean
                    var errorMessage: String? = null
                    try {
                        logger(Log.DEBUG, "${broadcastTag(broadcastId)} [FILE_WRITE] Attempting to write file: ${state.metadata.fileName}")
                        filePath = writeBroadcastFile(broadcastId, state.metadata.fileName, fileBytes)
                        hasError = false
                        logger(Log.INFO, "${broadcastTag(broadcastId)} ✅ [FILE_WRITE] Complete, file written to: $filePath")
                    } catch (e: IllegalStateException) {
                        // Drop folder not set - create error notification instead
                        filePath = null
                        hasError = true
                        errorMessage = "No storage folder set"
                        Log.e(broadcastTag(broadcastId), "❌ [FILE_WRITE] Drop folder not set, file cannot be saved", e)
                    } catch (e: Exception) {
                        // Other errors (permission, IO, etc.)
                        filePath = null
                        hasError = true
                        errorMessage = "Failed to save file: ${e.message}"
                        Log.e(broadcastTag(broadcastId), "❌ [FILE_WRITE] Failed: ${e.message}", e)
                    }
                    
                    // ONLY notify listeners if file write succeeded (do NOT increment notification count for errors)
                    if (!hasError) {
                        logger(Log.DEBUG, "${broadcastTag(broadcastId)} [NOTIFICATION] Creating notification DTO for successful file transfer")
                        val notification = com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto(
                            broadcastId = broadcastId,
                            messageText = state.messageText,
                            fileId = state.metadata.fileId,
                            fileName = state.metadata.fileName,
                            filePath = filePath ?: "",
                            senderNodeId = state.senderNodeId,
                            latitude = state.metadata.latitude,
                            longitude = state.metadata.longitude,
                            receivedAt = System.currentTimeMillis(),
                            hasError = false,
                            errorMessage = null
                        )
                        
                        if (state.senderNodeId != virtualNode.addressAsInt) {
                            logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] Notifying ${receiveListeners.size} listeners for successful file broadcast: fileName='${state.metadata.fileName}'")
                            synchronized(receiveListeners) {
                                receiveListeners.forEach { it(notification) }
                            }
                            logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] ✅ All ${receiveListeners.size} listeners notified")
                        } else {
                            logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] Skipping notification for senderNodeId=${state.senderNodeId} (self)")
                        }
                    } else {
                        // Log error but do NOT notify listeners (no dropdown, no count increment)
                        logger(Log.WARN, "${broadcastTag(broadcastId)} [NOTIFICATION] ⏭️ Skipping listener notification for failed file transfer: $errorMessage")
                    }
                    
                    // Cleanup
                    incomingBroadcasts.remove(broadcastId)
                }
                
            } catch (e: Exception) {
                Log.e(TAG, "Failed to process broadcast chunk: ${e.message}", e)
            }
        }
    
    /**
     * Monitor incomplete broadcast and send NACK request if timeout occurs
     * Runs in background thread, waits 60 seconds then checks if broadcast completed
     */
    private fun startTimeoutMonitor(broadcastId: String, senderNodeId: Int) {
        virtualNode.connectionExecutor.execute {
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
                Log.e(TAG, "Timeout monitor failed for broadcast $broadcastId", e)
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
            Log.e(TAG, "Failed to send NACK for broadcast $broadcastId", e)
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
            Log.e(TAG, "Failed to process NACK request: ${e.message}", e)
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
                        hash = hash,
                        latitude = state.latitude,
                        longitude = state.longitude
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
                    Log.e(TAG, "Failed to resend chunk $chunkIndex for broadcast $broadcastId", e)
                }
            }
            
            logger(Log.INFO, "$TAG Completed resending ${chunkIndices.size} chunks for broadcast $broadcastId")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resend chunks for broadcast $broadcastId", e)
        }
    }
    
    /**
     * Write received broadcast file to SharedWithMe/ folder in drop folder
     * Creates SharedWithMe/ subfolder if it doesn't exist
     * 
     * @return Absolute path to written file
     * @throws IllegalStateException if drop folder not selected
     */
    private fun writeBroadcastFile(broadcastId: String, fileName: String, fileBytes: ByteArray): String {
        logger(Log.DEBUG, "${broadcastTag(broadcastId)} [SHARED_FOLDER] writeBroadcastFile called: fileName=$fileName, fileSize=${fileBytes.size}")
        
        val dropFolderDoc = getDropFolderCallback() 
        
        if (dropFolderDoc == null) {
            logger(Log.ERROR, "${broadcastTag(broadcastId)} [SHARED_FOLDER] ❌ Drop folder callback returned NULL")
            throw IllegalStateException("Drop folder not selected")
        }
        
        logger(Log.INFO, "${broadcastTag(broadcastId)} [SHARED_FOLDER] Drop folder URI: ${dropFolderDoc.uri}, exists=${dropFolderDoc.exists()}, canWrite=${dropFolderDoc.canWrite()}")
        
        // Find or create SharedWithMe subdirectory using DocumentFile API
        var sharedFolderDoc = dropFolderDoc.findFile("SharedWithMe")
        
        if (sharedFolderDoc == null || !sharedFolderDoc.exists()) {
            logger(Log.INFO, "${broadcastTag(broadcastId)} [SHARED_FOLDER] 📁 Creating SharedWithMe folder")
            sharedFolderDoc = dropFolderDoc.createDirectory("SharedWithMe")
            
            if (sharedFolderDoc == null) {
                logger(Log.ERROR, "${broadcastTag(broadcastId)} [SHARED_FOLDER] ❌ createDirectory() returned NULL")
                throw IllegalStateException("Failed to create SharedWithMe directory")
            }
            logger(Log.INFO, "${broadcastTag(broadcastId)} [SHARED_FOLDER] ✅ Folder created: ${sharedFolderDoc.uri}")
        } else {
            logger(Log.INFO, "${broadcastTag(broadcastId)} [SHARED_FOLDER] ✓ SharedWithMe folder already exists")
        }
        
        require(sharedFolderDoc.isDirectory) { "SharedWithMe path exists but is not a directory: ${sharedFolderDoc.uri}" }
        
        // Handle duplicate filenames
        var finalFileName = fileName
        var counter = 1
        while (sharedFolderDoc.findFile(finalFileName) != null) {
            logger(Log.DEBUG, "${broadcastTag(broadcastId)} [SHARED_FOLDER] File exists, trying alternative name (counter=$counter)")
            val nameWithoutExt = fileName.substringBeforeLast(".")
            val ext = fileName.substringAfterLast(".", "")
            finalFileName = "${nameWithoutExt}_$counter${if (ext.isNotEmpty()) ".$ext" else ""}"
            counter++
        }
        
        // Create file using DocumentFile API
        val mimeType = when (fileName.substringAfterLast(".", "").lowercase()) {
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "pdf" -> "application/pdf"
            "txt" -> "text/plain"
            else -> "application/octet-stream"
        }
        
        val fileDoc = sharedFolderDoc.createFile(mimeType, finalFileName)
        if (fileDoc == null) {
            logger(Log.ERROR, "${broadcastTag(broadcastId)} [SHARED_FOLDER] ❌ createFile() returned NULL")
            throw IllegalStateException("Failed to create file: $finalFileName")
        }
        
        // Write bytes using ContentResolver
        val context = virtualNode.appContext
        logger(Log.INFO, "${broadcastTag(broadcastId)} [SHARED_FOLDER] Writing ${fileBytes.size} bytes to: ${fileDoc.uri}")
        context.contentResolver.openOutputStream(fileDoc.uri)?.use { outputStream ->
            outputStream.write(fileBytes)
            outputStream.flush()
        } ?: throw IllegalStateException("Failed to open output stream for: ${fileDoc.uri}")
        
        logger(Log.INFO, "${broadcastTag(broadcastId)} [SHARED_FOLDER] ✅ File write complete: ${fileDoc.uri} (${fileBytes.size} bytes)")
        
        // Return URI string for notification
        return fileDoc.uri.toString()
    }
    
    /**
     * Cleanup incomplete broadcasts older than timeout
     */
    fun cleanupStaleTransfers() {
        virtualNode.connectionExecutor.execute {
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
     * Handle completion of text-only broadcast (no file)
     */
    private fun onTextOnlyBroadcastComplete(
        broadcastId: String,
        messageText: String,
        senderNodeId: Int,
        latitude: Double?,
        longitude: Double?
    ) {
        logger(Log.INFO, "${broadcastTag(broadcastId)} [TEXT_RECEPTION] Text-only broadcast received: message='$messageText', sender=$senderNodeId, lat=$latitude, lon=$longitude")
        
        // Notify listeners (no file path, no error)
        val notification = com.ustadmobile.meshrabiya.api.model.BroadcastReceivedDto(
            broadcastId = broadcastId,
            messageText = messageText,
            fileId = "",
            fileName = "",
            filePath = "",
            senderNodeId = senderNodeId,
            latitude = latitude,
            longitude = longitude,
            receivedAt = System.currentTimeMillis(),
            hasError = false,
            errorMessage = null
        )
        
        logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] Notifying ${receiveListeners.size} listeners for text broadcast: message='$messageText'")
        synchronized(receiveListeners) {
            receiveListeners.forEach { listener ->
                try {
                    listener(notification)
                } catch (e: Exception) {
                    Log.e(broadcastTag(broadcastId), "[NOTIFICATION] ❌ Listener exception", e)
                }
            }
        }
        logger(Log.INFO, "${broadcastTag(broadcastId)} [NOTIFICATION] ✅ All ${receiveListeners.size} listeners notified for text broadcast")
    }
}
