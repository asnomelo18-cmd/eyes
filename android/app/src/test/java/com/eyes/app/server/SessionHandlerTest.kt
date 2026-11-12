package com.eyes.app.server

import com.eyes.app.pairing.InMemoryPairedPcStore
import com.eyes.app.pairing.PairResult
import com.eyes.app.pairing.PairingManager
import com.eyes.app.protocol.Camera
import com.eyes.app.protocol.Codec
import com.eyes.app.protocol.Denied
import com.eyes.app.protocol.DeniedReason
import com.eyes.app.protocol.ErrorCode
import com.eyes.app.protocol.ErrorMsg
import com.eyes.app.protocol.Msg
import com.eyes.app.protocol.Offer
import com.eyes.app.protocol.Paired
import com.eyes.app.protocol.SetMsg
import com.eyes.app.protocol.Start
import com.eyes.app.protocol.Welcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class FakeMedia : MediaController {
    var failWith: Exception? = null
    val starts = mutableListOf<Start>()
    val answers = mutableListOf<String>()
    val sets = mutableListOf<SetMsg>()
    var stops = 0
    override suspend fun start(s: Start): String {
        failWith?.let { throw it }
        starts += s
        return "offre-sdp"
    }
    var answerFails = false
    var setFails = false
    override suspend fun applyAnswer(sdp: String) {
        if (answerFails) throw IllegalStateException("SDP invalide")
        answers += sdp
    }
    override fun set(m: SetMsg) {
        if (setFails) throw IllegalStateException("échec")
        sets += m
    }
    override fun stop() { stops++ }
}

class SessionHandlerTest {
    private val store = InMemoryPairedPcStore()
    private val pairing = PairingManager(store)
    private val media = FakeMedia()
    private val active = AtomicReference<SessionHandler?>(null)

    inner class Conn {
        val sent = mutableListOf<Msg>()
        var closed = false
        var stopsWhenDenied = -1
        val sharing = mutableListOf<String?>()
        val handler = SessionHandler(
            pairing, media, active, phoneId = "ph1", phoneName = "Tel",
            send = { sent += it; if (it is Denied) stopsWhenDenied = media.stops }, close = { closed = true },
            listener = object : SessionListener {
                override fun onSharingStarted(pcName: String) { sharing += pcName }
                override fun onSharingStopped() { sharing += null }
            },
        )
        suspend fun msg(raw: String) = handler.onMessage(raw)
    }

    private suspend fun pairPc(pcId: String): String {
        val code = pairing.openPairing()
        return (pairing.tryPair(code, pcId, "PC $pcId") as PairResult.Success).token
    }

    private suspend fun hello(c: Conn, pcId: String, token: String, v: Int = 1) =
        c.msg("""{"type":"hello","pcId":"$pcId","token":"$token","v":$v}""")

    private val startRaw = """{"type":"start","video":true,"audio":true,"camera":"back"}"""

    @Test
    fun pairOverSocketReturnsPairedAndCloses() = runTest {
        val code = pairing.openPairing()
        val c = Conn()
        c.msg("""{"type":"pair","code":"$code","pcId":"pc1","pcName":"Bureau","v":1}""")
        val paired = c.sent.single() as Paired
        assertEquals("ph1", paired.phoneId)
        assertTrue(c.closed)
    }

    @Test
    fun wrongCodeOverSocketDenied() = runTest {
        pairing.openPairing()
        val c = Conn()
        c.msg("""{"type":"pair","code":"xxxxxx","pcId":"pc1","pcName":"Bureau","v":1}""")
        assertEquals(Denied(DeniedReason.bad_code), c.sent.single())
        assertTrue(c.closed)
    }

    @Test
    fun startBeforeHelloIsDenied() = runTest {
        val c = Conn()
        c.msg(startRaw)
        assertEquals(Denied(DeniedReason.unknown), c.sent.single())
        assertTrue(c.closed)
        assertTrue(media.starts.isEmpty())
    }

