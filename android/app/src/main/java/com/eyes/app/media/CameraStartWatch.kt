package com.eyes.app.media

import com.eyes.app.server.CameraBusyException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Attend que la caméra produise sa première image. Une erreur ou une déconnexion avant,
 * ou aucune image dans le délai, veut dire que la caméra est occupée (autre application).
 */
class CameraStartWatch {
    private val result = CompletableDeferred<String?>()

    fun onFirstFrame() { result.complete(null) }
    fun onError(description: String) { result.complete(description) }
    fun onDisconnected() { result.complete("caméra déconnectée") }

    suspend fun await(timeoutMs: Long) {
        val error = try {
            withTimeout(timeoutMs) { result.await() }
        } catch (e: TimeoutCancellationException) {
            "aucune image"
        }
        if (error != null) throw CameraBusyException()
    }
}
