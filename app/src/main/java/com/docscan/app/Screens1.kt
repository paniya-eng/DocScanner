@file:OptIn(ExperimentalMaterial3Api::class)

package com.docscan.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.PointF
import android.media.MediaActionSound
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.RotateLeft
import androidx.compose.material.icons.filled.RotateRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

val Blue = Color(0xFF2F80FF)
val Green = Color(0xFF2E9E50)
val PanelGray = Color(0xFF2A2A2E)

// ───────────── shared building blocks ─────────────
@Composable
fun Ltr(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr, content = content)
}

@Composable
fun Header(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
    )
}

@Composable
fun sizeText(s: PageSize): String {
    val dims = if (s.autoH) stringResource(R.string.size_auto_h, fmtNum(s.wMm))
    else "${fmtNum(s.wMm)}×${fmtNum(s.hMm)} mm"
    val label = s.label
    return if (label != null) stringResource(label) + " · " + dims else dims
}

/** Bottom bar: always one line, pinned to the bottom. */
@Composable
fun BottomBar(content: @Composable RowScope.() -> Unit) {
    Ltr {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            content = content
        )
    }
}

@Composable
fun BarIcon(
    icon: ImageVector, desc: String, onClick: () -> Unit,
    enabled: Boolean = true, container: Color? = null
) {
    val colors = if (container == null) IconButtonDefaults.filledTonalIconButtonColors()
    else IconButtonDefaults.filledIconButtonColors(containerColor = container, contentColor = Color.White)
    FilledIconButton(onClick = onClick, enabled = enabled, colors = colors, modifier = Modifier.size(58.dp)) {
        Icon(icon, desc, Modifier.size(30.dp))
    }
}