    @Test
    fun helloThenStartSendsOffer() = runTest {
        val t = pairPc("pc1")
        val c = Conn()
        hello(c, "pc1", t)
        c.msg(startRaw)
        assertEquals(listOf(Welcome("ph1", "Tel"), Offer("offre-sdp")), c.sent)
        assertEquals(Start(true, true, Camera.back), media.starts.single())
        assertEquals(listOf<String?>("PC pc1"), c.sharing)
        c.msg("""{"type":"answer","sdp":"rep"}""")
        assertEquals(listOf("rep"), media.answers)
        c.msg("""{"type":"set","camera":"front"}""")
        assertEquals(listOf(SetMsg(camera = Camera.front)), media.sets)
    }

    @Test
    fun secondPcGetsBusy() = runTest {
        val t1 = pairPc("pc1")
        val t2 = pairPc("pc2")
        val a = Conn(); hello(a, "pc1", t1); a.msg(startRaw)
        val b = Conn(); hello(b, "pc2", t2); b.msg(startRaw)
        assertEquals(ErrorMsg(ErrorCode.busy), b.sent.last())
        assertEquals(1, media.starts.size)
        a.handler.onClosed()
        b.msg(startRaw)
        assertEquals(Offer("offre-sdp"), b.sent.last())
    }

    @Test
    fun invalidJsonGetsBadMessageAndStaysOpen() = runTest {
        val t = pairPc("pc1")
        val c = Conn(); hello(c, "pc1", t)
        c.msg("pas du json")
        c.msg("""{"type":"inconnu"}""")
        assertEquals(ErrorMsg(ErrorCode.bad_message), c.sent.last())
        assertFalse(c.closed)
        c.msg(startRaw)
        assertEquals(Offer("offre-sdp"), c.sent.last())
    }

    @Test
    fun stopReleasesMedia() = runTest {
        val t = pairPc("pc1")
        val c = Conn(); hello(c, "pc1", t); c.msg(startRaw)
        c.msg("""{"type":"stop"}""")
        assertEquals(1, media.stops)
        assertEquals(null, active.get())
        assertEquals(listOf<String?>("PC pc1", null), c.sharing)
    }

    @Test
    fun disconnectReleasesMedia() = runTest {
        val t = pairPc("pc1")
        val c = Conn(); hello(c, "pc1", t); c.msg(startRaw)
        c.handler.onClosed()
        assertEquals(1, media.stops)
        assertEquals(null, active.get())
    }

    @Test
    fun disconnectWithoutSharingDoesNotTouchMedia() = runTest {
        val t = pairPc("pc1")
        val c = Conn(); hello(c, "pc1", t)
        c.handler.onClosed()
        assertEquals(0, media.stops)
    }

    @Test
    fun revokeDuringViewStopsAndSendsRevoked() = runTest {
        val t = pairPc("pc1")
        val c = Conn(); hello(c, "pc1", t); c.msg(startRaw)
        pairing.revoke("pc1")
        assertEquals(Denied(DeniedReason.revoked), c.sent.last())
        assertEquals(1, media.stops)
        assertTrue(c.closed)
        assertEquals(null, active.get())
    }

    @Test
    fun revokeTellsThePcBeforeStoppingTheStream() = runTest {
        // Sinon le PC voit la vidéo couper avant le refus et affiche « Connexion perdue ».
        val t = pairPc("pc1")
        val c = Conn(); hello(c, "pc1", t); c.msg(startRaw)
        pairing.revoke("pc1")
        assertEquals(0, c.stopsWhenDenied)
        assertEquals(1, media.stops)
    }

    @Test
    fun revokingAnotherPcDoesNothing() = runTest {
        val t = pairPc("pc1")
        pairPc("pc2")
        val c = Conn(); hello(c, "pc1", t); c.msg(startRaw)
        pairing.revoke("pc2")
        assertFalse(c.closed)
        assertEquals(0, media.stops)
    }

