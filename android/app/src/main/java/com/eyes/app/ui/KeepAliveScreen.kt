package com.eyes.app.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

@Composable
fun KeepAliveScreen(intents: List<Intent>, onDone: () -> Unit) {
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Pour que Eyes reste actif", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Ce téléphone peut fermer les applications en arrière-plan. Ouvrez le réglage ci-dessous et " +
                "autorisez Eyes à rester actif (démarrage automatique / exécution en arrière-plan).",
        )
        intents.forEachIndexed { i, intent ->
            Button(onClick = { runCatching { context.startActivity(intent) } }) {
                Text(if (intents.size == 1) "Ouvrir le réglage" else "Ouvrir le réglage ${i + 1}")
            }
        }
        TextButton(onClick = onDone) { Text("C'est fait") }
    }
}
