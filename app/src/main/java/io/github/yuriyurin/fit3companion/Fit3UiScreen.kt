package io.github.yuriyurin.fit3companion

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.yuriyurin.fit3companion.ble.*
import io.github.yuriyurin.fit3companion.protocol.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun Fit3UiScreen(snapshot: BleSnapshot) {
    val context=LocalContext.current
    val library=remember(context){Fit3UiLibrary(context)}
    val scope=rememberCoroutineScope()
    val files=snapshot.bandFiles; val watch=files.fit3Ui
    var collection by remember { mutableStateOf(emptyList<Fit3UiPackage>()) }
    var tab by remember { mutableIntStateOf(0) }
    var localBusy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Pair<String,String>?>(null) }
    var exporting by remember { mutableStateOf<Fit3UiPackage?>(null) }
    val enabled=snapshot.sapReady&&!files.busy&&!snapshot.firmwareFlash.busy&&!snapshot.firmwareFlash.checking&&!localBusy
    fun local(action: () -> String?) {
        localBusy=true;error=null
        scope.launch { runCatching { withContext(Dispatchers.IO) { val message=action();message to library.list() } }
            .onSuccess { notice=it.first;collection=it.second }.onFailure { error=it.message }
            localBusy=false }
    }
    fun operation(kind: String, path: String?=null) {
        error=null;notice=null
        runCatching { Fit3ConnectionService.active?.fileOperation("/user","fit3ui-$kind",path) ?: error("Сервис подключения недоступен") }
            .onFailure { error=it.message }
    }
    val pick=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()){uri ->
        if(uri!=null){tab=1;local { val face=library.import(uri);"Добавлен «${face.title}» в коллекцию телефона" }}
    }
    val save=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")){uri ->
        val face=exporting;exporting=null
        if(uri!=null&&face!=null)local { context.contentResolver.openOutputStream(uri,"wt")?.use{it.write(face.bytes)} ?: error("Не удалось сохранить файл");"Файл циферблата сохранён" }
    }
    LaunchedEffect(Unit){local { library.addExamples();null }}
    LaunchedEffect(snapshot.sapReady){if(snapshot.sapReady&&!files.busy)operation("refresh")}
    LaunchedEffect(files.busy,watch){if(!files.busy)collection=withContext(Dispatchers.IO){library.list()}}
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Card(colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.primaryContainer)) {
            Column(Modifier.padding(18.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text("Fit3UI",style=MaterialTheme.typography.headlineSmall,localize=false)
                Text("Свои циферблаты · один файл",style=MaterialTheme.typography.titleSmall)
                Text("Установите файл .f3ui и выберите оформление для приложения Fit3UI на часах.",style=MaterialTheme.typography.bodyMedium)
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Button(onClick={pick.launch(arrayOf("*/*"))},enabled=!localBusy&&!files.busy,modifier=Modifier.weight(1f)){Text("Добавить файл")}
            OutlinedButton(onClick={operation("refresh")},enabled=enabled){Text("Обновить")}
        }
        if(!snapshot.sapReady)Text("Подключите часы, чтобы установить или выбрать циферблат. Коллекция телефона доступна без подключения.")
        if(files.busy||localBusy){
            if(files.total>0&&files.busy)LinearProgressIndicator(progress={ (files.done.toFloat()/files.total).coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
            else LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if(files.busy)files.status else "Проверяем файл…",style=MaterialTheme.typography.bodySmall)
            if(files.busy)TextButton(onClick={Fit3ConnectionService.active?.cancelFileOperation()}){Text("Отменить")}
        }
        (error?:files.error)?.let{Text(it,color=MaterialTheme.colorScheme.error)}
        (notice?:files.status.takeIf{!files.busy})?.takeIf{it.isNotBlank()}?.let{Text(it,style=MaterialTheme.typography.bodySmall)}
        TabRow(selectedTabIndex=tab) {
            Tab(selected=tab==0,onClick={tab=0},text={Text("На часах (${watch.faces.size})")})
            Tab(selected=tab==1,onClick={tab=1},text={Text("Моя коллекция (${collection.size})")})
        }
        if(tab==0) {
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Встроенный циферблат",style=MaterialTheme.typography.titleMedium)
                    Text(if(watch.loaded&&watch.activeKey==null)"Активен" else "Всегда доступен на часах",color=MaterialTheme.colorScheme.onSurfaceVariant)
                    if(watch.invalidActive)Text("Файл выбранного циферблата повреждён. Fit3UI использует встроенный.",color=MaterialTheme.colorScheme.error)
                    TextButton(onClick={operation("reset")},enabled=enabled&&watch.loaded&&(watch.activeKey!=null||watch.invalidActive)){Text("Использовать встроенный")}
                }
            }
            if(watch.loaded&&watch.faces.isEmpty())Text("Пока нет своих циферблатов. В «Моей коллекции» уже есть два примера — установите любой.")
            if(!watch.loaded&&!files.busy)Text("Нажмите «Обновить», чтобы получить список с часов. Нужна прошивка Fit3UI 0.2 или новее.")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=540.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(watch.faces,key={it.path}){face ->
                    val localFace=collection.firstOrNull{it.key==face.key};val active=face.key!=null&&face.key==watch.activeKey
                    Fit3UiFaceCard(localFace,face.title,"${face.size / 1024} КБ · ${if(active)"Активен" else "На часах"}",face.error) {
                        TextButton(onClick={operation("select",face.key)},enabled=enabled&&localFace!=null&&!active){Text("Выбрать")}
                        TextButton(onClick={localFace?.let{exporting=it;save.launch(it.fileName)}},enabled=!localBusy&&localFace!=null){Text("Сохранить")}
                        TextButton(onClick={deleting=face.path to face.title},enabled=enabled&&!active){Text("Удалить")}
                    }
                }
            }
        } else {
            if(collection.isEmpty())Text("Добавьте файл .f3ui. Он останется в коллекции телефона, даже если удалить его с часов.")
            LazyColumn(Modifier.fillMaxWidth().heightIn(max=540.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                items(collection,key={it.key}){face ->
                    val installed=watch.faces.any{it.key==face.key}
                    Fit3UiFaceCard(face,face.title,"256 × 402 · ${face.bytes.size} Б${if(installed)" · На часах" else ""}",null) {
                        TextButton(onClick={operation("install",face.key)},enabled=enabled&&!installed){Text(if(installed)"Установлен" else "Установить")}
                        TextButton(onClick={exporting=face;save.launch(face.fileName)},enabled=!localBusy){Text("Сохранить")}
                        TextButton(onClick={deleting=face.key to face.title},enabled=!files.busy&&!localBusy){Text("Убрать")}
                    }
                }
            }
        }
        Text("Основной экран Samsung сохраняется. После выбора откройте Fit3UI заново на часах.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
    deleting?.let { (target,title) ->
        val onWatch=target.startsWith('/')
        AlertDialog(onDismissRequest={deleting=null},title={Text("Удалить «$title»?",localize=false)},
            text={Text(if(onWatch)"Файл будет удалён с часов. Копия в коллекции телефона сохранится." else "Файл будет убран из коллекции телефона. Циферблат на часах сохранится.")},
            confirmButton={TextButton(onClick={deleting=null;if(onWatch)operation("delete",target)else local{library.remove(target);"Убрано из коллекции"}}){Text("Удалить")}},
            dismissButton={TextButton(onClick={deleting=null}){Text("Отмена")}})
    }
}

@Composable
private fun Fit3UiFaceCard(face: Fit3UiPackage?,title: String,detail: String,error: String?,actions: @Composable RowScope.()->Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(14.dp)) {
                if(face!=null){val image=remember(face.key){Fit3UiPreview.bitmap(face)};Image(image.asImageBitmap(),contentDescription="Предпросмотр циферблата",modifier=Modifier.size(74.dp,116.dp))}
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(title,style=MaterialTheme.typography.titleMedium,localize=false)
                    Text(detail,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    error?.let{Text(it,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)}
                }
            }
            Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(2.dp),content=actions)
        }
    }
}