    @Test
    fun cameraBusyIsReported() = runTest {
        val t = pairPc("pc1")
        media.failWith = CameraBusyException()
        val c = Conn(); hello(c, "pc1", t); c.msg(startRaw)
        assertEquals(ErrorMsg(ErrorCode.camera_busy), c.sent.last())
        assertEquals(null, active.get())
    }

    @Test
    fun permissionMissingIsReported() = runTest {
        val t = pairPc("pc1")
        media.failWith = PermissionMissingException()
        val c = Conn(); hello(c, "pc1", t); c.msg(startRaw)
        assertEquals(ErrorMsg(ErrorCode.permission_missing), c.sent.last())
    }

    @Test
    fun versionMismatchDenied() = runTest {
        val t = pairPc("pc1")
        val c = Conn()
        hello(c, "pc1", t, v = 2)
        assertEquals(Denied(DeniedReason.version), c.sent.single())
        assertTrue(c.closed)
    }

    @Test
    fun badTokenDenied() = runTest {
        pairPc("pc1")
        val c = Conn()
        hello(c, "pc1", "faux")
        assertEquals(Denied(DeniedReason.unknown), c.sent.single())
        assertTrue(c.closed)
    }

    @Test
    fun wireFormatOfOfferMatchesPc() = runTest {
        val t = pairPc("pc1")
        val c = Conn(); hello(c, "pc1", t); c.msg(startRaw)
        assertEquals("""{"type":"offer","sdp":"offre-sdp"}""", Codec.encode(c.sent.last()))
    }

    // --- relecture finale

    @Test
    fun reconnectFromSamePcTakesOverAndOldCloseDoesNotStopNewStream() = runTest {
        val t = pairPc("pc1")
        val old = Conn(); hello(old, "pc1", t); old.msg(startRaw)
        val fresh = Conn(); hello(fresh, "pc1", t); fresh.msg(startRaw)
        assertEquals(Offer("offre-sdp"), fresh.sent.last())
        assertTrue("l'ancienne connexion est fermée", old.closed)
        old.handler.onClosed() // la vieille connexion finit par se fermer
        assertEquals("le nouveau flux n'est pas coupé", 0, media.stops)
        assertTrue(active.get() === fresh.handler)
        assertEquals(listOf<String?>("PC pc1"), old.sharing)
        // un autre PC reste refusé
        val t2 = pairPc("pc2")
        val other = Conn(); hello(other, "pc2", t2); other.msg(startRaw)
        assertEquals(ErrorMsg(ErrorCode.busy), other.sent.last())
    }

    @Test
    fun unexpectedMediaErrorIsReportedNotThrown() = runTest {
        val t = pairPc("pc1")
        media.failWith = IllegalStateException("PeerConnection impossible")
        val c = Conn(); hello(c, "pc1", t); c.msg(startRaw)
        assertEquals(ErrorMsg(ErrorCode.media_error), c.sent.last())
        assertEquals(null, active.get())
    }

    @Test
    fun badAnswerIsReportedAndReleases() = runTest {
        val t = pairPc("pc1")
        media.answerFails = true
        val c = Conn(); hello(c, "pc1", t); c.msg(startRaw)
        c.msg("""{"type":"answer","sdp":"n'importe quoi"}""")
        assertEquals(ErrorMsg(ErrorCode.media_error), c.sent.last())
        assertEquals(1, media.stops)
        assertEquals(null, active.get())
    }

    @Test
    fun failingSetDoesNotThrow() = runTest {
        val t = pairPc("pc1")
        media.setFails = true
        val c = Conn(); hello(c, "pc1", t); c.msg(startRaw)
        c.msg("""{"type":"set","video":false}""")
        assertFalse(c.closed)
    }

    @Test
    fun failingSendDoesNotThrow() = runTest {
        val t = pairPc("pc1")
        val h = SessionHandler(pairing, media, active, "ph1", "Tel",
            send = { throw IllegalStateException("socket fermée") }, close = {})
        h.onMessage("""{"type":"hello","pcId":"pc1","token":"$t","v":1}""")
        h.onMessage(startRaw)
    }
}
