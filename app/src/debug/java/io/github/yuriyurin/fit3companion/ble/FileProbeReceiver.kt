package io.github.yuriyurin.fit3companion.ble

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Debug APK only, protected by DUMP (adb shell); read-only fixed directory. */
class FileProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "io.github.yuriyurin.fit3companion.PROBE_FILES") return
        val service = Fit3ConnectionService.active
        android.util.Log.i("Fit3Files", "read-only probe requested; service=${service != null}")
        service?.fileOperation("/user", when {
            intent.getBooleanExtra("export", false) -> "probe-export"
            intent.getBooleanExtra("control", false) -> "probe-control"
            else -> "list"
        })
    }
}
