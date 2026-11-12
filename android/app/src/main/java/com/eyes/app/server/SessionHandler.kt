package com.eyes.app.server

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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicReference

class CameraBusyException : Exception("caméra occupée")
class PermissionMissingException : Exception("permission caméra ou micro manquante")

/** Caméra + micro + WebRTC côté téléphone (implémenté par WebRtcMedia). */
interface MediaController {
    /** Ouvre caméra/micro et renvoie la SDP d'offre complète. */
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
 * [activeViewer] est partagé entre toutes les connexions : un seul PC regarde à la fois.
 */
class SessionHandler(
    private val pairing: PairingManager,
    private val media: MediaController,
    private val activeViewer: AtomicReference<String?>,
    private val phoneId: String,
    private val phoneName: String,
    private val send: suspend (Msg) -> Unit,
    private val close: suspend () -> Unit,
    private val listener: SessionListener = object : SessionListener {},
) {
    private val mutex = Mutex()
    private var pc: PairedPc? = null
    private var sharing = false
    private var closed = false

    private val onRevoked: suspend (String) -> Unit = { revokedId ->
        mutex.withLock {
            if (revokedId == pc?.pcId && !closed) {
                releaseLocked()
                send(Denied(DeniedReason.revoked))
                closeLocked()
            }
        }
    }

    suspend fun onMessage(raw: String) = mutex.withLock {
        if (closed) return@withLock
        val msg = try {
            Codec.decode(raw)
        } catch (e: ProtocolException) {
            send(ErrorMsg(ErrorCode.bad_message))
            return@withLock
        }
        handle(msg)
    }

    suspend fun onClosed() = mutex.withLock {
        releaseLocked()
        closed = true
        pairing.removeRevocationListener(onRevoked)
    }

    private suspend fun handle(msg: Msg) {
        when (msg) {
            is PairMsg -> {
                if (msg.v != PROTOCOL_VERSION) return deny(DeniedReason.version)
                when (val r = pairing.tryPair(msg.code, msg.pcId, msg.pcName)) {
                    is PairResult.Success -> send(Paired(r.token, phoneId, phoneName))
                    is PairResult.Denied -> send(Denied(r.reason))
                }
                closeLocked()
            }
            is Hello -> {
                if (msg.v != PROTOCOL_VERSION) return deny(DeniedReason.version)
                when (val r = pairing.authenticate(msg.pcId, msg.token)) {
                    is AuthResult.Ok -> {
                        pc = r.pc
                        pairing.addRevocationListener(onRevoked)
                        send(Welcome(phoneId, phoneName))
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
            is Start -> {
                val owner = activeViewer.get()
                if (owner != current.pcId && !activeViewer.compareAndSet(null, current.pcId)) {
                    send(ErrorMsg(ErrorCode.busy))
                    return
                }
                if (sharing) media.stop()
                val sdp = try {
                    media.start(msg)
                } catch (e: CameraBusyException) {
                    releaseLocked(stopMedia = false)
                    send(ErrorMsg(ErrorCode.camera_busy))
                    return
                } catch (e: PermissionMissingException) {
                    releaseLocked(stopMedia = false)
                    send(ErrorMsg(ErrorCode.permission_missing))
                    return
                }
                if (!sharing) {
                    sharing = true
                    listener.onSharingStarted(current.pcName)
                }
                send(Offer(sdp))
            }
            is Answer -> if (sharing) media.applyAnswer(msg.sdp)
            is SetMsg -> if (sharing) media.set(msg)
            is Stop -> releaseLocked()
            else -> send(ErrorMsg(ErrorCode.bad_message))
        }
    }

    private suspend fun deny(reason: DeniedReason) {
        send(Denied(reason))
        closeLocked()
    }

    private fun releaseLocked(stopMedia: Boolean = true) {
        val id = pc?.pcId
        if (sharing) {
            if (stopMedia) media.stop()
            sharing = false
            listener.onSharingStopped()
        } else if (!stopMedia) {
            // échec de démarrage : rien à arrêter
        }
        if (id != null) activeViewer.compareAndSet(id, null)
    }

    private suspend fun closeLocked() {
        if (closed) return
        closed = true
        pairing.removeRevocationListener(onRevoked)
        close()
    }
}
