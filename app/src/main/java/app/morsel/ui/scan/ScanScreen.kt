package app.morsel.ui.scan

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.launch
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.NoPhotography
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.morsel.ui.components.PrimaryButton
import app.morsel.ui.components.RoundIconButton
import coil3.compose.AsyncImage

@Composable
fun ScanScreen(viewModel: ScanViewModel, onResult: () -> Unit) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    LaunchedEffect(Unit) { if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA) }

    val galleryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ScanViewModel.MAX_PHOTOS),
    ) { uris -> viewModel.importUris(uris) }

    val imageCapture = remember {
        ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
    }
    var showNote by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Scan", style = MaterialTheme.typography.headlineMedium)
                    Text(
                        "Plate, nutrition label or ingredients",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RoundIconButton(
                    icon = if (viewModel.torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff,
                    contentDescription = if (viewModel.torch) "Turn flash off" else "Turn flash on",
                    onClick = { viewModel.torch = !viewModel.torch },
                    size = 44.dp,
                    container = if (viewModel.torch) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                    content = if (viewModel.torch) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                )
            }

            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(Color(0xFF0D0D0F)),
            ) {
                if (hasPermission) {
                    CameraPreview(imageCapture, viewModel.torch, Modifier.fillMaxSize())
                } else {
                    NoPermission(onAllow = { permissionLauncher.launch(Manifest.permission.CAMERA) })
                }
                ScannerCorners(Modifier.fillMaxSize())
                // Shutter flash: appears the instant the button is pressed and fades out fast.
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = flash.value }
                        .background(Color.White),
                )
                if (viewModel.photos.isNotEmpty()) {
                    PhotoStrip(
                        viewModel = viewModel,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(14.dp),
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoundIconButton(
                    Icons.Rounded.PhotoLibrary,
                    "Choose from gallery",
                    {
                        galleryLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    size = 52.dp,
                )
                Shutter(
                    enabled = hasPermission && viewModel.photos.size < ScanViewModel.MAX_PHOTOS,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        scope.launch {
                            flash.snapTo(0.8f)
                            flash.animateTo(0f, tween(150, easing = FastOutLinearInEasing))
                        }
                        val file = viewModel.newCaptureFile()
                        imageCapture.takePicture(
                            ImageCapture.OutputFileOptions.Builder(file).build(),
                            ContextCompat.getMainExecutor(context),
                            object : ImageCapture.OnImageSavedCallback {
                                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                                    viewModel.addPhoto(file)
                                }

                                override fun onError(exception: ImageCaptureException) {
                                    viewModel.error = "Couldn't take the photo. ${exception.message.orEmpty()}"
                                }
                            },
                        )
                    },
                )
                RoundIconButton(
                    Icons.Rounded.Edit,
                    "Describe the meal",
                    { showNote = true },
                    size = 52.dp,
                    container = if (viewModel.note.isNotBlank()) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                    content = if (viewModel.note.isNotBlank()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            }

            AnimatedVisibility(viewModel.canAnalyze) {
                PrimaryButton(
                    text = when (viewModel.photos.size) {
                        0 -> "Analyze description"
                        1 -> "Analyze photo"
                        else -> "Analyze ${viewModel.photos.size} photos"
                    },
                    onClick = { viewModel.analyze(onResult) },
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                )
            }
        }

        AnimatedVisibility(viewModel.analyzing, enter = fadeIn(), exit = fadeOut()) {
            AnalyzingOverlay(onCancel = viewModel::cancelAnalysis)
        }
    }

    if (showNote) {
        NoteDialog(
            initial = viewModel.note,
            onDismiss = { showNote = false },
            onSave = {
                viewModel.note = it
                showNote = false
            },
            onManual = {
                showNote = false
                viewModel.manual(onResult)
            },
        )
    }

    viewModel.error?.let { message ->
        AlertDialog(
            onDismissRequest = { viewModel.error = null },
            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
            title = { Text("Couldn't analyze") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { viewModel.error = null }) { Text("OK") } },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.error = null
                    viewModel.manual(onResult)
                }) { Text("Enter manually") }
            },
        )
    }
}

@Composable
private fun CameraPreview(imageCapture: ImageCapture, torch: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var camera by remember { mutableStateOf<Camera?>(null) }

    LaunchedEffect(lifecycleOwner) {
        val provider = ProcessCameraProvider.awaitInstance(context)
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        provider.unbindAll()
        camera = runCatching<Camera> {
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
        }.getOrNull()
    }
    LaunchedEffect(camera, torch) {
        camera?.let { if (it.cameraInfo.hasFlashUnit()) it.cameraControl.enableTorch(torch) }
    }

    AndroidView(factory = { previewView }, modifier = modifier)
}

@Composable
private fun ScannerCorners(modifier: Modifier) {
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier.padding(22.dp)) {
        val len = 34.dp.toPx()
        val r = 14.dp.toPx()
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        val w = size.width
        val h = size.height
        fun corner(x: Float, y: Float, dx: Float, dy: Float) {
            val path = Path().apply {
                moveTo(x, y + dy * len)
                lineTo(x, y + dy * r)
                quadraticTo(x, y, x + dx * r, y)
                lineTo(x + dx * len, y)
            }
            drawPath(path, color, style = stroke)
        }
        corner(0f, 0f, 1f, 1f)
        corner(w, 0f, -1f, 1f)
        corner(0f, h, 1f, -1f)
        corner(w, h, -1f, -1f)
        drawCircle(color.copy(alpha = 0.5f), radius = 3.dp.toPx(), center = Offset(w / 2, h / 2))
    }
}

@Composable
private fun PhotoStrip(viewModel: ScanViewModel, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        viewModel.photos.forEach { file ->
            Box {
                AsyncImage(
                    model = file,
                    contentDescription = "Captured photo",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(14.dp)),
                )
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.7f))
                        .clickable { viewModel.removePhoto(file) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Rounded.Close, "Remove photo", Modifier.size(14.dp), tint = Color.White)
                }
            }
        }
        Text(
            "${viewModel.photos.size} of ${ScanViewModel.MAX_PHOTOS}",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.55f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

@Composable
private fun Shutter(enabled: Boolean, onClick: () -> Unit) {
    val ring = MaterialTheme.colorScheme.onSurface
    Box(
        Modifier
            .size(78.dp)
            .clip(CircleShape)
            .border(4.dp, if (enabled) ring else ring.copy(alpha = 0.3f), CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(if (enabled) ring else ring.copy(alpha = 0.3f)),
        )
    }
}

@Composable
private fun NoPermission(onAllow: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(Icons.Rounded.NoPhotography, null, Modifier.size(40.dp), tint = Color(0xFF8E8E93))
        Spacer(Modifier.height(12.dp))
        Text(
            "Platebit needs the camera to scan your meals.",
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFFF5F5F7),
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        TextButton(onClick = onAllow) { Text("Allow camera") }
    }
}

@Composable
private fun AnalyzingOverlay(onCancel: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.78f))
            .clickable(enabled = true, onClick = {}),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(44.dp))
            Spacer(Modifier.height(20.dp))
            Text("Estimating your meal…", style = MaterialTheme.typography.titleMedium, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(
                "This usually takes a few seconds",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF8E8E93),
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    }
}

@Composable
private fun NoteDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onManual: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        title = { Text("Describe the meal") },
        text = {
            Column {
                Text(
                    "Add details the photo can't show, or describe the meal without a photo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("200 g chicken, rice, cooked in 1 tbsp olive oil") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                )
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onManual) { Text("Skip AI and log manually") }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
