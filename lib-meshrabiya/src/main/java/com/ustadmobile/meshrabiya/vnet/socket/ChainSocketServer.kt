package com.ustadmobile.meshrabiya.vnet.socket

import android.util.Log
import com.ustadmobile.meshrabiya.ext.readyByteArrayOfSizeOrThrow
import com.ustadmobile.meshrabiya.ext.writeChainSocketInitResponse
import com.ustadmobile.meshrabiya.log.MNetLogger
import java.io.Closeable
import java.lang.ref.WeakReference
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Chain Sockets create a chain of sockets to connect a socket over multiple hops. Let's say device
 * A wants to connect to device D via device B and C:
 *
 *  1. Device A will open a socket to the ChainSocketServer on device B. Device A writes the
 *     ChainInitRequest containing the final destination to the stream.
 *  2. Device B will read the ChainInitRequest, and finds a route to Device D where the next hop
 *     is device C. Device opens a socket to the ChainSocketServer on Device C, and writes the
 *     ChainInitRequest containing the final destination to the stream.
 *  3. Device C will read the ChainInitRequest, and finds that the final destination device D is a
 *     neighbor node. Device C will open a socket to the "real" IP address that it knows and can
 *     reach for device D.
 *
 * Each node copies input/output streams back/forth.  The end result is that when Device A read/writes
 * from its socket all data is relayed back/forth, so it can communicate with device D even though
 * it does not have a direct route to device D.
 *
 * Next steps: Handling HTTP: ForwardServer could read the first "magic bytes" to see if this is a socket forward or http request
 * if http request, then read it as a raw http request and proxy it
 *
 */
import com.ustadmobile.meshrabiya.net.SocketTimeoutsProvider

