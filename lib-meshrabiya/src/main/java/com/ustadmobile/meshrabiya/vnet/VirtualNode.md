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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.asSharedFlow
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
import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.ustadmobile.meshrabiya.storage.DistributedStorageManager
// Removed: import com.ustadmobile.meshrabiya.service.compute.IntelligentDistributedComputeService (deprecated - replaced by DistributedComputeClient/Server)
import com.ustadmobile.meshrabiya.service.compute.TaskManager
import com.ustadmobile.meshrabiya.service.compute.DistributedComputeClient
import com.ustadmobile.meshrabiya.service.compute.DistributedComputeServer
// Removed: import com.ustadmobile.meshrabiya.role.EmergentRoleManager (old package)
import com.ustadmobile.meshrabiya.vnet.OriginatingMessageManager
// NEW: Import hardware capability classes for getCurrentNodeCapabilities()
import com.ustadmobile.meshrabiya.vnet.hardware.ResourceCapabilities
import com.ustadmobile.meshrabiya.vnet.hardware.BatteryInfo
import com.ustadmobile.meshrabiya.vnet.hardware.BatteryHealth
import com.ustadmobile.meshrabiya.vnet.hardware.PowerState
import com.ustadmobile.meshrabiya.vnet.hardware.ThermalState  // Use hardware package version

