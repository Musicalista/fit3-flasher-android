package io.github.yuriyurin.fit3companion.ble

import io.github.yuriyurin.fit3companion.R
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.security.MessageDigest
import java.io.InputStream
import java.io.OutputStream
import io.github.yuriyurin.fit3companion.protocol.Fit3UiCodec
import io.github.yuriyurin.fit3companion.Fit3UiLibrary

data class Fit3UiWatchFace(val key: String?, val title: String, val path: String, val size: Long,
    val error: String? = null)
data class Fit3UiWatchState(val loaded: Boolean = false, val available: Boolean? = null,
    val faces: List<Fit3UiWatchFace> = emptyList(), val activeKey: String? = null,
    val invalidActive: Boolean = false)

data class BandFileState(val folder: String = "/user", val entries: List<BandFileEntry> = emptyList(),
    val busy: Boolean = false, val done: Long = 0, val total: Long = 0, val status: String = "", val error: String? = null,
    val consoleAvailable: Boolean? = null, val fit3Ui: Fit3UiWatchState = Fit3UiWatchState())

@SuppressLint("MissingPermission")
internal class BandFileManager(private val context: Context, private val update: (BandFileState) -> Unit) {
    private data class Channel(val inputStream: InputStream, val outputStream: OutputStream)
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var state = BandFileState()
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var stopped = false
    @Volatile private var cancelled = false
    @Volatile private var pending: CompletableFuture<Unit>? = null
    private val wakeLock = context.getSystemService(PowerManager::class.java)
        .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fit3App:Files")
    val busy get() = state.busy
    private fun publish(value: BandFileState) { state = value; if (!stopped) update(value) }
    private fun trace(value: String) { if (io.github.yuriyurin.fit3companion.BuildConfig.DEBUG) android.util.Log.i("Fit3Files", value) }
    fun cancel() { cancelled = true; pending?.completeExceptionally(java.io.IOException("Cancelled")); runCatching { socket?.close() } }
    fun close() { stopped = true; cancel(); worker.shutdownNow() }
    fun run(folder: String, address: String?, prepare: () -> CompletableFuture<Unit>, finish: () -> Unit,
            operation: String = "list", path: String? = null, uri: Uri? = null, entry: BandFileEntry? = null) {
        if (busy || stopped) return
        if (address == null) { trace("rejected: no connected address"); publish(state.copy(error = context.getString(R.string.files_connect))); return }
        val lease = runCatching { BandFilePaths.validate(folder); prepare() }.getOrElse {
            trace("rejected: ${it.message}"); publish(state.copy(error = it.message)); return
        }
        cancelled = false
        pending = lease
        trace("begin $operation folder=$folder")
        val operationStatus=when(operation) {
            "fit3ui-refresh" -> "Получаем циферблаты с часов…"
            "fit3ui-install" -> "Устанавливаем циферблат…"
            "fit3ui-select" -> "Выбираем циферблат…"
            "fit3ui-delete" -> "Удаляем циферблат…"
            "fit3ui-reset" -> "Возвращаем встроенный циферблат…"
            else -> context.getString(R.string.files_working)
        }
        publish(state.copy(busy = true, done = 0, total = 0, status = operationStatus, error = null))
        worker.execute {
            val local = File(context.cacheDir, "band-file-${UUID.randomUUID()}.tmp")
            val verify = File(context.cacheDir, "band-readback-${UUID.randomUUID()}.tmp")
            var consoleAvailable = state.consoleAvailable
            var uiState = state.fit3Ui
            val uiOperation = operation.startsWith("fit3ui-")
            var completeStatus = context.getString(R.string.files_done)
            fun checkActive() { check(!cancelled && !stopped) { "Отменено" } }
            fun progress(done: Long, total: Long) { main.post { if (!cancelled) publish(state.copy(done = done, total = total)) } }
            fun <T> channel(action: (Channel) -> T): T {
                checkActive()
                val adapter = context.getSystemService(BluetoothManager::class.java).adapter
                require(adapter != null && adapter.isEnabled) { "Bluetooth выключен" }
                val connection = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(
                    UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3"))
                socket = connection
                val timeout = Runnable { if (socket === connection) runCatching { connection.close() } }
                main.postDelayed(timeout, 20_000)
                try {
                    try { connection.connect() } finally { main.removeCallbacks(timeout) }
                    checkActive(); trace("RFCOMM connected")
                    val source = connection.inputStream.buffered(960)
                    val input = object : InputStream(), TimedBandInput {
                        override fun read(): Int = source.read()
                        override fun readWithin(data: ByteArray, offset: Int, length: Int, timeoutMillis: Long): Int {
                            val expired = java.util.concurrent.atomic.AtomicBoolean(false)
                            val deadline = Runnable { expired.set(true); runCatching { connection.close() } }
                            main.postDelayed(deadline, timeoutMillis)
                            try { return source.read(data, offset, length) }
                            catch (error: java.io.IOException) { if (expired.get()) throw java.io.IOException("Watch response timeout", error); throw error }
                            finally { main.removeCallbacks(deadline) }
                        }
                    }
                    return action(Channel(input, connection.outputStream))
                } finally { runCatching { connection.close() }; socket = null }
            }
            val result = runCatching {
                wakeLock.acquire(20 * 60_000L)
                lease.get(30, TimeUnit.SECONDS); trace("BT_OPEN accepted"); checkActive(); Thread.sleep(750)
                if (uiOperation) {
                    channel { BandFileTransport.requireFit3Ui(it.inputStream, it.outputStream) }
                    uiState = uiState.copy(available = true)
                    val library = Fit3UiLibrary(context)
                    fun list() = channel { BandFileTransport.list(it.inputStream, it.outputStream, "/user") }
                    fun download(path: String, output: File) {
                        channel { c -> output.outputStream().use { BandFileTransport.download(c.inputStream, c.outputStream, path, it, ::progress) } }
                        require(output.length() <= Fit3UiCodec.MAX_BYTES) { "Слишком большой файл Fit3UI на часах" }
                    }
                    fun upload(path: String, input: File) {
                        val expected = library.read(input)
                        channel { BandFileTransport.upload(it.inputStream, it.outputStream, path, input, ::progress) }
                        Thread.sleep(500);checkActive()
                        main.post { if(!cancelled)publish(state.copy(status="Проверяем запись на часах…",done=0,total=0)) }
                        download(path, verify)
                        require(library.read(verify).bytes.contentEquals(expected.bytes)) { "Проверка циферблата после передачи не прошла" }
                    }
                    when(operation) {
                        "fit3ui-refresh" -> Unit
                        "fit3ui-install", "fit3ui-select" -> {
                            val face = library.read(library.file(requireNotNull(path)))
                            if (face.formatVersion > 1) channel { BandFileTransport.requireFit3Ui(it.inputStream, it.outputStream, face.formatVersion) }
                            if (operation == "fit3ui-install") {
                                val current=list();require(current.any { it.path==face.watchPath } || current.count { Fit3UiCodec.ownedPath(it.path) } < 24) { "На часах уже 24 циферблата. Удалите один из них" }
                                upload(face.watchPath, library.file(face.key))
                                completeStatus = "Циферблат установлен на часы"
                            } else {
                                require(list().any { it.path==face.watchPath }) { "Сначала установите циферблат на часы" }
                                upload(Fit3UiCodec.ACTIVE_PATH, library.file(face.key))
                                completeStatus = "Циферблат выбран. Откройте Fit3UI на часах заново"
                            }
                        }
                        "fit3ui-delete" -> {
                            val target=requireNotNull(path);require(Fit3UiCodec.ownedPath(target))
                            // Do not remove the active copy; deletion is allowed only for inactive faces.
                            val current=list()
                            if(current.any { it.path==Fit3UiCodec.ACTIVE_PATH }) {
                                download(Fit3UiCodec.ACTIVE_PATH, verify)
                                val active=runCatching { library.read(verify) }.getOrNull()
                                require(active?.watchPath!=target) { "Сначала выберите другой циферблат или встроенный" }
                            }
                            channel { BandFileTransport.delete(it.inputStream,it.outputStream,BandFileEntry(target,false)) }
                            completeStatus="Циферблат удалён с часов"
                        }
                        "fit3ui-reset" -> {
                            if(list().any { it.path==Fit3UiCodec.ACTIVE_PATH }) channel { BandFileTransport.delete(it.inputStream,it.outputStream,BandFileEntry(Fit3UiCodec.ACTIVE_PATH,false)) }
                            completeStatus="Выбран встроенный циферблат. Откройте Fit3UI заново"
                        }
                        else -> error("Неизвестная операция Fit3UI")
                    }
                    checkActive()
                    val entries=list()
                    val faces=entries.filter { !it.directory&&Fit3UiCodec.ownedPath(it.path) }.take(24).map { entry ->
                        checkActive()
                        val key=entry.name.removePrefix("fit3ui_").removeSuffix(".f3ui")
                        val cached=library.file(key)
                        val validSize=(entry.size?:0) in Fit3UiCodec.HEADER.toLong()..Fit3UiCodec.MAX_BYTES.toLong()
                        val downloaded=if(validSize&&(!cached.exists()||cached.length()!=entry.size)) {
                            download(entry.path,local);checkActive();local.readBytes()
                        }else null
                        val parsed=runCatching {
                            require(validSize) { "Неверный размер файла" }
                            val face=if(downloaded!=null) library.add(downloaded) else library.read(cached)
                            require(face.watchPath==entry.path) { "Содержимое файла не соответствует его имени" };face
                        }
                        parsed.fold({ Fit3UiWatchFace(it.key,it.title,entry.path,entry.size?:0) },
                            { Fit3UiWatchFace(null,entry.name,entry.path,entry.size?:0,it.message?:"Повреждённый файл") })
                    }
                    val activeEntry=entries.firstOrNull { it.path==Fit3UiCodec.ACTIVE_PATH }
                    val validActiveSize=(activeEntry?.size?:0) in Fit3UiCodec.HEADER.toLong()..Fit3UiCodec.MAX_BYTES.toLong()
                    val active=if(activeEntry!=null&&validActiveSize) {
                        checkActive();download(Fit3UiCodec.ACTIVE_PATH,verify);checkActive()
                        runCatching { library.add(verify.readBytes()) }.getOrNull()
                    } else null
                    checkActive()
                    uiState=Fit3UiWatchState(true,true,faces,active?.key,activeEntry!=null&&active==null)
                    consoleAvailable=true
                    entries
                } else {
                when (operation) {
                    "probe-control" -> {
                        channel { BandFileTransport.checkLink(it.inputStream, it.outputStream) }
                        trace("SPP control acknowledged 340")
                    }
                    "probe-export" -> {
                        channel { c -> local.outputStream().use { out ->
                            BandFileTransport.download(c.inputStream, c.outputStream, "/user/fit3_test.bin", out, ::progress)
                        } }
                        trace("SPP export verified bytes=${local.length()}")
                    }
                    "upload" -> {
                        require(path != null && uri != null && BandFilePaths.writable(path))
                        context.contentResolver.openInputStream(uri)?.use { source -> local.outputStream().use { out ->
                            val buffer = ByteArray(65536); var count = 0L
                            while (true) { checkActive(); val n = source.read(buffer); if (n < 0) break
                                count += n; require(count <= BandFileTransport.MAX_BYTES) { "Максимум 16 МиБ" }; out.write(buffer, 0, n) }
                        } } ?: error("Не удалось открыть файл")
                        channel { BandFileTransport.upload(it.inputStream, it.outputStream, path, local, ::progress) }
                        Thread.sleep(500); checkActive()
                        main.post { publish(state.copy(status = context.getString(R.string.files_verifying))) }
                        channel { c -> verify.outputStream().use { BandFileTransport.download(c.inputStream, c.outputStream, path, it, ::progress) } }
                        fun hash(f: File): ByteArray { val digest = MessageDigest.getInstance("SHA-256")
                            f.inputStream().use { input -> val b = ByteArray(65536); while (true) { val n = input.read(b); if (n < 0) break; digest.update(b, 0, n) } }; return digest.digest() }
                        require(hash(local).contentEquals(hash(verify))) { "Записанный файл отличается от исходного" }
                    }
                    "download" -> {
                        require(path != null && uri != null)
                        channel { c -> local.outputStream().use { BandFileTransport.download(c.inputStream, c.outputStream, path, it, ::progress) } }
                        checkActive()
                        context.contentResolver.openOutputStream(uri, "wt")?.use { out -> local.inputStream().use { it.copyTo(out) } }
                            ?: error("Не удалось сохранить файл")
                    }
                    "mkdir" -> channel { BandFileTransport.mkdir(it.inputStream, it.outputStream, requireNotNull(path)) }
                    "delete" -> channel { BandFileTransport.delete(it.inputStream, it.outputStream, requireNotNull(entry)) }
                    "list" -> Unit
                    else -> error("Unknown operation")
                }
                checkActive(); Thread.sleep(300)
                if (operation in listOf("probe-control", "probe-export") || (consoleAvailable == false && operation in listOf("upload", "download"))) state.entries
                else try {
                    channel { BandFileTransport.list(it.inputStream, it.outputStream, folder) }.also { consoleAvailable = true }
                } catch (error: BandFileConsoleUnavailable) {
                    consoleAvailable = false
                    if (operation in listOf("upload", "download")) state.entries else throw error
                }
                }
            }
            trace(result.fold({ "completed entries=${it.size}" }, { "failed: ${it.cause?.message ?: it.message}" }))
            local.delete(); verify.delete()
            pending = null
            if (wakeLock.isHeld) wakeLock.release()
            main.post {
                finish()
                result.fold({ entries -> publish(BandFileState(folder, entries, status = completeStatus, consoleAvailable = consoleAvailable, fit3Ui = uiState)) }, {
                    publish(state.copy(busy = false, status = "", consoleAvailable = if (it is BandFileConsoleUnavailable) false else consoleAvailable, error = if (cancelled)
                        context.getString(R.string.files_cancelled) else if (it is BandFileConsoleUnavailable) context.getString(R.string.files_console_unavailable)
                        else it.cause?.message ?: it.message ?: "Ошибка"))
                })
            }
        }
    }
}
