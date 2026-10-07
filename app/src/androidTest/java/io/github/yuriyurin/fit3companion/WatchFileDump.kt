package io.github.yuriyurin.fit3companion

import android.app.Instrumentation
import android.app.Activity
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import io.github.yuriyurin.fit3companion.ble.Fit3ConnectionService
import io.github.yuriyurin.fit3companion.ble.Fit3BleClient
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.json.JSONArray
import org.json.JSONObject

/** Explicit adb-only read/export session. Does not flash or write watch files.
 * Runs in a separate test APK; the installed companion APK is unchanged. */
class WatchFileDump : Instrumentation() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var root: File
    private fun log(s: String) { File(root, "progress.log").appendText("${System.currentTimeMillis()} $s\n"); android.util.Log.i("Fit3Dump", s) }
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        root = File(targetContext.filesDir, "watch-file-dump-20261005").apply { mkdirs() }
        val result = Bundle()
        val lock = targetContext.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Fit3Dump:ReadOnly")
        var ble: Fit3BleClient? = null
        try {
            lock.acquire(4 * 60 * 60_000L)
            log("Starting file export; raw NAND/RAM/ROM excluded")
            main.post { targetContext.startForegroundService(Intent(targetContext, Fit3ConnectionService::class.java)) }
            val deadline = System.currentTimeMillis() + 90_000
            while (Fit3ConnectionService.active == null && System.currentTimeMillis() < deadline) Thread.sleep(200)
            val service = requireNotNull(Fit3ConnectionService.active) { "Companion service unavailable" }
            ble = Fit3ConnectionService::class.java.getDeclaredField("ble").apply { isAccessible = true }.get(service) as Fit3BleClient
            val client = ble
            var address: String? = null
            while (address == null && System.currentTimeMillis() < deadline) { address = client.connectedFirmwareAddress(); Thread.sleep(300) }
            requireNotNull(address) { "Watch not connected" }
            var granted = false
            while(!granted && System.currentTimeMillis() < deadline) {
                val lease = CompletableFuture<Unit>()
                main.post { try { client.prepareFileTransfer().whenComplete { _, e -> if(e == null) lease.complete(Unit) else lease.completeExceptionally(e) } } catch(e: Exception) { lease.completeExceptionally(e) } }
                try { lease.get(40, TimeUnit.SECONDS); granted = true }
                catch(e: java.util.concurrent.ExecutionException) { if(e.cause !is IllegalStateException) throw e; Thread.sleep(1000) }
            }
            check(granted) { "Watch did not become ready for file export" }; Thread.sleep(750)
            val adapter = targetContext.getSystemService(BluetoothManager::class.java).adapter
            fun <T> channel(action: (InputStream, OutputStream) -> T): T {
                val socket = adapter.getRemoteDevice(address).createRfcommSocketToServiceRecord(UUID.fromString("db764ac8-4b08-7f25-aafe-59d03c27bae3"))
                val expiry = Runnable { runCatching { socket.close() } }
                main.postDelayed(expiry, 30_000)
                try {
                    socket.connect(); main.removeCallbacks(expiry)
                    main.postDelayed(expiry, 10 * 60_000L)
                    return action(socket.inputStream.buffered(4096), socket.outputStream)
                } finally { main.removeCallbacks(expiry); runCatching { socket.close() } }
            }
            fun send(o: OutputStream, s: String) { o.write(s.toByteArray(Charsets.US_ASCII)); o.flush() }
            fun exact(i: InputStream, n: Int): ByteArray { val b = ByteArray(n); var p = 0; while(p < n) { val k = i.read(b, p, n-p); check(k > 0) { "Export closed at $p/$n" }; p += k }; return b }
            fun uint(i: InputStream): Long = exact(i,4).fold(0L) { a,b -> (a shl 8) or (b.toLong() and 255) }
            fun text(i: InputStream, end: String): String { val b = StringBuilder(); while(b.length < 2_000_000) { val c = i.read(); check(c >= 0); b.append(c.toChar()); if(c == 10) { val line = b.substring(b.lastIndexOf("\n", b.length-2)+1).trim(); check(line != "ERR") { "Listing rejected" }; if(line == end) return b.toString() } }; error("Listing too large") }
            val paths = linkedSetOf<String>()
            val crcFile = File(root,"crc_record.txt")
            crcFile.readLines().forEach { line -> val p = "/" + line.substringBefore(' '); if(p.startsWith("/nand/")) paths.add(p) }
            val listings = JSONArray(); val directories = java.util.ArrayDeque<String>(); directories.add("/nand"); directories.add("/user")
            val visited = hashSetOf<String>()
            while(directories.isNotEmpty()) {
                val dir = directories.removeFirst(); if(!visited.add(dir)) continue
                try {
                    log("Listing $dir")
                    val reply = channel { i,o -> send(o,"00AT^BLUETOOTH"); text(i,"OK"); send(o,"00FI^LS=$dir"); text(i,"^LS=OK") }
                    File(root,"listing-${visited.size}.txt").writeText(reply)
                    listings.put(JSONObject().put("path",dir).put("reply",reply))
                    val allDirs = mutableListOf<String>()
                    reply.lineSequence().forEach { raw -> val line=raw.trim(); if(line.startsWith("F ")) paths.add(line.substring(2).trimStart().substringBeforeLast('\t')); if(line.startsWith("D ")) allDirs.add(line.substring(2).trim().trimEnd('/')) }
                    // Native listing is recursive. Only expand directories at a reported depth boundary.
                    if(reply.contains("limited by depth")) allDirs.filter { d -> allDirs.none { it.startsWith("$d/") } }.forEach(directories::add)
                } catch(e: Exception) { log("Listing failed $dir: ${e.message}"); listings.put(JSONObject().put("path",dir).put("error",e.toString())) }
            }
            val files = JSONArray(); val saved = File(root,"files").apply { mkdirs() }
            for ((index,path) in paths.withIndex()) {
                val item=JSONObject().put("path",path); files.put(item)
                try {
                    require(path.length <= 99 && path.startsWith('/') && path.substring(1).split('/').all { it.isNotEmpty() && it != "." && it != ".." })
                    val output=File(saved,path.substring(1)); output.parentFile!!.mkdirs()
                    log("Export ${index+1}/${paths.size} $path")
                    channel { i,o ->
                        send(o,"061$path\u0000"); check(exact(i,1)[0] == 0x40.toByte()) { "File unavailable" }
                        val total=uint(i); require(total <= 128L*1024*1024) { "Implausible export size $total" }
                        var received=0L
                        output.outputStream().use { target -> while(received < total) {
                            send(o,"062"); check(exact(i,1)[0] == 0x40.toByte()); val n=uint(i); require(n in 1..1_048_576)
                            val block=exact(i,n.toInt()); val sum=uint(i); check(block.fold(0L) { a,b -> (a+(b.toInt() and 255)) and 0xffffffffL } == sum) { "Block checksum mismatch" }
                            require(n <= total-received) { "Block exceeds advertised size" }; target.write(block); received+=n
                        } }
                        send(o,"062"); item.put("size",total)
                    }
                    item.put("sha256",MessageDigest.getInstance("SHA-256").digest(output.readBytes()).joinToString("") { "%02x".format(it) })
                    item.put("crc32","%08x".format(CRC32().apply { output.inputStream().use { input -> val b=ByteArray(65536); while(true) { val n=input.read(b); if(n < 0) break; update(b,0,n) } } }.value))
                    item.put("status","verified-block-checksums")
                } catch(e: Exception) { File(saved,path.removePrefix("/")).delete(); item.put("status","failed").put("error",e.toString()); log("Failed $path: ${e.message}") }
                File(root,"manifest.json").writeText(JSONObject().put("scope","live filesystem export; no raw NAND, RAM or ROM; not an atomic snapshot").put("watch_address",address).put("files",files).put("listings",listings).toString(2))
            }
            File(root,"README_RU.txt").writeText("Живая файловая копия часов, снятая 05.10.2026 через штатные команды чтения 061/062 и FI^LS. Это не сырой дамп всей памяти: RAM, ROM, удалённые файлы и неразмеченные области NAND не включены. Часы продолжали работать, снимок не атомарный. Каждый полученный блок проверен контрольной суммой. manifest.json содержит ошибки, размеры, SHA-256 и CRC32; исходный crc_record.txt относится к другой исходной сборке и не гарантирует совпадение нынешних файлов.\n")
            val zip=File(targetContext.filesDir,"Fit3_live_files_2026-10-05.zip")
            ZipOutputStream(zip.outputStream().buffered()).use { z -> root.walkTopDown().filter { it.isFile }.forEach { f -> z.putNextEntry(ZipEntry(f.relativeTo(root).invariantSeparatorsPath)); f.inputStream().use { it.copyTo(z) }; z.closeEntry() } }
            log("COMPLETE zip=${zip.length()} files=${files.length()}"); result.putString("archive",zip.absolutePath)
            finish(Activity.RESULT_OK,result)
        } catch(e: Exception) { log("FATAL ${e.stackTraceToString()}"); result.putString("error",e.toString()); finish(Activity.RESULT_CANCELED,result) }
        finally { ble?.let { main.post { it.releaseFileTransfer() } }; if(lock.isHeld) lock.release() }
    }
}
