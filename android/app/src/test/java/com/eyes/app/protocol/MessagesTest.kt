package com.eyes.app.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MessagesTest {

    @Test
    fun roundtripStart() {
        val m = Start(video = true, audio = false, camera = Camera.front)
        assertEquals(m, Codec.decode(Codec.encode(m)))
    }

    @Test
    fun decodesPcMessages() {
        assertEquals(PairMsg("123456", "pc1", "Bureau", 1), Codec.decode("""{"type":"pair","code":"123456","pcId":"pc1","pcName":"Bureau","v":1}"""))
        assertEquals(Hello("pc1", "tok", 1), Codec.decode("""{"type":"hello","pcId":"pc1","token":"tok","v":1}"""))
        assertEquals(Answer("s"), Codec.decode("""{"type":"answer","sdp":"s"}"""))
        assertEquals(Stop, Codec.decode("""{"type":"stop"}"""))
        assertEquals(SetMsg(camera = Camera.back), Codec.decode("""{"type":"set","camera":"back"}"""))
    }

    @Test
    fun encodeIsCompatibleWithPc() {
        assertEquals("""{"type":"offer","sdp":"x"}""", Codec.encode(Offer("x")))
        assertEquals("""{"type":"denied","reason":"revoked"}""", Codec.encode(Denied(DeniedReason.revoked)))
        assertEquals("""{"type":"error","code":"camera_busy"}""", Codec.encode(ErrorMsg(ErrorCode.camera_busy)))
        assertEquals("""{"type":"set","video":false}""", Codec.encode(SetMsg(video = false)))
        assertEquals(
            """{"type":"paired","token":"t","phoneId":"p","phoneName":"Tel"}""",
            Codec.encode(Paired("t", "p", "Tel")),
        )
        assertEquals("""{"type":"welcome","phoneId":"p","phoneName":"Tel"}""", Codec.encode(Welcome("p", "Tel")))
    }

    @Test
    fun invalidMessagesThrow() {
        val bad = listOf(
            "pas du json",
            "{}",
            "[]",
            """{"type":"inconnu"}""",
            """{"type":"offer"}""",
            """{"type":"start","video":"oui","audio":true,"camera":"back"}""",
            """{"type":"start","video":true,"audio":true,"camera":"cote"}""",
            """{"type":"set"}""",
            """{"type":"hello","pcId":"p","token":"t","v":true}""",
        )
        for (raw in bad) {
            assertThrows(raw, ProtocolException::class.java) { Codec.decode(raw) }
        }
    }

    @Test
    fun protocolVersionIsOne() {
        assertEquals(1, PROTOCOL_VERSION)
    }
}
