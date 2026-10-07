package io.github.yuriyurin.fit3companion

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.AtomicFile
import java.io.File
import java.time.Instant

/** Separate opt-in report. It never reads logcat, packet logs, notifications or health storage. */
object ErrorReport {
    enum class Event { RECORDING_STARTED, APP_STARTED, SCREEN_OPENED, SCREEN_CLOSED,
        SERVICE_STARTED, SERVICE_STOPPED, LINK_CONNECTED, LINK_DISCONNECTED, CHANNEL_READY,
        CONNECTION_FAILED, EXPORT_FAILED, UNCAUGHT_EXCEPTION }
    private var context: Context? = null
    @Volatile private var enabled = false
    private var log = ""
    private const val LIMIT = 128 * 1024
    private fun prefs(context: Context) = context.getSharedPreferences("fit3_error_report", Context.MODE_PRIVATE)
    fun isEnabled(context: Context) = prefs(context).getBoolean("enabled", false)

    @Synchronized fun initialize(application: Application) {
        if (context != null) return
        context = application
        enabled = isEnabled(application)
        log = runCatching { AtomicFile(File(application.filesDir, "error-report.txt")).readFully()
            .toString(Charsets.UTF_8).takeLast(LIMIT) }.getOrDefault("")
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try { record(Event.UNCAUGHT_EXCEPTION, error) } catch (_: Throwable) { }
            finally {
                if (previous != null) previous.uncaughtException(thread, error)
                else { android.os.Process.killProcess(android.os.Process.myPid()); kotlin.system.exitProcess(10) }
            }
        }
        application.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) = record(Event.SCREEN_OPENED)
            override fun onActivityStopped(activity: Activity) = record(Event.SCREEN_CLOSED)
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
        record(Event.APP_STARTED)
    }

    @Synchronized fun setEnabled(context: Context, value: Boolean) {
        check(prefs(context).edit().putBoolean("enabled", value).commit())
        enabled = value
        if (value) record(Event.RECORDING_STARTED)
    }

    @Synchronized fun record(event: Event, error: Throwable? = null) {
        if (!enabled) return
        val app = context ?: return
        runCatching {
            val entry = "${Instant.now()} ${event.name}\n" + (error?.let(ReportPrivacy::error) ?: "")
            log = (log + entry).takeLast(LIMIT)
            val file = AtomicFile(File(app.filesDir, "error-report.txt"))
            val stream = file.startWrite()
            try { stream.write(log.toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
            catch (error: Throwable) { file.failWrite(stream); throw error }
        }
    }

    @Synchronized fun export(): String =
        "Fit3 App ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nAndroid API ${android.os.Build.VERSION.SDK_INT}\n" +
            "Private data and exception messages are excluded.\n\n" + log.ifEmpty { "No recorded events.\n" }
}
