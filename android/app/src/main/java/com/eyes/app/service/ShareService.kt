package com.eyes.app.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.eyes.app.ShareState
import com.eyes.app.eyes
import com.eyes.app.media.WebRtcMedia
import com.eyes.app.security.TlsIdentity
import com.eyes.app.server.SessionHandler
import com.eyes.app.server.SessionListener
import com.eyes.app.server.SignalingServer
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * « Prêt à partager » : service au premier plan (type caméra + micro), démarré seulement depuis l'interface.
 * Tient les verrous, le serveur wss et l'annonce mDNS ; le partage démarre à la demande d'un PC jumelé.
 */
class ShareService : Service() {
    companion object {
        private const val ACTION_STOP = "com.eyes.app.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, ShareService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ShareService::class.java).setAction(ACTION_STOP))
        }
    }

    private var server: SignalingServer? = null
    private var media: WebRtcMedia? = null
    private var keepAwake: KeepAwake? = null
    private var nsd: NsdAdvertiser? = null
    private val activeViewer = AtomicReference<SessionHandler?>(null)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        Notifications.ensureChannel(this)
        val notification = Notifications.build(this, null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(
                Notifications.ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(Notifications.ID, notification)
        }
        if (server == null) startSharing()
        // Pas de redémarrage automatique : Android interdit de rouvrir la caméra depuis l'arrière-plan.
        return START_NOT_STICKY
    }

    private fun startSharing() {
        val app = eyes
        val m = WebRtcMedia(this)
        media = m
        keepAwake = KeepAwake(this).also { it.acquire() }
        val listener = object : SessionListener {
            override fun onSharingStarted(pcName: String) {
                app.mutableState.value = ShareState.Sharing(pcName)
                Notifications.update(this@ShareService, pcName)
            }
            override fun onSharingStopped() {
                app.mutableState.value = ShareState.Waiting
                Notifications.update(this@ShareService, null)
            }
        }
        val s = SignalingServer(TlsIdentity(File(filesDir, "tls"))) { send, close ->
            SessionHandler(app.pairing, m, activeViewer, app.phoneId, app.phoneName, send, close, listener)
        }
        try {
            val prefs = getSharedPreferences("eyes", Context.MODE_PRIVATE)
            val port = s.start(preferredPort = prefs.getInt("lastPort", 0))
            prefs.edit().putInt("lastPort", port).apply()
            server = s
            nsd = NsdAdvertiser(this).also { it.register(app.phoneName, app.phoneId, port) }
            app.mutableState.value = ShareState.Waiting
        } catch (e: Exception) {
            Log.e("EyesService", "démarrage impossible", e)
            stopSelf()
        }
    }

    override fun onDestroy() {
        nsd?.unregister()
        server?.stop()
        media?.dispose()
        keepAwake?.release()
        server = null; media = null; nsd = null; keepAwake = null
        eyes.mutableState.value = ShareState.Off
        super.onDestroy()
    }
}
