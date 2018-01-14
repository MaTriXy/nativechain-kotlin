package com.sys1yagi.nativechain.p2p

import com.sys1yagi.nativechain.Block
import com.sys1yagi.nativechain.NativeChain
import com.sys1yagi.nativechain.util.JsonConverter
import com.sys1yagi.nativechain.p2p.connection.KtorConnection
import com.sys1yagi.nativechain.p2p.connection.TryusConnection
import com.sys1yagi.nativechain.p2p.connection.Connection
import io.ktor.application.install
import io.ktor.features.CallLogging
import io.ktor.features.DefaultHeaders
import io.ktor.routing.routing
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.websocket.WebSockets
import io.ktor.websocket.webSocket
import kotlinx.coroutines.experimental.async
import kotlinx.coroutines.experimental.channels.consumeEach
import org.slf4j.LoggerFactory
import java.net.URI
import java.time.Duration

class NativeChainAgent(val nativeChain: NativeChain, val jsonConverter: JsonConverter) {

    private val logger = LoggerFactory.getLogger("NativeChainAgent")

    private fun buildChainMessage() =
        """
        {
            'type': ${MessageType.RESPONSE_BLOCKCHAIN.ordinal},
            'blockchain': ${jsonConverter.toJson(nativeChain.blockchain)}
        }
        """

    private fun buildLatestMessage() =
        """
        {
            'type': ${MessageType.RESPONSE_BLOCK.ordinal},
            'block': ${jsonConverter.toJson(nativeChain.getLatestBlock())}
        }
        """

    private val sockets = arrayListOf<Connection>()

    fun sockets(): List<Connection> = sockets

    fun connectToPeers(newPeers: List<Peer>) {
        newPeers.forEach { peer ->
            val webSocketChannel = TryusConnection(URI.create(peer.host))
            webSocketChannel.connect {
                async {
                    initConnection(webSocketChannel)
                }
            }
        }
    }

    fun startP2PServer(port: Int) {
        embeddedServer(Netty, port) {
            install(DefaultHeaders)
            install(CallLogging)
            install(WebSockets) {
                pingPeriod = Duration.ofMinutes(1)
            }
            routing {
                webSocket {
                    logger.debug("receive p2p connection")
                    val socket = KtorConnection(this)
                    initConnection(socket)
                }
            }
        }.start(wait = false)

    }

    suspend fun initConnection(session: Connection) {
        sockets += session
        chainLengthMessage(session)

        try {
            session.receiveChannel().consumeEach {
                logger.debug("receive connection")
                handleMessage(session, it)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun broadcastLatestMessage() {
        broadcast(buildLatestMessage())
    }

    private fun chainLengthMessage(session: Connection) {
        write(session, "{'type': ${MessageType.QUERY_LATEST.ordinal}}")
    }

    private fun sendLatestMessage(session: Connection) {
        write(session, buildLatestMessage())
    }

    private fun sendChainMessage(session: Connection) {
        write(session, buildChainMessage())
    }

    private fun broadcastAllMessage() {
        broadcast("{'type': ${MessageType.QUERY_ALL.ordinal}}")
    }

    private fun handleBlockchainResponse(receivedBlocks: List<Block>) {
        val latestBlockReceived = receivedBlocks.last()
        val latestBlockHeld = nativeChain.getLatestBlock()
        if (latestBlockReceived.index > latestBlockHeld.index) {
            logger.debug("blockchain possibly behind. We got: ${latestBlockHeld.index} Peer got: ${latestBlockReceived.index}")
            if (latestBlockHeld.hash === latestBlockReceived.previousHash) {
                logger.debug("We can append the received block to our chain")
                nativeChain.addBlock(latestBlockReceived)
                broadcastLatestMessage()
            } else if (receivedBlocks.size == 1) {
                logger.debug("We have to query the chain from our connection")
                broadcastAllMessage()
            } else {
                logger.debug("Received blockchain is longer than current blockchain")
                nativeChain.replaceChain(receivedBlocks)
                broadcastLatestMessage()
            }
        } else {
            logger.debug("received blockchain is not longer than current blockchain. Do nothing")
        }
    }

    private fun handleMessage(from: Connection, json: String) {
        logger.debug("receive ${json}")
        val message = jsonConverter.fromJson(json, Message::class.java)
        when (message.messageType()) {
            MessageType.QUERY_LATEST -> {
                sendLatestMessage(from)
            }
            MessageType.QUERY_ALL -> {
                sendChainMessage(from)
            }
            MessageType.RESPONSE_BLOCK -> {
                handleBlockchainResponse(listOf(message.block!!))
            }
            MessageType.RESPONSE_BLOCKCHAIN -> {
                handleBlockchainResponse(message.blockchain)
            }
        }
    }

    private fun write(session: Connection, message: String) {
        session.send(message)
    }

    private fun broadcast(message: String) {
        sockets.forEach {
            write(it, message)
        }
    }
}
