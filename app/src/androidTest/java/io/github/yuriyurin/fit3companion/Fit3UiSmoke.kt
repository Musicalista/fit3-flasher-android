package io.github.yuriyurin.fit3companion

import android.app.Activity
import android.app.Instrumentation
import android.content.ContextWrapper
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.MutableState
import java.io.File

/** Local storage/preview test in a private sandbox; never writes to the watch. */
class Fit3UiSmoke : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments);start() }
    override fun onStart() {
        val result=Bundle()
        try {
            val root=File(targetContext.cacheDir,"fit3ui-smoke-${System.nanoTime()}").apply { mkdirs() }
            val sandbox=object: ContextWrapper(targetContext){override fun getFilesDir()=root}
            val library=Fit3UiLibrary(sandbox);library.addExamples();assert(library.list().size==3)
            library.addExamples();check(library.list().size==3)
            val faces=library.list();check(faces.any { it.formatVersion==2 && it.layers.any { layer -> layer.type==6 } });check(faces.all{Fit3UiPreview.bitmap(it).width==256})
            val selectedFile=File(root,"selected.f3ui").apply { writeBytes(faces[0].bytes) }
            check(library.import(android.net.Uri.fromFile(selectedFile)).key==faces[0].key)
            check(library.list().size==3)
            selectedFile.writeBytes(faces[0].bytes.copyOf().apply { this[0]=0 })
            check(runCatching { library.import(android.net.Uri.fromFile(selectedFile)) }.isFailure)
            check(library.list().size==3)
            for(face in faces) {
                val preview=Fit3UiPreview.bitmap(face)
                File(targetContext.cacheDir,"Fit3UI_${face.title}_android.png").outputStream().use { check(preview.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)) }
            }
            library.remove(faces[0].key);library.addExamples();check(library.list().size==2)
            root.deleteRecursively()
            val activity=startActivitySync(Intent(targetContext,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            runOnMainSync {
                val type=Class.forName("io.github.yuriyurin.fit3companion.Page")
                val selected=requireNotNull(type.enumConstants).first{(it as Enum<*>).name=="FIT3UI"}
                val field=MainActivity::class.java.getDeclaredField("page\$delegate").apply{isAccessible=true}
                @Suppress("UNCHECKED_CAST")
                val value=field.get(activity) as MutableState<Any>;value.value=selected
            }
            waitForIdleSync();Thread.sleep(6000)
            fun screenshot(name:String){val image=uiAutomation.takeScreenshot();check(image!=null);File(targetContext.cacheDir,name).outputStream().use{image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it)}}
            screenshot("Fit3UI_on_watch_screen.png")
            fun clickCollection(node: AccessibilityNodeInfo?): Boolean {
                if(node==null)return false
                if(node.text?.toString()?.startsWith("Моя коллекция")==true){var parent:AccessibilityNodeInfo?=node;repeat(4){if(parent?.isClickable==true)return parent!!.performAction(AccessibilityNodeInfo.ACTION_CLICK);parent=parent?.parent}}
                for(i in 0 until node.childCount)if(clickCollection(node.getChild(i)))return true
                return false
            }
            check(clickCollection(uiAutomation.rootInActiveWindow)) { "Collection tab not found" }
            waitForIdleSync();Thread.sleep(500);screenshot("Fit3UI_collection_screen.png")
            result.putString("fit3ui","PASS: single-file URI import, corruption rejection, examples, deduplication, preview, persistent local deletion, real application screen")
            finish(Activity.RESULT_OK,result)
        } catch(error:Throwable){result.putString("failure",android.util.Log.getStackTraceString(error));finish(Activity.RESULT_CANCELED,result)}
    }
}
