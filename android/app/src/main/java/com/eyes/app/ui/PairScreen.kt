package com.eyes.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eyes.app.eyes
import kotlinx.coroutines.delay

@Composable
fun PairScreen(onDone: () -> Unit) {
    val app = LocalContext.current.eyes
    val pcs by app.store.changes.collectAsState(initial = null)
    var code by remember { mutableStateOf(app.pairing.openPairing()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var knownIds by remember { mutableStateOf<Set<String>?>(null) }
    var pairedName by remember { mutableStateOf<String?>(null) }

    DisposableEffect(Unit) { onDispose { app.pairing.closePairing() } }
    LaunchedEffect(code) {
        while (true) { now = System.currentTimeMillis(); delay(500) }
    }
    LaunchedEffect(pcs) {
        val list = pcs ?: return@LaunchedEffect
        val ids = list.associateBy { it.pcId }
        val before = knownIds
        if (before == null) knownIds = ids.keys
        else (ids.keys - before).firstOrNull()?.let { pairedName = ids.getValue(it).pcName }
    }

    val remaining = ((app.pairing.codeExpiresAt() - now) / 1000).coerceAtLeast(0)
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
    ) {
        val name = pairedName
        if (name != null) {
            Text("$name est jumelé.", style = MaterialTheme.typography.headlineSmall)
            Button(onClick = onDone) { Text("Terminé") }
        } else if (remaining > 0 && app.pairing.isPairingOpen) {
            Text("Sur le PC, cliquez sur « Jumeler » et tapez ce code :", style = MaterialTheme.typography.titleMedium)
            Text(
                code.chunked(3).joinToString(" "),
                fontSize = 56.sp, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.displayLarge,
            )
            Text("Valable encore ${remaining / 60}:${"%02d".format(remaining % 60)}")
            TextButton(onClick = onDone) { Text("Annuler") }
        } else {
            Text("Le code n'est plus valable.", style = MaterialTheme.typography.titleMedium)
            Button(onClick = { code = app.pairing.openPairing() }) { Text("Nouveau code") }
            TextButton(onClick = onDone) { Text("Retour") }
        }
    }
}
