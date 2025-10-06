package com.ustadmobile.meshrabiya.vnet.socket

import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * Chain Socket Factory provides a SocketFactory that can connect to addresses on the virtual network.
 * See concept notes in ChainSocketServer.
 *
 * @param virtualRouter local node virtual router
 * @param systemSocketFactory the underlying system socket factory
 */
abstract class ChainSocketFactory: SocketFactory() {

    data class ChainSocketResult(
        val socket: Socket,
        val nextHop: ChainSocketNextHop,
    )

    abstract fun createChainSocket(address: InetAddress, port: Int): ChainSocketResult

    /** New overload to allow implementations to receive socket timeout provider for created sockets. */
    open fun createSocket(socketTimeoutsProvider: com.ustadmobile.meshrabiya.net.SocketTimeoutsProvider = com.ustadmobile.meshrabiya.net.DefaultSocketTimeoutsProvider()): Socket {
        // default to existing behavior
        return createSocket()
    }

}