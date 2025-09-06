package com.ustadmobile.meshrabiya.portforward

import com.ustadmobile.meshrabiya.log.MNetLoggerStdout
import com.ustadmobile.meshrabiya.test.EchoDatagramServer
import org.junit.Assert
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.Executors

class ForwardingTest {

    @Test(timeout = 5000)
    fun givenEchoSent_whenListening_willReceive() {
        val executor = Executors.newCachedThreadPool()
        val echoServer = EchoDatagramServer(0, executor)

        val client = DatagramSocket()

        val helloBytes = "Hello".toByteArray()
        val helloPacket = DatagramPacket(helloBytes, helloBytes.size,
            InetAddress.getLoopbackAddress(), echoServer.listeningPort)
        client.send(helloPacket)

        val receiveBuffer = ByteArray(100)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        client.receive(receivePacket)

        val decoded = String(receivePacket.data, receivePacket.offset, receivePacket.length)
        Assert.assertEquals("Hello", decoded)
        executor.shutdown()
        echoServer.close()
    }

    @Test(timeout = 5000)
    fun givenPortForwardingRuleActive_whenPacketSentToForwarder_thenReplyWillBeReceived() {
        val executor = Executors.newCachedThreadPool()
        val echoServer = EchoDatagramServer(0, executor)

        // Create a bound socket for the forwarding rule
        val boundSocket = DatagramSocket(0)
        val forwardingRule = UdpForwardRule(
            boundSocket = boundSocket,
            ioExecutor = executor,
            destAddress = InetAddress.getLoopbackAddress(),
            destPort = echoServer.listeningPort,
            logger = MNetLoggerStdout()
        )

        // Start the forwarding rule
        executor.submit(forwardingRule)

        val client = DatagramSocket()
        val helloBytes = "Hello".toByteArray()
        val helloPacket = DatagramPacket(helloBytes, helloBytes.size,
            InetAddress.getLoopbackAddress(), boundSocket.localPort)
        client.send(helloPacket)

        val receiveBuffer = ByteArray(100)
        val receivePacket = DatagramPacket(receiveBuffer, receiveBuffer.size)
        client.receive(receivePacket)

        val decoded = String(receivePacket.data, receivePacket.offset, receivePacket.length)
        Assert.assertEquals("Hello", decoded)
        
        // Cleanup
        executor.shutdown()
        echoServer.close()
        forwardingRule.close()
        boundSocket.close()
        client.close()
    }
}