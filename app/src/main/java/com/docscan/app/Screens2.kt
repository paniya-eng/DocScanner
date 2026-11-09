@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.docscan.app

import android.content.ComponentName
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** Numeric field: a double tap (word selection) selects the WHOLE number. */
@Composable
fun NumField(value: String, onValue: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    var tf by remember { mutableStateOf(TextFieldValue(value)) }
    LaunchedEffect(value) {
        if (tf.text != value) tf = TextFieldValue(value, TextRange(value.length))
    }
    OutlinedTextField(
        value = tf,
        onValueChange = { n ->
            var nv = n
            if (n.text == tf.text && !n.selection.collapsed && n.selection != TextRange(0, n.text.length)) {
                nv = n.copy(selection = TextRange(0, n.text.length))
            }
            tf = nv
            if (nv.text != value) onValue(nv.text)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier
    )
}

// ───────────── Page size (asked after every scan) ─────────────
@Composable
fun PageSizeScreen(vm: ScanVM) {
    val idx = vm.editIndex
    val page = vm.pages.getOrNull(idx)
    if (page == null) {
        LaunchedEffect(Unit) { vm.step = Step.PAGES }
        return
    }
    val thumb by produceState<Bitmap?>(null, page.file, page.corners) {
        value = withContext(Dispatchers.Default) { DocScanner.preview(page, 400) }
    }

    var custom by remember(page.id) { mutableStateOf(page.size.label == null) }
    var sel by remember(page.id) { mutableStateOf(page.size) }
    var unitCm by remember(page.id) { mutableStateOf(false) }
    var wText by remember(page.id) { mutableStateOf(fmtNum(page.size.wMm)) }
    var hText by remember(page.id) { mutableStateOf(if (page.size.autoH) "" else fmtNum(page.size.hMm)) }
    var autoH by remember(page.id) { mutableStateOf(page.size.autoH) }

    val factor = if (unitCm) 10.0 else 1.0
    val cw = parseNum(wText)?.times(factor)
    val ch = parseNum(hText)?.times(factor)
    val customOk = cw != null && cw in 5.0..2000.0 && (autoH || (ch != null && ch in 5.0..2000.0))
    val canConfirm = !custom || customOk

    fun setUnit(cm: Boolean) {
        if (cm == unitCm) return
        val w = parseNum(wText)
        val h = parseNum(hText)
        val k = if (cm) 0.1 else 10.0
        unitCm = cm
        if (w != null) wText = fmtNum(w * k)
        if (h != null) hText = fmtNum(h * k)
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Header(stringResource(R.string.size_title))

            Box(Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
                val t = thumb
                if (t == null) CircularProgressIndicator()
                else Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.height(6.dp))

            PRESETS.forEach { p ->
                val pick = { sel = p.toSize(); custom = false }
                Row(
                    Modifier.fillMaxWidth().clickable { pick() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = !custom && sel.label == p.name, onClick = { pick() })
                    Text(stringResource(p.name), modifier = Modifier.weight(1f))
                    Text(
                        if (p.autoH) stringResource(R.string.size_auto_h, fmtNum(p.wMm))
                        else "${fmtNum(p.wMm)}×${fmtNum(p.hMm)} mm",
                        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { custom = true },
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = custom, onClick = { custom = true })
                Text(stringResource(R.string.size_custom), modifier = Modifier.weight(1f))
            }

            if (custom) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !unitCm, onClick = { setUnit(false) }, label = { Text(stringResource(R.string.unit_mm)) })
                    FilterChip(selected = unitCm, onClick = { setUnit(true) }, label = { Text(stringResource(R.string.unit_cm)) })
                }
                val unit = stringResource(if (unitCm) R.string.unit_cm else R.string.unit_mm)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NumField(wText, { wText = it }, stringResource(R.string.label_width, unit), Modifier.weight(1f))
                    if (!autoH) {
                        NumField(hText, { hText = it }, stringResource(R.string.label_height, unit), Modifier.weight(1f))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().clickable { autoH = !autoH },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = autoH, onCheckedChange = { autoH = it })
                    Text(stringResource(R.string.size_auto_check), fontSize = 13.sp)
                }
                if (!customOk) {
                    Text(stringResource(R.string.size_invalid), color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        BottomBar {
            BarIcon(Icons.Filled.ArrowBack, stringResource(R.string.cd_back), { vm.back() })
            BarIcon(Icons.Filled.Check, stringResource(R.string.cd_confirm), {
                val size = if (custom) PageSize(cw!!, if (autoH) 0.0 else ch!!, autoH, null) else sel
                vm.setPageSize(idx, size)
            }, enabled = canConfirm, container = Green)
        }
    }
}

// ───────────── Export: filter + quality + save/send on ONE page ─────────────
@Composable
fun ExportScreen(vm: ScanVM) {
    var showSheet by remember { mutableStateOf(false) }
    var confirmNew by remember { mutableStateOf(false) }
    var applyMenu by remember { mutableStateOf(false) }

    val first = vm.pages.firstOrNull()
    val preview by produceState<Bitmap?>(null, vm.filter, first) {
        value = if (first == null) null else withContext(Dispatchers.Default) {
            val (wMm, hMm) = DocScanner.resolveSize(first)
            val ar = wMm / hMm
            val w = if (ar >= 1) 700 else (700 * ar).roundToInt().coerceAtLeast(1)
            val h = (w / ar).roundToInt().coerceAtLeast(1)
            val src = DocScanner.load(first.file, 1400)
            val r = DocScanner.render(src, first.corners, w, h, vm.filter)
            src.recycle()
            r
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.export_title),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = { confirmNew = true }) { Text(stringResource(R.string.new_scan)) }
            }

            // preview of the first page
            Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                val p = preview
                if (p == null) CircularProgressIndicator()
                else Image(p.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
            }
            Spacer(Modifier.height(10.dp))

            // sizes chosen for each page (tap to change)
            Text(stringResource(R.string.section_sizes), style = MaterialTheme.typography.labelLarge)
            vm.pages.forEachIndexed { i, p ->
                Row(
                    Modifier.fillMaxWidth().clickable { vm.editPageSize(i, Step.EXPORT) }.padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.page_n, i + 1), modifier = Modifier.weight(1f))
                    Text(sizeText(p.size), fontSize = 13.sp, color = Blue)
                }
            }
            Box {
                OutlinedButton(onClick = { applyMenu = true }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                    Text(stringResource(R.string.apply_all))
                    Icon(Icons.Filled.ArrowDropDown, null)
                }
                DropdownMenu(expanded = applyMenu, onDismissRequest = { applyMenu = false }) {
                    PRESETS.forEach { p ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    stringResource(p.name) + "  (" +
                                        (if (p.autoH) stringResource(R.string.size_auto_h, fmtNum(p.wMm))
                                        else "${fmtNum(p.wMm)}×${fmtNum(p.hMm)} mm") + ")"
                                )
                            },
                            onClick = { vm.applySizeToAll(p.toSize()); applyMenu = false }
                        )
                    }
                }
            }
            Spacer(Modifier.height(10.dp))

            // filter
            Text(stringResource(R.string.section_filter), style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ScanFilter.values().forEach { f ->
                    FilterChip(selected = vm.filter == f, onClick = { vm.filter = f }, label = { Text(stringResource(f.label)) })
                }
            }
            Spacer(Modifier.height(10.dp))

            // dpi
            Text(stringResource(R.string.section_dpi), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(150, 200, 300, 600).forEach { d ->
                    FilterChip(selected = vm.dpi == d, onClick = { vm.dpi = d }, label = { Text("$d") })
                }
            }
            Spacer(Modifier.height(10.dp))

            // format + quality
            Text(stringResource(R.string.section_format), style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Fmt.values().forEach { f ->
                    FilterChip(selected = vm.format == f, onClick = { vm.format = f }, label = { Text(f.label) })
                }
            }
            Text(
                stringResource(
                    when (vm.format) {
                        Fmt.JPG -> R.string.fmt_desc_jpg
                        Fmt.PNG -> R.string.fmt_desc_png
                        Fmt.PDF -> R.string.fmt_desc_pdf
                    }
                ),
                style = MaterialTheme.typography.bodySmall
            )
            if (vm.format != Fmt.PNG) {
                Text(stringResource(R.string.jpeg_quality, vm.quality), style = MaterialTheme.typography.labelLarge)
                Slider(
                    value = vm.quality.toFloat(), onValueChange = { vm.quality = it.roundToInt() },
                    valueRange = 50f..100f
                )
            }
            if (vm.format != Fmt.PDF && vm.pages.size > 1) {
                Text(stringResource(R.string.multi_files_hint), style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(10.dp))

            // name
            OutlinedTextField(
                value = vm.name, onValueChange = { vm.name = it },
                label = { Text(stringResource(R.string.label_file_name)) },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.storage_hint), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
        }
        BottomBar {
            BarIcon(Icons.Filled.ArrowBack, stringResource(R.string.cd_prev), { vm.back() })
            BarIcon(
                Icons.Filled.Share, stringResource(R.string.cd_send), { showSheet = true },
                enabled = !vm.busy && vm.pages.isNotEmpty(), container = Blue
            )
        }
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ) {
            ShareSheet(vm) { kind, comp ->
                showSheet = false
                vm.send(kind, comp)
            }
        }
    }

    if (confirmNew) {
        AlertDialog(
            onDismissRequest = { confirmNew = false },
            title = { Text(stringResource(R.string.new_scan)) },
            text = { Text(stringResource(R.string.new_scan_msg)) },
            confirmButton = { TextButton(onClick = { confirmNew = false; vm.newScan() }) { Text(stringResource(R.string.yes)) } },
            dismissButton = { TextButton(onClick = { confirmNew = false }) { Text(stringResource(R.string.cancel)) } }
        )
    }
}

