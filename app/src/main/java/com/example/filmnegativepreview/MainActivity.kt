package com.example.filmnegativepreview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
        
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "OpenCV initialization failed")
        }

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

@OptIn(ExperimentalCamera2Interop::class)
@Composable
fun FilmScannerApp(executor: ExecutorService) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val screenHeight = config.screenHeightDp.dp
    
    var exposure by remember { mutableStateOf(0f) }
    var temperature by remember { mutableStateOf(0.5f) }
    var isAutoTemp by remember { mutableStateOf(false) } 
    var resolutionMode by remember { mutableStateOf(ResolutionMode.HD) }
    
    // 强制探测所有物理后置摄像头
    var availableBackCameras by remember { mutableStateOf<List<CameraInfo>>(emptyList()) }
    var backCameraIndex by remember { mutableStateOf(0) }
    
    var showResolutionMenu by remember { mutableStateOf(false) }
    var isSelectorExpanded by remember { mutableStateOf(false) }
    
    var selectedStock by remember { mutableStateOf(ImageProcessor.FilmStock.COLOR) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rawBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var manualMaskColor by remember { mutableStateOf<Scalar?>(null) }
    
    val imageProcessor = remember { ImageProcessor() }

    var hasPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasPermission = it }
    
    LaunchedEffect(Unit) {
        if (!hasPermission) launcher.launch(Manifest.permission.CAMERA)
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            // 改进探测逻辑：获取具有不同物理 ID 的后置镜头
            availableBackCameras = cameraProvider.availableCameraInfos.filter { 
                it.lensFacing == CameraSelector.LENS_FACING_BACK 
            }
            Log.d("Camera", "Found ${availableBackCameras.size} back cameras")
        }, ContextCompat.getMainExecutor(context))
    }

    val currentSelector = remember(availableBackCameras, backCameraIndex) {
        if (availableBackCameras.isNotEmpty()) {
            val info = availableBackCameras[backCameraIndex]
            CameraSelector.Builder().addCameraFilter { it.filter { i -> i == info } }.build()
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }
    }

    if (hasPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            rawBitmap?.let { bitmap ->
                                try {
                                    val x = (offset.x / size.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                                    val y = (offset.y / size.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                                    val pixel = bitmap.getPixel(x, y)
                                    manualMaskColor = Scalar(
                                        android.graphics.Color.red(pixel).toDouble(),
                                        android.graphics.Color.green(pixel).toDouble(),
                                        android.graphics.Color.blue(pixel).toDouble()
                                    )
                                } catch (e: Exception) { Log.e("Sample", "Sampling failed", e) }
                            }
                        }
                    )
                }
        ) {
            processedBitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } ?: Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
            }

            CameraFrameHandler(
                executor = executor,
                exposure = exposure,
                temperature = temperature,
                isAutoTemp = isAutoTemp,
                stock = selectedStock,
                resolutionMode = resolutionMode,
                cameraSelector = currentSelector,
                manualMaskColor = manualMaskColor,
                imageProcessor = imageProcessor,
                onFrameProcessed = { processed, raw, autoT ->
                    if (isAutoTemp) temperature = autoT 
                    processedBitmap = processed
                    rawBitmap = raw
                }
            )

            // 顶部工具栏
            Row(
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 40.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 分辨率切换
                Box {
                    Button(
                        onClick = { showResolutionMenu = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(20.dp)
                    ) {
                        Icon(Icons.Default.Settings, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(resolutionMode.displayName, fontSize = 12.sp)
                    }
                    DropdownMenu(
                        expanded = showResolutionMenu,
                        onDismissRequest = { showResolutionMenu = false },
                        modifier = Modifier.background(Color.DarkGray)
                    ) {
                        ResolutionMode.values().forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.displayName, color = Color.White) },
                                onClick = { resolutionMode = mode; showResolutionMenu = false }
                            )
                        }
                    }
                }
                
                // 切换后置镜头
                IconButton(
                    onClick = { 
                        if (availableBackCameras.size > 1) {
                            backCameraIndex = (backCameraIndex + 1) % availableBackCameras.size
                            val cameraId = Camera2CameraInfo.from(availableBackCameras[backCameraIndex]).cameraId
                            Toast.makeText(context, "切换至镜头 ID: $cameraId", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "未探测到多颗物理后置镜头", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.size(40.dp).background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Cameraswitch, "Switch", tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }

            val sliderHeight = screenHeight * 0.5f

            // 左侧 (EV)
            Box(modifier = Modifier.fillMaxHeight().width(100.dp).align(Alignment.CenterStart).padding(start = 12.dp), contentAlignment = Alignment.Center) {
                VerticalControlBar(value = exposure, onValueChange = { exposure = it }, range = -3f..3f, label = "EV", valueDisplay = String.format("%+.1f", exposure), height = sliderHeight)
            }

            // 右侧 (Kelvin)
            Box(modifier = Modifier.fillMaxHeight().width(100.dp).align(Alignment.CenterEnd).padding(end = 12.dp)) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val kelvinValue = 10000 - (temperature * 8000).toInt()
                    VerticalControlBar(value = temperature, onValueChange = { if (!isAutoTemp) temperature = it }, range = 0f..1f, label = "TEMP", valueDisplay = "${kelvinValue}K", height = sliderHeight, enabled = !isAutoTemp)
                }
                Column(modifier = Modifier.align(Alignment.Center).offset(y = (sliderHeight / 2) + 110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    IconButton(onClick = { isAutoTemp = !isAutoTemp }, modifier = Modifier.size(48.dp).background(if (isAutoTemp) Color.White else Color.Black.copy(alpha = 0.5f), CircleShape).border(1.dp, Color.White, CircleShape)) {
                        Icon(Icons.Default.Refresh, null, tint = if (isAutoTemp) Color.Black else Color.White, modifier = Modifier.size(24.dp))
                    }
                    Text("AUTO", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }

            // 底部模式选择
            Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(if (isSelectorExpanded) Color.Black.copy(alpha = 0.8f) else Color.Transparent)) {
                Column {
                    Box(modifier = Modifier.fillMaxWidth().clickable { isSelectorExpanded = !isSelectorExpanded }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        Icon(imageVector = if (isSelectorExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp, contentDescription = null, tint = Color.White.copy(alpha = if (isSelectorExpanded) 0.5f else 0.2f), modifier = Modifier.size(20.dp))
                    }
                    AnimatedVisibility(visible = isSelectorExpanded, enter = expandVertically(), exit = shrinkVertically()) {
                        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 30.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ImageProcessor.FilmStock.values().forEach { stock ->
                                Box(modifier = Modifier.weight(1f).clickable { selectedStock = stock }.background(if (selectedStock == stock) Color.White else Color.Transparent, RoundedCornerShape(4.dp)).border(1.2.dp, Color.White, RoundedCornerShape(4.dp)).padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                    Text(text = stock.displayName.uppercase(), color = if (selectedStock == stock) Color.Black else Color.White, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp, maxLines = 1)
                                }
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
    executor: ExecutorService,
    exposure: Float,
    temperature: Float,
    isAutoTemp: Boolean,
    stock: ImageProcessor.FilmStock,
    resolutionMode: ResolutionMode,
    cameraSelector: CameraSelector,
    manualMaskColor: Scalar?,
    imageProcessor: ImageProcessor,
    onFrameProcessed: (Bitmap, Bitmap, Float) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentIsAutoTemp by rememberUpdatedState(isAutoTemp)
    val currentExposure by rememberUpdatedState(exposure)
    val currentTemperature by rememberUpdatedState(temperature)
    val currentStock by rememberUpdatedState(stock)
    val currentMaskColor by rememberUpdatedState(manualMaskColor)
    val currentOnFrameProcessed by rememberUpdatedState(onFrameProcessed)
    val dummySurfaceTexture = remember { SurfaceTexture(0).apply { detachFromGLContext() } }

    LaunchedEffect(resolutionMode, cameraSelector) {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val resSelector = if (resolutionMode.targetSize != null) {
                ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(resolutionMode.targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER)).build()
            } else {
                ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
            }
            
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider { request ->
                dummySurfaceTexture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
                val surface = Surface(dummySurfaceTexture)
                request.provideSurface(surface, executor) { surface.release() }
            }

            val imageAnalysis = ImageAnalysis.Builder()
                .setResolutionSelector(resSelector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()

            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                try {
                    val rotation = imageProxy.imageInfo.rotationDegrees
                    val bitmap = imageProxy.toBitmap()
                    val finalTemp = if (currentIsAutoTemp) imageProcessor.estimateTemperature(bitmap) else currentTemperature
                    val result = imageProcessor.processFrame(bitmap, currentExposure, finalTemp, currentStock, rotation, currentMaskColor)
                    currentOnFrameProcessed(result, bitmap, finalTemp)
                } catch (e: Exception) { Log.e("Analyzer", "Error", e) } finally { imageProxy.close() }
            }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview, imageAnalysis)
            } catch (e: Exception) { Log.e("Camera", "Binding failed", e) }
        }, ContextCompat.getMainExecutor(context))
    }
}

@Composable
fun VerticalControlBar(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, label: String, valueDisplay: String, height: androidx.compose.ui.unit.Dp, enabled: Boolean = true) {
    Column(modifier = Modifier.width(80.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = valueDisplay, color = if (enabled) Color.White else Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(text = label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(modifier = Modifier.height(20.dp))
        Box(modifier = Modifier.height(height).width(80.dp), Alignment.Center) {
            Slider(value = value, onValueChange = onValueChange, valueRange = range, enabled = enabled, modifier = Modifier.requiredWidth(height).graphicsLayer { rotationZ = 270f }, colors = SliderDefaults.colors(thumbColor = if (enabled) Color.White else Color.Transparent, activeTrackColor = if (enabled) Color.White else Color.Gray.copy(alpha = 0.3f), inactiveTrackColor = Color.White.copy(alpha = 0.1f)))
        }
    }
}
