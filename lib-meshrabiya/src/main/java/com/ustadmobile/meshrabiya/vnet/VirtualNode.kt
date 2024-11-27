package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.ext.addressToByteArray
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.asInetAddress
import com.ustadmobile.meshrabiya.ext.prefixMatches
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpMessageAndPacketHeader
import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.portforward.ForwardBindPoint
import com.ustadmobile.meshrabiya.portforward.UdpForwardRule
import com.ustadmobile.meshrabiya.util.findFreePort
import com.ustadmobile.meshrabiya.vnet.bluetooth.MeshrabiyaBluetoothState
import com.ustadmobile.meshrabiya.vnet.datagram.VirtualDatagramSocket2
import com.ustadmobile.meshrabiya.vnet.datagram.VirtualDatagramSocketImpl
import com.ustadmobile.meshrabiya.vnet.netinterface.VirtualNetworkInterface
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketFactory
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketFactoryImpl
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketNextHop
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketServer
import com.ustadmobile.meshrabiya.vnet.wifi.ConnectBand
import com.ustadmobile.meshrabiya.vnet.wifi.HotspotType
import com.ustadmobile.meshrabiya.vnet.wifi.WifiConnectConfig
import com.ustadmobile.meshrabiya.vnet.wifi.MeshrabiyaWifiManager
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotRequest
import com.ustadmobile.meshrabiya.vnet.wifi.LocalHotspotResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.atomic.AtomicInteger
import javax.net.SocketFactory
import kotlin.random.Random

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
abstract class VirtualNode(
    val port: Int = 0,
    val json: Json = Json,
    val logger: MNetLogger = MNetLoggerStdout(),
    final override val address: InetAddress = randomApipaInetAddr(),
    override val networkPrefixLength: Int = 16,
    val config: NodeConfig = NodeConfig.DEFAULT_CONFIG,
) : VirtualRouter, Closeable {

    val addressAsInt: Int = address.requireAddressAsInt()

    //This executor is used for direct I/O activities
    protected val connectionExecutor: ExecutorService = Executors.newCachedThreadPool()

    //This executor is used to schedule maintenance e.g. pings etc.
    protected val scheduledExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(2)

    protected val coroutineScope = CoroutineScope(Dispatchers.Default + Job())

    private val mmcpMessageIdAtomic = AtomicInteger()

    protected val _state = MutableStateFlow(LocalNodeState())

    val state: Flow<LocalNodeState> = _state.asStateFlow()

    abstract val meshrabiyaWifiManager: MeshrabiyaWifiManager

    protected val logPrefix: String = "[VirtualNode ${addressAsInt.addressToDotNotation()}]"

    protected val iDatagramSocketFactory = VirtualNodeReturnPathSocketFactory(this)

    private val forwardingRules: MutableMap<ForwardBindPoint, UdpForwardRule> = ConcurrentHashMap()

    private val _virtualNetworkInterfaces = MutableStateFlow<List<VirtualNetworkInterface>>(
        emptyList()
    )
    val virtualNetworkInterfaces: Flow<List<VirtualNetworkInterface>> =
        _virtualNetworkInterfaces.asStateFlow()

    /**
     * @param originatorMessage the Originator message itself
     * @param timeReceived the time this message was received
     * @param lastHopAddr the recorded last hop address (Virtual address)
     */
    data class LastOriginatorMessage(
        val originatorMessage: MmcpOriginatorMessage,
        val timeReceived: Long,
        val lastHopAddr: Int,
        val hopCount: Byte,
        val receivedFromInterface: VirtualNetworkInterface? = null,

    )

    @Suppress("unused") //Part of the API
    enum class Zone {
        VNET, REAL
    }

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

    val incomingMmcpMessages: Flow<MmcpMessageAndPacketHeader> =
        _incomingMmcpMessages.asSharedFlow()

    private val originatingMessageManager = OriginatingMessageManager(
        virtualNetworkInterfaces = {
            _virtualNetworkInterfaces.value
        },
        logger = logger,
        scheduledExecutorService = scheduledExecutor,
        nextMmcpMessageId = this::nextMmcpMessageId,
        getWifiState = { _state.value.wifiState },
        incomingMmcpMessages =  incomingMmcpMessages,
    )

    private val activeSockets: MutableMap<Int, VirtualDatagramSocketImpl> = ConcurrentHashMap()

    init {
        _state.update { prev ->
            prev.copy(
                address = addressAsInt,
                connectUri = generateConnectLink(hotspot = null).uri
            )
        }

        coroutineScope.launch {
            originatingMessageManager.state.collect {
                _state.update { prev ->
                    prev.copy(
                        originatorMessages = it
                    )
                }
            }
        }
    }

    fun addVirtualNetworkInterface(networkInterface: VirtualNetworkInterface){
        _virtualNetworkInterfaces.update { it->
            it + networkInterface
        }
    }

    override fun nextMmcpMessageId() = mmcpMessageIdAtomic.incrementAndGet()


    override fun allocateUdpPortOrThrow(
        virtualDatagramSocketImpl: VirtualDatagramSocketImpl,
        portNum: Int
    ): Int {
        if (portNum > 0) {
            if (activeSockets.containsKey(portNum))
                throw IllegalStateException("VirtualNode: port $portNum already allocated!")

            //requested port is not allocated, everything OK
            activeSockets[portNum] = virtualDatagramSocketImpl
            return portNum
        }

        var attemptCount = 0
        do {
            val randomPort = Random.nextInt(0, Short.MAX_VALUE.toInt())
            if (!activeSockets.containsKey(randomPort)) {
                activeSockets[randomPort] = virtualDatagramSocketImpl
                return randomPort
            }

            attemptCount++
        } while (attemptCount < 100)

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

    /**
     *
     */
    fun forward(
        bindAddress: InetAddress,
        bindPort: Int,
        destAddress: InetAddress,
        destPort: Int,
    ): Int {
        val listenSocket = if (
            bindAddress.prefixMatches(networkPrefixLength, address)
        ) {
            createBoundDatagramSocket(bindPort)
        } else {
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
        val listenSocket = if (bindZone == Zone.VNET) {
            createBoundDatagramSocket(bindPort)
        } else {
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
    ): UdpForwardRule {
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
    ): MeshrabiyaConnectLink {
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
        receivedFromInterface: VirtualNetworkInterface
    ) {
        //This is an Mmcp message
        try {
            val mmcpMessage = MmcpMessage.fromVirtualPacket(virtualPacket)
            val from = virtualPacket.header.fromAddr
            logger(Log.VERBOSE,
                message = {
                    "$logPrefix received MMCP message (${mmcpMessage::class.simpleName}) " +
                            "from ${from.addressToDotNotation()}"
                }
            )

            when (mmcpMessage) {
                is MmcpPing -> {
                    logger(Log.VERBOSE,
                        message = {
                            "$logPrefix Received ping(id=${mmcpMessage.messageId}) from ${from.addressToDotNotation()}"
                        }
                    )
                    //send pong
                    val pongMessage = MmcpPong(
                        messageId = nextMmcpMessageId(),
                        replyToMessageId = mmcpMessage.messageId
                    )

                    val replyPacket = pongMessage.toVirtualPacket(
                        toAddr = from,
                        fromAddr = addressAsInt
                    )

                    logger(
                        Log.VERBOSE,
                        { "$logPrefix Sending pong to ${from.addressToDotNotation()}" })
                    route(replyPacket)
                }

                else -> {
                    // do nothing
                }
            }

            _incomingMmcpMessages.tryEmit(
                MmcpMessageAndPacketHeader(
                    mmcpMessage,
                    virtualPacket.header,
                    receivedFromInterface,
                )
            )
        } catch (e: Exception) {
            logger(Log.ERROR, "Exception handling MMCP message", e)
        }
    }


    override fun route(
        packet: VirtualPacket,
        receivedFromInterface: VirtualNetworkInterface?
    ) {
        try {
            if (packet.header.hopCount >= config.maxHops) {
                logger(
                    Log.DEBUG,
                    "Drop packet from ${packet.header.fromAddr.addressToDotNotation()} - " +
                            "${packet.header.hopCount} exceeds ${config.maxHops}",
                    null
                )
                return
            }

            val localAddresses = _virtualNetworkInterfaces.value.map {
                it.virtualAddress.requireAddressAsInt()
            }

            when {
                /*
                 * Packet is destined for an interface on this node
                 */
                packet.header.toAddr in localAddresses-> {
                    if(packet.header.toPort == 0 && receivedFromInterface != null) {
                        onIncomingMmcpMessage(packet, receivedFromInterface)
                    }else {
                        val listeningSocket = activeSockets[packet.header.toPort]
                        if (listeningSocket != null) {
                            listeningSocket.onIncomingPacket(packet)
                        } else {
                            logger(
                                Log.DEBUG,
                                "$logPrefix Incoming packet received, but no socket listening on: ${packet.header.toPort}"
                            )
                        }
                    }
                }

                /*
                 * Packet needs to hop to its final destination
                 */
                else -> {
                    val originatorMessage = originatingMessageManager
                        .findOriginatingMessageFor(packet.header.toAddr)
                    if (originatorMessage != null) {
                        originatorMessage.receivedFromInterface?.send(
                            nextHopAddress = packet.header.toAddr.asInetAddress(),
                            virtualPacket = packet
                        )
                    } else {
                        logger(
                            Log.WARN, "$logPrefix route: Cannot route packet to " +
                                    "${packet.header.toAddr.addressToDotNotation()} : no known nexthop"
                        )
                    }
                }
            }
        } catch (e: Exception) {
            logger(
                Log.ERROR,
                "$logPrefix : route : exception routing packet from ${packet.header.fromAddr.addressToDotNotation()}",
                e
            )
            throw e
        }
    }

    override fun lookupNextHopForChainSocket(address: InetAddress, port: Int): ChainSocketNextHop {
        return originatingMessageManager.lookupNextHopForChainSocket(address, port)
    }

    open suspend fun setWifiHotspotEnabled(
        enabled: Boolean,
        preferredBand: ConnectBand = ConnectBand.BAND_2GHZ,
        hotspotType: HotspotType = HotspotType.AUTO,
    ): LocalHotspotResponse? {
        return if (enabled) {
            meshrabiyaWifiManager.requestHotspot(
                requestMessageId = nextMmcpMessageId(),
                request = LocalHotspotRequest(
                    preferredBand = preferredBand,
                    preferredType = hotspotType,
                )
            )
        } else {
            meshrabiyaWifiManager.deactivateHotspot()
            LocalHotspotResponse(
                responseToMessageId = 0,
                config = null,
                errorCode = 0,
                redirectAddr = 0,
            )
        }
    }

    override fun close() {
        datagramSocket.close(closeSocket = true)
        chainSocketServer.close(closeSocket = true)
        coroutineScope.cancel(message = "VirtualNode closed")

        connectionExecutor.shutdown()
        scheduledExecutor.shutdown()
    }

}