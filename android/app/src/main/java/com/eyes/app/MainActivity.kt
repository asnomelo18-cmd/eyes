package com.eyes.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.eyes.app.service.ShareService
import com.eyes.app.ui.HomeScreen
import com.eyes.app.ui.KeepAliveScreen
import com.eyes.app.ui.PairScreen
import com.eyes.app.ui.oemIntents

enum class Screen { Home, Pair, KeepAlive }

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.Home)
    private var missingPermissions by mutableStateOf(false)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        missingPermissions = !hasPermissions()
    }

    private val required: Array<String>
        get() = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray()

    private fun hasPermissions() = required.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        missingPermissions = !hasPermissions()
        if (missingPermissions) permissionLauncher.launch(required)
        setContent {
            MaterialTheme {
                Surface {
                    when (screen) {
                        Screen.Home -> HomeScreen(
                            missingPermissions = missingPermissions,
                            onAskPermissions = { permissionLauncher.launch(required) },
                            onReadyChanged = ::setReady,
                            onPair = { screen = Screen.Pair },
                        )
                        Screen.Pair -> PairScreen(onDone = { screen = Screen.Home })
                        Screen.KeepAlive -> KeepAliveScreen(
                            intents = oemIntents(this, Build.MANUFACTURER),
                            onDone = { screen = Screen.Home },
                        )
                    }
                }
            }
        }
    }

    @Deprecated("Navigation simple")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (screen != Screen.Home) screen = Screen.Home else super.onBackPressed()
    }

    private fun setReady(ready: Boolean) {
        if (!ready) {
            ShareService.stop(this)
            return
        }
        if (!hasPermissions()) {
            permissionLauncher.launch(required)
            return
        }
        ShareService.start(this)
        val prefs = getSharedPreferences("eyes", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("keepAliveShown", false)) {
            prefs.edit().putBoolean("keepAliveShown", true).apply()
            requestBatteryExemption()
            if (oemIntents(this, Build.MANUFACTURER).isNotEmpty()) screen = Screen.KeepAlive
        }
    }

    @SuppressLint("BatteryLife") // demandé une fois : le mode surveillance doit tenir écran éteint
    private fun requestBatteryExemption() {
        val pm = getSystemService(PowerManager::class.java)
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            runCatching {
                startActivity(
                    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                )
            }
        }
    }
}
