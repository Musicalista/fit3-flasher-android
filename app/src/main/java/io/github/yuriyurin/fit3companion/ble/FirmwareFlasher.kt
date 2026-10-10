package io.github.yuriyurin.fit3companion.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import io.github.yuriyurin.fit3companion.R
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

data class FirmwareFlashState(val info: FirmwareInfo? = null, val checking: Boolean = false,
    val busy: Boolean = false, val sent: Long = 0, val total: Long = 0,
    val startSent: Boolean = false, val error: String? = null, val log: List<String> = emptyList())

/** Service-owned operation: survives screen changes, never resumes or starts OTA on process restart. */
@SuppressLint("MissingPermission")
internal class FirmwareFlasher(private val context: Context, private val onUpdate: (FirmwareFlashState) -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val stopped = AtomicBoolean(false)
    private val file = File(context.filesDir, "selected-firmware.fwd")
    private var state = FirmwareFlashState()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var commandAttempted = false
    @Volatile private var linkLost = false
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fit3App:FOTA")
    val busy: Boolean get() = state.busy || state.checking

    private fun update(value: FirmwareFlashState) {
        if (stopped.get()) return
        state = value; onUpdate(state)
    }
    private fun note(value: String) = update(state.copy(log = (state.log + value).takeLast(80)))

    /** Fork: asks Android for a classic (BR/EDR) bond with the band from inside the app, which also avoids the Samsung "open Galaxy Wearable" redirect of the system settings. */
    fun pairClassic(address: String?) {
        if (busy) return
        val target = address ?: run { note("Connect to the band first (home screen)."); return }
        worker.execute {
            runCatching {
                val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                val device = adapter.getRemoteDevice(target)
                main.post { note("Classic Bluetooth: searching for the band (6 s)...") }
                runCatching { adapter.cancelDiscovery(); adapter.startDiscovery() }
                Thread.sleep(6000); runCatching { adapter.cancelDiscovery() }
                val ok = runCatching { device.javaClass.getMethod("createBond", Int::class.javaPrimitiveType).invoke(device, 1) as Boolean }
                    .recoverCatching { device.createBond() }.getOrDefault(false)
                main.post { note(if (ok) "Pairing requested: accept it on the phone and on the band."
                    else "No new pairing started (already paired, or the band refused). Bond state: ${device.bondState}.") }
            }.onFailure { e -> main.post { note("Pairing failed: ${e.message}") } }
        }
    }

    fun choose(uri: Uri) {
        if (busy) return
        update(FirmwareFlashState(checking = true, log = listOf(context.getString(R.string.flasher_checking))))
        worker.execute {
            val temporary = File(context.filesDir, "selected-firmware.tmp")
            val result = runCatching {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null } ?: uri.lastPathSegment ?: "Fit3 firmware"
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temporary.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var size = 0L
                        while (true) {
                            val n = input.read(buffer); if (n < 0) break
                            size += n
                            require(size <= FirmwarePackage.MAX_BYTES) { "Firmware exceeds 32 MiB" }
                            output.write(buffer, 0, n)
                        }
                        output.fd.sync()
                    }
                } ?: error("Cannot open firmware")
                val info = FirmwarePackage.inspect(temporary, name.take(180))
                check(temporary.renameTo(file)) { "Cannot save firmware" }
                info
            }
            temporary.delete()
            main.post {
                result.fold({ info -> update(FirmwareFlashState(info = info, total = info.size,
                    log = listOf(context.getString(R.string.flasher_verified)))) }, { error ->
                    update(FirmwareFlashState(error = error.message ?: "Firmware check failed",
                        log = listOf(context.getString(R.string.flasher_rejected, error.message ?: "check failed"))))
                })
            }
        }
    }

    fun flash(snapshot: BleSnapshot, address: String?, accepted: Boolean,
              prepare: (String, Int) -> java.util.concurrent.CompletableFuture<Unit>,
              finish: () -> Unit, reconnect: (String) -> Unit) {
        val info = state.info ?: return
        if (busy || state.startSent) return
        val reason = when {
            !accepted -> "Accept responsibility before flashing."
            !snapshot.sapReady || address == null -> context.getString(R.string.flasher_connect)
            snapshot.faceInstallPending || snapshot.faceChangePending -> "Wait for the watchface transfer to finish."
            snapshot.softwareVersion != info.source -> context.getString(R.string.flasher_version_mismatch)
            snapshot.batteryPercent == null || snapshot.batteryPercent < 30 -> context.getString(R.string.flasher_battery)
            else -> null
        }
        if (reason != null) { update(state.copy(error = reason)); note(reason); return }
        val targetAddress = address ?: return
        update(state.copy(busy = true, error = null, sent = 0))
        note(context.getString(R.string.flasher_preparing))
        worker.execute {
            commandAttempted = false
            linkLost = false
            try {
                wakeLock.acquire(20 * 60_000L)
                val checked = FirmwarePackage.inspect(file, info.name)
                require(checked == info) { "Firmware changed after selection" }
                val permission = java.util.concurrent.CompletableFuture<Unit>()
                main.post {
                    runCatching { prepare("R390_Fit3App.bin", info.size.toInt()) }
                        .fold({ pending -> pending.whenComplete { _, error ->
                            if (error == null) permission.complete(Unit) else permission.completeExceptionally(error)
                        } }, { permission.completeExceptionally(it) })
                }
                permission.get(30, java.util.concurrent.TimeUnit.SECONDS)
                val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                require(adapter != null && adapter.isEnabled) { "Bluetooth is off" }
                val device = adapter.getRemoteDevice(targetAddress)
                // Fork: the band does not always accept the first way of opening the serial link (stale service record, secure/insecure pairing).
                // 0 = secure + service UUID (upstream), 1 = insecure + UUID, 2 = fixed RFCOMM channel 29, 3 = channel 29 insecure.
                var mode = 0
                val serviceUuid = UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3")
                fun rfcommChannel(insecure: Boolean): BluetoothSocket = device.javaClass
                    .getMethod(if (insecure) "createInsecureRfcommSocket" else "createRfcommSocket", Int::class.javaPrimitiveType)
                    .invoke(device, 29) as BluetoothSocket
                fun connect(): BluetoothSocket {
                    check(!stopped.get()) { "Flasher stopped" }
                    check(!linkLost) { "Band disconnected before OTA start" }
                    val channel = when (mode) {
                        0 -> device.createRfcommSocketToServiceRecord(serviceUuid)
                        1 -> device.createInsecureRfcommSocketToServiceRecord(serviceUuid)
                        2 -> rfcommChannel(false)
                        else -> rfcommChannel(true)
                    }
                    socket = channel
                    check(!linkLost) { "Band disconnected before OTA start" }
                    val timeout = Runnable { if (socket === channel) runCatching { channel.close() } }
                    main.postDelayed(timeout, 20_000)
                    try { channel.connect() } finally { main.removeCallbacks(timeout) }
                    return channel
                }
                var sentAny = false
                var attempt = 0
                while (true) {
                    mode = attempt % 4
                    try {
                        Thread.sleep(750)
                        connect().use { channel ->
                            main.post { note(context.getString(R.string.flasher_transferring)) }
                            FirmwareTransport.stage(channel.inputStream, channel.outputStream, file) { sent, total ->
                                if (sent > 0) sentAny = true
                                main.post { update(state.copy(sent = sent, total = total)) }
                            }
                        }
                        break
                    } catch (e: Exception) {
                        runCatching { socket?.close() }; socket = null
                        // Nothing was sent yet, so trying another way to open the link is safe. Once data went out, never retry automatically.
                        if (sentAny || attempt >= 3 || stopped.get() || linkLost) throw e
                        val next = (attempt + 1) % 4
                        main.post { note("Serial link attempt ${attempt + 1}/4 failed (${e.message}). Trying again: " +
                            listOf("secure + service", "insecure + service", "channel 29", "channel 29 insecure")[next]) }
                        attempt++
                        Thread.sleep(2000)
                    }
                }
                socket = null
                main.post { note(context.getString(R.string.flasher_size_check)) }
                Thread.sleep(750)
                connect().use { channel -> FirmwareTransport.verifySize(channel.inputStream, channel.outputStream, info.size) }
                socket = null
                Thread.sleep(750)
                connect().use { channel ->
                    // A write failure here is ambiguous. Never automatically retry the start command.
                    check(!linkLost) { "Band disconnected before OTA start" }
                    commandAttempted = true
                    FirmwareTransport.start(channel.outputStream)
                    Thread.sleep(250)
                }
                socket = null
                main.post {
                    update(state.copy(startSent = true))
                    note(context.getString(R.string.flasher_command_sent))
                }
                Thread.sleep(60_000)
                main.post {
                    update(state.copy(busy = false))
                    note(context.getString(R.string.flasher_reconnect))
                    finish()
                    reconnect(targetAddress)
                }
            } catch (error: Exception) {
                runCatching { socket?.close() }; socket = null
                main.post {
                    update(state.copy(busy = false, startSent = commandAttempted,
                        error = if (commandAttempted) context.getString(R.string.flasher_uncertain)
                        else error.message ?: "Firmware transfer failed"))
                    note(state.error ?: "Firmware transfer failed")
                    finish()
                }
            } finally {
                runCatching { socket?.close() }; socket = null
                main.post { finish() }
                if (wakeLock.isHeld) runCatching { wakeLock.release() }
            }
        }
    }

    fun close() {
        stopped.set(true)
        runCatching { socket?.close() }
        worker.shutdownNow()
        if (wakeLock.isHeld) runCatching { wakeLock.release() }
    }

    fun onBandDisconnected() {
        if (!busy || commandAttempted) return // Reboot after the start command is expected.
        linkLost = true
        runCatching { socket?.close() }
    }
}
