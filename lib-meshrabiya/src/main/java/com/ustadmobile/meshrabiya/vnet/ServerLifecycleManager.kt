package com.ustadmobile.meshrabiya.vnet

import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketFactory
import com.ustadmobile.meshrabiya.vnet.socket.ChainSocketServer
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

internal object ServerLifecycleManager {
    private val lock = Any()
    private val servers = mutableMapOf<String, ServerEntry>()

    private data class ServerEntry(
        val name: String,
        val bindAddr: InetAddress,
        val port: Int,
        val serverSocket: ServerSocket,
        val server: ChainSocketServer,
        val refCount: AtomicInteger = AtomicInteger(1)
    )

    private val scheduler: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor()

    fun acquire(name: String, bindAddr: InetAddress, port: Int,
                chainSocketFactory: ChainSocketFactory, logger: MNetLogger): ChainSocketServer {
        synchronized(lock) {
            val key = "$name@${bindAddr.hostAddress}:$port"
            val existing = servers[key]
            if (existing != null) {
                existing.refCount.incrementAndGet()
                return existing.server
            }

            val ss = ServerSocket(port, 50, bindAddr)
            val server = ChainSocketServer(ss, Executors.newCachedThreadPool(), chainSocketFactory, name, logger)
            // start server accept loop explicitly
            server.start()

            val entry = ServerEntry(name, bindAddr, port, ss, server)
            servers[key] = entry
            return server
        }
    }

    fun release(name: String, bindAddr: InetAddress, port: Int, idleTimeoutMs: Long = 5000L) {
        synchronized(lock) {
            val key = "$name@${bindAddr.hostAddress}:$port"
            val entry = servers[key] ?: return
            val refs = entry.refCount.decrementAndGet()
            if (refs <= 0) {
                scheduler.schedule({
                    synchronized(lock) {
                        val e = servers[key] ?: return@schedule
                        if (e.refCount.get() <= 0) {
                            try { e.server.stop() } catch (_: Exception) {}
                            try { e.serverSocket.close() } catch (_: Exception) {}
                            servers.remove(key)
                        }
                    }
                }, idleTimeoutMs, TimeUnit.MILLISECONDS)
            }
        }
    }

    fun forceStop(name: String, bindAddr: InetAddress, port: Int) {
        synchronized(lock) {
            val key = "$name@${bindAddr.hostAddress}:$port"
            servers.remove(key)?.let { entry ->
                try { entry.server.stop() } catch (_: Exception) {}
                try { entry.serverSocket.close() } catch (_: Exception) {}
            }
        }
    }
}