@Composable
fun ShareSheet(vm: ScanVM, onPick: (TargetKind, ComponentName?) -> Unit) {
    val ctx = LocalContext.current
    val multi = vm.format != Fmt.PDF && vm.pages.size > 1
    val apps by produceState<List<ShareTarget>?>(null, vm.format, multi) {
        value = withContext(Dispatchers.Default) { Exporter.shareTargets(ctx, vm.format.mime, multi) }
    }

    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
        Text(stringResource(R.string.share_title), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(12.dp))
        val list = apps
        if (list == null) {
            Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(4),
                modifier = Modifier.heightIn(max = 460.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    TargetCell(stringResource(R.string.target_storage), {
                        Icon(Icons.Filled.Folder, null, Modifier.size(46.dp), tint = Color(0xFFFFC107))
                    }) { onPick(TargetKind.STORAGE, null) }
                }
                items(list) { t ->
                    TargetCell(t.label, {
                        val ic = t.icon
                        if (ic != null) Image(ic, null, Modifier.size(46.dp))
                        else Icon(Icons.Filled.Share, null, Modifier.size(40.dp))
                    }) { onPick(TargetKind.APP, t.component) }
                }
                item {
                    TargetCell(stringResource(R.string.target_other), {
                        Icon(Icons.Filled.Share, null, Modifier.size(40.dp))
                    }) { onPick(TargetKind.CHOOSER, null) }
                }
            }
        }
    }
}

@Composable
fun TargetCell(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) { icon() }
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, maxLines = 2, textAlign = TextAlign.Center)
    }
}
