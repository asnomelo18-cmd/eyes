package com.eyes.app.pairing

import com.eyes.app.protocol.DeniedReason
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList

sealed interface PairResult {
    data class Success(val token: String) : PairResult
    data class Denied(val reason: DeniedReason) : PairResult
}

sealed interface AuthResult {
    data class Ok(val pc: PairedPc) : AuthResult
    data class Denied(val reason: DeniedReason) : AuthResult
}

/**
 * Code de jumelage (6 chiffres, 2 minutes, 5 essais faux max, seulement écran ouvert),
 * jetons de 256 bits stockés hachés, révocation.
 */
class PairingManager(
    private val store: PairedPcStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val random: SecureRandom = SecureRandom(),
) {
    companion object {
        const val CODE_VALIDITY_MS = 120_000L
        const val MAX_ATTEMPTS = 5
    }

    private val mutex = Mutex()
    private val listeners = CopyOnWriteArrayList<suspend (String) -> Unit>()
    private val lock = Any()

    private var code: String? = null
    private var expiresAt = 0L
    private var wrongAttempts = 0

    val isPairingOpen: Boolean get() = synchronized(lock) { code != null }

    /** Ouvre l'écran « Jumeler un PC » : nouveau code. */
    fun openPairing(): String = synchronized(lock) {
        val c = "%06d".format(random.nextInt(1_000_000))
        code = c
        expiresAt = clock() + CODE_VALIDITY_MS
        wrongAttempts = 0
        c
    }

    fun codeExpiresAt(): Long = synchronized(lock) { expiresAt }

    fun closePairing() = synchronized(lock) { code = null }

    /** Vérifie le code (sous verrou) ; null si bon, sinon la raison du refus. */
    private fun checkCode(candidate: String): DeniedReason? = synchronized(lock) {
        val expected = code ?: return DeniedReason.not_pairing
        if (clock() > expiresAt) return DeniedReason.code_expired
        if (wrongAttempts >= MAX_ATTEMPTS) return DeniedReason.bad_code
        if (!MessageDigest.isEqual(candidate.toByteArray(), expected.toByteArray())) {
            wrongAttempts++
            return DeniedReason.bad_code
        }
        code = null // usage unique
        null
    }

    suspend fun tryPair(candidate: String, pcId: String, pcName: String): PairResult = mutex.withLock {
        checkCode(candidate)?.let { return PairResult.Denied(it) }
        val bytes = ByteArray(32).also(random::nextBytes)
        val token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        store.put(PairedPc(pcId, pcName, hash(token), clock()))
        store.setRevoked(pcId, false)
        PairResult.Success(token)
    }

    suspend fun authenticate(pcId: String, token: String): AuthResult {
        val pc = store.all().firstOrNull { it.pcId == pcId }
        if (pc == null) {
            val reason = if (pcId in store.revokedIds()) DeniedReason.revoked else DeniedReason.unknown
            return AuthResult.Denied(reason)
        }
        return if (MessageDigest.isEqual(hash(token).toByteArray(), pc.tokenHash.toByteArray())) {
            AuthResult.Ok(pc)
        } else {
            AuthResult.Denied(DeniedReason.unknown)
        }
    }

    suspend fun revoke(pcId: String) {
        mutex.withLock {
            store.remove(pcId)
            store.setRevoked(pcId, true)
        }
        // Le partage doit s'arrêter même si l'écran qui a demandé le retrait disparaît.
        withContext(NonCancellable) { for (l in listeners) l(pcId) }
    }

    fun addRevocationListener(l: suspend (String) -> Unit) { listeners += l }
    fun removeRevocationListener(l: suspend (String) -> Unit) { listeners -= l }

    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
