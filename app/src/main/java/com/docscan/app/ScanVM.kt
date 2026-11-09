package com.docscan.app

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.graphics.PointF
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.opencv.android.OpenCVLoader
import java.io.File

class ScanVM(app: Application) : AndroidViewModel(app) {

    init { OpenCVLoader.initLocal() }

    var step by mutableStateOf(Step.SPLASH)
    val pages = mutableStateListOf<ScanPage>()
    var editIndex by mutableIntStateOf(-1)
    var editingNew by mutableStateOf(false)

    var lastSize by mutableStateOf(PRESETS[0].toSize())
    var sizeReturn by mutableStateOf(Step.PAGES)
    var modeIndex by mutableIntStateOf(0)
    var dpi by mutableIntStateOf(300)
    var quality by mutableIntStateOf(90)
    var filter by mutableStateOf(ScanFilter.ORIGINAL)
    var name by mutableStateOf("")
    var format by mutableStateOf(Fmt.PDF)

    var busy by mutableStateOf(false)
    var busyMsg by mutableStateOf<Msg?>(null)
    var error by mutableStateOf<Msg?>(null)
    var info by mutableStateOf<Msg?>(null)
    var infoFolder by mutableStateOf<String?>(null)
    var pendingIntent by mutableStateOf<Intent?>(null)
    var pendingChooser by mutableStateOf(false)

    private val cache get() = getApplication<Application>().cacheDir
    private fun fail(t: Throwable, id: Int = R.string.err_generic) {
        error = Msg(id, listOf(t.message ?: ""))
    }

    fun setMode(i: Int) { modeIndex = i; lastSize = MODES[i].second.toSize() }

    fun setPageSize(i: Int, size: PageSize) {
        if (i in pages.indices) pages[i] = pages[i].copy(size = size)
        lastSize = size
        step = if (editingNew) Step.SCAN else sizeReturn
        editingNew = false
    }

    fun editPageSize(i: Int, returnTo: Step) {
        editIndex = i; editingNew = false; sizeReturn = returnTo; step = Step.SIZE
    }

    fun applySizeToAll(size: PageSize) {
        for (i in pages.indices) pages[i] = pages[i].copy(size = size)
        lastSize = size
    }

    // ---- pages ----
    fun importUri(uri: Uri) {
        viewModelScope.launch {
            try {
                val f = withContext(Dispatchers.IO) {
                    val file = File(cache, "p_${System.nanoTime()}.jpg")
                    getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                        file.outputStream().use { i.copyTo(it) }
                    }
                    file
                }
                addImage(f)
            } catch (t: Throwable) { fail(t, R.string.err_load) }
        }
    }

    fun addImage(file: File) {
        viewModelScope.launch {
            busy = true; busyMsg = Msg(R.string.busy_detect)
            try {
                val corners = withContext(Dispatchers.Default) {
                    val b = DocScanner.load(file, 1200)
                    val c = DocScanner.detect(b)
                    b.recycle()
                    c
                }
                pages.add(ScanPage(System.nanoTime(), file, corners, lastSize))
                editIndex = pages.lastIndex
                editingNew = true
                step = Step.CROP
            } catch (t: Throwable) {
                fail(t)
            } finally { busy = false }
        }
    }

    fun updateCorners(i: Int, c: List<PointF>) {
        if (i in pages.indices) pages[i] = pages[i].copy(corners = c)
    }

    fun autoDetect() {
        val i = editIndex
        val p = pages.getOrNull(i) ?: return
        viewModelScope.launch {
            busy = true; busyMsg = Msg(R.string.busy_auto)
            try {
                val c = withContext(Dispatchers.Default) {
                    val b = DocScanner.load(p.file, 1200)
                    val r = DocScanner.detect(b)
                    b.recycle()
                    r
                }
                if (i in pages.indices && pages[i].id == p.id) pages[i] = pages[i].copy(corners = c)
            } catch (t: Throwable) {
                fail(t)
            } finally { busy = false }
        }
    }

    fun transform(xf: Xf, current: List<PointF>) {
        val i = editIndex
        val p = pages.getOrNull(i) ?: return
        viewModelScope.launch {
            busy = true; busyMsg = Msg(R.string.busy_apply)
            try {
                val nf = File(cache, "p_${System.nanoTime()}.jpg")
                withContext(Dispatchers.Default) { DocScanner.transformFile(p.file, nf, xf) }
                if (i in pages.indices && pages[i].id == p.id) {
                    pages[i] = p.copy(file = nf, corners = DocScanner.transformCorners(current, xf))
                    p.file.delete()
                }
            } catch (t: Throwable) {
                fail(t)
            } finally { busy = false }
        }
    }

    fun confirmCrop() { step = if (editingNew) Step.SIZE else Step.PAGES }

    fun retake() {
        if (editIndex in pages.indices) pages.removeAt(editIndex)
        step = Step.SCAN
    }

    fun editPage(i: Int) { editIndex = i; editingNew = false; step = Step.CROP }
    fun deletePage(i: Int) {
        if (i in pages.indices) pages.removeAt(i)
        if (pages.isEmpty()) step = Step.SCAN
    }

    // ---- navigation ----
    fun canGoBack() = step != Step.SPLASH && step != Step.HOME

    fun back() {
        step = when (step) {
            Step.SPLASH -> Step.SPLASH
            Step.HOME -> Step.HOME
            Step.SCAN -> Step.HOME
            Step.CROP -> Step.PAGES
            Step.SIZE -> if (editingNew) Step.CROP else sizeReturn
            Step.PAGES -> Step.SCAN
            Step.EXPORT -> Step.PAGES
        }
    }

    fun newScan() {
        pages.clear(); name = ""; step = Step.HOME
    }

    // ---- export / send ----
    fun send(kind: TargetKind, comp: ComponentName?) {
        val spec = ExportSpec(dpi, quality, filter, name, format)
        val list = pages.toList()
        val toStorage = kind == TargetKind.STORAGE
        viewModelScope.launch {
            busy = true; busyMsg = Msg(R.string.busy_processing)
            try {
                val uris = withContext(Dispatchers.Default) {
                    Exporter.export(getApplication(), list, spec, toStorage) {
                        busyMsg = Msg(R.string.busy_page, listOf(it, list.size))
                    }
                }
                if (toStorage) {
                    val folder = if (spec.format == Fmt.PDF) "Download/DocScanner" else "Pictures/DocScanner"
                    infoFolder = folder
                    info = Msg(R.string.saved_ok, listOf(folder))
                } else {
                    val i = Exporter.buildSend(uris, spec.format.mime)
                    if (kind == TargetKind.APP) i.component = comp
                    pendingChooser = kind != TargetKind.APP
                    pendingIntent = i
                }
            } catch (t: Throwable) {
                fail(t)
            } finally { busy = false }
        }
    }
}
