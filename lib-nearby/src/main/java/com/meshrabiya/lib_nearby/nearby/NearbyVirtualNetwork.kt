package com.meshrabiya.lib_nearby.nearby


import android.content.Context
import android.util.Log
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.meshrabiya.lib_nearby.nearby.ext.toPrettyString
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.vnet.VirtualPacket
import com.ustadmobile.meshrabiya.vnet.netinterface.VSocket
import com.ustadmobile.meshrabiya.vnet.netinterface.VirtualNetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.InputStream
import java.net.InetAddress
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random


class NearbyVirtualNetwork(
    context: Context,
    private val name: String,
    private val serviceId: String,
    private val virtualIpAddress: Int,
    private val broadcastAddress: Int,
    private val strategy: Strategy = Strategy.P2P_CLUSTER,
    val logger: MNetLogger,
    private val onPacketReceived: (VirtualPacket) -> Unit
) : VirtualNetworkInterface {

    override val virtualAddress: InetAddress
        get() = InetAddress.getByAddress(virtualIpAddress.addressToByteArray())

    private val streamReplies = ConcurrentHashMap<Int, CompletableFuture<InputStream>>()

    private val endpointIpMap = ConcurrentHashMap<String, InetAddress>()

    private val connectionsClient = Nearby.getConnectionsClient(context)
    private val desiredOutgoingConnections = 3
    private val scope = CoroutineScope(Dispatchers.Default)
    private val isClosed = AtomicBoolean(false)

    data class EndpointInfo(
        val endpointId: String,
        val status: EndpointStatus,
        val ipAddress: InetAddress,
        val isOutgoing: Boolean
    )

    enum class EndpointStatus {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING
    }

    enum class LogLevel {
        VERBOSE, DEBUG, INFO, WARNING, ERROR
    }

    private val _endpointStatusFlow = MutableStateFlow<Map<String, EndpointInfo>>(mutableMapOf())
    val endpointStatusFlow = _endpointStatusFlow.asStateFlow()

    private fun ConnectionInfo.requireAddress(): InetAddress {
        return InetAddress.getByName(endpointName.substringAfter("|"))
    }

    /**
     * If two endpoints discover each other and then attempt to connect at the same time, then this
     * will cause an error. This will happen if both nodes are looking to form additional outgoing
     * connections.
     *
     * To avoid this, we use a system where nodes will yield (wait for 10 seconds by default) to a
     * higher virtual IP address to avoid such a clash.
     */
    private val InetAddress.shouldYieldWhenConnecting: Boolean
        get() = requireAddressAsInt() > virtualIpAddress

    /**
     *
     */
    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            log(LogLevel.INFO, "onConnectionInitiated: $endpointId / incoming=${connectionInfo.isIncomingConnection}")
            assertNotClosed()

            _endpointStatusFlow.update { prev ->
                prev.toMutableMap().also {
                    it.compute(endpointId) { _, _existingInfo ->
                        _existingInfo?.copy(
                            status = EndpointStatus.CONNECTING,
                            isOutgoing = !connectionInfo.isIncomingConnection,
                        ) ?: EndpointInfo(
                            endpointId = endpointId,
                            status = EndpointStatus.CONNECTING,
                            ipAddress = connectionInfo.requireAddress(),
                            isOutgoing = !connectionInfo.isIncomingConnection,
                        )
                    }
                }
            }

            log(LogLevel.INFO, "onConnectionInitiated: Accepting connection from $endpointId")
            connectionsClient.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            log(LogLevel.INFO, "onConnectionResult for $endpointId status=${result.status.toPrettyString()}")
            assertNotClosed()

            //As per https://developers.google.com/nearby/connections/android/manage-connections
            when(result.status.statusCode) {
                ConnectionsStatusCodes.STATUS_OK -> {
                    log(LogLevel.INFO, "onConnectionResult: Success! Connected with $endpointId")
                }

                ConnectionsStatusCodes.STATUS_CONNECTION_REJECTED -> {
                    log(LogLevel.ERROR, "onConnectionResult: Rejected by other side: $endpointId")
                }

                ConnectionsStatusCodes.STATUS_ERROR -> {
                    log(LogLevel.ERROR, "onConnectionResult: Error: $endpointId: code ${result.status.statusCode}")
                }

                else -> {
                    log(LogLevel.ERROR, "onConnectionResult: Unknown status: $endpointId: code ${result.status.statusCode}")
                }
            }

            _endpointStatusFlow.update { prev ->
                prev.toMutableMap().also { map ->
                    map.computeIfPresent(endpointId) { _, endpointInfo ->
                        endpointInfo.copy(
                            status = if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK)
                                EndpointStatus.CONNECTED
                            else
                                EndpointStatus.DISCONNECTED
                        )
                    }
                }
            }
        }

        override fun onDisconnected(endpointId: String) {
            assertNotClosed()
            log(LogLevel.INFO, "onDisconnected: Disconnected from endpoint: $endpointId")
            _endpointStatusFlow.update { prev ->
                prev.toMutableMap().also { map ->
                    map.computeIfPresent(endpointId) { _, endpointInfo ->
                        endpointInfo.copy(status = EndpointStatus.DISCONNECTED)
                    }
                }
            }
        }
    }


    override val knownNeighbors: List<InetAddress>
        get() = _endpointStatusFlow.value.filter {
            it.value.status == EndpointStatus.CONNECTED
        }.mapNotNull { it.value.ipAddress }

    fun start() {
        startAdvertising()
        startDiscovery()
        observeEndpointStatusFlow()
    }

    override fun close() {
        try {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
            _endpointStatusFlow.value = emptyMap()
            scope.cancel()
            streamReplies.forEach { (_, future) -> future.cancel(true) }
            streamReplies.clear()
            endpointIpMap.clear()
            log(LogLevel.INFO, "Network is closed and all operations have been stopped.")
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Error during network closure", e)
        }
    }

    /**
     * Broadcasts device presence to nearby peers using Nearby Connections API.
     * Includes device identification (name and virtual IP) in advertising payload.
     *
     * Throws IllegalStateException if network is closed.
     */
    private fun startAdvertising() {
        assertNotClosed()
        // Send actual virtual IP address, not broadcast
        val deviceNameWithIP = "$name|${virtualAddress.hostAddress}"
        val advertisingOptions = AdvertisingOptions.Builder().setStrategy(strategy).build()

        log(LogLevel.DEBUG, "Starting advertising with device IP: ${virtualAddress.hostAddress} callback =$connectionLifecycleCallback")

        connectionsClient.startAdvertising(
            deviceNameWithIP, serviceId, connectionLifecycleCallback, advertisingOptions
        ).addOnSuccessListener {
            log(LogLevel.INFO, "Started advertising with name: $deviceNameWithIP")
        }.addOnFailureListener { e ->
            log(LogLevel.ERROR, "Failed to start advertising", e)
        }
    }

    /**
     * Initiates discovery of nearby peers advertising the same service.
     * Uses configured Strategy (P2P_CLUSTER by default) for connection type.
     *
     * Throws IllegalStateException if network is closed.
     */
    private fun startDiscovery() {
        assertNotClosed()
        val discoveryOptions = DiscoveryOptions.Builder().setStrategy(strategy).build()
        log(LogLevel.INFO, "request start discovery: serviceId = $serviceId")
        connectionsClient.startDiscovery(
            serviceId, endpointDiscoveryCallback, discoveryOptions
        ).addOnSuccessListener {
            log(LogLevel.INFO, "Started discovery successfully")
        }.addOnFailureListener { e ->
            log(LogLevel.ERROR, "Failed to start discovery", e)
        }
    }

    /**
     * Routes virtual packets to appropriate endpoints.
     * Handles both broadcast and unicast transmission modes.
     *
     * @param virtualPacket The packet to send
     * @param nextHopAddress Target address (broadcast or specific endpoint)
     * @throws IllegalArgumentException if target endpoint not found for unicast
     */
    override fun send(virtualPacket: VirtualPacket, nextHopAddress: InetAddress) {
        val connectedEndpoints = _endpointStatusFlow.value.filter {
            it.value.status == EndpointStatus.CONNECTED
        }

        //If the nextHopAddress is the broadcast address, send to all known points.
        //Else, send only to the related endpoint; if not found, throw exception.
        if (nextHopAddress.address.contentEquals(InetAddress.getByAddress(broadcastAddress.addressToByteArray()).address)) {
            log(LogLevel.INFO, "Broadcasting packet to all connected endpoints")
            connectedEndpoints.filter {
                it.value.status == EndpointStatus.CONNECTED
            }.forEach { (endpointId, _) ->
                sendPacketToEndpoint(endpointId, virtualPacket)
            }
        }
        else {
            val matchingEndpoint = connectedEndpoints.entries.find { (_, info) ->
                info.ipAddress.address?.contentEquals(nextHopAddress.address) == true
            }

            if (matchingEndpoint != null) {
                log(LogLevel.INFO, "Sending packet to specific endpoint: ${matchingEndpoint.key}")
                sendPacketToEndpoint(matchingEndpoint.key, virtualPacket)
            } else {
                throw IllegalArgumentException("No connected endpoint found for IP address: $nextHopAddress")
            }
        }
    }

    /**
     * Transmits a virtual packet to a specific endpoint.
     * Converts packet to Nearby Connections payload format.
     *
     * @param endpointId Identifier of target endpoint
     * @param virtualPacket Packet data to transmit
     */
    private fun sendPacketToEndpoint(endpointId: String, virtualPacket: VirtualPacket) {
        val payload = Payload.fromBytes(virtualPacket.data)
        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener { log(LogLevel.INFO, "Virtual packet sent to $endpointId") }
            .addOnFailureListener { e -> log(LogLevel.ERROR, "Failed to send virtual packet to $endpointId", e) }
    }

    override fun connectSocket(nextHopAddress: InetAddress, destAddress: InetAddress, destPort: Int): VSocket {
        log(LogLevel.INFO, "Connecting to socket: $nextHopAddress -> $destAddress:$destPort")
        return TODO("Provide the return value")
    }



    /**
     * Endpoint discovery callback processor.
     * Manages endpoint discovery events:
     * - Processing new endpoint detection
     * - Handling endpoint loss/disconnection
     * - Maintaining endpoint status mapping
     */
    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            assertNotClosed()

            // Check if already connected
            val currentState = _endpointStatusFlow.value[endpointId]?.status
            if (currentState == EndpointStatus.CONNECTED || currentState == EndpointStatus.CONNECTING) {
                log(LogLevel.INFO, "Endpoint $endpointId already connected/connecting")
                return
            }

            val endpointIp = try {
                val parts = info.endpointName.split("|")
                if (parts.size > 1) InetAddress.getByName(parts[1]) else return
            } catch (e: Exception) {
                log(LogLevel.ERROR, "Failed to parse IP from endpoint name", e)
                return
            }

            log(LogLevel.DEBUG, "New endpoint found: $endpointId")

            _endpointStatusFlow.update { prev ->
                prev.toMutableMap().also {
                    it[endpointId] = EndpointInfo(
                        endpointId = endpointId,
                        status = EndpointStatus.DISCONNECTED,
                        ipAddress = endpointIp,
                        isOutgoing = false
                    )
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            assertNotClosed()
            log(LogLevel.INFO, "Lost endpoint: $endpointId")
            _endpointStatusFlow.update { currentMap ->
                currentMap.toMutableMap().apply {
                    remove(endpointId)
                }
            }
        }
    }

    /**
     * Payload data processor callback.
     * Routes different payload types to appropriate handlers:
     * - BYTES: Network packets and control messages
     * - STREAM: Continuous data streams
     *
     * Maintains transfer status logging.
     */
    val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            assertNotClosed()
            when (payload.type) {
                Payload.Type.BYTES -> handleBytesPayload(endpointId, payload)
                Payload.Type.STREAM -> handleStreamPayload(endpointId, payload)
                else -> log(LogLevel.WARNING, "Received unsupported payload type from: $endpointId")
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            assertNotClosed()
            log(LogLevel.DEBUG, "Payload transfer update for $endpointId: ${update.status}")
        }
    }

    /**
     * Observe endpoints and initiate connections to endpoints as required. This happens when:
     *  a) We have less connections than desired
     *  b) There are available endpoints to connect to
     *
     * Handling this as a flow ensures the process is initiated when new nodes are discovered or
     * existing connections are lost, or any combination.
     */
    private fun observeEndpointStatusFlow() {
        scope.launch {
            endpointStatusFlow.collect { endpointMap ->
                val connectedEndpoints = endpointMap.values.count {
                    it.status == EndpointStatus.CONNECTED
                }

                // Only proceed if we need more connections
                if (connectedEndpoints < desiredOutgoingConnections) {
                    val endpointsToConnectTo = endpointMap.values.filter {
                        it.status == EndpointStatus.DISCONNECTED
                    }.take(desiredOutgoingConnections - connectedEndpoints)

                    val endpointIdsToConnect = endpointsToConnectTo.map { it.endpointId }.toSet()

                    //Update the status of each endpoint that we are going to try to connect to
                    //so that when the flow updates there is no possibility of trying to connect
                    //to the same endpoint again
                    _endpointStatusFlow.update { prev ->
                        prev.mapValues { entry ->
                            if(entry.key in endpointIdsToConnect) {
                                entry.value.copy(
                                    status = EndpointStatus.CONNECTING
                                )
                            }else {
                                entry.value
                            }
                        }
                    }

                    endpointsToConnectTo.forEach {
                        launchConnectionRequest(it)
                    }
                }
            }
        }
    }

    /**
     * Connects to the given endpoint. Will yield as per the yield logic if required.
     *
     * @param endpointInfo Target endpoint identifier
     */
    private fun launchConnectionRequest(endpointInfo: EndpointInfo) {
        assertNotClosed()

        val endpointId = endpointInfo.endpointId
        scope.launch {
            if(endpointInfo.ipAddress.shouldYieldWhenConnecting) {
                log(LogLevel.INFO, "launchConnectionRequest : waiting to yield for other node")
                delay(YIELD_WAIT.toLong())

                val statusAfterYield = _endpointStatusFlow.value[endpointId]

                val connectedAfterYield = statusAfterYield?.status == EndpointStatus.CONNECTED
                log(LogLevel.INFO, "launchConnectionRequest: connected after yield = $connectedAfterYield")
                if(connectedAfterYield) {
                    return@launch
                }
            }

            connectionsClient.requestConnection(name, endpointId, connectionLifecycleCallback)
                .addOnSuccessListener {
                    log(LogLevel.INFO, "Connection request sent to endpoint: $endpointId: success")
                }
                .addOnFailureListener { e ->
                    when ((e as? ApiException)?.statusCode) {
                        8003 -> { // Already connected
                            log(LogLevel.ERROR, "ERROR: Endpoint $endpointId is already connected")
                            //Do not make any update to the status
                            //updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
                        }
                        else -> {
                            log(LogLevel.ERROR, "Failed to request connection to endpoint: $endpointId", e)
                            updateEndpointStatus(endpointId, EndpointStatus.DISCONNECTED)
                        }
                    }
                }
        }
    }

    /**
     * Processes received byte payloads from endpoints.
     * - Extracts virtual packet data
     * - Updates endpoint IP mappings
     * - Forwards to packet handler
     *
     * @param endpointId Source endpoint identifier
     * @param payload Received byte payload
     */
    private fun handleBytesPayload(endpointId: String, payload: Payload) {
        assertNotClosed()

        val bytes = payload.asBytes() ?: run {
            log(LogLevel.WARNING, "Received null payload from endpoint: $endpointId")
            return
        }

        val virtualPacket = try {
            VirtualPacket.fromData(bytes, 0)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Failed to convert payload to VirtualPacket from endpoint: $endpointId", e)
            return
        }

        try {
            onPacketReceived(virtualPacket)
        } catch (e: Exception) {
            log(LogLevel.ERROR, "Error processing VirtualPacket from $endpointId", e)
        }
    }

    /**
     * Processes received stream payloads.
     * Routes streams based on header information:
     * - Reply streams to pending requests
     * - New streams to stream handler
     *
     * @param endpointId Source endpoint identifier
     * @param payload Stream payload data
     */
    private fun handleStreamPayload(endpointId: String, payload: Payload) {
        assertNotClosed()
        payload.asStream()?.asInputStream()?.use { inputStream ->
            val header = inputStream.readNearbyStreamHeader()

            if (header.isReply) {
                streamReplies[header.streamId]?.complete(inputStream)
            } else {
                handleIncomingStream(endpointId, header, inputStream)
            }
        }
    }

    private fun handleIncomingStream(endpointId: String, header: NearbyStreamHeader, inputStream: InputStream) {
        log(LogLevel.INFO, "Received new stream from $endpointId with streamId: ${header.streamId}, fromAddress: ${header.fromAddress}, toAddress: ${header.toAddress}")
    }

    private fun updateEndpointStatus(endpointId: String, status: EndpointStatus) {
        _endpointStatusFlow.update { currentMap ->
            currentMap.toMutableMap().apply {
                this[endpointId]?.also { currentVal ->
                    this[endpointId] = currentVal.copy(status = status)
                }
            }
        }
    }

    private fun sendMmcpPingPacket(endpointId: String) {
        assertNotClosed()
        // Use virtual IP instead of broadcast for ping
        val mmcpPing = MmcpPing(Random.nextInt())
        val virtualPacket = mmcpPing.toVirtualPacket(virtualIpAddress, virtualIpAddress)  // Changed here
        val payload = Payload.fromBytes(virtualPacket.data)

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener { log(LogLevel.DEBUG, "Sent MMCP Ping to $endpointId") }
            .addOnFailureListener { e -> log(LogLevel.ERROR, "Failed to send MMCP Ping to $endpointId", e) }
    }

    private fun sendMmcpPongPacket(endpointId: String, replyToMessageId: Int) {
        assertNotClosed()
        val mmcpPong = MmcpPong(Random.nextInt(), replyToMessageId)
        val virtualPacket = mmcpPong.toVirtualPacket(virtualIpAddress, broadcastAddress)
        val payload = Payload.fromBytes(virtualPacket.data)

        connectionsClient.sendPayload(endpointId, payload)
            .addOnSuccessListener { log(LogLevel.DEBUG, "Sent MMCP Pong to $endpointId") }
            .addOnFailureListener { e -> log(LogLevel.ERROR, "Failed to send MMCP Pong to $endpointId", e) }
    }

    private fun log(level: LogLevel, message: String, exception: Exception? = null) {
        val prefix = "[NearbyVirtualNetwork:$name] "
        when (level) {
            LogLevel.VERBOSE -> logger(Log.VERBOSE, "$prefix$message", exception)
            LogLevel.DEBUG -> logger(Log.DEBUG, "$prefix$message", exception)
            LogLevel.INFO -> logger(Log.INFO, "$prefix$message", exception)
            LogLevel.WARNING -> logger(Log.WARN, "$prefix$message", exception)
            LogLevel.ERROR -> logger(Log.ERROR, "$prefix$message", exception)
        }
    }

    private fun assertNotClosed() {
        if (isClosed.get()) {
            log(LogLevel.ERROR, "assertNotClosed: Network is closed")
            throw IllegalStateException("Network is closed")
        }
    }

    companion object {

        const val YIELD_WAIT = 10_000

    }
}
