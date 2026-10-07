package io.github.yuriyurin.fit3companion

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

@Composable
internal fun ErrorReportSettings(onSave: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ErrorReport.isEnabled(context)) }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Card {
            Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.error_report_recording), modifier = Modifier.weight(1f))
                Switch(checked = enabled, onCheckedChange = { ErrorReport.setEnabled(context, it); enabled = it })
            }
        }
        Text(stringResource(R.string.error_report_privacy), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(stringResource(R.string.error_report_limits), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = onSave, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.error_report_save))
        }
    }
}
