package io.github.yuriyurin.fit3companion

import android.content.Context
import android.net.Uri
import io.github.yuriyurin.fit3companion.protocol.Fit3UiCodec
import io.github.yuriyurin.fit3companion.protocol.Fit3UiPackage
import java.io.File

internal class Fit3UiLibrary(private val context: Context) {
    private val folder get() = File(context.filesDir, "fit3ui").apply { mkdirs() }
    fun list(): List<Fit3UiPackage> = folder.listFiles().orEmpty().filter { it.name.matches(Regex("[0-9a-f]{16}\\.f3ui")) }
        .mapNotNull { runCatching { read(it) }.getOrNull() }.sortedBy { it.title.lowercase() }
    fun read(file: File): Fit3UiPackage {
        require(file.length() in Fit3UiCodec.HEADER.toLong()..Fit3UiCodec.MAX_BYTES.toLong()) { "Слишком большой файл Fit3UI" }
        return Fit3UiCodec.decode(file.readBytes())
    }
    fun add(bytes: ByteArray): Fit3UiPackage {
        val face = Fit3UiCodec.decode(bytes); val target = File(folder, "${face.key}.f3ui")
        if (!target.exists()) { val tmp=File(folder,"${face.key}.tmp"); tmp.writeBytes(bytes); check(tmp.renameTo(target)); }
        return face
    }
    fun import(uri: Uri): Fit3UiPackage = context.contentResolver.openInputStream(uri)?.use {
        val data=it.readBytesBounded(); add(data)
    } ?: error("Не удалось открыть файл")
    private fun java.io.InputStream.readBytesBounded(): ByteArray {
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true){val n=read(buffer);if(n<0)break;require(output.size()+n<=Fit3UiCodec.MAX_BYTES){"Слишком большой файл Fit3UI"};output.write(buffer,0,n)}
        return output.toByteArray()
    }
    fun file(key: String): File { require(key.matches(Regex("[0-9a-f]{16}"))); return File(folder,"$key.f3ui") }
    fun remove(key: String) { check(file(key).delete()) { "Не удалось удалить файл с телефона" } }
    fun addExamples() {
        val marker=File(folder,".examples_added")
        if(!marker.exists()) {
            for(name in listOf("midnight.f3ui","mint.f3ui"))context.assets.open("fit3ui/$name").use { add(it.readBytes()) }
            marker.writeText("1")
        }
        val materialMarker=File(folder,".material_weather_v05_added")
        if(!materialMarker.exists()) {
            context.assets.open("fit3ui/material_weather.f3ui").use { add(it.readBytes()) }
            materialMarker.writeText("1")
        }
    }
}
