package com.ustadmobile.meshrabiya.vnet

import android.util.Log
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import com.ustadmobile.meshrabiya.ext.asInetAddress
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.vnet.netinterface.VirtualNetworkInterface
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketNextHop
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit


/**
 * @param virtualNetworkInterfaces function that provide a list of current virtual ip addresses for node.
 */

class OriginatingMessageManager(
    private val virtualNetworkInterfaces: () -> List<VirtualNetworkInterface>,
    private val logger: MNetLogger,
    private val scheduledExecutorService: ScheduledExecutorService,
    private val nextMmcpMessageId: () -> Int,
    private val getWifiState: () -> MeshrabiyaWifiState,
    private val pingTimeout: Int = 15_000,
    private val originatingMessageNodeLostThreshold: Int = 10000,
    lostNodeCheckInterval: Int = 1_000,
) {

    private val logPrefix = "[OriginatingMessageManager for ${virtualNetworkInterfaces}] "

    private val scope = CoroutineScope(Dispatchers.IO + Job())

    /**
     * The currently known latest originator messages that can be used to route traffic.
     */
    private val originatorMessages: MutableMap<Int, VirtualNode.LastOriginatorMessage> =
        ConcurrentHashMap()

    private val _state = MutableStateFlow<Map<Int, VirtualNode.LastOriginatorMessage>>(emptyMap())

    val state: Flow<Map<Int, VirtualNode.LastOriginatorMessage>> = _state.asStateFlow()

    private val receivedMessages: Flow<VirtualNode.LastOriginatorMessage> = MutableSharedFlow(
        replay = 1, extraBufferCapacity = 0, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    data class PendingPing(
        val ping: MmcpPing,
        val toVirtualAddr: Int,
        val timesent: Long
    )

    data class PingTime(
        val nodeVirtualAddr: Int,
        val pingTime: Short,
        val timeReceived: Long,
    )

    private val pendingPings = CopyOnWriteArrayList<PendingPing>()

    private val neighborPingTimes: MutableMap<Int, PingTime> = ConcurrentHashMap()

    private val sendOriginatingMessageRunnable = Runnable {
        val originatingMessage = makeOriginatingMessage()

        logger(
            priority = Log.VERBOSE,
            message = {
                "$logPrefix sending originating message " +
                        "messageId=${originatingMessage.messageId} sentTime=${originatingMessage.sentTime}"
            }
        )

        val networkInterfaces = virtualNetworkInterfaces()

        networkInterfaces.forEach { networkInterface ->
            //TODO: This needs to consider telling each neighbor about our other IP addresses
            // so this works properly when there are multiple 'interfaces'

            networkInterface.knownNeighbors.forEach { neighborIpAddr ->
                networkInterface.send(
                    virtualPacket = originatingMessage.toVirtualPacket(
                        toAddr = neighborIpAddr.requireAddressAsInt(),
                        fromAddr = networkInterface.virtualAddress.requireAddressAsInt(),
                        lastHopAddr = networkInterface.virtualAddress.requireAddressAsInt(),
                        hopCount = 1,
                    ),
                    nextHopAddress = neighborIpAddr,
                )
            }
        }

    }

    private val pingNeighborsRunnable = Runnable {

        val neighbors = neighbors()
        neighbors.forEach { neighbor ->
            val neighborVirtualAddr = neighbor.first
            val lastOrigininatorMessage = neighbor.second
            val pingMessage = MmcpPing(messageId = nextMmcpMessageId())
            pendingPings.add(
                PendingPing(
                    pingMessage,
                    neighborVirtualAddr,
                    System.currentTimeMillis()
                )
            )
            logger(
                priority = Log.VERBOSE,
                message = { "$logPrefix pingNeighborsRunnable: send ping to ${neighborVirtualAddr.addressToDotNotation()}" }
            )

            val networkInterfaces = virtualNetworkInterfaces()
            val addresses = networkInterfaces.map { it.virtualAddress }

            networkInterfaces.forEach { virtualNetworkInterface ->

                val networkInterfaceAddress = virtualNetworkInterface.virtualAddress.requireAddressAsInt()

                addresses.forEach { address->
                    virtualNetworkInterface.send(
                        nextHopAddress = neighborVirtualAddr.asInetAddress(),
                        virtualPacket = pingMessage.toVirtualPacket(
                            toAddr = neighborVirtualAddr,
                            fromAddr = networkInterfaceAddress,
                            lastHopAddr = networkInterfaceAddress,
                            hopCount = 1,
                        )
                    )

                }
            }


        }

        //Remove expired pings
        val pingTimeoutThreshold = System.currentTimeMillis() - pingTimeout
        pendingPings.removeIf { it.timesent < pingTimeoutThreshold }
    }

    private val checkLostNodesRunnable = Runnable {
        val timeNow = System.currentTimeMillis()
        val nodesLost = originatorMessages.entries.filter {
            (timeNow - it.value.timeReceived) > originatingMessageNodeLostThreshold
        }

        nodesLost.forEach {
            logger(Log.DEBUG, {
                "$logPrefix : checkLostNodesRunnable: " +
                        "Lost ${it.key.addressToDotNotation()} - no contact for ${timeNow - it.value.timeReceived}ms"
            })
            originatorMessages.remove(it.key)
        }

        _state.takeIf { !nodesLost.isEmpty() }?.value = originatorMessages.toMap()
    }

    private val sendOriginatorMessagesFuture = scheduledExecutorService.scheduleWithFixedDelay(
        sendOriginatingMessageRunnable, 1000, 3000, TimeUnit.MILLISECONDS
    )
//
//    private val pingNeighborsFuture = scheduledExecutorService.scheduleWithFixedDelay(
//        pingNeighborsRunnable, 1000, 10000, TimeUnit.MILLISECONDS
//    )
//
//    private val checkLostNodesFuture = scheduledExecutorService.scheduleWithFixedDelay(
//        checkLostNodesRunnable,
//        lostNodeCheckInterval.toLong(),
//        lostNodeCheckInterval.toLong(),
//        TimeUnit.MILLISECONDS
//    )

    @Volatile
    private var closed = false


    private fun makeOriginatingMessage(): MmcpOriginatorMessage {
        return MmcpOriginatorMessage(
            messageId = nextMmcpMessageId(),
            pingTimeSum = 0,
            connectConfig = getWifiState().connectConfig,
            sentTime = System.currentTimeMillis()
        )
    }


    private fun assertNotClosed() {
        if (closed)
            throw IllegalStateException("$logPrefix is closed!")
    }


    fun onReceiveOriginatingMessage(
        mmcpMessage: MmcpOriginatorMessage,
        virtualPacket: VirtualPacket,
        receivedFromInterface: VirtualNetworkInterface,
    ): Boolean {
        assertNotClosed()
        //Dont keep originator messages in our own table for this node
        logger(
            Log.VERBOSE,
            message = {
                "$logPrefix received originating message from " +
                        "${virtualPacket.header.fromAddr.addressToDotNotation()} via " +
                        virtualPacket.header.lastHopAddr.addressToDotNotation()
            }
        )


        val connectionPingTime = neighborPingTimes[virtualPacket.header.lastHopAddr]?.pingTime ?: 0
        MmcpOriginatorMessage.takeIf { connectionPingTime != 0.toShort() }
            ?.incrementPingTimeSum(virtualPacket, connectionPingTime)

        val currentOriginatorMessage = originatorMessages[virtualPacket.header.fromAddr]


        //Update this only if it is more recent and/or better. It might be that we are getting it back
        //via some other (suboptimal) route with more hops
        val currentlyKnownSentTime = (currentOriginatorMessage?.originatorMessage?.sentTime ?: 0)
        val currentlyKnownHopCount = (currentOriginatorMessage?.hopCount ?: Byte.MAX_VALUE)
        val isMoreRecentOrBetter = mmcpMessage.sentTime > currentlyKnownSentTime
                || mmcpMessage.sentTime == currentlyKnownSentTime && virtualPacket.header.hopCount < currentlyKnownHopCount
        val isNewNeighbor = virtualPacket.header.hopCount == 1.toByte() &&
                !originatorMessages.containsKey(virtualPacket.header.fromAddr)

        logger(
            Log.VERBOSE,
            message = {
                "$logPrefix received originating message from " +
                        "${virtualPacket.header.fromAddr.addressToDotNotation()} via ${virtualPacket.header.lastHopAddr.addressToDotNotation()}" +
                        " messageId=${mmcpMessage.messageId} " +
                        " hopCount=${virtualPacket.header.hopCount} sentTime=${mmcpMessage.sentTime} " +
                        " Currently known: senttime=$currentlyKnownSentTime  hop count = $currentlyKnownHopCount " +
                        "isMoreRecentOrBetter=$isMoreRecentOrBetter "
            }
        )

        if (currentOriginatorMessage == null || isMoreRecentOrBetter) {
            originatorMessages[virtualPacket.header.fromAddr] = VirtualNode.LastOriginatorMessage(
                originatorMessage = mmcpMessage.copyWithPingTimeIncrement(connectionPingTime),
                timeReceived = System.currentTimeMillis(),
                lastHopAddr = virtualPacket.header.lastHopAddr,
                hopCount = virtualPacket.header.hopCount,
                receivedFromInterface = receivedFromInterface,
            )
            logger(
                Log.VERBOSE,
                message = {
                    "$logPrefix update originator messages: " +
                            "currently known nodes = ${originatorMessages.keys.joinToString { it.addressToDotNotation() }}"
                }
            )

            _state.value = originatorMessages.toMap()

            //TODO: rebroadcast it
        }

        if (isNewNeighbor) {
            //trigger immediate sending of originator messages so it can see us
            scheduledExecutorService.submit(sendOriginatingMessageRunnable)
        }

        return isMoreRecentOrBetter
    }

    fun onPongReceived(
        fromVirtualAddr: Int,
        pong: MmcpPong,
    ) {
        val pendingPingPredicate: (PendingPing) -> Boolean = {
            it.ping.messageId == pong.replyToMessageId && it.toVirtualAddr == fromVirtualAddr
        }

        val pendingPing = pendingPings.firstOrNull(pendingPingPredicate)

        if (pendingPing == null) {
            logger(
                Log.WARN, "$logPrefix : onPongReceived : pong from " +
                        "${fromVirtualAddr.addressToDotNotation()} does not match any known sent ping"
            )
            return
        }

        val timeNow = System.currentTimeMillis()

        //Sometimes unit tests will run very quickly, and test may fail if ping time is 0
        val pingTime = maxOf((timeNow - pendingPing.timesent).toShort(), 1)
        logger(
            Log.VERBOSE, {
                "$logPrefix received ping from ${fromVirtualAddr.addressToDotNotation()} " +
                        "pingTime=$pingTime"
            }
        )

        neighborPingTimes[fromVirtualAddr] = PingTime(
            nodeVirtualAddr = fromVirtualAddr,
            pingTime = pingTime,
            timeReceived = timeNow,
        )

        pendingPings.removeIf(pendingPingPredicate)
    }

    fun findOriginatingMessageFor(addr: Int): VirtualNode.LastOriginatorMessage? {
        return originatorMessages[addr]
    }


    fun lookupNextHopForChainSocket(address: InetAddress, port: Int): ChainSocketNextHop {
        val addressInt = address.requireAddressAsInt()

        val originatorMessage = originatorMessages[addressInt]

        return when {
            //Destination address is this node
            virtualNetworkInterfaces().any { it.virtualAddress.requireAddressAsInt() == addressInt } -> {
                ChainSocketNextHop(InetAddress.getLoopbackAddress(), port, true, null)
            }


            //No route available to reach the given address
            else -> {
                logger(Log.ERROR, "$logPrefix : No route to virtual host: $address")
                throw NoRouteToHostException("No route to virtual host $address")
            }
        }
    }

    fun neighbors(): List<Pair<Int, VirtualNode.LastOriginatorMessage>> {
        return originatorMessages.filter { it.value.hopCount == 1.toByte() }.map {
            it.key to it.value
        }
    }

    fun selectOutgoingAddrForDestination(destination: InetAddress): InetAddress {
        val destinationInt = destination.requireAddressAsInt()

        // Find the originator message with the shortest path to the destination
        val bestRoute = originatorMessages[destinationInt]

        if (bestRoute == null) {
            logger(Log.WARN, "$logPrefix No route found to destination $destination")
            throw NoRouteToHostException("No route to host $destination")
        }

        // Find the virtual network interface that matches the last hop address
        val outgoingInterface = virtualNetworkInterfaces().find { iface ->
            iface.virtualAddress.requireAddressAsInt() == bestRoute.lastHopAddr
        }

        if (outgoingInterface == null) {
            logger(Log.ERROR, "$logPrefix No matching interface found for route to $destination")
            throw IllegalStateException("No matching interface for route to $destination")
        }

        logger(Log.INFO, "$logPrefix Selected outgoing address ${outgoingInterface.virtualAddress} with ${bestRoute.hopCount} hops to reach $destination")
        return outgoingInterface.virtualAddress
    }

    fun close() {
        sendOriginatorMessagesFuture.cancel(true)
//        pingNeighborsFuture.cancel(true)
//        checkLostNodesFuture.cancel(true)
        scope.cancel("$logPrefix closed")
        closed = true
    }

}