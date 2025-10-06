package com.ustadmobile.meshrabiya.vnet.socket

import android.util.Log
import com.ustadmobile.meshrabiya.ext.prefixMatches
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.VirtualRouter
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

class ChainSocketFactoryImpl(
    internal val virtualRouter: VirtualRouter,
    private val systemSocketFactory: SocketFactory = getDefault(),
    private val logger: MNetLogger,
    private val socketTimeoutsProvider: com.ustadmobile.meshrabiya.net.SocketTimeoutsProvider = com.ustadmobile.meshrabiya.net.DefaultSocketTimeoutsProvider(),
) : ChainSocketFactory() {

    private val logPrefix: String = "[ChainSocketFactoryImpl for ${virtualRouter.address}]"

    private fun createSocketForVirtualAddress(
        address: InetAddress,
        port: Int,
        localAddress: InetAddress? = null,
        localPort: Int? = null
    ) : ChainSocketResult {
        try {
            val nextHop = virtualRouter.lookupNextHopForChainSocket(address, port)
            val socketFactory = nextHop.network?.socketFactory ?: systemSocketFactory
            val socket = if(localAddress != null && localPort != null) {
                // Create unconnected socket and connect with timeout if provider specifies
                val s = socketFactory.createSocket() as java.net.Socket
                if (localAddress != null && localPort != null) {
                    s.bind(java.net.InetSocketAddress(localAddress, localPort))
                }
                val connectTimeout = socketTimeoutsProvider.connectTimeoutMillis
                // Retry connect a few times to tolerate spurious failures in tests/environments
                val maxRetries = 3
                var attempt = 0
                var lastEx: Exception? = null
                while(attempt < maxRetries) {
                    try {
                        if (connectTimeout > 0) {
                            s.connect(java.net.InetSocketAddress(nextHop.address, nextHop.port), connectTimeout)
                        } else {
                            s.connect(java.net.InetSocketAddress(nextHop.address, nextHop.port))
                        }
                        lastEx = null
                        break
                    } catch(e: Exception) {
                        lastEx = e
                        attempt++
                        if(attempt < maxRetries) Thread.sleep(50L * attempt)
                    }
                }
                if(lastEx != null) throw lastEx
                s
            }else {
                val s = socketFactory.createSocket() as java.net.Socket
                val connectTimeout = socketTimeoutsProvider.connectTimeoutMillis
                // Retry connect a few times to tolerate spurious failures in tests/environments
                val maxRetries = 3
                var attempt = 0
                var lastEx: Exception? = null
                while(attempt < maxRetries) {
                    try {
                        if (connectTimeout > 0) {
                            s.connect(java.net.InetSocketAddress(nextHop.address, nextHop.port), connectTimeout)
                        } else {
                            s.connect(java.net.InetSocketAddress(nextHop.address, nextHop.port))
                        }
                        lastEx = null
                        break
                    } catch(e: Exception) {
                        lastEx = e
                        attempt++
                        if(attempt < maxRetries) Thread.sleep(50L * attempt)
                    }
                }
                if(lastEx != null) throw lastEx
                s
            }

            // apply SO_TIMEOUT/read/write settings where applicable
            try {
                val so = socketTimeoutsProvider.socketSoTimeoutMillis
                if (so > 0) socket.soTimeout = so
            } catch (_: Exception) {}

            socket.initializeChainIfNotFinalDest(
                ChainSocketInitRequest(
                    virtualDestAddr = address,
                    virtualDestPort = port,
                    fromAddr = virtualRouter.address
                ),
                nextHop
            )

            logger(Log.INFO, "$logPrefix created socket to $address:$port " +
                    "nexthop = ${nextHop.address}:${nextHop.port}")
            return ChainSocketResult(socket, nextHop)
        }catch(e: Exception) {
            logger(Log.ERROR, "$logPrefix exception creating socket", e)
            throw e
        }
    }

    private fun InetAddress.isVirtualAddress(): Boolean {
        return prefixMatches(virtualRouter.networkPrefixLength, virtualRouter.address)
    }

    override fun createSocket(host: String, port: Int): Socket {
        val address = InetAddress.getByName(host)
        return if(address.isVirtualAddress()) {
            createSocketForVirtualAddress(address, port).socket
        }else {
            systemSocketFactory.createSocket(host, port)
        }
    }

    override fun createSocket(host: String, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        val address = InetAddress.getByName(host)
        return if(address.isVirtualAddress()) {
            createSocketForVirtualAddress(address, port, localAddress, localPort).socket
        }else {
            systemSocketFactory.createSocket(host, port, localAddress, localPort)
        }
    }

    override fun createSocket(address: InetAddress, port: Int): Socket {
        return if(address.isVirtualAddress()) {
            createSocketForVirtualAddress(address, port).socket
        }else {
            systemSocketFactory.createSocket(address, port)
        }
    }

    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket {
        return if(address.isVirtualAddress()) {
            createSocketForVirtualAddress(address, port, localAddress, localPort).socket
        }else {
            systemSocketFactory.createSocket(address, port, localAddress, localPort)
        }
    }

    override fun createSocket(): Socket {
        return ChainSocket(virtualRouter, logger, socketTimeoutsProvider)
    }

    override fun createChainSocket(address: InetAddress, port: Int): ChainSocketResult {
        return createSocketForVirtualAddress(address, port)
    }
}