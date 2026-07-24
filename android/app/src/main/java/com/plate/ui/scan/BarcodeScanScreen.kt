package com.plate.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.plate.data.remote.FoodDetailOut
import com.plate.ui.diary.DiaryViewModel
import com.plate.ui.food.AddFoodDialog
import com.plate.util.UiState
import java.util.concurrent.Executors

/**
 * Barcode scanning screen (Phase 4). Requests the camera permission, streams frames through ML Kit,
 * and on a resolved product reuses the search screen's [AddFoodDialog] so the user picks quantity +
 * meal before it's logged. Local cache → Open Food Facts resolution happens server-side.
 */
@Composable
fun BarcodeScanScreen(
    onLogged: () -> Unit,
    onBack: () -> Unit,
    scanViewModel: BarcodeScanViewModel = hiltViewModel(),
    diaryViewModel: DiaryViewModel = hiltViewModel(),
) {
    val state by scanViewModel.state.collectAsState()
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> hasPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    val unitSystem by scanViewModel.unitSystem.collectAsState()
    (state as? UiState.Success<FoodDetailOut>)?.let { success ->
        AddFoodDialog(
            food = success.data.toFoodOut(),
            portions = success.data.portions,
            unitSystem = unitSystem,
            onDismiss = { scanViewModel.reset() },
            onConfirm = { meal, args ->
                diaryViewModel.addEntry(success.data.id, meal, args.quantity, args.unit, args.portionId)
                onLogged()
            },
        )
    }

    BarcodeScanContent(
        hasPermission = hasPermission,
        state = state,
        onBack = onBack,
        onBarcode = scanViewModel::onBarcodeScanned,
        onRetry = scanViewModel::reset,
        onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BarcodeScanContent(
    hasPermission: Boolean,
    state: UiState<FoodDetailOut>,
    onBack: () -> Unit,
    onBarcode: (String) -> Unit,
    onRetry: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan barcode") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            if (!hasPermission) {
                PermissionRationale(onRequestPermission = onRequestPermission)
            } else {
                CameraPreview(onBarcode = onBarcode, modifier = Modifier.fillMaxSize())
                ScanStatusBar(
                    state = state,
                    onRetry = onRetry,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        }
    }
}

/** Live camera preview wired to the ML Kit [BarcodeAnalyzer]. Not exercised by JVM screenshots. */
@Composable
private fun CameraPreview(onBarcode: (String) -> Unit, modifier: Modifier = Modifier) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            val previewView = PreviewView(ctx)
            val providerFuture = ProcessCameraProvider.getInstance(ctx)
            providerFuture.addListener({
                val provider = providerFuture.get()
                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(analysisExecutor, BarcodeAnalyzer(onBarcode)) }
                try {
                    provider.unbindAll()
                    provider.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        analysis,
                    )
                } catch (_: Exception) {
                    // Camera unavailable (e.g. no back camera) — the status bar still guides the user.
                }
            }, ContextCompat.getMainExecutor(ctx))
            previewView
        },
    )
}

/** Stateless status overlay: scanning hint, in-flight spinner, or a retryable lookup error. */
@Composable
internal fun ScanStatusBar(
    state: UiState<FoodDetailOut>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.medium,
        tonalElevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state) {
                is UiState.Loading -> {
                    CircularProgressIndicator()
                    Text("Looking up product…", style = MaterialTheme.typography.bodyMedium)
                }
                is UiState.Error -> {
                    Text(
                        state.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onRetry) { Text("Scan again") }
                }
                else -> Text(
                    "Point the camera at a product barcode",
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** Shown when the camera permission has not been granted. */
@Composable
internal fun PermissionRationale(onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            "Camera access needed",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            "Plate uses the camera to scan product barcodes. Nothing is recorded — frames are " +
                "analyzed on-device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(vertical = 12.dp),
        )
        Button(onClick = onRequestPermission) { Text("Grant camera access") }
    }
}
