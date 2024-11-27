package com.ustadmobile.meshrabiya.vnet

import app.cash.turbine.test
import com.ustadmobile.meshrabiya.ext.requireAddressAsInt
import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.mmcp.MmcpMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpMessageAndPacketHeader
import com.ustadmobile.meshrabiya.mmcp.MmcpOriginatorMessage
import com.ustadmobile.meshrabiya.mmcp.MmcpPing
import com.ustadmobile.meshrabiya.mmcp.MmcpPong
import com.ustadmobile.meshrabiya.vnet.netinterface.VirtualNetworkInterface
import com.ustadmobile.meshrabiya.vnet.wifi.state.MeshrabiyaWifiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argWhere
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.timeout
import org.mockito.kotlin.verify
import java.net.InetAddress
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

class OriginatingMessageManagerTest {

    data class OriginatingMessageManagerTestContext(
        val originatingMessageManager: OriginatingMessageManager,
        val neighborAddr: InetAddress,
        val virtualNetworkInterface: VirtualNetworkInterface,
        val incomingMmcpMessages: MutableSharedFlow<MmcpMessageAndPacketHeader>,
    ) {

        fun createOriginatorMessageFromNeighborToVirtualNetwork(): Pair<MmcpOriginatorMessage, VirtualPacket> {
            val originatorMessage = MmcpOriginatorMessage(
                messageId = 42,
                pingTimeSum = 0,
                connectConfig = null,
            )

            val originatorPacket = originatorMessage.toVirtualPacket(
                toAddr = virtualNetworkInterface.virtualAddress.requireAddressAsInt(),
                fromAddr = neighborAddr.requireAddressAsInt(),
                lastHopAddr = neighborAddr.requireAddressAsInt(),
            )

            return originatorMessage to originatorPacket
        }
    }

    private fun createOriginatingMessageManagerTestContext(
        pongDelay: Long = 50,
    ): OriginatingMessageManagerTestContext {
        val neighborAddr = InetAddress.getByName("169.254.1.3")
        val atomicInt = AtomicInteger()
        val localNetworkInterfaceAddr = InetAddress.getByName("169.254.1.2")


        val incomingMmcpMessages = MutableSharedFlow<MmcpMessageAndPacketHeader>(
            replay = 8,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )

        val virtualNetworkInterface = mock<VirtualNetworkInterface> {
            on { virtualAddress }.thenReturn(localNetworkInterfaceAddr)
            on { knownNeighbors }.thenReturn(listOf(neighborAddr))

            on { send(virtualPacket = any(), nextHopAddress = any()) }.then { invocation ->
                val packet = invocation.arguments.first() as VirtualPacket
                val nextHopAddr = invocation.arguments[1] as InetAddress

                if(packet.header.toAddr == neighborAddr.requireAddressAsInt()
                    && nextHopAddr == neighborAddr
                    && packet.header.toPort == 0
                ) {
                    val mmcpMessage = MmcpMessage.fromVirtualPacket(packet)
                    if(mmcpMessage is MmcpPing) {
                        val pongReply = MmcpPong(atomicInt.incrementAndGet(), mmcpMessage.messageId)

                        val pongPacket = pongReply.toVirtualPacket(
                            toAddr = localNetworkInterfaceAddr.requireAddressAsInt(),
                            fromAddr = neighborAddr.requireAddressAsInt(),
                            lastHopAddr = neighborAddr.requireAddressAsInt(),
                        )

                        Thread.sleep(pongDelay)

                        incomingMmcpMessages.tryEmit(
                            MmcpMessageAndPacketHeader(
                                pongReply, pongPacket.header, invocation.mock as VirtualNetworkInterface
                            )
                        )
                    }
                }

                Unit
            }
        }



        val originatingMessageManager = OriginatingMessageManager(
            virtualNetworkInterfaces = { listOf(virtualNetworkInterface) },
            logger = MNetLoggerStdout(),
            scheduledExecutorService = Executors.newScheduledThreadPool(2),
            nextMmcpMessageId = { atomicInt.incrementAndGet() },
            getWifiState = { MeshrabiyaWifiState() },
            incomingMmcpMessages = incomingMmcpMessages,
        )

        return OriginatingMessageManagerTestContext(
            originatingMessageManager,
            neighborAddr,
            virtualNetworkInterface,
            incomingMmcpMessages,
        )
    }


