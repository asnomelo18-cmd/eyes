package com.eyes.app.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/** Écran de réglage d'un fabricant qui laisse une application tourner en arrière-plan. */
data class OemComponent(val pkg: String, val cls: String)

private val HUAWEI = listOf(
    OemComponent("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
    OemComponent("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    OemComponent("com.huawei.systemmanager", "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
)

private val BY_BRAND: Map<String, List<OemComponent>> = mapOf(
    "huawei" to HUAWEI,
    "honor" to HUAWEI,
    "xiaomi" to listOf(
        OemComponent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    ),
    "redmi" to listOf(
        OemComponent("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"),
    ),
    "samsung" to listOf(
        OemComponent("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
        OemComponent("com.samsung.android.sm", "com.samsung.android.sm.ui.battery.BatteryActivity"),
    ),
    "oppo" to listOf(
        OemComponent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        OemComponent("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    ),
    "realme" to listOf(
        OemComponent("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
    ),
    "vivo" to listOf(
        OemComponent("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        OemComponent("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
    ),
    "oneplus" to listOf(
        OemComponent("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
    ),
)

fun oemComponents(manufacturer: String): List<OemComponent> = BY_BRAND[manufacturer.trim().lowercase()].orEmpty()

/** Intents utilisables sur cet appareil (ceux dont l'écran existe vraiment). */
fun oemIntents(context: Context, manufacturer: String): List<Intent> =
    oemComponents(manufacturer)
        .map { Intent().setComponent(ComponentName(it.pkg, it.cls)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        .filter { context.packageManager.resolveActivity(it, PackageManager.MATCH_DEFAULT_ONLY) != null }
