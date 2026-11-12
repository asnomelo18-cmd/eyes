package com.eyes.app.server

import android.util.Log
import com.eyes.app.pairing.AuthResult
import com.eyes.app.pairing.PairResult
import com.eyes.app.pairing.PairedPc
import com.eyes.app.pairing.PairingManager
import com.eyes.app.protocol.Answer
import com.eyes.app.protocol.Codec
import com.eyes.app.protocol.Denied
import com.eyes.app.protocol.DeniedReason
import com.eyes.app.protocol.ErrorCode
import com.eyes.app.protocol.ErrorMsg
import com.eyes.app.protocol.Hello
import com.eyes.app.protocol.Msg
import com.eyes.app.protocol.Offer
import com.eyes.app.protocol.PROTOCOL_VERSION
import com.eyes.app.protocol.PairMsg
import com.eyes.app.protocol.Paired
import com.eyes.app.protocol.ProtocolException
import com.eyes.app.protocol.SetMsg
import com.eyes.app.protocol.Start
import com.eyes.app.protocol.Stop
import com.eyes.app.protocol.Welcome
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

class CameraBusyException : Exception("caméra occupée")
class PermissionMissingException : Exception("permission caméra ou micro manquante")

/** Caméra + micro + WebRTC côté téléphone (implémenté par WebRtcMedia). */
interface MediaController {
    /** Ouvre caméra/micro (en remplaçant un éventuel flux précédent) et renvoie la SDP d'offre complète. */
    suspend fun start(s: Start): String
    suspend fun applyAnswer(sdp: String)
    fun set(m: SetMsg)
    fun stop()
}

interface SessionListener {
    fun onSharingStarted(pcName: String) {}
    fun onSharingStopped() {}
}

/**
 * Logique d'une connexion PC → téléphone (protocole v1). Sans état réseau : les tests l'appellent directement.
 * [activeViewer] est partagé entre toutes les connexions : la connexion qui possède la caméra.
 * Un seul PC regarde à la fois ; une nouvelle connexion du même PC (reconnexion) remplace l'ancienne.
 */
class SessionHandler(
    private val pairing: PairingManager,
    private val media: MediaController,
    private val activeViewer: AtomicReference<SessionHandler?>,
    private val phoneId: String,
    private val phoneName: String,
    private val send: suspend (Msg) -> Unit,
    private val close: suspend () -> Unit,
    private val listener: SessionListener = object : SessionListener {},
) {
    private val mutex = Mutex()
    @Volatile private var pc: PairedPc? = null
    private var closed = false

    private val owner: Boolean get() = activeViewer.get() === this

    private val onRevoked: suspend (String) -> Unit = { revokedId ->
        mutex.withLock {
            if (revokedId == pc?.pcId && !closed) {
                // Prévenir d'abord : sinon le PC voit la vidéo couper et croit à une coupure réseau.
                sendSafely(Denied(DeniedReason.revoked))
                releaseLocked()
                closeLocked()
            }
        }
    }

    suspend fun onMessage(raw: String) = mutex.withLock {
        if (closed) return@withLock
        val msg = try {
            Codec.decode(raw)
        } catch (e: ProtocolException) {
            sendSafely(ErrorMsg(ErrorCode.bad_message))
            return@withLock
        }
        try {
            handle(msg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("EyesSession", "erreur inattendue", e)
            releaseLocked()
            sendSafely(ErrorMsg(ErrorCode.media_error))
        }
    }

    suspend fun onClosed() = mutex.withLock {
        releaseLocked()
        closed = true
        pairing.removeRevocationListener(onRevoked)
    }

    /** Une connexion plus récente du même PC a pris la caméra : on ferme sans toucher au flux. */
    private suspend fun supersede() = mutex.withLock {
        if (closed) return@withLock
        closeLocked()
    }

    private suspend fun handle(msg: Msg) {
        when (msg) {
            is PairMsg -> {
                if (msg.v != PROTOCOL_VERSION) return deny(DeniedReason.version)
                when (val r = pairing.tryPair(msg.code, msg.pcId, msg.pcName)) {
                    is PairResult.Success -> sendSafely(Paired(r.token, phoneId, phoneName))
                    is PairResult.Denied -> sendSafely(Denied(r.reason))
                }
                closeLocked()
            }
            is Hello -> {
                if (msg.v != PROTOCOL_VERSION) return deny(DeniedReason.version)
                // Écoute installée AVANT de vérifier : un retrait pendant la vérification n'est pas manqué.
                pairing.addRevocationListener(onRevoked)
                when (val r = pairing.authenticate(msg.pcId, msg.token)) {
                    is AuthResult.Ok -> {
                        pc = r.pc
                        sendSafely(Welcome(phoneId, phoneName))
                    }
                    is AuthResult.Denied -> deny(r.reason)
                }
            }
            else -> {
                val current = pc ?: return deny(DeniedReason.unknown)
                handleAuthed(current, msg)
            }
        }
    }

    private suspend fun handleAuthed(current: PairedPc, msg: Msg) {
        when (msg) {
            is Start -> start(current, msg)
            is Answer -> if (owner) {
                try {
                    media.applyAnswer(msg.sdp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w("EyesSession", "réponse SDP refusée", e)
                    releaseLocked()
                    sendSafely(ErrorMsg(ErrorCode.media_error))
                }
            }
            is SetMsg -> if (owner) runCatching { media.set(msg) }.onFailure { Log.w("EyesSession", "set", it) }
            is Stop -> releaseLocked()
            else -> sendSafely(ErrorMsg(ErrorCode.bad_message))
        }
    }

    private suspend fun start(current: PairedPc, msg: Start) {
        // Prendre la caméra : libre, déjà à nous, ou tenue par une ancienne connexion du même PC.
        while (true) {
            val holder = activeViewer.get()
            if (holder === this) break
            if (holder != null && holder.pc?.pcId != current.pcId) {
                sendSafely(ErrorMsg(ErrorCode.busy))
                return
            }
            if (activeViewer.compareAndSet(holder, this)) {
                holder?.supersede()
                break
            }
        }
        val sdp = try {
            media.start(msg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val code = when (e) {
                is CameraBusyException -> ErrorCode.camera_busy
                is PermissionMissingException -> ErrorCode.permission_missing
                else -> ErrorCode.media_error.also { Log.w("EyesSession", "démarrage média", e) }
            }
            if (activeViewer.compareAndSet(this, null)) runCatching { media.stop() }
            sendSafely(ErrorMsg(code))
            return
        }
        listener.onSharingStarted(current.pcName)
        sendSafely(Offer(sdp))
    }

    private suspend fun deny(reason: DeniedReason) {
        sendSafely(Denied(reason))
        closeLocked()
    }

    /** Arrête le flux seulement si cette connexion en est encore propriétaire. */
    private fun releaseLocked() {
        if (activeViewer.compareAndSet(this, null)) {
            runCatching { media.stop() }.onFailure { Log.w("EyesSession", "arrêt média", it) }
            listener.onSharingStopped()
        }
    }

    private suspend fun sendSafely(m: Msg) {
        try {
            send(m)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.i("EyesSession", "envoi impossible : ${e.message}")
        }
    }

    private suspend fun closeLocked() {
        if (closed) return
        closed = true
        pairing.removeRevocationListener(onRevoked)
        runCatching { close() }
    }
}
