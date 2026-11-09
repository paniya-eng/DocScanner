package com.docscan.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase, LocaleHelper.get(newBase)))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val lang = LocaleHelper.get(this)
        setContent {
            val scheme = lightColorScheme(
                primary = Color(0xFF2F80FF),
                onPrimary = Color.White,
                background = Color(0xFFF3F5FA),
                surface = Color(0xFFF3F5FA),
                onSurface = Color(0xFF1B2333),
                surfaceVariant = Color.White,
                onSurfaceVariant = Color(0xFF5B6475),
                secondaryContainer = Color(0xFFE4ECFF),
                onSecondaryContainer = Color(0xFF1F4FD8),
                outline = Color(0xFFB8C0D0),
                outlineVariant = Color(0xFFD9DEE8)
            )
            MaterialTheme(colorScheme = scheme) {
                CompositionLocalProvider(
                    LocalLayoutDirection provides if (lang == "fa") LayoutDirection.Rtl else LayoutDirection.Ltr
                ) {
                    App()
                }
            }
        }
    }
}

@Composable
fun App(vm: ScanVM = viewModel()) {
    val ctx = LocalContext.current
    BackHandler(enabled = vm.canGoBack()) { vm.back() }

    // launch a prepared share intent (specific app or system chooser)
    val chooserTitle = stringResource(R.string.send_chooser)
    LaunchedEffect(vm.pendingIntent) {
        val i = vm.pendingIntent
        if (i != null) {
            val toStart = if (vm.pendingChooser) android.content.Intent.createChooser(i, chooserTitle) else i
            try {
                ctx.startActivity(toStart)
            } catch (t: Throwable) {
                vm.error = Msg(R.string.err_send, listOf(t.message ?: ""))
            }
            vm.pendingIntent = null
        }
    }

    Surface(Modifier.fillMaxSize().systemBarsPadding()) {
        when (vm.step) {
            Step.SPLASH -> SplashScreen(vm)
            Step.HOME -> HomeScreen(vm)
            Step.SCAN -> CameraScreen(vm)
            Step.CROP -> CropScreen(vm)
            Step.SIZE -> PageSizeScreen(vm)
            Step.PAGES -> PagesScreen(vm)
            Step.EXPORT -> ExportScreen(vm)
        }

        if (vm.busy) {
            Box(
                Modifier.fillMaxSize().background(Color(0x99000000)).clickable(
                    interactionSource = remember { MutableInteractionSource() }, indication = null
                ) {},
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    vm.busyMsg?.let { Text(stringResource(it.id, *it.args.toTypedArray()), color = Color.White) }
                }
            }
        }

        vm.error?.let { m ->
            AlertDialog(
                onDismissRequest = { vm.error = null },
                confirmButton = { TextButton(onClick = { vm.error = null }) { Text(stringResource(R.string.ok)) } },
                text = { Text(stringResource(m.id, *m.args.toTypedArray())) }
            )
        }

        vm.info?.let { m ->
            val folder = vm.infoFolder
            AlertDialog(
                onDismissRequest = { vm.info = null; vm.infoFolder = null },
                confirmButton = {
                    if (folder != null) {
                        TextButton(onClick = {
                            val ok = Exporter.openFolder(ctx, folder)
                            vm.info = null; vm.infoFolder = null
                            if (!ok) vm.error = Msg(R.string.err_open_folder, listOf(folder))
                        }) { Text(stringResource(R.string.open_folder)) }
                    } else {
                        TextButton(onClick = { vm.info = null }) { Text(stringResource(R.string.ok)) }
                    }
                },
                dismissButton = {
                    if (folder != null) {
                        TextButton(onClick = { vm.info = null; vm.infoFolder = null }) { Text(stringResource(R.string.close)) }
                    }
                },
                text = { Text(stringResource(m.id, *m.args.toTypedArray())) }
            )
        }
    }
}
