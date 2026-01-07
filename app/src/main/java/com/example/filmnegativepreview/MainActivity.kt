package com.example.filmnegativepreview

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.filmnegativepreview.ui.theme.FilmNegativePreviewTheme
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.Point
import org.opencv.core.Scalar
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class ResolutionMode(val displayName: String, val targetSize: Size?) {
    SD("SD (480p)", Size(640, 480)),
    HD("HD (720p)", Size(1280, 720)),
    FHD("FHD (1080p)", Size(1920, 1080)),
    UHD("4K (2160p)", Size(3840, 2160)),
    MAX("Native Max", null)
}

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!OpenCVLoader.initDebug()) Log.e("OpenCV", "Init failed")
        cameraExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors().coerceAtLeast(4))
        setContent {
            FilmNegativePreviewTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = Color.Black) {
                    FilmScannerApp(cameraExecutor)
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}

@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun FilmScannerApp(executor: ExecutorService) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val screenHeight = config.screenHeightDp.dp
    val screenWidth = config.screenWidthDp.dp
    
    // 状态控制
    var exposure by remember { mutableStateOf(0f) }
    var temperature by remember { mutableStateOf(0.5f) }
    var zoom by remember { mutableStateOf(0.1f) } 
    var focus by remember { mutableStateOf(1f) }
    
    var isAutoTemp by remember { mutableStateOf(false) } 
    var isAutoFocus by remember { mutableStateOf(true) }
    var isAELocked by remember { mutableStateOf(false) } 
    var resolutionMode by remember { mutableStateOf(ResolutionMode.FHD) }
    var availableBackCameras by remember { mutableStateOf<List<CameraInfo>>(emptyList()) }
    var backCameraIndex by remember { mutableStateOf(0) }
    var isSelectorExpanded by remember { mutableStateOf(false) }
    var showResolutionMenu by remember { mutableStateOf(false) }
    var showZoomSlider by remember { mutableStateOf(false) }
    var selectedStock by remember { mutableStateOf(ImageProcessor.FilmStock.COLOR) }
    
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rawBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var lastCapturedThumbnail by remember { mutableStateOf<Bitmap?>(null) }
    var lastCapturedUri by remember { mutableStateOf<Uri?>(null) }
    var manualMaskColor by remember { mutableStateOf<Scalar?>(null) }
    
    // 核心优化：ROI 定位与平滑
    var detectedPoints by remember { mutableStateOf<Array<Point>?>(null) }
    var frameCount by remember { mutableStateOf(0) }
    
    val imageProcessor = remember { ImageProcessor() }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    
    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            launcher.launch(Manifest.permission.CAMERA)
        }
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        availableBackCameras = cameraProvider.availableCameraInfos.filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
    }

    val currentSelector = remember(availableBackCameras, backCameraIndex) {
        if (availableBackCameras.isNotEmpty()) {
            val info = availableBackCameras[backCameraIndex]
            CameraSelector.Builder().addCameraFilter { cameraInfos -> cameraInfos.filter { i -> i == info } }.build()
        } else CameraSelector.DEFAULT_BACK_CAMERA
    }

    LaunchedEffect(zoom, focus, isAutoFocus, isAELocked, cameraControl) {
        cameraControl?.let { control ->
            val control2 = Camera2CameraControl.from(control)
            val builder = CaptureRequestOptions.Builder()
            if (isAutoFocus) {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            } else {
                builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                builder.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus * 10f)
            }
            builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_LOCK, isAELocked)
            control2.captureRequestOptions = builder.build()
            control.setLinearZoom(zoom)
        }
    }

    Box(modifier = Modifier.fillMaxSize().pointerInput(Unit) {
        detectTapGestures(onLongPress = { offset ->
            rawBitmap?.let { bitmap ->
                val x = (offset.x / size.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                val y = (offset.y / size.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y)
                manualMaskColor = Scalar(android.graphics.Color.red(pixel).toDouble(), android.graphics.Color.green(pixel).toDouble(), android.graphics.Color.blue(pixel).toDouble())
            }
        })
    }) {
        processedBitmap?.let {
            Image(bitmap = it.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }

        CameraFrameHandler(
            executor = executor, exposure = exposure, temperature = temperature, isAutoTemp = isAutoTemp,
            stock = selectedStock, resolutionMode = resolutionMode, cameraSelector = currentSelector,
            manualMaskColor = manualMaskColor, imageProcessor = imageProcessor, isAELocked = isAELocked,
            detectedPoints = detectedPoints,
            onFrameProcessed = { processed, raw, autoT, newPoints ->
                if (isAutoTemp) temperature = autoT 
                processedBitmap = processed
                rawBitmap = raw
                
                // 优化策略：每 8 帧重新扫描一次，或在丢失目标时立即扫描
                frameCount++
                if (frameCount % 8 == 0 || detectedPoints == null) {
                    detectedPoints = newPoints
                }
            },
            onCameraControlReady = { cameraControl = it }
        )

        // 顶部工具栏
        Row(modifier = Modifier.align(Alignment.TopCenter).padding(top = 44.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box {
                Button(onClick = { showResolutionMenu = true }, colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.5f)), contentPadding = PaddingValues(horizontal = 8.dp)) {
                    Text(resolutionMode.displayName, fontSize = 10.sp)
                }
                DropdownMenu(expanded = showResolutionMenu, onDismissRequest = { showResolutionMenu = false }) {
                    ResolutionMode.values().forEach { mode ->
                        DropdownMenuItem(text = { Text(mode.displayName) }, onClick = { resolutionMode = mode; showResolutionMenu = false })
                    }
                }
            }
            IconButton(onClick = { isAutoFocus = !isAutoFocus }, modifier = Modifier.background(if (isAutoFocus) Color.White else Color.Black.copy(alpha = 0.5f), CircleShape)) {
                Text("AF", color = if (isAutoFocus) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            IconButton(onClick = { isAutoTemp = !isAutoTemp }, modifier = Modifier.background(if (isAutoTemp) Color.White else Color.Black.copy(alpha = 0.5f), CircleShape)) {
                Text("AWB", color = if (isAutoTemp) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 10.sp)
            }
            IconButton(onClick = { isAELocked = !isAELocked }, modifier = Modifier.background(if (isAELocked) Color.White else Color.Black.copy(alpha = 0.5f), CircleShape)) {
                Text("AE", color = if (isAELocked) Color.Black else Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
        }

        // 左右控制区
        val sliderHeight = screenHeight * 0.2f
        Column(modifier = Modifier.align(Alignment.CenterStart).padding(start = 16.dp).fillMaxHeight(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            VerticalControlBar(exposure, { exposure = it }, -3f..3f, "EV", String.format("%+.1f", exposure), sliderHeight)
            Spacer(modifier = Modifier.height(config.screenHeightDp.dp * 0.05f))
            VerticalControlBar(focus, { if (!isAutoFocus) focus = it }, 0f..1f, "FOCUS", if (isAutoFocus) "AF-C" else "MF", sliderHeight, enabled = !isAutoFocus)
        }
        Box(modifier = Modifier.align(Alignment.CenterEnd).padding(end = 16.dp)) {
            VerticalControlBar(temperature, { if (!isAutoTemp) temperature = it }, 0f..1f, "TEMP", if (isAutoTemp) "AUTO" else "${10000 - (temperature * 8000).toInt()}K", screenHeight * 0.45f, enabled = !isAutoTemp)
        }

        // 变焦控制
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 180.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                AnimatedVisibility(visible = showZoomSlider) {
                    Slider(value = zoom, onValueChange = { zoom = it }, modifier = Modifier.width(config.screenWidthDp.dp * 0.6f).padding(bottom = 16.dp), colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = Color.White))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    listOf(0.0f to "0.6x", 0.1f to "1x", 0.3f to "3x").forEach { (valZoom, label) ->
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape).background(if (zoom == valZoom) Color.White else Color.Black.copy(alpha = 0.5f)).border(1.dp, Color.White, CircleShape).clickable { zoom = valZoom; showZoomSlider = !showZoomSlider },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(label, color = if (zoom == valZoom) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }

        // 底部快门
        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .offset(x = (-100).dp)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color.DarkGray)
                    .border(1.dp, Color.White.copy(alpha = 0.5f), CircleShape)
                    .clickable { lastCapturedUri?.let { viewFullImage(context, it) } ?: openFilmGallery(context) },
                contentAlignment = Alignment.Center
            ) {
                if (lastCapturedThumbnail != null) {
                    Image(bitmap = lastCapturedThumbnail!!.asImageBitmap(), contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Default.PhotoLibrary, null, tint = Color.White.copy(alpha = 0.5f))
                }
            }
            Box(
                modifier = Modifier.size(84.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.2f)).border(4.dp, Color.White, CircleShape).clickable { 
                    processedBitmap?.let { 
                        // 拍照时执行物理裁剪
                        val finalPhoto = imageProcessor.processFrame(rawBitmap!!, exposure, temperature, selectedStock, 0, manualMaskColor, detectedPoints, true)
                        saveBitmapToGallery(context, finalPhoto) { uri ->
                            lastCapturedThumbnail = finalPhoto
                            lastCapturedUri = uri
                        }
                    } 
                }.padding(8.dp).clip(CircleShape).background(Color.White)
            )
        }

        // 底部折叠切换
        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(if (isSelectorExpanded) Color.Black.copy(alpha = 0.8f) else Color.Transparent)) {
            Column {
                Box(modifier = Modifier.fillMaxWidth().clickable { isSelectorExpanded = !isSelectorExpanded }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Icon(if (isSelectorExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, null, tint = Color.White.copy(alpha = 0.4f))
                }
                AnimatedVisibility(visible = isSelectorExpanded) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ImageProcessor.FilmStock.values().forEach { stock ->
                            Button(onClick = { selectedStock = stock }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = if (selectedStock == stock) Color.White else Color.DarkGray)) {
                                Text(stock.displayName, color = if (selectedStock == stock) Color.Black else Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun CameraFrameHandler(
    executor: ExecutorService, exposure: Float, temperature: Float, isAutoTemp: Boolean,
    stock: ImageProcessor.FilmStock, resolutionMode: ResolutionMode, cameraSelector: CameraSelector,
    manualMaskColor: Scalar?, imageProcessor: ImageProcessor, isAELocked: Boolean,
    detectedPoints: Array<Point>?,
    onFrameProcessed: (Bitmap, Bitmap, Float, Array<Point>?) -> Unit,
    onCameraControlReady: (CameraControl) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val dummySurfaceTexture = remember { SurfaceTexture(0).apply { detachFromGLContext() } }
    val currentIsAutoTemp by rememberUpdatedState(isAutoTemp)
    val currentExposure by rememberUpdatedState(exposure)
    val currentTemperature by rememberUpdatedState(temperature)
    val currentStock by rememberUpdatedState(stock)
    val currentMaskColor by rememberUpdatedState(manualMaskColor)
    val currentPoints by rememberUpdatedState(detectedPoints)

    LaunchedEffect(resolutionMode, cameraSelector) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(resolutionMode.targetSize ?: Size(1920, 1080), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER)).build())
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
            .build()

        analysis.setAnalyzer(executor) { imageProxy ->
            val mat = Mat()
            val bitmap = imageProxy.toBitmap()
            Utils.bitmapToMat(bitmap, mat)
            
            // 优化策略：尝试寻找新轮廓，如果失败则使用旧的
            val newPoints = imageProcessor.findFilmContour(mat) ?: currentPoints
            
            val finalTemp = if (currentIsAutoTemp) imageProcessor.estimateTemperature(bitmap) else currentTemperature
            val result = imageProcessor.processFrame(bitmap, currentExposure, finalTemp, currentStock, imageProxy.imageInfo.rotationDegrees, currentMaskColor, newPoints, false)
            
            onFrameProcessed(result, bitmap, finalTemp, newPoints)
            mat.release()
            imageProxy.close()
        }

        val preview = Preview.Builder().build()
        preview.setSurfaceProvider { request ->
            val surface = Surface(dummySurfaceTexture.apply { setDefaultBufferSize(request.resolution.width, request.resolution.height) })
            request.provideSurface(surface, executor) { surface.release() }
        }

        try {
            cameraProvider.unbindAll()
            val camera = cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, analysis)
            onCameraControlReady(camera.cameraControl)
        } catch (e: Exception) { Log.e("Camera", "Bind failed", e) }
    }
}

@Composable
fun VerticalControlBar(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, label: String, valueDisplay: String, height: androidx.compose.ui.unit.Dp, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Column(modifier = modifier.width(64.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(valueDisplay, color = if (enabled) Color.White else Color.Gray, fontSize = 12.sp, fontWeight = FontWeight.Black)
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 8.sp)
        Box(modifier = Modifier.height(height).width(40.dp), Alignment.Center) {
            Slider(value = value, onValueChange = onValueChange, valueRange = range, enabled = enabled, modifier = Modifier.requiredWidth(height).graphicsLayer { rotationZ = 270f }, colors = SliderDefaults.colors(thumbColor = if (enabled) Color.White else Color.Transparent, activeTrackColor = if (enabled) Color.White else Color.Gray.copy(alpha = 0.3f), inactiveTrackColor = Color.White.copy(alpha = 0.1f)))
        }
    }
}

fun saveBitmapToGallery(context: Context, bitmap: Bitmap, onSaved: (Uri) -> Unit) {
    val filename = "Film_${System.currentTimeMillis()}.jpg"
    val contentValues = ContentValues().apply {
        put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
        put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/FilmScanner")
        }
    }
    val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
    uri?.let {
        context.contentResolver.openOutputStream(it)?.use { stream ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
            Toast.makeText(context, "底片已校正并保存", Toast.LENGTH_SHORT).show()
            onSaved(it)
        }
    }
}

fun viewFullImage(context: Context, uri: Uri) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "image/*")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
    }
    context.startActivity(intent)
}

fun openFilmGallery(context: Context) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*")
        flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    context.startActivity(intent)
}
