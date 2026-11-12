package com.eyes.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.provider.Settings
import com.eyes.app.pairing.DataStorePairedPcStore
import com.eyes.app.pairing.PairingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

sealed interface ShareState {
    data object Off : ShareState
    data object Waiting : ShareState
    data class Sharing(val pcName: String) : ShareState
}

/** Objets partagés entre l'interface et le service. */
class EyesApp : Application() {
    val store by lazy { DataStorePairedPcStore(this) }
    val pairing by lazy { PairingManager(store) }
    internal val mutableState = MutableStateFlow<ShareState>(ShareState.Off)
    val state: StateFlow<ShareState> get() = mutableState
    /** Pour les actions qui doivent aller au bout même si l'écran change (ex. retirer un PC). */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val phoneId: String by lazy {
        val prefs = getSharedPreferences("eyes", Context.MODE_PRIVATE)
        prefs.getString("phoneId", null) ?: UUID.randomUUID().toString().replace("-", "").also {
            prefs.edit().putString("phoneId", it).apply()
        }
    }

    val phoneName: String
        get() = Settings.Global.getString(contentResolver, "device_name")?.takeIf { it.isNotBlank() }
            ?: "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
}

val Context.eyes: EyesApp get() = applicationContext as EyesApp
