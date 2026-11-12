package com.eyes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.eyes.app.ShareState
import com.eyes.app.eyes
import com.eyes.app.pairing.PairedPc
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    missingPermissions: Boolean,
    onAskPermissions: () -> Unit,
    onReadyChanged: (Boolean) -> Unit,
    onPair: () -> Unit,
) {
    val app = LocalContext.current.eyes
    val state by app.state.collectAsState()
    val pcs by app.store.changes.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    var toRemove by remember { mutableStateOf<PairedPc?>(null) }

    Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("Eyes", style = MaterialTheme.typography.headlineMedium)
        if (missingPermissions) {
            Text("Eyes a besoin de la caméra, du micro et des notifications.", color = MaterialTheme.colorScheme.error)
            Button(onClick = onAskPermissions) { Text("Autoriser") }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Prêt à partager", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Switch(checked = state != ShareState.Off, onCheckedChange = onReadyChanged)
        }
        Text(
            when (val s = state) {
                ShareState.Off -> "Arrêté"
                ShareState.Waiting -> "En attente"
                is ShareState.Sharing -> "Partage avec ${s.pcName}"
            },
            style = MaterialTheme.typography.bodyLarge,
        )
        Button(onClick = onPair, enabled = state != ShareState.Off) { Text("Jumeler un PC") }
        if (state == ShareState.Off) Text("Activez « Prêt à partager » pour jumeler un PC.")
        HorizontalDivider()
        Text("PC jumelés", style = MaterialTheme.typography.titleMedium)
        if (pcs.isEmpty()) Text("Aucun PC jumelé.")
        LazyColumn {
            items(pcs, key = { it.pcId }) { pc ->
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(pc.pcName, modifier = Modifier.weight(1f))
                    TextButton(onClick = { toRemove = pc }) { Text("Retirer") }
                }
            }
        }
        Spacer(Modifier.weight(1f))
    }

    toRemove?.let { pc ->
        AlertDialog(
            onDismissRequest = { toRemove = null },
            title = { Text("Retirer ${pc.pcName} ?") },
            text = { Text("Ce PC ne pourra plus voir la caméra ni entendre le micro.") },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch { app.pairing.revoke(pc.pcId) }
                    toRemove = null
                }) { Text("Retirer") }
            },
            dismissButton = { TextButton(onClick = { toRemove = null }) { Text("Annuler") } },
        )
    }
}