import com.ustadmobile.meshrabiya.service.MeshEcosystemMessage
import com.ustadmobile.meshrabiya.MeshrabiyaConstants
import android.content.Context
// import com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler
import com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastPacketSerializer

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
    val appContext: Context
): VirtualRouter, Closeable, HasNodeState {

    /**
     * Data class to hold real-time bit rate metrics.
     */
    data class BitRateMetrics(
        val uploadBitRateBps: Long = 0L,
        val downloadBitRateBps: Long = 0L
    )

    // StateFlow to expose real-time bit rate metrics
    private val _bitRateMetrics = MutableStateFlow(BitRateMetrics())
    val bitRateMetrics: StateFlow<BitRateMetrics> = _bitRateMetrics.asStateFlow()

    val addressAsInt: Int = address.requireAddressAsInt()
    fun getInetAddressFor(addr: Int) = InetAddress.getByAddress(addr.addressToByteArray())
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
    internal val connectionExecutor: ExecutorService = Executors.newCachedThreadPool()

    //This executor is used to schedule maintenance e.g. pings etc.
    protected val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    protected val coroutineScope = CoroutineScope(Dispatchers.Default + Job())

    /**
     * Public method to increment uploadBytes in LocalNodeState in a thread-safe way.
     */
    fun incrementUploadBytes(amount: Long) {
        updateNodeState { prev ->
            prev.copy(uploadBytes = prev.uploadBytes + amount)
        }
    }

    /**
     * Public method to increment downloadBytes in LocalNodeState in a thread-safe way.
     */
    fun incrementDownloadBytes(amount: Long) {
        updateNodeState { prev ->
            prev.copy(downloadBytes = prev.downloadBytes + amount)
        }
    }

    private val messageCounter = AtomicInteger(0)

    protected open val _state = MutableStateFlow(LocalNodeState())

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

    // MeshConnectionPool: instantiate and initialize singleton
    protected val meshConnectionPool: MeshConnectionPool = MeshConnectionPool(this)

    /**
     * Public exposure for mesh connection pool metrics (available/total).
     * Used by BroadcastMessageHandler for diagnostics without requiring protected access.
     */
    fun getMeshConnectionPoolStats(): Pair<Int, Int> {
        return meshConnectionPool.availableConnections() to meshConnectionPool.maxPoolSize()
    }

    fun acquireMeshConnection(timeoutMs: Long = MeshrabiyaConstants.ROUTE_CONNECTION_ACQUIRE_TIMEOUT_MS): MeshConnectionPool.Connection? {
        return try {
            meshConnectionPool.acquireConnection(timeoutMs)
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix acquireMeshConnection failed: ${e.message}")
            null
        }
    }

    fun releaseMeshConnection(connection: MeshConnectionPool.Connection) {
        meshConnectionPool.releaseConnection(connection)
    }

    init {
        MeshConnectionPool.init(this)
        // Coroutine to calculate and update real-time bit rates
        coroutineScope.launch {
            var lastUploadBytes = currentNodeState.uploadBytes
            var lastDownloadBytes = currentNodeState.downloadBytes
            var lastTimestamp = System.currentTimeMillis()
            while (true) {
                delay(1000L) // 1 second interval
                val now = System.currentTimeMillis()
                val elapsedMs = now - lastTimestamp
                val elapsedSec = if (elapsedMs > 0) elapsedMs / 1000.0 else 1.0
                val currentUpload = currentNodeState.uploadBytes
                val currentDownload = currentNodeState.downloadBytes
                val uploadDelta = currentUpload - lastUploadBytes
                val downloadDelta = currentDownload - lastDownloadBytes
                val uploadBps = (uploadDelta / elapsedSec).toLong()
                val downloadBps = (downloadDelta / elapsedSec).toLong()
                _bitRateMetrics.value = BitRateMetrics(
                    uploadBitRateBps = if (uploadBps >= 0) uploadBps else 0L,
                    downloadBitRateBps = if (downloadBps >= 0) downloadBps else 0L
                )
                lastUploadBytes = currentUpload
                lastDownloadBytes = currentDownload
                lastTimestamp = now
            }
        }
    }

    data class LastOriginatorMessage(
        val originatorMessage: MmcpOriginatorMessage,  // Correct type
        val timeReceived: Long,
        val lastHopAddr: Int,
        val hopCount: Byte,
        val lastHopRealInetAddr: InetAddress,
        val receivedFromSocket: VirtualNodeDatagramSocket,
        val lastHopRealPort: Int,
        val neighborAddr: InetAddress,
    )

    @Suppress("unused")
    enum class Zone {
        VNET, REAL
    }

    /**
     * Get current node capabilities. Default implementation returns a basic snapshot.
     * Can be overridden by subclasses to provide real hardware metrics.
     */
    protected open fun getCurrentNodeCapabilities(): NodeCapabilitySnapshot {
        return NodeCapabilitySnapshot(
            nodeId = addressAsInt.toString(),
            resources = ResourceCapabilities(
                availableCPU = 0.5f,
                availableRAM = Runtime.getRuntime().freeMemory(),
                availableBandwidth = 10_000_000L,
                storageOffered = 0L,
                batteryLevel = 50,
                thermalThrottling = false,
                powerState = PowerState.BATTERY_MEDIUM,

            
                networkInterfaces = emptySet()
            ),
            batteryInfo = BatteryInfo(
                level = 50,
                isCharging = false,
                estimatedTimeRemaining = null,
                temperatureCelsius = 25,
                health = BatteryHealth.GOOD,
                chargingSource = null
            ),
            thermalState = ThermalState.COOL,
            networkQuality = 0.5f,
            stability = 0.8f
        )
    }

    // === STEP 1: Create EmergentRoleManager with topology callback ===
    open val emergentRoleManager: EmergentRoleManager = EmergentRoleManager(
        virtualNode = this,
        context = appContext,
        getTopologyMap = { originatingMessageManager.getTopologyMapInfo() },
        getCurrentNodeCapabilities = { getCurrentNodeCapabilities() }
    )

    // === STEP 2: Create OriginatingMessageManager with EmergentRoleManager callbacks ===
    open val originatingMessageManager = OriginatingMessageManager(
        localNodeInetAddr = address,
        logger = logger,
        scheduledExecutor = scheduledExecutor,
        nextMmcpMessageId = { nextMmcpMessageId() },
        getWifiState = { currentNodeState.wifiState },
        
        // === NEW: Callbacks to EmergentRoleManager ===
        getCentralityScore = { emergentRoleManager.calculateCentralityScore() },
        getMeshRoles = { emergentRoleManager.currentMeshRoles.value },
        getFitnessScore = { 
            emergentRoleManager.calculateNormalizedFitness(getCurrentNodeCapabilities()) 
        },
        getInternetSignalInfo = {
            (meshrabiyaWifiManager as? MeshrabiyaWifiManagerAndroid)
                ?.getInternetWifiSignalInfo()
                ?.let { Pair(it.rssiDbm, it.linkSpeedMbps) }
                ?: Pair(0, 0)
        },

        // === EXISTING PARAMS ===
        pingTimeout = 15_000,
        originatingMessageNodeLostThreshold = 10_000,
        lostNodeCheckInterval = 1_000
    )

    // === Gateway Selector and Router (Phase 4) ===
    protected val gatewaySelector: GatewaySelector by lazy {
        GatewaySelector(
            originatingMessageManager = originatingMessageManager,
            emergentRoleManager = emergentRoleManager,
            logger = logger,
            localNodeAddress = addressAsInt
        )
    }

    protected val gatewayRouter: GatewayRouter by lazy {
        GatewayRouter(
            gatewaySelector = gatewaySelector,
            virtualNode = this,
            logger = logger,
            localNodeAddress = addressAsInt
        )
    }

    private val localPort = findFreePort(0)

    val datagramSocket = VirtualNodeDatagramSocket(
        socket = DatagramSocket(localPort),
        ioExecutorService = connectionExecutor,
        router = this,
        localNodeVirtualAddress = addressAsInt,
        logger = logger,
        name = "main-virtual-node",
        boundNetwork = null
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
    
    // Core mesh services instantiated with proper dependency injection
    protected val meshGossipService: MeshGossipService = MeshGossipService.initialize(this)
    
    open val coreGossipBroadcastService: CoreGossipBroadcastService = 
        CoreGossipBroadcastService.getInstance()
    
    
    // MeshEcosystemListener depends on emergentRoleManager and meshGossipService
    protected val meshEcosystemListener: MeshEcosystemListener by lazy {
        val listener = MeshEcosystemListener(this)
        // Register compute services when they're initialized
        listener.registerComputeClient(distributedComputeClient)
        listener.registerComputeServer(distributedComputeServer)
        listener
    }
    
    // DEPRECATED: IntelligentDistributedComputeService - replaced by DistributedComputeClient/Server in CANONICAL_WORKFLOW_v2
    // Will be removed after Part 2 implementation completes
    // protected val intelligentDistributedComputeService: IntelligentDistributedComputeService by lazy {
    //     IntelligentDistributedComputeService(
    //         virtualNode = this,
    //         emergentRoleManager = emergentRoleManager,
    //         betaLogger = BetaTestLogger.getInstance(
    //             getContext() ?: throw IllegalStateException("Context required")
    //         )
    //     )
    // }
    
    // Storage service requires additional dependencies (Context, etc.)
    // Will be initialized later via initialize() method when dependencies are available
    open var distributedStorageManager: DistributedStorageManager? = null
    
    /**
     * Handler for broadcast message+file operations
     * Set by MeshrabiyaApiImpl during initialization
     * Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
     */
    var broadcastMessageHandler: com.ustadmobile.meshrabiya.vnet.broadcast.BroadcastMessageHandler? = null
    
    // TaskManager: Orchestrates compute task lifecycle on compute node
    protected val taskManager: TaskManager by lazy {
        TaskManager(
            context = appContext ?: throw IllegalStateException("Context required for TaskManager"),
            virtualNode = this,
            distributedStorageClient = distributedStorageManager?.getDistributedStorageClient()
                ?: throw IllegalStateException("DistributedStorageClient required for TaskManager"),
            betaLogger = BetaTestLogger.getInstance(
                appContext ?: throw IllegalStateException("Context required")
            )
        )
    }
    
    // DistributedComputeClient: Client-side distributed compute service
    protected val distributedComputeClient: DistributedComputeClient by lazy {
        DistributedComputeClient(
            context = appContext ?: throw IllegalStateException("Context required for DistributedComputeClient"),
            virtualNode = this,
            betaLogger = BetaTestLogger.getInstance(
                appContext ?: throw IllegalStateException("Context required")
            )
        )
    }
    
    /**
     * Public accessor for DistributedComputeClient (for MeshrabiyaApi)
     * Added 2025-12-06 for API-level task submission
     */
    fun obtainDistributedComputeClient(): DistributedComputeClient = distributedComputeClient
    
    /**
     * Public accessor for MeshEcosystemListener (for MeshrabiyaApi)
     * Added 2025-12-06 for distributed storage enable/disable
     */
    fun obtainMeshEcosystemListener(): MeshEcosystemListener = meshEcosystemListener
    
    // DistributedComputeServer: Server-side distributed compute service
    protected val distributedComputeServer: DistributedComputeServer by lazy {
        DistributedComputeServer(
            context = appContext ?: throw IllegalStateException("Context required for DistributedComputeServer"),
            virtualNode = this,
            emergentRoleManager = emergentRoleManager,
            taskManager = taskManager,
            distributedStorageClient = distributedStorageManager?.getDistributedStorageClient()
                ?: throw IllegalStateException("DistributedStorageClient required for DistributedComputeServer"),
            betaLogger = BetaTestLogger.getInstance(
                appContext ?: throw IllegalStateException("Context required")
            )
        )
    }
    
    // Deprecated: PythonExecutor and LiteRTEngine stubs removed. Canonical compute logic is implemented in IntelligentDistributedComputeService and PythonExecutor domain files.
    

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

    // Abstract methods removed - now using callbacks through OriginatingMessageManager and EmergentRoleManager

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

    override fun notifyHotspotInterference(reconnectionCount: Int) {
        logger(Log.WARN, "Hotspot interference: WiFi reconnected $reconnectionCount times", null)
        // Subclasses can override to show UI notification
    }

    override fun notifyHotspotLost(reason: String) {
        logger(Log.ERROR, "Hotspot lost: $reason", null)
        // Subclasses can override to show UI notification
    }

    fun createDatagramSocket(): DatagramSocket {
        return VirtualDatagramSocket2(this, addressAsInt, logger, this, appContext)
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
        // CRITICAL FIX: Check if this is a broadcast packet BEFORE attempting MMCP parsing
        val payload = virtualPacket.data
        val payloadSize = virtualPacket.header.payloadSize
        val offset = virtualPacket.payloadOffset

        logger(Log.DEBUG, "$logPrefix: [PKT_CHECK] dataSize=${payload.size}, payloadOffset=$offset, payloadSize=$payloadSize, toPort=${virtualPacket.header.toPort}, fromAddr=${virtualPacket.header.fromAddr.addressToDotNotation()}")
        logger(Log.DEBUG, "$logPrefix: [PKT_CHECK] isEmpty=${payload.isEmpty()}, offsetValid=${offset < payload.size}, boundsCheck=${offset + payloadSize <= payload.size}")

        

        // Handle GATEWAY_DOWN before full MMCP parsing — what=18 is not a registered MMCP class
        val payloadOff = virtualPacket.payloadOffset
        if (payload.size > payloadOff && payload[payloadOff] == MmcpMessage.WHAT_GATEWAY_DOWN) {
            val senderAddr = virtualPacket.header.fromAddr
            logger(Log.INFO, "$logPrefix GATEWAY_DOWN from ${senderAddr.addressToDotNotation()}: clearing gateway roles")
            originatingMessageManager.markNodeGatewayDown(senderAddr)
            emergentRoleManager.updateRoles()
            return false
        }
        
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

                // Phase 3: Changed from MmcpNodeAnnouncement to MmcpOriginatorMessage
                mmcpMessage is MmcpOriginatorMessage -> {
                    shouldRoute = originatingMessageManager.onReceiveOriginatingMessage(
                        mmcpMessage = mmcpMessage,
                        datagramPacket = datagramPacket ?: return false,
                        datagramSocket = datagramSocket ?: return false,
                        virtualPacket = virtualPacket,
                    )
                }

                // DEPRECATED: MmcpGatewayAnnouncement class moved to .md (commented out to fix compilation)
                // mmcpMessage is MmcpGatewayAnnouncement -> {
                //     logger(Log.INFO, "$logPrefix received gateway announcement from ${from.addressToDotNotation()}: ${mmcpMessage.gatewayType}", null)
                //     onGatewayAnnouncementReceived(mmcpMessage, from)
                //     shouldRoute = true
                // }

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
    private val seenBroadcasts = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val broadcastTtlMs: Long = 60_000L
    
    private fun computeBroadcastId(packet: VirtualPacket): String {
        return "${packet.header.fromAddr}-${packet.header.fromPort}-${packet.header.payloadSize}"
    }

    override fun route(
        packet: VirtualPacket,
        datagramPacket: DatagramPacket?,
        virtualNodeDatagramSocket: VirtualNodeDatagramSocket?
    ) {
        // Copy packet data immediately to prevent buffer corruption
        // The original datagramPacket buffer is reused by network socket
        val packetDataCopy = packet.data.copyOfRange(0, packet.data.size)
        val packetCopy = VirtualPacket.fromHeaderAndPayloadData(
            header = packet.header,
            data = packetDataCopy,
            payloadOffset = packet.payloadOffset,
            headerAlreadyInData = true
        )

        // Log routing decision and packet details
        logger(Log.DEBUG, "$logPrefix Routing packet: from=${packetCopy.header.fromAddr.addressToDotNotation()} to=${packetCopy.header.toAddr.addressToDotNotation()} size=${packetCopy.header.payloadSize}")

        // --- ADDED: Deep packet inspection for broadcast chunk ---
        try {
            val payloadSize = packetCopy.header.payloadSize
            if (payloadSize > 0) {
                val payload = packetCopy.data.copyOfRange(packetCopy.payloadOffset, packetCopy.payloadOffset + payloadSize)
                val packetType = BroadcastPacketSerializer.getPacketType(payload)
                if (packetType == BroadcastPacketSerializer.TYPE_BROADCAST_CHUNK) {
                    val (broadcastId, _, chunkPair) = BroadcastPacketSerializer.deserialize(payload)
                    val chunkMeta = chunkPair.first
                    logger(Log.INFO, "$logPrefix [ROUTE_BROADCAST_CHUNK] from=${packetCopy.header.fromAddr.addressToDotNotation()} to=${packetCopy.header.toAddr.addressToDotNotation()} broadcastId=$broadcastId chunkId=${chunkMeta.chunkId} chunkIndex=${chunkMeta.chunkIndex}")
                }
            }
        } catch (e: Exception) {
            logger(Log.WARN, "$logPrefix [ROUTE_BROADCAST_CHUNK] failed to inspect broadcast chunk: ${e.message}")
        }
        // --- END ADDED ---

        // Offload ALL processing to connection pool to free IO thread immediately
        connectionExecutor.execute {
            var connection: MeshConnectionPool.Connection? = null
            val startTime = System.currentTimeMillis()
            
            try {
                // Acquire connection from pool with timeout
                connection = meshConnectionPool.acquireConnection(
                    timeoutMs = MeshrabiyaConstants.ROUTE_CONNECTION_ACQUIRE_TIMEOUT_MS
                )
                
                if (connection == null && MeshrabiyaConstants.ROUTE_DROP_ON_POOL_EXHAUSTION) {
                    logger(Log.WARN, 
                        "$logPrefix Dropped packet from ${packetCopy.header.fromAddr.addressToDotNotation()}: " +
                        "connection pool exhausted after ${MeshrabiyaConstants.ROUTE_CONNECTION_ACQUIRE_TIMEOUT_MS}ms",
                        null
                    )
                    return@execute
                }
                
                // Process packet using extracted method
                processRoutePacket(packetCopy, datagramPacket, virtualNodeDatagramSocket)
                
                // Check processing time and log if slow
                val processingTime = System.currentTimeMillis() - startTime
                if (processingTime > MeshrabiyaConstants.ROUTE_PROCESSING_TIMEOUT_MS) {
                    logger(Log.WARN,
                        "$logPrefix Slow packet processing: ${processingTime}ms for packet from " +
                        "${packetCopy.header.fromAddr.addressToDotNotation()}",
                        null
                    )
                }
                
            } catch (e: Exception) {
                logger(Log.ERROR, 
                    "$logPrefix : route : exception routing packet from ${packetCopy.header.fromAddr.addressToDotNotation()}", 
                    e
                )
            } finally {
                // Always release connection back to pool
                connection?.let { meshConnectionPool.releaseConnection(it) }
            }
        }
    }

     /**
     * Internal method containing the actual routing logic
     * Separated from route() to enable connection pooling wrapper
     * 
     * @param packet VirtualPacket to process (with data already copied)
     * @param datagramPacket Original DatagramPacket (may be null)
     * @param virtualNodeDatagramSocket Socket that received packet (may be null)
     */
    private fun processRoutePacket(
        packet: VirtualPacket,
        datagramPacket: DatagramPacket?,
        virtualNodeDatagramSocket: VirtualNodeDatagramSocket?
    ) {
        val srcAddr = packet.header.fromAddr.addressToDotNotation()
        val dstAddr = packet.header.toAddr.addressToDotNotation()
        val payloadLen = packet.header.payloadSize
        logger(Log.DEBUG, "route() called: src=$srcAddr, dst=$dstAddr, toPort=${packet.header.toPort}, hopCount=${packet.header.hopCount}, payloadLen=$payloadLen")

        // If this is a broadcast chunk, log broadcastId/chunkId if possible
        if (payloadLen > 0) {
            val payload = packet.data.copyOfRange(packet.payloadOffset, packet.payloadOffset + payloadLen)
            try {
                val packetType = BroadcastPacketSerializer.getPacketType(payload)
                if (packetType == BroadcastPacketSerializer.TYPE_BROADCAST_CHUNK) {
                    val (broadcastId, _, chunkPair) = BroadcastPacketSerializer.deserialize(payload)
                    val chunkMeta = chunkPair.first
                    logger(Log.INFO, "ROUTE_BROADCAST_CHUNK: src=$srcAddr, dst=$dstAddr, broadcastId=$broadcastId, chunkId=${chunkMeta.chunkId}, chunkIndex=${chunkMeta.chunkIndex}, payloadLen=$payloadLen")
                }
            } catch (e: Exception) {
                logger(Log.WARN, "ROUTE_BROADCAST_CHUNK: failed to inspect broadcast chunk: ${e.message}")
            }
        }
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
            logger(Log.DEBUG, "$logPrefix route: Processing MMCP message from ${packet.header.fromAddr.addressToDotNotation()} toPort=${packet.header.toPort}", null)
            val (shouldRoute, dropReason) = onIncomingMmcpMessage(packet, datagramPacket, virtualNodeDatagramSocket)
            if(!shouldRoute) {
                logger(Log.DEBUG,
                    "$logPrefix Drop mmcp packet from ${packet.header.fromAddr} reason=${dropReason ?: "UNKNOWN"}"
                )
            }
        }else if(packet.header.toPort == 0){
            logger(Log.DEBUG, "$logPrefix route: Skipping MMCP from self (fromAddr=${packet.header.fromAddr.addressToDotNotation()} myAddr=${addressAsInt.addressToDotNotation()})", null)
        }

        // Ecosystem message handling (UDP broadcast or direct)
        // Route ALL Distributed Storage & Compute messages to MeshEcosystemListener
        val ecosystemPort = MeshrabiyaConstants.getEcosystemGossipPort()
        if(packet.header.toPort == ecosystemPort) {
            val bytes = packet.data.copyOfRange(packet.payloadOffset, packet.payloadOffset + packet.header.payloadSize)
            try {
                val message = MeshEcosystemMessage.fromBytes(bytes)
                val senderId = packet.header.fromAddr
                
                // MeshEcosystemListener is the global listener for all ecosystem messages
                meshEcosystemListener.routeMessage(senderId, message)
            } catch (e: Exception) {
                logger(Log.WARN, "$logPrefix: Failed to deserialize or route MeshEcosystemMessage: ${e.message}", e)
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

        // --- CLEARNET GATEWAY DISPATCH ---
        // Guard: toPort == 0 identifies MMCP control traffic (originator messages, pings, pongs,
        // hotspot requests, etc.). These are mesh-internal and must never be forwarded to the
        // internet. Without this guard, broadcast originator messages (to=255.255.255.255, port=0)
        // pass shouldRouteViaProxy() because 255.255.255.255 is outside the 169.254.0.0/16 prefix,
        // causing ClearnetGatewayForwarder to attempt sendto(255.255.255.255:0) → EINVAL on every
        // originator broadcast (every ~3 seconds) on all gateway nodes.
        if (currentRoles.contains(MeshRole.CLEARNET_GATEWAY)
                && packet.header.toPort != 0
                && shouldRouteViaProxy(packet)) {
            if (onClearnetGatewayPacket(packet)) return
        }

        // --- TOR GATEWAY DISPATCH ---
        // Same guard applied for consistency, though the gatewayType check already excludes
        // most MMCP traffic in practice.
        if (currentRoles.contains(MeshRole.TOR_GATEWAY)
                && packet.header.toPort != 0
                && packet.header.gatewayType == VirtualPacketHeader.GATEWAY_TYPE_TOR) {
            if (onTorGatewayPacket(packet)) return
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
            // Deduplication for broadcast packets moved to MeshEcosystemListener
            if(toAddr == ADDR_BROADCAST) {
                val broadcastId = computeBroadcastId(packet)
                val now = System.currentTimeMillis()
                val prev = seenBroadcasts.putIfAbsent(broadcastId, now)
                if (prev == null) {
                    // PT8: Check TTL before forwarding (prevent infinite loops)
                    if (packet.header.maxHops > 0) {
                        val meshRoles = emergentRoleManager.getCurrentMeshRoles()
                        // UPDATED: Allow MESH_HUB nodes to forward broadcasts
                        if (meshRoles.contains(MeshRole.MESH_ROUTER) || meshRoles.contains(MeshRole.MESH_HUB)) {
                            val roleType = when {
                                meshRoles.contains(MeshRole.MESH_ROUTER) -> "MESH_ROUTER"
                                else -> "MESH_HUB"
                            }
                            logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, forwarding to neighbors (role=$roleType, hops remaining: ${packet.header.maxHops})")
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
                            logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId not seen before, but node is not MESH_ROUTER or MESH_HUB, not forwarding")
                        }
                    } else {
                        logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId TTL exhausted (maxHops=0), not forwarding")
                    }
                    
                    // MOVED INSIDE DEDUP CHECK: Check if this is a broadcast message packet (MMCP port 0, version 1)
                    // Added: 2026-02-01 for NETWORK_BROADCAST_v2 implementation
                    // Fixed: 2026-02-20 for Issue #1 - prevent sender loopback notification
                    if (packet.header.toPort == 0 && packet.header.payloadSize >= 4) {
                        try {
                            // Peek at payload to check version field
                            val payloadBuffer = java.nio.ByteBuffer.wrap(
                                packet.data,
                                packet.payloadOffset,
                                packet.header.payloadSize
                            )
                            val version = payloadBuffer.getInt()
                            
                            
                            
                        } catch (e: Exception) {
                            logger(Log.WARN, "$logPrefix: Failed to check broadcast message packet version", e)
                        }
                    }
                } else {
                    logger(Log.VERBOSE, "$logPrefix: Broadcast packet $broadcastId already seen, ignoring (last seen ${now - prev}ms ago)")
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
                    // Phase 3A: Check if packet requires gateway routing
                    if (packet.header.gatewayType != VirtualPacketHeader.GATEWAY_TYPE_NONE) {
                        logger(Log.DEBUG,
                            "$logPrefix Destination ${packet.header.toAddr.addressToDotNotation()} not on mesh, " +
                            "attempting gateway routing (type=${packet.header.gatewayType})",
                            null
                        )
                        routeViaGateway(packet, null)
                    } else {
                        logger(Log.WARN, "$logPrefix route: Cannot route packet to " +
                                "${packet.header.toAddr.addressToDotNotation()} : no known nexthop")
                    }
                }
            }
        }
    }

    /**
     * Routes packet to internet via mesh gateway.
     * Phase 3A: Gateway Routing Core
     * 
     * Uses gateway type from packet header to select appropriate gateway.
     * Implements failover if primary gateway type unavailable.
     *
     * @param packet Virtual packet with internet-bound destination
     * @param datagramPacket Original datagram (for metadata)
     */
    private fun routeViaGateway(
        packet: VirtualPacket,
        datagramPacket: DatagramPacket?
    ) {
        val gatewayType = packet.header.gatewayType
        
        logger(Log.DEBUG,
            "$logPrefix Routing internet-bound packet via gateway (type=$gatewayType)",
            null
        )
        
        // Get available gateways of requested type
        val gateways = when (gatewayType) {
            VirtualPacketHeader.GATEWAY_TYPE_TOR -> {
                getAvailableTorGateways()
            }
            VirtualPacketHeader.GATEWAY_TYPE_CLEARNET -> {
                getAvailableClearnetGateways()
            }
            else -> {
                logger(Log.ERROR, "$logPrefix Invalid gateway type: $gatewayType", null)
                return
            }
        }
        
        if (gateways.isEmpty()) {
            handleNoGatewayAvailable(packet, gatewayType)
            return
        }
        
        // Select best gateway (closest, lowest load, etc.)
        val selectedGateway = selectBestGateway(gateways, packet)
        
        if (selectedGateway == null) {
            logger(Log.WARN, "$logPrefix No suitable gateway found for type=$gatewayType", null)
            return
        }
        
        // Phase 3C: Track gateway message for return path routing
        originatingMessageManager.trackGatewayMessage(
            fromAddr = packet.header.fromAddr,
            fromPort = packet.header.fromPort,
            toAddr = packet.header.toAddr,
            toPort = packet.header.toPort,
            gatewayType = packet.header.gatewayType,
            gatewayAddr = selectedGateway.nodeAddress
        )
        
        // Forward packet to gateway
        forwardToGateway(packet, selectedGateway)
    }

    /**
     * Gets list of available Tor gateways from mesh topology.
     * 
     * @return List of NodeTopologyInfo for nodes advertising TOR_GATEWAY role
     */
    private fun getAvailableTorGateways(): List<NodeTopologyInfo> {
        return originatingMessageManager.getNodesWithRole(MeshRole.TOR_GATEWAY)
            .filter { !it.isStale(GATEWAY_STALE_TIMEOUT_MS) }
    }

    /**
     * Gets list of available clearnet gateways.
     * 
     * @return List of NodeTopologyInfo for nodes advertising CLEARNET_GATEWAY role
     */
    private fun getAvailableClearnetGateways(): List<NodeTopologyInfo> {
        return originatingMessageManager.getNodesWithRole(MeshRole.CLEARNET_GATEWAY)
            .filter { !it.isStale(GATEWAY_STALE_TIMEOUT_MS) }
    }

    /**
     * Returns integer virtual addresses of all known CLEARNET_GATEWAY peers.
     * Used by MeshLocalSocksProxy to resolve the best gateway to route traffic through.
     */
    fun getAvailableClearnetGatewayAddresses(): List<Int> =
        getAvailableClearnetGateways().map { it.nodeAddress }

    /**
     * Returns integer virtual addresses of all known gateway peers regardless of type.
     * Includes both CLEARNET_GATEWAY and TOR_GATEWAY nodes, deduplicated.
     * Used by MeshLocalSocksProxy when either gateway type is acceptable for routing.
     */
    fun getAvailableGatewayAddresses(): List<Int> =
        (getAvailableClearnetGateways() + getAvailableTorGateways())
            .distinctBy { it.nodeAddress }
            .map { it.nodeAddress }

    /**
     * Selects best gateway from available list.
     * 
     * Selection criteria:
     * 1. Filter out stale gateways
     * 2. Use gateway suitability score (centrality, fitness, latency)
     * 3. Select highest scoring gateway
     *
     * @param gateways List of available gateway nodes
     * @param packet Packet being routed
     * @return Selected gateway NodeTopologyInfo, or null if none suitable
     */
    private fun selectBestGateway(
        gateways: List<NodeTopologyInfo>,
        packet: VirtualPacket
    ): NodeTopologyInfo? {
        if (gateways.isEmpty()) return null
        
        // Determine gateway role from packet header
        val gatewayRole = when (packet.header.gatewayType) {
            VirtualPacketHeader.GATEWAY_TYPE_TOR -> MeshRole.TOR_GATEWAY
            VirtualPacketHeader.GATEWAY_TYPE_CLEARNET -> MeshRole.CLEARNET_GATEWAY
            else -> return null
        }
        
        // Calculate suitability scores and select best
        return gateways
            .map { gateway -> 
                Pair(gateway, gateway.calculateGatewaySuitability(gatewayRole)) 
            }
            .filter { it.second > 0f }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Internal test accessor for selectBestGateway.
     * Allows testing of gateway selection algorithm without VirtualPacket dependency.
     * 
     * @param gateways List of available gateway nodes
     * @param role Gateway role to filter by
     * @return Selected gateway NodeTopologyInfo, or null if none suitable
     */
    internal fun testSelectBestGateway(
        gateways: List<NodeTopologyInfo>,
        role: MeshRole
    ): NodeTopologyInfo? {
        return gateways
            .map { gateway -> 
                Pair(gateway, gateway.calculateGatewaySuitability(role)) 
            }
            .filter { it.second > 0f }
            .maxByOrNull { it.second }
            ?.first
    }

    /**
     * Forwards packet to selected gateway.
     * 
     * Updates packet header (toAddr, hopCount, lastHopAddr) and sends to gateway.
     *
     * @param packet Packet to forward
     * @param gateway Target gateway node info
     */
    private fun forwardToGateway(
        packet: VirtualPacket,
        gateway: NodeTopologyInfo
    ) {
        logger(Log.DEBUG,
            "$logPrefix Forwarding packet to gateway ${gateway.nodeAddress.addressToDotNotation()} " +
                "(hop ${packet.header.hopCount + 1})",
            null
        )
        
        // Create new packet with updated header to route to gateway
        val modifiedHeader = VirtualPacketHeader(
            toAddr = gateway.nodeAddress,  // Route to gateway
            toPort = packet.header.toPort,
            fromAddr = packet.header.fromAddr,
            fromPort = packet.header.fromPort,
            lastHopAddr = addressAsInt,
            hopCount = (packet.header.hopCount + 1).toByte(),
            maxHops = packet.header.maxHops,
            gatewayType = packet.header.gatewayType,  // Preserve gateway type
            payloadSize = packet.header.payloadSize
        )
        
        val forwardedPacket = VirtualPacket.fromHeaderAndPayloadData(
            header = modifiedHeader,
            data = packet.data,
            payloadOffset = packet.payloadOffset
        )
        
        // Find next hop to reach gateway
        val originatorMessage = originatingMessageManager
            .findOriginatingMessageFor(gateway.nodeAddress)
        
        if (originatorMessage != null) {
            originatorMessage.receivedFromSocket.send(
                nextHopAddress = originatorMessage.lastHopRealInetAddr,
                nextHopPort = originatorMessage.lastHopRealPort,
                virtualPacket = forwardedPacket
            )
        } else {
            logger(Log.ERROR,
                "$logPrefix Cannot forward to gateway ${gateway.nodeAddress.addressToDotNotation()}: no route",
                null
            )
        }
    }

    /**
     * Handles case where no gateway is available.
     * 
     * Behavior based on gateway preference (from GatewayPreference enum):
     * - TOR_ONLY: Drop packet (no fallback)
     * - CLEARNET_ONLY: Drop packet (no fallback)
     * - EITHER: Try alternate gateway type
     *
     * @param packet Packet that couldn't be routed
     * @param requestedType Gateway type that was requested
     */
    private fun handleNoGatewayAvailable(
        packet: VirtualPacket,
        requestedType: Byte
    ) {
        logger(Log.WARN, "$logPrefix No gateway available for type=$requestedType", null)
        
        // For EITHER preference, try alternate gateway type
        // Note: Gateway preference is managed by GatewayTypeResolver at packet creation time
        // Here we just attempt fallback for EITHER case
        
        val alternateType = if (requestedType == VirtualPacketHeader.GATEWAY_TYPE_TOR) {
            VirtualPacketHeader.GATEWAY_TYPE_CLEARNET
        } else {
            VirtualPacketHeader.GATEWAY_TYPE_TOR
        }
        
        val alternateGateways = when (alternateType) {
            VirtualPacketHeader.GATEWAY_TYPE_TOR -> getAvailableTorGateways()
            VirtualPacketHeader.GATEWAY_TYPE_CLEARNET -> getAvailableClearnetGateways()
            else -> emptyList()
        }
        
        if (alternateGateways.isNotEmpty()) {
            logger(Log.INFO, "$logPrefix Attempting fallback to gateway type=$alternateType", null)
            
            // Create new packet with alternate gateway type
            val fallbackHeader = VirtualPacketHeader(
                toAddr = packet.header.toAddr,
                toPort = packet.header.toPort,
                fromAddr = packet.header.fromAddr,
                fromPort = packet.header.fromPort,
                lastHopAddr = packet.header.lastHopAddr,
                hopCount = packet.header.hopCount,
                maxHops = packet.header.maxHops,
                gatewayType = alternateType,  // Updated to alternate type
                payloadSize = packet.header.payloadSize
            )
            
            val fallbackPacket = VirtualPacket.fromHeaderAndPayloadData(
                header = fallbackHeader,
                data = packet.data,
                payloadOffset = packet.payloadOffset
            )
            
            routeViaGateway(fallbackPacket, null)
        } else {
            // No fallback available - drop packet
            logger(Log.WARN,
                "$logPrefix Dropping packet: no gateway available (requested=$requestedType)",
                null
            )
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
        logger(Log.INFO,
            "$logPrefix 🆕 addNewNeighborConnection - Starting connection setup for " +
                    "virtualAddr=${neighborNodeVirtualAddr.addressToDotNotation()} " +
                    "realAddr=$address:$port socket.localPort=${socket.localPort}",
            null
        )

        coroutineScope.launch {
            try {
                logger(Log.DEBUG, "$logPrefix 🚀 addNewNeighborConnection - Launching addNeighbor coroutine", null)
                
                originatingMessageManager.addNeighbor(
                    neighborRealInetAddr = address,
                    neighborRealPort = port,
                    socket =  socket,
                )
                
                logger(Log.INFO, "$logPrefix ✅ addNewNeighborConnection - Successfully established neighbor connection to ${neighborNodeVirtualAddr.addressToDotNotation()}", null)
            } catch (e: Exception) {
                logger(Log.ERROR, "$logPrefix ❌ addNewNeighborConnection - FAILED to establish neighbor connection to ${neighborNodeVirtualAddr.addressToDotNotation()}", e)
            }
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
        preferredPassphrase: String? = null,
    ): LocalHotspotResponse? {
        return if(enabled){
             meshrabiyaWifiManager.requestHotspot(
                requestMessageId = nextMmcpMessageId(),
                request = LocalHotspotRequest(
                    preferredBand = preferredBand,
                    preferredType = hotspotType,
                    preferredPassphrase = preferredPassphrase,
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

    /**
     * Send a direct ecosystem message to a specific node.
     * Constructs VirtualPacket with ecosystem port and routes it.
     * 
     * @param targetAddress Destination node address
     * @param messageBytes Serialized message bytes
     * @param toPort Destination port (defaults to ecosystem gossip port)
     */
    fun sendEcosystemMessage(
        targetAddress: Int,
        messageBytes: ByteArray,
        toPort: Int = MeshrabiyaConstants.getEcosystemGossipPort()
    ) {
        val packetData = ByteArray(VirtualPacketHeader.HEADER_SIZE + messageBytes.size)
        val header = VirtualPacketHeader(
            toAddr = targetAddress,
            toPort = toPort,
            fromAddr = addressAsInt,
            fromPort = toPort,
            lastHopAddr = addressAsInt,
            hopCount = 0,
            maxHops = 10,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE, //V3: Mesh-local message
            payloadSize = messageBytes.size
        )
        System.arraycopy(messageBytes, 0, packetData, VirtualPacketHeader.HEADER_SIZE, messageBytes.size)
        val packet = VirtualPacket.fromHeaderAndPayloadData(
            header = header,
            data = packetData,
            payloadOffset = VirtualPacketHeader.HEADER_SIZE
        )
        route(packet, null, null)
    }

    // DEPRECATED: MmcpGatewayAnnouncement class moved to .md (commented out to fix compilation)
    // protected open fun onGatewayAnnouncementReceived(announcement: MmcpGatewayAnnouncement, fromNodeAddr: Int) {
    //     logger(Log.INFO, "$logPrefix Gateway ${announcement.gatewayType} available from ${fromNodeAddr.addressToDotNotation()}")
    //     try {
    //         if (announcement.isActive && announcement.capacity.downloadMbps > 0) {
    //             logger(Log.DEBUG, "$logPrefix Valid gateway: capacity=${announcement.capacity.downloadMbps}Mbps, latency=${announcement.latency.averageMs}ms")
    //         }
    //     } catch (e: Exception) {
    //         logger(Log.WARN, "$logPrefix Error processing gateway announcement: ${e.message}")
    //     }
    // }

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
    protected open fun shouldRouteViaProxy(packet: VirtualPacket): Boolean {
        val destInetAddress = getInetAddressFor(packet.header.toAddr)
        return !destInetAddress.prefixMatches(networkPrefixLength, address)
    }

    /**
     * Called when this node is a CLEARNET_GATEWAY and a non-mesh packet arrives.
     * Override in AndroidVirtualNode to forward via the internet WiFi network.
     * @return true if the packet was handled (caller should return), false to fall through.
     */
    protected open fun onClearnetGatewayPacket(packet: VirtualPacket): Boolean = false

    protected open fun onTorGatewayPacket(packet: VirtualPacket): Boolean = false

    fun broadcastGatewayDown() {
        val mmcpPayload = byteArrayOf(MmcpMessage.WHAT_GATEWAY_DOWN)
        val header = VirtualPacketHeader(
            toAddr = ADDR_BROADCAST,
            toPort = 0,
            fromAddr = addressAsInt,
            fromPort = 0,
            lastHopAddr = addressAsInt,
            hopCount = 0,
            maxHops = 3,
            gatewayType = VirtualPacketHeader.GATEWAY_TYPE_NONE,
            payloadSize = mmcpPayload.size,
        )
        val pkt = VirtualPacket.fromHeaderAndPayloadData(header, mmcpPayload, 0)
        originatingMessageManager.neighbors().forEach { (_, neighbor) ->
            neighbor.receivedFromSocket.send(
                nextHopAddress = neighbor.lastHopRealInetAddr,
                nextHopPort = neighbor.lastHopRealPort,
                virtualPacket = pkt,
            )
        }
        logger(Log.INFO, "$logPrefix broadcastGatewayDown: sent to ${originatingMessageManager.neighbors().size} neighbors")
    }

    // === Gateway Routing Methods (Phase 4) ===
    
    /**
     * Check if this node is acting as a gateway of given type
     */
    fun isGatewayNode(gatewayType: MeshRole): Boolean {
        return emergentRoleManager.currentMeshRoles.value.contains(gatewayType)
    }

    /**
     * Route packet through gateway based on destination analysis
     * CLIENT NODE: Select gateway from topology, route to gateway
     * GATEWAY NODE: Route through proxy
     */
    fun routeThroughGateway(packet: VirtualPacket): Boolean {
        // Determine gateway type needed based on destination
        val gatewayType = determineGatewayType(packet)
        
        return if (gatewayType != null) {
            gatewayRouter.routeToGateway(packet, gatewayType)
        } else {
            // No gateway needed, route directly (route() returns Unit, so wrap in true)
            route(packet)
            true
        }
    }

    /**
     * Determine which gateway type is needed for this packet
     * @return Gateway type (TOR/CLEARNET/I2P) or null for direct routing
     */
    private fun determineGatewayType(packet: VirtualPacket): MeshRole? {
        // TODO: Implement packet inspection logic
        // Phase 1: Explicit tagging (application layer specifies gateway)
        // Phase 2: Destination-based (.onion → TOR, .i2p → I2P, else CLEARNET)
        // Phase 3: Port-based (443 → CLEARNET, 9150 → TOR, 7657 → I2P)
        
        // For now, return null (no gateway routing until classification implemented)
        return null
    }

    /**
     * Route packet through configured proxy (Tor/etc)
     * GATEWAY NODE behavior - called by GatewayRouter
     * Enhanced to return Boolean for success/failure
     */
    fun routeViaProxy(packet: VirtualPacket): Boolean {
        val host = proxyHost ?: return false
        val port = proxyPort ?: return false
        
        try {
            val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(host, port))
            val socket = Socket(proxy)
            socket.getOutputStream().write(packet.data)
            socket.close()
            return true
        } catch (e: Exception) {
            logger(Log.ERROR, "$logPrefix Failed to route via proxy: ${e.message}", e)
            return false
        }
    }

    companion object {
        /**
         * Timeout threshold for gateway staleness check.
         * Gateways not seen within this period are considered stale.
         * Phase 3A: Gateway Routing Core
         */
        const val GATEWAY_STALE_TIMEOUT_MS = 30_000L  // 30 seconds
    }

    // Removed explicit getter functions - Kotlin auto-generates them from protected val properties
    // This eliminates "Platform declaration clash" errors from duplicate JVM signatures
}