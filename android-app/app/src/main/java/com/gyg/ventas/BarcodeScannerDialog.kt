package com.gyg.ventas

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

@Composable
internal fun BarcodeScannerDialog(onBarcode: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var hasPermission by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var permissionDenied by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf("") }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    val scanned = remember { AtomicBoolean(false) }
    val candidateValue = remember { AtomicReference<String?>(null) }
    val candidateFrames = remember { AtomicInteger(0) }
    val candidateSince = remember { AtomicLong(0L) }
    var detectedBarcode by remember { mutableStateOf<String?>(null) }
    val currentOnBarcode by rememberUpdatedState(onBarcode)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
        permissionDenied = !granted
    }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val scanner = remember {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_ITF
            ).build()
        BarcodeScanning.getClient(options)
    }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }

    DisposableEffect(scanner, cameraExecutor) { onDispose { scanner.close(); cameraExecutor.shutdown() } }
    DisposableEffect(previewView, hasPermission) {
        val view = previewView
        var provider: ProcessCameraProvider? = null
        if (view != null && hasPermission) {
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    provider = future.get()
                    val preview = Preview.Builder().build().also { it.setSurfaceProvider(view.surfaceProvider) }
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(960, 540))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                    analysis.setAnalyzer(cameraExecutor) { proxy ->
                        val mediaImage = proxy.image
                        if (mediaImage == null) {
                            proxy.close()
                        } else {
                            val image = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
                            scanner.process(image)
                                .addOnSuccessListener { barcodes ->
                                    if (!scanned.get()) {
                                        val frameWidth = proxy.width.toFloat()
                                        val frameHeight = proxy.height.toFloat()
                                        val centerCandidate = barcodes.mapNotNull { barcode ->
                                            val value = barcode.rawValue?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                                            val bounds = barcode.boundingBox ?: return@mapNotNull null
                                            val x = bounds.exactCenterX() / frameWidth
                                            val y = bounds.exactCenterY() / frameHeight
                                            if (x !in 0.15f..0.85f || y !in 0.25f..0.75f) return@mapNotNull null
                                            val distance = kotlin.math.abs(x - 0.5f) + kotlin.math.abs(y - 0.5f)
                                            value to distance
                                        }.minByOrNull { it.second }?.first
                                        val now = System.currentTimeMillis()
                                        if (centerCandidate == null) {
                                            candidateValue.set(null)
                                            candidateFrames.set(0)
                                            candidateSince.set(0L)
                                        } else if (candidateValue.get() == centerCandidate) {
                                            val frames = candidateFrames.incrementAndGet()
                                            if (frames >= 3 && now - candidateSince.get() >= 500L && scanned.compareAndSet(false, true)) {
                                                detectedBarcode = centerCandidate
                                            }
                                        } else {
                                            candidateValue.set(centerCandidate)
                                            candidateFrames.set(1)
                                            candidateSince.set(now)
                                        }
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        }
                    }
                    provider?.unbindAll()
                    provider?.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                } catch (e: Exception) {
                    cameraError = e.localizedMessage ?: "No se pudo iniciar la cámara."
                }
            }, ContextCompat.getMainExecutor(context))
        }
        onDispose { provider?.unbindAll() }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escanear código de barras") },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (hasPermission) {
                    Box(Modifier.fillMaxWidth().height(300.dp).background(Color.Black, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                        AndroidView(
                            factory = { ctx -> PreviewView(ctx).apply { scaleType = PreviewView.ScaleType.FILL_CENTER; implementationMode = PreviewView.ImplementationMode.COMPATIBLE }.also { previewView = it } },
                            modifier = Modifier.fillMaxSize()
                        )
                        Surface(color = Color.Transparent, shape = RoundedCornerShape(12.dp), border = BorderStroke(2.dp, Color.White.copy(alpha = 0.9f)), modifier = Modifier.width(280.dp).height(150.dp)) {}
                        Text("Centra el código y mantenlo quieto un momento", color = Color.White, modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp))
                    }
                    if (detectedBarcode == null) {
                        Text("Lee más rápido. Mantén el código dentro del recuadro; podrás confirmar antes de usarlo.", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Text("Código detectado: $detectedBarcode", fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text("¿Es el código que quieres usar?", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Text(if (permissionDenied) "Se necesita permiso de cámara para leer los códigos. Actívalo y vuelve a intentarlo." else "Solicitando permiso de cámara…")
                    if (permissionDenied) TextButton(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) { Text("Permitir cámara") }
                }
                if (cameraError.isNotBlank()) Text(cameraError, color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = {
            if (detectedBarcode == null) TextButton(onClick = onDismiss) { Text("Cerrar") }
            else Button(onClick = { currentOnBarcode(detectedBarcode!!) }) { Text("Usar este código") }
        },
        dismissButton = {
            if (detectedBarcode != null) TextButton(onClick = {
                scanned.set(false)
                detectedBarcode = null
                candidateValue.set(null)
                candidateFrames.set(0)
                candidateSince.set(0L)
            }) { Text("Volver a escanear") }
        }
    )
}
