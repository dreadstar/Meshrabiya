
package com.ustadmobile.meshrabiya.testapp.server


import android.util.Log
import com.ustadmobile.meshrabiya.log.MNetLogger
import com.ustadmobile.meshrabiya.vnet.netinterface.VirtualNetworkInterface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.net.DatagramPacket
import java.net.DatagramSocket


data class ChatMessage(
    val sender: String?,
    val message: String,
    val timestamp: Long
)

class ChatServer(
    private val logger: MNetLogger,
    private val socket: DatagramSocket,
    private val virtualNetworkInterfaces: () -> List<VirtualNetworkInterface>,
) {
    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())

    val chatMessages = _chatMessages.asStateFlow()

    fun sendMessage(message: String) {
        try {
            val data = message.toByteArray()

            val networkInterfaces = virtualNetworkInterfaces()
            networkInterfaces.forEach { networkInterface ->
                // Create broadcast packet
                val packet = DatagramPacket(
                    data,
                    data.size,
                    networkInterface.broadcastAddress,
                    UDP_PORT
                )

                socket.send(packet)
            }

            // Add message to local chat immediately
            val chatMessage = ChatMessage(
                timestamp = System.currentTimeMillis(),
                sender = null,
                message = message
            )
            _chatMessages.update { it + chatMessage }

            logger(Log.INFO, "Message sent: $message")
        } catch (e: Exception) {
            logger(Log.ERROR, "Failed to send message", e)
        }
    }


    fun close() {
        socket.close()
    }

    companion object {
        const val UDP_PORT = 8888
    }
}
