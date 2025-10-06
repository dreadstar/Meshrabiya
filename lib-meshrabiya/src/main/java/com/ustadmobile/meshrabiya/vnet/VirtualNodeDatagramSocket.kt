package com.ustadmobile.meshrabiya.vnet

import android.net.Network
import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.ext.addressToDotNotation
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future

/**
 *
 * VirtualNodeDatagramSocket listens on the real network interface. It uses the executor service
 * to run a thread that will receive all packets, convert them from a DatagramPacket into a
 * VirtualPacket, and then give them to the VirtualRouter.
 *
 * @param socket - the underlying DatagramSocket to use - this can be bound to a network, interface etc if required
 * neighbor connects.
 * @param boundNetwork The Network object that the DatagramSocket is/will be bound to, if any. This
 *                     is needed if/when we want to establish a TCP connection. Because the
 *                     VirtualNodeDatagramSocket reference is kept as part of the originator message,
 *                     and it is created at the time the network object is available, this is the
 *                     most convenient and logical place to keep this reference.
 */
import com.ustadmobile.meshrabiya.net.SocketTimeoutsProvider

class VirtualNodeDatagramSocket(
    private val socket: DatagramSocket,
    private val localNodeVirtualAddress: Int,
    ioExecutorService: ExecutorService,
    private val router: VirtualRouter,
    private val logger: MNetLogger,
    name: String? = null,
    val boundNetwork: Network? = null,
    private val socketTimeoutsProvider: SocketTimeoutsProvider = com.ustadmobile.meshrabiya.net.DefaultSocketTimeoutsProvider(),
    /** Optional callback invoked once the receive-loop has started. Used by callers/tests to
     *  be notified when the datagram receive thread is alive. */
    private val onStarted: (() -> Unit)? = null,
):  Runnable, Closeable {

    private val future: Future<*>

    private val logPrefix: String

    val localPort: Int = socket.localPort

    init {
        logPrefix = buildString {
            append("[VirtualNodeDatagramSocket for ${localNodeVirtualAddress.addressToDotNotation()} ")
            if(name != null)
                append("- $name")
            append("] ")
        }
        future = ioExecutorService.submit(this)
    }

    override fun run() {
        val buffer = ByteArray(VirtualPacket.MAX_PAYLOAD_SIZE)
    logger(Log.DEBUG, "$logPrefix Started on ${socket.localPort} waiting for first packet", null)
    // Notify owner that the datagram receive loop has started.
    try { onStarted?.invoke() } catch (_: Throwable) {}
        var lastHeartbeat = System.currentTimeMillis()
    val isTestMode = java.lang.Boolean.getBoolean("meshrabiya.hardware.testMode") || Thread.currentThread().stackTrace.any { it.className.lowercase().contains("junit") || it.className.lowercase().contains("gradle") }
    // Allow suppressing repetitive heartbeat logs in CI/unit-test runs to avoid flooding
    // the test output. Default to true when in test mode.
    val silentHeartbeats = if (isTestMode) true else java.lang.Boolean.getBoolean("um.vnet.silentHeartbeats")

        // Configure socket receive timeout if provider suggests one. A receive timeout makes the
        // loop responsive to interruption and allows periodic heartbeats without relying on
        // thread interruption semantics which can be flaky across platforms.
        val soTimeout = socketTimeoutsProvider.socketSoTimeoutMillis
        try {
            if(soTimeout > 0) socket.soTimeout = soTimeout
        } catch (_: Exception) {
            // ignore any failures to set SO_TIMEOUT on specialized sockets
        }

        // If provider suggests a short socket timeout, make heartbeat interval a fraction of that so
        // we surface liveliness more often in tests. Otherwise use default 5s.
        val heartbeatInterval = when {
            soTimeout > 0 -> kotlin.math.max(250L, soTimeout / 2L)
            isTestMode -> 1000L
            else -> 5000L
        }

        while(!Thread.interrupted() && !socket.isClosed) {
            try {
                val rxPacket = DatagramPacket(buffer, 0, buffer.size)
                socket.receive(rxPacket)

                val rxVirtualPacket = VirtualPacket.fromDatagramPacket(rxPacket)
                router.route(
                    packet = rxVirtualPacket,
                    datagramPacket = rxPacket,
                    virtualNodeDatagramSocket = this,
                )
            } catch(e: java.net.SocketTimeoutException) {
                // Expected when a SO_TIMEOUT is configured. Treat as heartbeat opportunity.
                if(!silentHeartbeats)
                    logger(Log.DEBUG, "$logPrefix : run : receive timed out (soTimeout) - continuing", null)
            } catch(e: Exception) {
                if(!socket.isClosed)
                    logger(Log.WARN, "$logPrefix : run : exception handling packet", e)
            }

            // heartbeat to show the loop is alive and waiting
            val now = System.currentTimeMillis()
            if(now - lastHeartbeat > heartbeatInterval) {
                if(!silentHeartbeats)
                    logger(Log.DEBUG, "$logPrefix : run : heartbeat - waiting on socket (localPort=${socket.localPort})", null)
                lastHeartbeat = now
            }
        }
        logger(Log.DEBUG, "$logPrefix : run : finished")
    }


    /**
     *
     */
    fun send(
        nextHopAddress: InetAddress,
        nextHopPort: Int,
        virtualPacket: VirtualPacket
    ) {
        val datagramPacket = virtualPacket.toDatagramPacket()
        datagramPacket.address = nextHopAddress
        datagramPacket.port = nextHopPort
        socket.send(datagramPacket)
    }

    fun close(closeSocket: Boolean) {
        future.cancel(true)
        socket.takeIf { closeSocket }?.close()
    }

    override fun close() {
        // Ensure the underlying socket is closed so any blocking receive unblocks and the
        // run loop can finish promptly when tests/shutdown requests close().
        close(true)
    }
}
