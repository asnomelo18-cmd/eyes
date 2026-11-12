package com.eyes.app.server

import com.eyes.app.protocol.Codec
import com.eyes.app.protocol.Msg
import com.eyes.app.security.TlsIdentity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.DefaultSSLWebSocketServerFactory
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

typealias HandlerFactory = (send: suspend (Msg) -> Unit, close: suspend () -> Unit) -> SessionHandler

/** Serveur WebSocket sécurisé (wss) ; une [SessionHandler] par connexion, messages traités dans l'ordre. */
class SignalingServer(private val identity: TlsIdentity, private val factory: HandlerFactory) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: Inner? = null

    private class Conn(val inbox: Channel<String>)

    private inner class Inner : WebSocketServer(InetSocketAddress(0)) {
        val started = CountDownLatch(1)
        @Volatile var startError: Exception? = null

        override fun onOpen(ws: WebSocket, handshake: ClientHandshake) {
            val inbox = Channel<String>(Channel.UNLIMITED)
            ws.setAttachment(Conn(inbox))
            val handler = factory(
                { msg -> if (ws.isOpen) ws.send(Codec.encode(msg)) },
                { ws.close() },
            )
            scope.launch {
                try {
                    for (raw in inbox) handler.onMessage(raw)
                } finally {
                    handler.onClosed()
                }
            }
        }

        override fun onMessage(ws: WebSocket, message: String) {
            ws.getAttachment<Conn>()?.inbox?.trySend(message)
        }

        override fun onClose(ws: WebSocket, code: Int, reason: String?, remote: Boolean) {
            ws.getAttachment<Conn>()?.inbox?.close()
        }

        override fun onError(ws: WebSocket?, ex: Exception) {
            if (ws == null) {
                startError = ex
                started.countDown()
            } else {
                ws.getAttachment<Conn>()?.inbox?.close()
            }
        }

        override fun onStart() {
            started.countDown()
        }
    }

    /** Démarre sur un port libre et renvoie ce port. */
    fun start(): Int {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(identity.keyStore(), TlsIdentity.PASSWORD)
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val s = Inner()
        s.setWebSocketFactory(DefaultSSLWebSocketServerFactory(ssl))
        s.isReuseAddr = true
        s.connectionLostTimeout = 30
        s.start()
        if (!s.started.await(5, TimeUnit.SECONDS)) error("le serveur n'a pas démarré")
        s.startError?.let { throw it }
        server = s
        return s.port
    }

    fun stop() {
        server?.stop(1000)
        server = null
        scope.cancel()
    }
}
