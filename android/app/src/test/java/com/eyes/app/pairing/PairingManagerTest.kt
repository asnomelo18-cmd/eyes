package com.eyes.app.pairing

import com.eyes.app.protocol.DeniedReason
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom
import java.util.Base64

class PairingManagerTest {
    private var now = 1_000_000L
    private val store = InMemoryPairedPcStore()
    private val pm = PairingManager(store, clock = { now }, random = SecureRandom())

    private fun token(r: PairResult) = (r as PairResult.Success).token

    @Test
    fun codeIsSixDigits() {
        repeat(50) { assertTrue(Regex("[0-9]{6}").matches(pm.openPairing())) }
    }

    @Test
    fun correctCodeReturnsToken256Bits() = runTest {
        val code = pm.openPairing()
        val t = token(pm.tryPair(code, "pc1", "Bureau"))
        assertEquals(32, Base64.getUrlDecoder().decode(t).size)
        assertEquals(listOf("pc1"), store.all().map { it.pcId })
        assertTrue("le jeton n'est pas stocké en clair", store.all().none { it.tokenHash == t })
    }

    @Test
    fun codeIsSingleUse() = runTest {
        val code = pm.openPairing()
        pm.tryPair(code, "pc1", "Bureau")
        assertEquals(PairResult.Denied(DeniedReason.not_pairing), pm.tryPair(code, "pc2", "Autre"))
    }

    @Test
    fun codeExpiresAfter2Minutes() = runTest {
        val code = pm.openPairing()
        now += 119_000
        assertTrue(pm.tryPair(code, "pc1", "A") is PairResult.Success)
        val code2 = pm.openPairing()
        now += 121_000
        assertEquals(PairResult.Denied(DeniedReason.code_expired), pm.tryPair(code2, "pc2", "B"))
    }

    @Test
    fun fiveWrongAttemptsInvalidateCode() = runTest {
        val code = pm.openPairing()
        val wrong = if (code == "000000") "111111" else "000000"
        repeat(5) { assertEquals(PairResult.Denied(DeniedReason.bad_code), pm.tryPair(wrong, "pc1", "A")) }
        assertEquals(PairResult.Denied(DeniedReason.bad_code), pm.tryPair(code, "pc1", "A"))
    }

    @Test
    fun pairingRefusedWhenScreenClosed() = runTest {
        val code = pm.openPairing()
        pm.closePairing()
        assertEquals(PairResult.Denied(DeniedReason.not_pairing), pm.tryPair(code, "pc1", "A"))
    }

    @Test
    fun authenticateWithStoredToken() = runTest {
        val t = token(pm.tryPair(pm.openPairing(), "pc1", "Bureau"))
        val r = pm.authenticate("pc1", t)
        assertEquals("Bureau", (r as AuthResult.Ok).pc.pcName)
    }

    @Test
    fun wrongTokenIsUnknown() = runTest {
        token(pm.tryPair(pm.openPairing(), "pc1", "Bureau"))
        assertEquals(AuthResult.Denied(DeniedReason.unknown), pm.authenticate("pc1", "faux"))
        assertEquals(AuthResult.Denied(DeniedReason.unknown), pm.authenticate("inconnu", "faux"))
    }

    @Test
    fun revokedPcIsRevoked() = runTest {
        val t = token(pm.tryPair(pm.openPairing(), "pc1", "Bureau"))
        val heard = mutableListOf<String>()
        pm.addRevocationListener { heard += it }
        pm.revoke("pc1")
        assertEquals(AuthResult.Denied(DeniedReason.revoked), pm.authenticate("pc1", t))
        assertEquals(listOf("pc1"), heard)
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun repairingRevokedPcWorksAgain() = runTest {
        pm.tryPair(pm.openPairing(), "pc1", "Bureau")
        pm.revoke("pc1")
        val t = token(pm.tryPair(pm.openPairing(), "pc1", "Bureau"))
        assertTrue(pm.authenticate("pc1", t) is AuthResult.Ok)
    }

    @Test
    fun revocationListenersRunEvenIfCallerIsCancelled() = runTest {
        pm.tryPair(pm.openPairing(), "pc1", "Bureau")
        var done = false
        pm.addRevocationListener { kotlinx.coroutines.delay(100); done = true }
        val job = launch { pm.revoke("pc1") }
        testScheduler.advanceTimeBy(10)
        job.cancel()
        testScheduler.advanceUntilIdle()
        assertTrue("l'arrêt du partage a eu lieu malgré l'annulation", done)
    }
}