// ───────────── Camera ─────────────
@Composable
fun CameraScreen(vm: ScanVM) {
    val ctx = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPerm = it }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) vm.importUri(uri)
    }
    LaunchedEffect(Unit) { if (!hasPerm) permLauncher.launch(Manifest.permission.CAMERA) }

    if (!hasPerm) {
        Column(
            Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.cam_perm_need))
            Spacer(Modifier.height(12.dp))
            Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                Text(stringResource(R.string.cam_perm_btn))
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { gallery.launch("image/*") }) { Text(stringResource(R.string.pick_gallery)) }
        }
        return
    }

    val sound = remember { MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) } }
    DisposableEffect(Unit) { onDispose { sound.release() } }

    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .build()
    }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setTargetAspectRatio(AspectRatio.RATIO_16_9)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var torch by remember { mutableStateOf(false) }
    var live by remember { mutableStateOf<List<Offset>?>(null) }

    // live document edge detection on camera frames
    DisposableEffect(Unit) {
        val exec = Executors.newSingleThreadExecutor()
        var last = 0L
        var miss = 0
        analysis.setAnalyzer(exec) { proxy ->
            val now = System.currentTimeMillis()
            if (now - last > 250) {
                last = now
                val r = DocScanner.detectLive(proxy)
                if (r != null) {
                    live = r.map { Offset(it.x, it.y) }
                    miss = 0
                } else {
                    miss++
                    if (miss > 3) live = null
                }
            }
            proxy.close()
        }
        onDispose { analysis.clearAnalyzer(); exec.shutdown() }
    }

    val lastPage = vm.pages.lastOrNull()
    val thumb by produceState<Bitmap?>(null, lastPage) {
        value = if (lastPage == null) null else withContext(Dispatchers.Default) { DocScanner.preview(lastPage, 200) }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // top bar: back · logo + name
        Ltr {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.back() }) {
                    Icon(Icons.Filled.ArrowBack, stringResource(R.string.cd_back), tint = Color.White)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Image(
                        painterResource(R.drawable.logo), null,
                        Modifier.size(26.dp).clip(RoundedCornerShape(7.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Scanner Doc", color = Color.White, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.size(48.dp))
            }
        }

        // large 9:16 (portrait 16:9) preview with live quad overlay
        BoxWithConstraints(
            Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            val ar = 9f / 16f
            val w = if (maxWidth / maxHeight > ar) maxHeight * ar else maxWidth
            Box(Modifier.size(w, w / ar).clip(RoundedCornerShape(20.dp))) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { c ->
                        PreviewView(c).also { pv ->
                            pv.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                            pv.scaleType = PreviewView.ScaleType.FILL_CENTER
                            val future = ProcessCameraProvider.getInstance(c)
                            future.addListener({
                                val provider = future.get()
                                val preview = Preview.Builder()
                                    .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                                    .build().also { it.setSurfaceProvider(pv.surfaceProvider) }
                                provider.unbindAll()
                                camera = try {
                                    provider.bindToLifecycle(
                                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                        preview, imageCapture, analysis
                                    )
                                } catch (_: Exception) {
                                    provider.unbindAll()
                                    provider.bindToLifecycle(
                                        lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture
                                    )
                                }
                            }, ContextCompat.getMainExecutor(c))
                        }
                    }
                )
                val q = live
                if (q != null) {
                    Canvas(Modifier.fillMaxSize()) {
                        val p = q.map { Offset(it.x * size.width, it.y * size.height) }
                        val path = Path().apply {
                            moveTo(p[0].x, p[0].y)
                            for (i in 1..3) lineTo(p[i].x, p[i].y)
                            close()
                        }
                        drawPath(path, Color(0x332F80FF), style = Fill)
                        drawPath(path, Blue, style = Stroke(width = 4f))
                        p.forEach {
                            drawCircle(Color.White, radius = 17f, center = it)
                            drawCircle(Blue, radius = 12f, center = it)
                        }
                    }
                }

                // hint + mode strip overlaid at the bottom of the preview
                Column(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        stringResource(if (q != null) R.string.cam_hint_found else R.string.cam_hint_find),
                        color = Color.White, fontSize = 13.sp,
                        modifier = Modifier.background(Color(0x99000000), CircleShape)
                            .padding(horizontal = 14.dp, vertical = 6.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Ltr {
                        Row(
                            Modifier.background(Color(0x66000000), CircleShape).padding(horizontal = 6.dp)
                        ) {
                            MODES.forEachIndexed { i, m ->
                                val sel = i == vm.modeIndex
                                Text(
                                    stringResource(m.first),
                                    color = if (sel) Blue else Color(0xFFDDDDDD),
                                    fontWeight = if (sel) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 14.sp,
                                    modifier = Modifier.clickable { vm.setMode(i) }
                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // controls: gallery + flash · shutter · last page
        Ltr {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 10.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(50.dp).clip(RoundedCornerShape(14.dp)).background(PanelGray)
                                .clickable { gallery.launch("image/*") },
                            contentAlignment = Alignment.Center
                        ) { Icon(Icons.Filled.PhotoLibrary, stringResource(R.string.cd_gallery), tint = Color.White) }
                        Spacer(Modifier.width(12.dp))
                        Box(
                            Modifier.size(50.dp).clip(CircleShape).background(PanelGray)
                                .clickable {
                                    torch = !torch
                                    camera?.cameraControl?.enableTorch(torch)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (torch) Icons.Filled.FlashOn else Icons.Filled.FlashOff,
                                stringResource(R.string.cd_flash),
                                tint = if (torch) Color(0xFFFFC107) else Color.White
                            )
                        }
                    }
                }

                Box(
                    Modifier.size(78.dp)
                        .border(4.dp, Color.White, CircleShape)
                        .padding(7.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable {
                            if (!vm.busy) {
                                sound.play(MediaActionSound.SHUTTER_CLICK)
                                val f = File(ctx.cacheDir, "p_${System.nanoTime()}.jpg")
                                imageCapture.takePicture(
                                    ImageCapture.OutputFileOptions.Builder(f).build(),
                                    ContextCompat.getMainExecutor(ctx),
                                    object : ImageCapture.OnImageSavedCallback {
                                        override fun onImageSaved(r: ImageCapture.OutputFileResults) { vm.addImage(f) }
                                        override fun onError(e: ImageCaptureException) {
                                            vm.error = Msg(R.string.err_capture, listOf(e.message ?: ""))
                                        }
                                    }
                                )
                            }
                        }
                )

                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    Box(Modifier.size(56.dp)) {
                        Box(
                            Modifier.fillMaxSize().clip(RoundedCornerShape(12.dp)).background(PanelGray)
                                .border(1.dp, Color.Gray, RoundedCornerShape(12.dp))
                                .clickable(enabled = vm.pages.isNotEmpty()) { vm.step = Step.PAGES }
                        ) {
                            val t = thumb
                            if (t != null) Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        if (vm.pages.isNotEmpty()) {
                            Box(
                                Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp).size(24.dp)
                                    .clip(CircleShape).background(Blue),
                                contentAlignment = Alignment.Center
                            ) { Text("${vm.pages.size}", color = Color.White, fontSize = 12.sp) }
                        }
                    }
                }
            }
        }
    }
}

// ───────────── Crop (with magnifier) ─────────────
@Composable
fun Magnifier(bmp: Bitmap, c: Offset, disp: IntSize, modifier: Modifier) {
    val img = remember(bmp) { bmp.asImageBitmap() }
    Canvas(
        modifier.clip(CircleShape).background(Color.Black).border(3.dp, Color.White, CircleShape)
    ) {
        val zoom = 3f
        withTransform({
            translate(
                left = size.width / 2f - c.x * disp.width * zoom,
                top = size.height / 2f - c.y * disp.height * zoom
            )
            scale(zoom, zoom, pivot = Offset.Zero)
        }) {
            drawImage(img, dstSize = IntSize(disp.width, disp.height))
        }
        val m = Offset(size.width / 2f, size.height / 2f)
        drawLine(Blue, Offset(m.x - 22f, m.y), Offset(m.x + 22f, m.y), strokeWidth = 2f)
        drawLine(Blue, Offset(m.x, m.y - 22f), Offset(m.x, m.y + 22f), strokeWidth = 2f)
        drawCircle(Blue, radius = 7f, center = m, style = Stroke(width = 2f))
    }
}

@Composable
fun CropScreen(vm: ScanVM) {
    val page = vm.pages.getOrNull(vm.editIndex)
    if (page == null) {
        LaunchedEffect(Unit) { vm.step = Step.PAGES }
        return
    }
    val bmp by produceState<Bitmap?>(null, page.file) {
        value = withContext(Dispatchers.Default) { DocScanner.load(page.file, 1600) }
    }
    var pts by remember(page.file, page.corners) { mutableStateOf(page.corners.map { Offset(it.x, it.y) }) }
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    var active by remember { mutableIntStateOf(-1) }
    fun current() = pts.map { PointF(it.x, it.y) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(stringResource(R.string.crop_title))

        Ltr {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                FilledTonalIconButton(onClick = { vm.transform(Xf.ROT_LEFT, current()) }) {
                    Icon(Icons.Filled.RotateLeft, stringResource(R.string.cd_rotate_left))
                }
                FilledTonalIconButton(onClick = { vm.transform(Xf.FLIP, current()) }) {
                    Icon(Icons.Filled.Flip, stringResource(R.string.cd_mirror))
                }
                FilledTonalIconButton(onClick = { vm.transform(Xf.ROT_RIGHT, current()) }) {
                    Icon(Icons.Filled.RotateRight, stringResource(R.string.cd_rotate_right))
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        val b = bmp
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (b == null) {
                CircularProgressIndicator()
            } else {
                val ar = b.width.toFloat() / b.height
                val w = if (maxWidth / maxHeight > ar) maxHeight * ar else maxWidth
                val h = w / ar
                Ltr {
                    Box(
                        Modifier
                            .size(w, h)
                            .onSizeChanged { boxSize = it }
                            .pointerInput(page.file) {
                                detectDragGestures(
                                    onDragStart = { p ->
                                        if (boxSize.width > 0) {
                                            var best = -1
                                            var bd = Float.MAX_VALUE
                                            pts.forEachIndexed { i, o ->
                                                val dx = o.x * boxSize.width - p.x
                                                val dy = o.y * boxSize.height - p.y
                                                val d = dx * dx + dy * dy
                                                if (d < bd) { bd = d; best = i }
                                            }
                                            active = best
                                        }
                                    },
                                    onDrag = { change, amount ->
                                        change.consume()
                                        if (active >= 0 && boxSize.width > 0) {
                                            val o = pts[active]
                                            val n = Offset(
                                                (o.x + amount.x / boxSize.width).coerceIn(0f, 1f),
                                                (o.y + amount.y / boxSize.height).coerceIn(0f, 1f)
                                            )
                                            pts = pts.toMutableList().also { it[active] = n }
                                        }
                                    },
                                    onDragEnd = { active = -1 },
                                    onDragCancel = { active = -1 }
                                )
                            }
                    ) {
                        Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                        Canvas(Modifier.fillMaxSize()) {
                            val p = pts.map { Offset(it.x * size.width, it.y * size.height) }
                            val path = Path().apply {
                                moveTo(p[0].x, p[0].y)
                                for (i in 1..3) lineTo(p[i].x, p[i].y)
                                close()
                            }
                            drawPath(path, Color(0x332F80FF), style = Fill)
                            drawPath(path, Blue, style = Stroke(width = 5f))
                            p.forEachIndexed { i, o ->
                                val r = if (i == active) 30f else 24f
                                drawCircle(Color.White, radius = r + 4f, center = o)
                                drawCircle(Blue, radius = r, center = o)
                            }
                        }
                        if (active >= 0 && boxSize.width > 0) {
                            val a = pts[active]
                            val magDp = 124.dp
                            val magPx = with(LocalDensity.current) { magDp.toPx() }
                            val nearTopLeft = a.x * boxSize.width < magPx * 1.9f && a.y * boxSize.height < magPx * 1.9f
                            Magnifier(
                                b, a, boxSize,
                                Modifier
                                    .align(if (nearTopLeft) Alignment.TopEnd else Alignment.TopStart)
                                    .padding(8.dp)
                                    .size(magDp)
                            )
                        }
                    }
                }
            }
        }

        BottomBar {
            BarIcon(Icons.Filled.Add, stringResource(R.string.cd_retake), { vm.retake() })
            BarIcon(Icons.Filled.AutoFixHigh, stringResource(R.string.cd_auto_detect), { vm.autoDetect() }, enabled = b != null)
            BarIcon(Icons.Filled.Check, stringResource(R.string.cd_confirm), {
                vm.updateCorners(vm.editIndex, current())
                vm.confirmCrop()
            }, enabled = b != null, container = Green)
        }
    }
}

// ───────────── Pages list ─────────────
@Composable
fun PagesScreen(vm: ScanVM) {
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Header(stringResource(R.string.pages_title, vm.pages.size))
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            itemsIndexed(vm.pages, key = { _, p -> p.id }) { i, p ->
                val thumb by produceState<Bitmap?>(null, p) {
                    value = withContext(Dispatchers.Default) { DocScanner.preview(p, 500) }
                }
                Card {
                    Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.page_n, i + 1), style = MaterialTheme.typography.labelLarge)
                        Box(Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
                            val t = thumb
                            if (t == null) CircularProgressIndicator()
                            else Image(t.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                        }
                        TextButton(onClick = { vm.editPageSize(i, Step.PAGES) }) {
                            Text(sizeText(p.size), fontSize = 12.sp, maxLines = 2, textAlign = TextAlign.Center)
                        }
                        Row {
                            IconButton(onClick = { vm.editPage(i) }) { Icon(Icons.Filled.Edit, stringResource(R.string.cd_edit)) }
                            IconButton(onClick = { vm.deletePage(i) }) { Icon(Icons.Filled.Delete, stringResource(R.string.cd_delete)) }
                        }
                    }
                }
            }
        }
        BottomBar {
            BarIcon(Icons.Filled.ArrowBack, stringResource(R.string.cd_prev), { vm.back() })
            BarIcon(Icons.Filled.Add, stringResource(R.string.cd_add_page), { vm.step = Step.SCAN })
            BarIcon(Icons.Filled.ArrowForward, stringResource(R.string.cd_continue), { vm.step = Step.EXPORT }, container = Blue)
        }
    }
}
