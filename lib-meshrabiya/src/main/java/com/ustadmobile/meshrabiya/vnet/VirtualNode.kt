package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.prefixMatches
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.beta.BetaTestLogger
import com.ustadmobile.meshrabiya.beta.LogLevel
import com.ustadmobile.meshrabiya.mmcp.*
import com.ustadmobile.meshrabiya.portforward.ForwardBindPoint
import com.ustadmobile.meshrabiya.portforward.UdpForwardRule
import com.ustadmobile.meshrabiya.util.findFreePort
import com.ustadmobile.meshrabiya.vnet.VirtualPacket.Companion.ADDR_BROADCAST
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.datagram.VirtualDatagramSocket2
import com.ustadmobile.meshrabiya.vnet.datagram.VirtualDatagramSocketImpl
import com.ustadmobile.meshrabiya.vnet.socket.*
import com.ustadmobile.meshrabiya.vnet.wifi.*
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.net.*
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.random.Random
import com.ustadmobile.meshrabiya.service.MeshEcosystemListener
import com.ustadmobile.meshrabiya.service.MeshGossipService
import com.ustadmobile.meshrabiya.vnet.CoreGossipBroadcastService
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService
import com.ustadmobile.meshrabiya.vnet.MeshNetworkInterface
import com.ustadmobile.meshrabiya.role.EmergentRoleManager
import com.ustadmobile.meshrabiya.vnet.OriginatingMessageManager
import com.ustadmobile.meshrabiya.service.MeshEcosystemMessage
import com.ustadmobile.meshrabiya.MeshrabiyaConstants

//Generate a random Automatic Private IP Address
fun randomApipaAddr(): Int {
    //169.254
    val fixedSection = (169 shl 24).or(254 shl 16)
    val randomSection = Random.nextInt(Short.MAX_VALUE.toInt())
    return fixedSection.or(randomSection)
}

fun randomApipaInetAddr() = InetAddress.getByAddress(randomApipaAddr().addressToByteArray())

/**
 * Mashrabiya Node
 *
 * Connection refers to the underlying "real" connection to some other device. There may be multiple
 * connections to the same remote node (e.g. Bluetooth, Sockets running over WiFi, etc)
 *
 * Addresses are 32 bit integers in the APIPA range
 */
interface HasNodeState {
    val currentNodeState: LocalNodeState
}

