package io.github.yuriyurin.fit3companion

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.yuriyurin.fit3companion.ble.BleSnapshot

@Composable
internal fun FirmwareScreen(snapshot: BleSnapshot, onChoose: () -> Unit, onFlash: (Boolean) -> Unit) {
    val state = snapshot.firmwareFlash
    val info = state.info
    var accepted by remember(info?.sha256) { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.flasher_warning), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onChoose, enabled = !state.busy && !state.checking) {
            Text(stringResource(R.string.flasher_choose))
        }
        if (state.checking) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (info != null) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(info.name, style = MaterialTheme.typography.titleMedium)
                    Text("${info.source} → ${info.target}")
                    Text(stringResource(R.string.flasher_size, info.size))
                    Text(stringResource(R.string.flasher_components, info.components))
                    Text("SHA-256: ${info.sha256}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = accepted, onCheckedChange = { accepted = it }, enabled = !state.busy)
                Text(stringResource(R.string.flasher_accept), style = MaterialTheme.typography.bodyMedium)
            }
            val ready = snapshot.sapReady && snapshot.softwareVersion == info.source &&
                (snapshot.batteryPercent ?: 0) >= 30 && !snapshot.faceInstallPending && !snapshot.faceChangePending
            if (!state.busy && !state.startSent) {
                if (!snapshot.sapReady) Text(stringResource(R.string.flasher_connect))
                else if (snapshot.softwareVersion != info.source) Text(stringResource(R.string.flasher_version_mismatch))
                else if ((snapshot.batteryPercent ?: 0) < 30) Text(stringResource(R.string.flasher_battery))
            }
            Button(onClick = { onFlash(accepted) }, enabled = accepted && ready && !state.busy && !state.startSent,
                modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.flasher_flash)) }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LinearProgressIndicator(progress = {
            if (state.total > 0) (state.sent.toFloat() / state.total).coerceIn(0f, 1f) else 0f
        }, modifier = Modifier.fillMaxWidth())
        Text(if (state.startSent) stringResource(R.string.flasher_wait)
            else stringResource(R.string.flasher_progress, if (state.total > 0) state.sent * 100 / state.total else 0),
            style = MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.flasher_log), style = MaterialTheme.typography.titleSmall)
        val scroll = rememberScrollState()
        LaunchedEffect(state.log.size, state.log.lastOrNull()) { scroll.animateScrollTo(scroll.maxValue) }
        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainer) {
            Column(Modifier.fillMaxWidth().height(150.dp).verticalScroll(scroll).padding(12.dp)) {
                Text(if (state.log.isEmpty()) stringResource(R.string.flasher_log_empty) else state.log.joinToString("\n"),
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