    @Test
    fun givenKnownNeighbors_whenRunning_thenWillSendOriginatingMessages() {
        val testContext = createOriginatingMessageManagerTestContext()

        verify(
            testContext.virtualNetworkInterface,
            timeout(10_000).atLeastOnce()
        ).send(
            //should check a little more carefully...
            virtualPacket = argWhere {
                it.header.toPort == 0 &&
                    it.header.toAddr == testContext.neighborAddr.requireAddressAsInt()
            },
            nextHopAddress = eq(testContext.neighborAddr),
        )

        testContext.originatingMessageManager.close()
    }

    @Test
    fun givenNodeRunning_whenOriginatorMessageReceived_thenStateWillUpdateToIncludeNewlyDiscoveredNode() {
        val testContext = createOriginatingMessageManagerTestContext()
        val (originatorMessage, originatorPacket) = testContext
            .createOriginatorMessageFromNeighborToVirtualNetwork()

        testContext.incomingMmcpMessages.tryEmit(
            MmcpMessageAndPacketHeader(
                message = originatorMessage,
                packetHeader = originatorPacket.header,
                receivedFromInterface = testContext.virtualNetworkInterface
            )
        )

        runBlocking {
            testContext.originatingMessageManager.state.filter {
                it.containsKey(testContext.neighborAddr.requireAddressAsInt())
            }.test(name = "Will receive newly discovered node", timeout = 10.seconds) {
                val stateWithNodeDiscovered = awaitItem()
                val nodeState = stateWithNodeDiscovered[testContext.neighborAddr.requireAddressAsInt()]!!

                Assert.assertEquals(testContext.virtualNetworkInterface, nodeState.receivedFromInterface)
                Assert.assertEquals(originatorMessage, nodeState.originatorMessage)
            }
        }

        testContext.originatingMessageManager.close()
    }

    @Test
    fun givenNodeDiscovered_whenPingRepliedTo_thenPingTimeWillBeUpdated() {
        val pingLatency = 60L
        val testContext = createOriginatingMessageManagerTestContext(pongDelay = pingLatency)

        val scope = CoroutineScope(Dispatchers.Default + Job())
        try {
            //Simulate the neighbor node repeatedly sending originator messages
            scope.launch {
                while(isActive) {
                    val (originatorMessage, originatorPacket) = testContext
                        .createOriginatorMessageFromNeighborToVirtualNetwork()

                    testContext.incomingMmcpMessages.tryEmit(
                        MmcpMessageAndPacketHeader(
                            message = originatorMessage,
                            packetHeader = originatorPacket.header,
                            receivedFromInterface = testContext.virtualNetworkInterface
                        )
                    )
                    delay(500)
                }
            }

            runBlocking {
                testContext.originatingMessageManager.state.filter {
                    val neighborPingTime = it[testContext.neighborAddr.requireAddressAsInt()]
                        ?.originatorMessage?.pingTimeSum
                    neighborPingTime != null && neighborPingTime >= pingLatency
                }.test(name = "wait for ping reply time to be set", timeout = 10.seconds) {
                    val stateWithPingTime = awaitItem()
                    val nodeState = stateWithPingTime[testContext.neighborAddr.requireAddressAsInt()]!!
                    Assert.assertTrue(nodeState.originatorMessage.pingTimeSum >= pingLatency)
                }
            }
        }finally {
            scope.cancel()
            testContext.originatingMessageManager.close()
        }
    }

    @Test
    fun givenNodeDiscovered_whenPingNotRepliedTo_thenNodeWillBeMarkedAsLost() {

    }

    @Test
    fun givenRunning_whenOriginatorMessageReceived_willBeRebroadcast() {

    }



}