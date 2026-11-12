package com.eyes.app.server

import com.eyes.app.pairing.InMemoryPairedPcStore
import com.eyes.app.pairing.PairResult
import com.eyes.app.pairing.PairingManager
import com.eyes.app.security.TlsIdentity
import kotlinx.coroutines.runBlocking
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.URI
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

class SignalingServerTest {
    @get:Rule val tmp = TemporaryFolder()
    private var server: SignalingServer? = null

    @After fun tearDown() { server?.stop() }

    private class Client(uri: URI) : WebSocketClient(uri) {
        val inbox = LinkedBlockingQueue<String>()
        val closed = LinkedBlockingQueue<Int>()
        override fun onOpen(h: ServerHandshake) {}
        override fun onMessage(m: String) { inbox += m }
        override fun onClose(code: Int, reason: String?, remote: Boolean) { closed += code }
        override fun onError(ex: Exception) { System.err.println("client: $ex") }
        // Comme le PC : pas de vérification de nom d'hôte, l'empreinte est vérifiée à part.
        override fun onSetSSLParameters(p: javax.net.ssl.SSLParameters) {}
        fun peerSha256(): String {
            val cert = (socket as SSLSocket).session.peerCertificates[0]
            return MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
        }
    }

    private fun trustAll(): SSLContext = SSLContext.getInstance("TLS").apply {
        val tm = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {}
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {}
            override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
        }
        init(null, arrayOf<TrustManager>(tm), null)
    }

    @Test
    fun helloOverRealTlsGetsWelcomeAndCertIsIdentity() = runBlocking {
        val identity = TlsIdentity(tmp.newFolder())
        val pairing = PairingManager(InMemoryPairedPcStore())
        val token = (pairing.tryPair(pairing.openPairing(), "pc1", "Bureau") as PairResult.Success).token
        val active = AtomicReference<String?>(null)
        val s = SignalingServer(identity) { send, close ->
            SessionHandler(pairing, FakeMedia(), active, "ph1", "Tel", send, close)
        }
        server = s
        val port = s.start()

        val c = Client(URI("wss://127.0.0.1:$port/"))
        c.setSocketFactory(trustAll().socketFactory)
        check(c.connectBlocking(5, TimeUnit.SECONDS)) { "connexion impossible" }
        assertEquals(identity.sha256(), c.peerSha256())
        c.send("""{"type":"hello","pcId":"pc1","token":"$token","v":1}""")
        assertEquals("""{"type":"welcome","phoneId":"ph1","phoneName":"Tel"}""", c.inbox.poll(5, TimeUnit.SECONDS))
        c.send("""{"type":"start","video":true,"audio":true,"camera":"back"}""")
        assertEquals("""{"type":"offer","sdp":"offre-sdp"}""", c.inbox.poll(5, TimeUnit.SECONDS))
        c.closeBlocking()
        // la déconnexion libère la place de spectateur
        val deadline = System.currentTimeMillis() + 5000
        while (active.get() != null && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(null, active.get())
    }

    @Test
    fun deniedClosesConnection() {
        val identity = TlsIdentity(tmp.newFolder())
        val pairing = PairingManager(InMemoryPairedPcStore())
        val s = SignalingServer(identity) { send, close ->
            SessionHandler(pairing, FakeMedia(), AtomicReference(null), "ph1", "Tel", send, close)
        }
        server = s
        val port = s.start()
        val c = Client(URI("wss://127.0.0.1:$port/"))
        c.setSocketFactory(trustAll().socketFactory)
        check(c.connectBlocking(5, TimeUnit.SECONDS))
        c.send("""{"type":"hello","pcId":"x","token":"y","v":1}""")
        assertEquals("""{"type":"denied","reason":"unknown"}""", c.inbox.poll(5, TimeUnit.SECONDS))
        check(c.closed.poll(5, TimeUnit.SECONDS) != null) { "la connexion aurait dû être fermée" }
    }
}