abstract class VirtualNode(
    val port: Int = 0,
    val json: Json = Json,
    val logger: MNetLogger = MNetLoggerStdout(),
    final override val address: InetAddress = randomApipaInetAddr(),
    override val networkPrefixLength: Int = 16,
    val config: NodeConfig = NodeConfig.DEFAULT_CONFIG,
): VirtualRouter, Closeable, HasNodeState {

    val addressAsInt: Int = address.requireAddressAsInt()
    
    /**
     * Provides context for service initialization.
     * Must be implemented by platform-specific subclasses (e.g., AndroidVirtualNode).
     */
    protected abstract fun getContext(): android.content.Context?

    // --- Proxy connection info ---
    @Volatile
    private var proxyHost: String? = null
    @Volatile
    private var proxyPort: Int? = null
    @Volatile
    private var proxyActive: Boolean = false

    // --- Set proxy connection info ---
    fun setProxy(host: String, port: Int) {
        proxyHost = host
        proxyPort = port
        logger(Log.INFO, "$logPrefix Proxy set to $host:$port", null)
    }

    // --- Set proxy active/inactive ---
    fun setProxyActive(active: Boolean) {
        proxyActive = active
        logger(Log.INFO, "$logPrefix Proxy active set to $active", null)
    }


    //This executor is used for direct I/O activities
    protected val connectionExecutor: ExecutorService = Executors.newCachedThreadPool()

    //This executor is used to schedule maintenance e.g. pings etc.
    protected val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    protected val coroutineScope = CoroutineScope(Dispatchers.Default + Job())

    private val messageCounter = AtomicInteger(0)

    private val _state = MutableStateFlow(LocalNodeState())

    val state: Flow<LocalNodeState> = _state.asStateFlow()

    override val currentNodeState: LocalNodeState
        get() = _state.value

    protected fun updateNodeState(update: (LocalNodeState) -> LocalNodeState) {
        _state.update(update)
    }

    abstract val meshrabiyaWifiManager: MeshrabiyaWifiManager

    private val pongListeners = CopyOnWriteArrayList<PongListener>()

    protected val logPrefix: String = "[VirtualNode ${addressAsInt.addressToDotNotation()}]"

    protected val iDatagramSocketFactory = VirtualNodeReturnPathSocketFactory(this)

    private val forwardingRules: MutableMap<ForwardBindPoint, UdpForwardRule> = ConcurrentHashMap()

    data class LastOriginatorMessage(
        val originatorMessage: MmcpNodeAnnouncement,
        val timeReceived: Long,
        val lastHopAddr: Int,
        val hopCount: Byte,
        val lastHopRealInetAddr: InetAddress,
        val receivedFromSocket: VirtualNodeDatagramSocket,
        val lastHopRealPort: Int,
    )

    @Suppress("unused")
    enum class Zone {
        VNET, REAL
    }

    protected open val originatingMessageManager = OriginatingMessageManager(
        localNodeInetAddr = address,
        logger = logger,
        scheduledExecutor = scheduledExecutor,
        nextMmcpMessageId = { nextMmcpMessageId() },
        getWifiState = { currentNodeState.wifiState },
        getFitnessScore = { getCurrentFitnessScore() },
        getNodeRole = { getCurrentNodeRole() }
    )

    private val localPort = findFreePort(0)

    val datagramSocket = VirtualNodeDatagramSocket(
        socket = DatagramSocket(localPort),
        ioExecutorService = connectionExecutor,
        router = this,
        localNodeVirtualAddress = addressAsInt,
        logger = logger,
    )

    protected val chainSocketFactory: ChainSocketFactory = ChainSocketFactoryImpl(
        virtualRouter = this,
        logger = logger,
    )

    val socketFactory: SocketFactory
        get() = chainSocketFactory

    private val chainSocketServer = ChainSocketServer(
        serverSocket = ServerSocket(localPort),
        executorService = connectionExecutor,
        chainSocketFactory = chainSocketFactory,
        name = addressAsInt.addressToDotNotation(),
        logger = logger
    )

    private val _incomingMmcpMessages = MutableSharedFlow<MmcpMessageAndPacketHeader>(
        replay = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val incomingMmcpMessages: Flow<MmcpMessageAndPacketHeader> = _incomingMmcpMessages.asSharedFlow()

    private val activeSockets: MutableMap<Int, VirtualDatagramSocketImpl> = ConcurrentHashMap()

    // === New Service Instantiations ===
    protected val scheduledExecutorService = Executors.newScheduledThreadPool(2)
    
    // Core mesh services instantiated with proper dependency injection
    protected val meshGossipService: MeshGossipService = MeshGossipService.initialize(this)
    
    protected val coreGossipBroadcastService: CoreGossipBroadcastService = 
        CoreGossipBroadcastService(meshGossipService)
    
    // MeshNetworkInterface implementation - bridge to VirtualNode capabilities
    protected val meshNetworkInterface: MeshNetworkInterface = 
        VirtualNode_MeshNetworkInterface(this)
    
    // MeshEcosystemListener depends on meshNetworkInterface and meshGossipService
    protected val meshEcosystemListener: MeshEcosystemListener = 
        MeshEcosystemListener(meshNetworkInterface, meshGossipService)
    
    // EmergentRoleManager initialized lazily with context from subclass
    protected val emergentRoleManager: EmergentRoleManager by lazy {
        val context = getContext() 
            ?: throw IllegalStateException("Context required for EmergentRoleManager initialization")
        EmergentRoleManager(this, context)
    }
    
    // IntelligentDistributedComputeService initialized lazily with all dependencies
    protected val intelligentDistributedComputeService: IntelligentDistributedComputeService by lazy {
        IntelligentDistributedComputeService(
            meshNetwork = meshNetworkInterface,
            resourceManager = com.ustadmobile.meshrabiya.service.compute.mesh.SimpleResourceManager(),
            pythonExecutor = createPythonExecutor(),
            // liteRTEngine = createLiteRTEngine(),
            emergentRoleManager = emergentRoleManager,
            betaLogger = com.ustadmobile.meshrabiya.beta.BetaTestLogger.getInstance(
                getContext() ?: throw IllegalStateException("Context required")
            )
        )
    }
    
    // Storage service requires additional dependencies (Context, etc.)
    // Will be initialized later via initialize() method when dependencies are available
    protected var distributedStorageManager: DistributedStorageManager? = null
    
    /**
     * Creates PythonExecutor instance. Can be overridden by subclasses.
     */
    protected open fun createPythonExecutor(): com.ustadmobile.meshrabiya.service.compute.executor.PythonExecutor {
        return object : com.ustadmobile.meshrabiya.service.compute.executor.PythonExecutor {
            override suspend fun executeTask(
                task: com.ustadmobile.meshrabiya.service.compute.model.ComputeTask.PythonTask
            ): com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutionResult {
                // Simple stub implementation - override in subclass for real functionality
                return com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutionResult.Failed(
                    taskId = task.taskId,
                    error = "PythonExecutor not implemented"
                )
            }
        }
    }
    
    /**
     * Creates LiteRTEngine instance. Can be overridden by subclasses.
     */
    // protected open fun createLiteRTEngine(): com.ustadmobile.meshrabiya.service.compute.executor.LiteRTEngine {
    //     return object : com.ustadmobile.meshrabiya.service.compute.executor.LiteRTEngine {
    //         override suspend fun executeTask(
    //             task: com.ustadmobile.meshrabiya.service.compute.model.ComputeTask.LiteRTTask
    //         ): com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutionResult {
    //             // Simple stub implementation - override in subclass for real functionality
    //             return com.ustadmobile.meshrabiya.service.compute.executor.TaskExecutionResult.Failed(
    //                 taskId = task.taskId,
    //                 error = "LiteRTEngine not implemented"
    //             )
    //         }
    //     }
    // }
    

    init {
        _state.update { prev ->
            prev.copy(
                address = addressAsInt,
                connectUri = generateConnectLink(hotspot = null).uri
            )
        }

        coroutineScope.launch {
            try {
                originatingMessageManager.state.collect { state ->
                    _state.update { prev ->
                        prev.copy(
                            originatorMessages = originatingMessageManager.getOriginatorMessages()
                        )
                    }
                }
            } catch (e: Exception) {
                safeLog(
                    LogLevel.ERROR,
                    "VirtualNode",
                    "Error in originatingMessageManager state collection",
                    mapOf("address" to address.hostAddress),
                    e
                )
            }
        }
    }

    override fun nextMmcpMessageId(): Int {
        return messageCounter.incrementAndGet()
    }

    abstract fun getCurrentFitnessScore(): Int
    abstract fun getCurrentNodeRole(): Byte

    override fun allocateUdpPortOrThrow(
        virtualDatagramSocketImpl: VirtualDatagramSocketImpl,
        portNum: Int
    ): Int {
        if(portNum > 0) {
            if(activeSockets.containsKey(portNum))
                throw IllegalStateException("VirtualNode: port $portNum already allocated!")
            activeSockets[portNum] = virtualDatagramSocketImpl
            return portNum
        }

        var attemptCount = 0
        do {
            val randomPort = Random.nextInt(0, Short.MAX_VALUE.toInt())
            if(!activeSockets.containsKey(randomPort)) {
                activeSockets[randomPort] = virtualDatagramSocketImpl
                return randomPort
            }
            attemptCount++
        }while(attemptCount < 100)

        throw IllegalStateException("Could not allocate random free port")
    }

    override fun deallocatePort(protocol: Protocol, portNum: Int) {
        activeSockets.remove(portNum)
    }

    fun createDatagramSocket(): DatagramSocket {
        return VirtualDatagramSocket2(this, addressAsInt, logger)
    }

    fun createBoundDatagramSocket(port: Int): DatagramSocket {
        return createDatagramSocket().also {
            it.bind(InetSocketAddress(address, port))
        }
    }

    fun forward(
        bindAddress: InetAddress,
        bindPort: Int,
        destAddress: InetAddress,
        destPort: Int,
    ) : Int {
        val listenSocket = if(
            bindAddress.prefixMatches(networkPrefixLength, address)
        ) {
            createBoundDatagramSocket(bindPort)
        }else {
            DatagramSocket(bindPort, bindAddress)
        }

        val forwardRule = createForwardRule(listenSocket, destAddress, destPort)
        val boundPort = listenSocket.localPort
        forwardingRules[ForwardBindPoint(bindAddress, null, boundPort)] = forwardRule

        return boundPort
    }

    fun forward(
        bindZone: Zone,
        bindPort: Int,
        destAddress: InetAddress,
        destPort: Int
    ): Int {
        val listenSocket = if(bindZone == Zone.VNET) {
            createBoundDatagramSocket(bindPort)
        }else {
            DatagramSocket(bindPort)
        }
        val forwardRule = createForwardRule(listenSocket, destAddress, destPort)
        val boundPort = listenSocket.localPort
        forwardingRules[ForwardBindPoint(null, bindZone, boundPort)] = forwardRule
        return boundPort
    }

    fun stopForward(
        bindZone: Zone,
        bindPort: Int
    ) {

    }

    fun stopForward(
        bindAddr: InetAddress,
        bindPort: Int,
    ) {

    }

    private fun createForwardRule(
        listenSocket: DatagramSocket,
        destAddress: InetAddress,
        destPort: Int,
    ) : UdpForwardRule {
        return UdpForwardRule(
            boundSocket = listenSocket,
            ioExecutor = this.connectionExecutor,
            destAddress = destAddress,
            destPort = destPort,
            logger = logger,
            returnPathSocketFactory = iDatagramSocketFactory,
        )
    }

    override val localDatagramPort: Int
        get() = datagramSocket.localPort

    protected fun generateConnectLink(
        hotspot: WifiConnectConfig?,
        bluetoothConfig: MeshrabiyaBluetoothState? = null,
    ) : MeshrabiyaConnectLink {
        return MeshrabiyaConnectLink.fromComponents(
            nodeAddr = addressAsInt,
            port = localDatagramPort,
            hotspotConfig = hotspot,
            bluetoothConfig = bluetoothConfig,
            json = json,
        )
    }

    private fun onIncomingMmcpMessage(
        virtualPacket: VirtualPacket,
        datagramPacket: DatagramPacket?,
        datagramSocket: VirtualNodeDatagramSocket?,
    ) : Boolean {
        try {
            val mmcpMessage = MmcpMessage.fromVirtualPacket(virtualPacket)
            val from = virtualPacket.header.fromAddr
            logger(Log.VERBOSE,
                message = {
                    "$logPrefix received MMCP message (${mmcpMessage::class.simpleName}) " +
                    "from ${from.addressToDotNotation()}"
                }
            )

            val isToThisNode = virtualPacket.header.toAddr == addressAsInt

            var shouldRoute = true

            when {
                mmcpMessage is MmcpPing && isToThisNode -> {
                    logger(Log.VERBOSE,
                        message = {
                            "$logPrefix Received ping(id=${mmcpMessage.messageId}) from ${from.addressToDotNotation()}"
                        }
                    )
                    val pongMessage = MmcpPong(
                        messageId = nextMmcpMessageId(),
                        replyToMessageId = mmcpMessage.messageId
                    )

                    val replyPacket = pongMessage.toVirtualPacket(
                        toAddr = from,
                        fromAddr = addressAsInt
                    )

                    logger(Log.VERBOSE, { "$logPrefix Sending pong to ${from.addressToDotNotation()}" })
                    route(replyPacket)
                }

                mmcpMessage is MmcpPong && isToThisNode -> {
                    logger(Log.VERBOSE, { "$logPrefix Received pong(id=${mmcpMessage.messageId})}" })
                    originatingMessageManager.onPongReceived(from, mmcpMessage)
                    pongListeners.forEach {
                        it.onPongReceived(from, mmcpMessage)
                    }
                }

                mmcpMessage is MmcpHotspotRequest && isToThisNode -> {
                    logger(Log.INFO, "$logPrefix Received hotspotrequest (id=${mmcpMessage.messageId})", null)
                    coroutineScope.launch {
                        val hotspotResult = meshrabiyaWifiManager.requestHotspot(
                            mmcpMessage.messageId, mmcpMessage.hotspotRequest
                        )

                        if(from != addressAsInt) {
                            val replyPacket = MmcpHotspotResponse(
                                messageId = mmcpMessage.messageId,
                                result = hotspotResult
                            ).toVirtualPacket(
                                toAddr = from,
                                fromAddr = addressAsInt
                            )
                            logger(Log.INFO, "$logPrefix sending hotspotresponse to ${from.addressToDotNotation()}", null)
                            route(replyPacket)
                        }
                    }
                }

                mmcpMessage is MmcpNodeAnnouncement -> {
                    shouldRoute = originatingMessageManager.onReceiveOriginatingMessage(
                        mmcpMessage = mmcpMessage,
                        datagramPacket = datagramPacket ?: return false,
                        datagramSocket = datagramSocket ?: return false,
                        virtualPacket = virtualPacket,
                    )
                }

                mmcpMessage is MmcpGatewayAnnouncement -> {
                    logger(Log.INFO, "$logPrefix received gateway announcement from ${from.addressToDotNotation()}: ${mmcpMessage.gatewayType}", null)
                    onGatewayAnnouncementReceived(mmcpMessage, from)
                    shouldRoute = true
                }

                else -> {
                    // do nothing
                }
            }

            _incomingMmcpMessages.tryEmit(MmcpMessageAndPacketHeader(mmcpMessage, virtualPacket.header))

            return shouldRoute
        }catch(e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    // Deduplication cache for broadcast packets
    private val seenBroadcasts = ConcurrentHashMap<String, Long>()
    private val broadcastTtlMs: Long = 60_000L

    override fun route(
        packet: VirtualPacket,
        datagramPacket: DatagramPacket?,
        virtualNodeDatagramSocket: VirtualNodeDatagramSocket?
    ) {
        try {
            val fromLastHop = packet.header.lastHopAddr

            if(packet.header.hopCount >= config.maxHops) {
                logger(Log.DEBUG,
                    "Drop packet from ${packet.header.fromAddr.addressToDotNotation()} - " +
                            "${packet.header.hopCount} exceeds ${config.maxHops}",
                    null)
                return
            }

            // MMCP message handling (unchanged)
            if(packet.header.toPort == 0 && packet.header.fromAddr != addressAsInt){
                if(!onIncomingMmcpMessage(packet, datagramPacket, virtualNodeDatagramSocket)){
                    logger(Log.DEBUG, "Drop mmcp packet from ${packet.header.fromAddr}", null)
                }
            }

            // Ecosystem message handling (UDP broadcast or direct)
            val ecosystemPort = MeshrabiyaConstants.getEcosystemGossipPort()
            if(packet.header.toPort == ecosystemPort) {
                val bytes = packet.data.copyOfRange(packet.payloadOffset, packet.payloadOffset + packet.header.payloadSize)
                val msgType = try {
                    MeshEcosystemMessage.fromBytes(bytes).type
                } catch (e: Exception) {
                    logger(Log.WARN, "$logPrefix: Failed to deserialize MeshEcosystemMessage: ${e.message}")
                    null
                }
                if (msgType != null) {
                    coreGossipBroadcastService.onReceiveBroadcast(bytes, msgType)
                }
                return
            }

            // --- CONDITIONAL PROXY ROUTING ---
            val currentRoles = emergentRoleManager.getCurrentMeshRoles()
            if (proxyActive && currentRoles.contains(MeshRole.TOR_GATEWAY)) {
                // Route internet traffic via proxy (Tor)
                if (shouldRouteViaProxy(packet)) {
                    routeViaProxy(packet)
                    logger(Log.INFO, "$logPrefix Routed packet via proxy $proxyHost:$proxyPort", null)
                    return
                }
            }

            if(packet.header.toAddr == addressAsInt) {
                val listeningSocket = activeSockets[packet.header.toPort]
                if(listeningSocket != null) {
                    listeningSocket.onIncomingPacket(packet)
                }else {
                    logger(Log.DEBUG, "$logPrefix Incoming packet received, but no socket listening on: ${packet.header.toPort}")
                }
            }else {
                val toAddr = packet.header.toAddr
                packet.updateLastHopAddrAndIncrementHopCountInData(addressAsInt)
                if(toAddr == ADDR_BROADCAST) {
                    val broadcastId = computeBroadcastId(packet)
                    val now = System.currentTimeMillis()
                    val prev = seenBroadcasts.putIfAbsent(broadcastId, now)
                    if (prev == null) {
                        val meshRoles = emergentRoleManager.getCurrentMeshRoles()
                        if (meshRoles.contains(MeshRole.MESH_ROUTER)) {
                            logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, forwarding to neighbors (role=MESH_ROUTER)")
                            originatingMessageManager.neighbors().filter {
                                it.first != fromLastHop && it.first != packet.header.fromAddr
                            }.forEach {
                                logger(Log.VERBOSE, "$logPrefix: Forwarding broadcast to neighbor ${it.first}")
                                it.second.receivedFromSocket.send(
                                    nextHopAddress = it.second.lastHopRealInetAddr,
                                    nextHopPort = it.second.lastHopRealPort,
                                    virtualPacket = packet,
                                )
                            }
                        } else {
                            logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, but node is not MESH_ROUTER, not forwarding")
                        }
                    }
                }else {
                    val originatorMessage = originatingMessageManager
                        .findOriginatingMessageFor(packet.header.toAddr)
                    if(originatorMessage != null) {
                        originatorMessage.receivedFromSocket.send(
                            nextHopAddress = originatorMessage.lastHopRealInetAddr,
                            nextHopPort = originatorMessage.lastHopRealPort,
                            virtualPacket = packet
                        )
                    }else {
                        logger(Log.WARN, "$logPrefix route: Cannot route packet to " +
                                "${packet.header.toAddr.addressToDotNotation()} : no known nexthop")
                    }
                }
            }
        }catch(e: Exception) {
            logger(Log.ERROR,
                "$logPrefix : route : exception routing packet from ${packet.header.fromAddr.addressToDotNotation()}",
                e
            )
            throw e
        }
    }

    override fun lookupNextHopForChainSocket(address: InetAddress, port: Int): ChainSocketNextHop {
        return originatingMessageManager.lookupNextHopForChainSocket(address, port)
    }

    fun addNewNeighborConnection(
        address: InetAddress,
        port: Int,
        neighborNodeVirtualAddr: Int,
        socket: VirtualNodeDatagramSocket,
    ) {
        logger(Log.DEBUG,
            "$logPrefix addNewNeighborConnection connection to virtual addr " +
                    "${neighborNodeVirtualAddr.addressToDotNotation()} " +
                    "via datagram to $address:$port",
            null
        )

        coroutineScope.launch {
            originatingMessageManager.addNeighbor(
                neighborRealInetAddr = address,
                neighborRealPort = port,
                socket =  socket,
            )
        }
    }

    fun addPongListener(listener: PongListener) {
        pongListeners += listener
    }

    fun removePongListener(listener: PongListener) {
        pongListeners -= listener
    }

    open suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand = ConnectBand.BAND_2GHZ,
        hotspotType: HotspotType = HotspotType.AUTO,
    ): LocalHotspotResponse? {
        return if(enabled){
             meshrabiyaWifiManager.requestHotspot(
                requestMessageId = nextMmcpMessageId(),
                request = LocalHotspotRequest(
                    preferredBand = preferredBand,
                    preferredType = hotspotType,
                )
            )
        }else {
            meshrabiyaWifiManager.deactivateHotspot()
            LocalHotspotResponse(
                responseToMessageId = 0,
                config = null,
                errorCode = 0,
                redirectAddr = 0,
            )
        }
    }

    fun sendMessage(message: MmcpMessage) {
        originatingMessageManager.sendMessage(message)
    }

    protected open fun onGatewayAnnouncementReceived(announcement: MmcpGatewayAnnouncement, fromNodeAddr: Int) {
        logger(Log.INFO, "$logPrefix Gateway ${announcement.gatewayType} available from ${fromNodeAddr.addressToDotNotation()}")
        try {
            if (announcement.isActive && announcement.capacity.downloadMbps > 0) {
                logger(Log.DEBUG, "$logPrefix Valid gateway: capacity=${announcement.capacity.downloadMbps}Mbps, latency=${announcement.latency.averageMs}ms")
            }
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix Error processing gateway announcement: ${e.message}")
        }
    }

    fun getCurrentState(): LocalNodeState {
        return currentNodeState
    }

    fun neighbors() = originatingMessageManager.neighbors()

    override fun close() {
        datagramSocket.close(closeSocket = true)
        chainSocketServer.close(closeSocket = true)
        coroutineScope.cancel(message = "VirtualNode closed")
        connectionExecutor.shutdown()
        scheduledExecutor.shutdown()
    }

    internal fun getOriginatingMessageManager() = originatingMessageManager

    protected fun safeLog(
        level: LogLevel,
        category: String,
        message: String,
        metadata: Map<String, String?> = emptyMap(),
        throwable: Throwable? = null
    ) {
        try {
            val betaLogger = (logger as? BetaTestLogger)
            if (betaLogger != null) {
                val nonNullMetadata: Map<String, String> = if (metadata.isEmpty()) {
                    emptyMap()
                } else {
                    metadata.mapValues { it.value ?: "" }
                }
                betaLogger.log(level, category, message, nonNullMetadata, throwable)
            } else {
                val formattedMessage = if (metadata.isNotEmpty()) {
                    "$message [${metadata.map { "${it.key}=${it.value}" }.joinToString(", ")}]"
                } else {
                    message
                }
                when (level) {
                    LogLevel.ERROR -> logger(Log.ERROR, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.WARN -> logger(Log.WARN, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.INFO -> logger(Log.INFO, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.DEBUG -> logger(Log.DEBUG, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.DETAILED -> logger(Log.DEBUG, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.FULL -> logger(Log.VERBOSE, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.BASIC -> logger(Log.INFO, "[$category] $formattedMessage", throwable as? Exception)
                    LogLevel.DISABLED -> { }
                }
            }
        } catch (e: Exception) {
            Log.e("VirtualNode", "Logging failed: ${e.message}, original message: $message", e)
        }
    }

    // --- Helper: Should route via proxy ---
    private fun shouldRouteViaProxy(packet: VirtualPacket): Boolean {
        // Define logic for which packets should go via proxy (Tor)
        // Example: packets destined for Internet (not mesh addresses)
        // Here, you may want to check packet.header.toAddr or other fields
        // For now, route all non-mesh traffic if proxy is active and TOR_GATEWAY role is present
        return true
    }

    // --- Helper: Route via proxy ---
    private fun routeViaProxy(packet: VirtualPacket) {
        val host = proxyHost ?: return
        val port = proxyPort ?: return
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
            val socket = Socket(proxy)
            // Example: send packet data via socket
            socket.getOutputStream().write(packet.data)
            socket.close()
        } catch (e: Exception) {
            logger(Log.ERROR, "$logPrefix Failed to route via proxy: ${e.message}", e)
        }
    }

    fun getMeshGossipService(): MeshGossipService = meshGossipService
    fun getCoreGossipBroadcastService(): CoreGossipBroadcastService = coreGossipBroadcastService
    fun getDistributedStorageManager(): DistributedStorageManager? = distributedStorageManager
    fun getIntelligentDistributedComputeService(): IntelligentDistributedComputeService = intelligentDistributedComputeService
    fun getMeshEcosystemListener(): MeshEcosystemListener = meshEcosystemListener
    fun getMeshNetworkInterface(): MeshNetworkInterface = meshNetworkInterface
    fun getEmergentRoleManager(): EmergentRoleManager = emergentRoleManager
    fun getOriginatingMessageManager(): OriginatingMessageManager = originatingMessageManager
}