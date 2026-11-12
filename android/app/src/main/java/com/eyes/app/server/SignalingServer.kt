package com.eyes.app.server

import com.eyes.app.protocol.Codec
import com.eyes.app.protocol.Msg
import com.eyes.app.security.TlsIdentity
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    // Une erreur imprévue dans une connexion ne doit jamais faire planter l'application.
    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.IO +
            CoroutineExceptionHandler { _, e -> Log.e("EyesServer", "erreur de connexion", e) },
    )
    private var server: Inner? = null

    private class Conn(val inbox: Channel<String>)

    private inner class Inner(port: Int) : WebSocketServer(InetSocketAddress(port)) {
        val started = CountDownLatch(1)
        @Volatile var startError: Exception? = null

        override fun onOpen(ws: WebSocket, handshake: ClientHandshake) {
            val inbox = Channel<String>(Channel.UNLIMITED)
            ws.setAttachment(Conn(inbox))
            val handler = factory(
                { msg -> if (ws.isOpen) runCatching { ws.send(Codec.encode(msg)) } },
                { ws.close() },
            )
            scope.launch {
                try {
                    for (raw in inbox) handler.onMessage(raw)
                } finally {
                    withContext(NonCancellable) { handler.onClosed() }
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

    /**
     * Démarre et renvoie le port. Essaie d'abord [preferredPort] (le dernier utilisé) pour que le PC
     * puisse retrouver le téléphone à sa dernière adresse même sans mDNS ; sinon un port libre.
     */
    fun start(preferredPort: Int = 0): Int {
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(identity.keyStore(), TlsIdentity.PASSWORD)
        val ssl = SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, null) }
        val s = if (preferredPort != 0) {
            runCatching { launch(ssl, preferredPort) }.getOrElse { launch(ssl, 0) }
        } else {
            launch(ssl, 0)
        }
        server = s
        return s.port
    }

    private fun launch(ssl: SSLContext, port: Int): Inner {
        val s = Inner(port)
        s.setWebSocketFactory(DefaultSSLWebSocketServerFactory(ssl))
        s.isReuseAddr = true
        s.connectionLostTimeout = 30
        s.start()
        if (!s.started.await(5, TimeUnit.SECONDS)) error("le serveur n'a pas démarré")
        s.startError?.let {
            runCatching { s.stop(100) }
            throw it
        }
        return s
    }

    fun stop() {
        server?.stop(1000)
        server = null
        scope.cancel()
    }
}