class ChainSocketServer(
    private val serverSocket: ServerSocket,
    private val executorService: ExecutorService,
    private val chainSocketFactory: ChainSocketFactory,
    name: String,
    private val logger: MNetLogger,
    /** Optional callback invoked once the accept loop has been started. Tests/use-cases
     *  may use this to know the server is ready to accept connections. */
    private val onStarted: (() -> Unit)? = null,
    private val socketTimeoutsProvider: SocketTimeoutsProvider = com.ustadmobile.meshrabiya.net.DefaultSocketTimeoutsProvider(),
    private val onMakeChainSocket: ChainSocketFactory.(address: InetAddress, port: Int) -> ChainSocketFactory.ChainSocketResult = { address, port ->
        createChainSocket(address, port)
    }
) : Closeable {

    private val logPrefix: String = "[ChainSocketServer: $name] "

    private val SYS_PROP_TEST_MODE = "meshrabiya.hardware.testMode"

    private val clientFutures: MutableList<WeakReference<Future<*>>> = CopyOnWriteArrayList()

    // Coroutine-backed lifecycle for accept loop. Accept loop is started/stopped explicitly via start()/stop().
    private var acceptScope: CoroutineScope? = null
    private var acceptJobActive = false

    private fun configureSoTimeout() {
        try {
            val acceptTimeout = socketTimeoutsProvider.acceptTimeoutMillis
            logger(Log.DEBUG, "$logPrefix configureSoTimeout: provider=${socketTimeoutsProvider.javaClass.simpleName} acceptTimeout=$acceptTimeout")
            // Accept timeout polling causes tight loops in test environments. Prefer a
            // blocking accept and use ServerSocket.close() to interrupt accept when
            // stopping. Keep this code for backward-compatibility only if an explicit
            // positive timeout is intentionally requested (avoid by default).
            if (acceptTimeout > 0) {
                serverSocket.soTimeout = acceptTimeout
            } else {
                // ensure no polling timeout by default
                try { serverSocket.soTimeout = 0 } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    /**
     * Start the accept loop. Safe to call multiple times.
     */
    fun start(scope: CoroutineScope = CoroutineScope(Dispatchers.IO + Job())) {
        if (acceptJobActive) return
        configureSoTimeout()
        acceptScope = scope
        acceptJobActive = true

        scope.launch {
            // Signal that the accept coroutine has been launched and is starting its work.
            try { onStarted?.invoke() } catch (_: Throwable) {}
            // Use a blocking accept loop. When stopping we will close the ServerSocket which
            // causes accept() to throw a SocketException and the loop will exit cleanly.
            val isTestMode = java.lang.Boolean.getBoolean(SYS_PROP_TEST_MODE)
            while (isActive && !serverSocket.isClosed) {
                try {
                    val incomingSocket = withContext(Dispatchers.IO) { serverSocket.accept() }
                    logger(Log.DEBUG, "$logPrefix accepted new client")
                    try {
                        val soTimeout = socketTimeoutsProvider.socketSoTimeoutMillis
                        if (soTimeout > 0) incomingSocket.soTimeout = soTimeout
                    } catch (_: Exception) {}
                    executorService.submit(ClientInitRunnable(incomingSocket))
                } catch (e: Exception) {
                    // If the server socket was closed as part of shutdown, accept will
                    // throw a SocketException; treat that as expected and break the loop.
                    if (e is java.net.SocketException && serverSocket.isClosed) {
                        logger(Log.INFO, "$logPrefix accept interrupted by serverSocket.close() - exiting accept loop")
                        break
                    }

                    // SocketTimeoutException is an expected event when an accept timeout is
                    // configured (used by tests). Treat it as a debug event and continue
                    // without using the backoff path to avoid looping warnings.
                    if (e is java.net.SocketTimeoutException) {
                        logger(Log.DEBUG, "$logPrefix accept timed out (soTimeout) - continuing")
                        continue
                    }

                    // For other exceptions, log and on active scope continue unless cancelled.
                    if (isActive && !serverSocket.isClosed) {
                        logger(Log.WARN, "$logPrefix exception accepting client", e)
                        // small backoff to avoid tight failure loops for genuine errors
                        try {
                            delay(250)
                        } catch (_: Exception) {
                            break
                        }
                    } else {
                        break
                    }
                }
            }
        }
    }

    /**
     * Stop the accept loop and cleanup. Does not close underlying ServerSocket unless closeSocket=true is used.
     */
    fun stop() {
        // Cancel accept job and close the ServerSocket to reliably interrupt accept().
        try { acceptScope?.cancel() } catch (_: Exception) {}
        try { if (!serverSocket.isClosed) serverSocket.close() } catch (_: Exception) {}
        acceptScope = null
        acceptJobActive = false
        clientFutures.forEach {
            it.get()?.cancel(true)
        }
        logger(Log.INFO, "$logPrefix stopped and server socket closed to interrupt accept")
    }

    val localPort: Int = serverSocket.localPort

    init {
        logger(Log.INFO, "$logPrefix init")
        // Start accept loop by default for backward compatibility with tests and callers
        // which previously relied on the accept loop starting during construction.
        try {
            start()
            // signal that the accept loop has been started (best-effort)
            try { onStarted?.invoke() } catch (_: Throwable) {}
        } catch (_: Exception) {
            // If starting fails for any reason, log and continue; callers can call start() themselves.
            logger(Log.WARN, "$logPrefix failed to auto-start accept loop")
        }
    }

    private inner class ClientInitRunnable(private val incomingSocket: Socket): Runnable {
        override fun run() {
            logger(
                Log.DEBUG,
                message = {"$logPrefix ${incomingSocket.remoteSocketAddress} : init client - reading init request..."}
            )

            //read the destination - find next connection
            val inStream = incomingSocket.getInputStream()
            val initRequest = ChainSocketInitRequest.fromBytes(
                inStream.readyByteArrayOfSizeOrThrow(ChainSocketInitRequest.MESSAGE_SIZE)
            )
            val clientAddr = incomingSocket.remoteSocketAddress

            logger(Log.DEBUG,
                message = {
                    "$logPrefix $clientAddr : receive init request to " +
                        "connect to ${initRequest.virtualDestAddr}:${initRequest.virtualDestPort}"
                }
            )

            val chainSocketResult = onMakeChainSocket(
                chainSocketFactory, initRequest.virtualDestAddr, initRequest.virtualDestPort
            )

            logger(Log.DEBUG,
                message = {
                    "$logPrefix $clientAddr : created onward socket"
                }
            )

            //If we made it here, everything is OK
            incomingSocket.getOutputStream().writeChainSocketInitResponse(
                ChainSocketInitResponse(200)
            )
            incomingSocket.getOutputStream().flush()
            logger(Log.DEBUG,
                message = {
                    "$logPrefix $clientAddr : wrote chain init response"
                }
            )

            val onwardSocket = chainSocketResult.socket

            val onwardToIncomingFuture = executorService.submit(
                CopyStreamRunnable(clientAddr, onwardSocket, incomingSocket, "onwardToIncoming")
            )
            val incomingToOnwardFuture = executorService.submit(
                CopyStreamRunnable(clientAddr, incomingSocket, onwardSocket, "incomingToOnward", onwardToIncomingFuture)
            )
            clientFutures += WeakReference(onwardToIncomingFuture)
            clientFutures += WeakReference(incomingToOnwardFuture)
        }
    }

    private inner class CopyStreamRunnable(
        private val clientAddr: SocketAddress,
        private val fromSocket: Socket,
        private val toSocket: Socket,
        private val name: String,
        private val otherFuture: Future<*>? = null,
    ): Runnable {

        override fun run() {
            val outStream = toSocket.getOutputStream()
            val inStream = fromSocket.getInputStream()
            try {
                logger(Log.VERBOSE, {"$logPrefix $clientAddr : CopyStream: $name - start copying input to output"})
                inStream.copyTo(outStream)
                logger(Log.VERBOSE, {"$logPrefix $clientAddr : CopyStream: $name - finished copying - reached end of stream"})
            } catch (e: Exception) {
                // A SocketException with message "Socket closed" is expected when shutdown
                // closes sockets while a thread is blocked in read(). Treat that as a
                // normal shutdown event and log at DEBUG level only. Similarly, interrupted
                // IO or thread interruption should be considered non-actionable here.
                when {
                    e is java.net.SocketException && e.message?.contains("Socket closed") == true ->
                        logger(Log.DEBUG, {"$logPrefix $clientAddr: CopyStream: aborting (socket closed)"})
                    e is java.io.InterruptedIOException ->
                        logger(Log.DEBUG, {"$logPrefix $clientAddr: CopyStream: aborting (interrupted)"})
                    else ->
                        logger(Log.WARN, {"$logPrefix $clientAddr: CopyStream: aborting"}, e)
                }
            } finally {
                // Need to explicitly close the streams we are handling immediately. Use
                // best-effort closes and avoid blocking on the otherFuture to prevent
                // potential deadlocks during shutdown.
                try { inStream.close() } catch (_: Exception) {}
                try { outStream.close() } catch (_: Exception) {}

                if (otherFuture != null) {
                    try {
                        if (!otherFuture.isDone) {
                            // If the paired copy task is still running try to cancel it rather
                            // than blocking indefinitely waiting for it to finish.
                            otherFuture.cancel(true)
                        } else {
                            // If it's done, attempt to get to propagate any exceptions (best-effort)
                            try { otherFuture.get() } catch (_: Exception) {}
                        }
                    } catch (_: Exception) {}

                    try { fromSocket.close() } catch (_: Exception) {}
                    try { toSocket.close() } catch (_: Exception) {}
                }
            }

            clientFutures.removeIf { it.get() == null }
        }
    }

    fun close(closeSocket: Boolean) {
        // stop accept loop and cancel client futures
        try { acceptScope?.cancel() } catch (_: Exception) {}
        clientFutures.forEach {
            it.get()?.cancel(true)
        }
        try { if (closeSocket) serverSocket.close() } catch (_: Exception) {}
        logger(Log.INFO, "$logPrefix closed")
    }

    override fun close() {
        close(closeSocket = false)
    }

}