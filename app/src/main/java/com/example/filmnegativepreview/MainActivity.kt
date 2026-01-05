package com.example.filmnegativepreview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.camera2.CaptureRequest
import android.os.Bundle
import android.util.Log
import android.util.Range
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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

// 修复分辨率定义：CameraX 内部通常使用横向尺寸进行匹配
enum class ResolutionMode(val displayName: String, val targetSize: Size?) {
    SD("SD (480p)", Size(640, 480)),
    HD("HD (720p)", Size(1280, 720)),
    FHD("FHD (1080p)", Size(1920, 1080)),
    UHD("4K (2160p)", Size(3840, 2160)), // 修正为标准的 4K 横向尺寸
    MAX("Native Max", null)
}

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "OpenCV initialization failed")
        }

        // 使用多线程处理高分辨率图像
        cameraExecutor = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors())
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

@Composable
fun FilmScannerApp(executor: ExecutorService) {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    val screenHeight = config.screenHeightDp.dp
    
    var exposure by remember { mutableStateOf(0f) }
    var temperature by remember { mutableStateOf(0.5f) }
    var isAutoTemp by remember { mutableStateOf(false) } 
    var resolutionMode by remember { mutableStateOf(ResolutionMode.FHD) }
    
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
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        availableBackCameras = cameraProvider.availableCameraInfos.filter { 
            it.lensFacing == CameraSelector.LENS_FACING_BACK 
        }
    }

    val currentSelector = remember(availableBackCameras, backCameraIndex) {
        availableBackCameras.getOrNull(backCameraIndex)?.let { info ->
            CameraSelector.Builder()
                .addCameraFilter { cameraInfos -> cameraInfos.filter { it == info } }
                .build()
        } ?: CameraSelector.DEFAULT_BACK_CAMERA
    }

    if (hasPermission) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onLongPress = { offset ->
                            rawBitmap?.let { bitmap ->
                                val x = (offset.x / size.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                                val y = (offset.y / size.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                                val pixel = bitmap.getPixel(x, y)
                                manualMaskColor = Scalar(
                                    android.graphics.Color.red(pixel).toDouble(),
                                    android.graphics.Color.green(pixel).toDouble(),
                                    android.graphics.Color.blue(pixel).toDouble()
                                )
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
                                onClick = {
                                    resolutionMode = mode
                                    showResolutionMenu = false
                                }
                            )
                        }
                    }
                }
                
                if (manualMaskColor != null) {
                    IconButton(
                        onClick = { manualMaskColor = null },
                        modifier = Modifier.background(Color.Red.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.Refresh, "Reset Mask", tint = Color.White)
                    }
                }

                if (availableBackCameras.size > 1) {
                    IconButton(
                        onClick = { backCameraIndex = (backCameraIndex + 1) % availableBackCameras.size },
                        modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), CircleShape)
                    ) {
                        Icon(Icons.Default.Cameraswitch, null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // 左右控制条：固定在两侧并垂直居中，高度统一为 50%
            val sliderHeight = screenHeight * 0.5f

            // 左侧控制区 (EV)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(100.dp)
                    .align(Alignment.CenterStart)
                    .padding(start = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                VerticalControlBar(
                    value = exposure,
                    onValueChange = { exposure = it },
                    range = -3f..3f,
                    label = "EV",
                    valueDisplay = String.format("%+.1f", exposure),
                    height = sliderHeight
                )
            }

            // 右侧控制区 (Kelvin)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(100.dp)
                    .align(Alignment.CenterEnd)
                    .padding(end = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                val kelvinValue = 10000 - (temperature * 8000).toInt()
                VerticalControlBar(
                    value = temperature,
                    onValueChange = { if (!isAutoTemp) temperature = it },
                    range = 0f..1f,
                    label = "TEMP",
                    valueDisplay = "${kelvinValue}K",
                    height = sliderHeight,
                    enabled = !isAutoTemp
                )
                
                // AUTO 按钮绝对偏移到滑块下方
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (sliderHeight / 2) + 110.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    IconButton(
                        onClick = { isAutoTemp = !isAutoTemp },
                        modifier = Modifier
                            .size(48.dp)
                            .background(if (isAutoTemp) Color.White else Color.Black.copy(alpha = 0.5f), CircleShape)
                            .border(1.dp, Color.White, CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Refresh, 
                            null, 
                            tint = if (isAutoTemp) Color.Black else Color.White, 
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    Text("AUTO", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Black)
                }
            }

            // 底部模式选择
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(if (isSelectorExpanded) Color.Black.copy(alpha = 0.8f) else Color.Transparent)
            ) {
                Column {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { isSelectorExpanded = !isSelectorExpanded }
                            .padding(vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isSelectorExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                            contentDescription = null,
                            tint = Color.White.copy(alpha = if (isSelectorExpanded) 0.5f else 0.2f),
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    AnimatedVisibility(
                        visible = isSelectorExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, bottom = 30.dp, top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            ImageProcessor.FilmStock.values().forEach { stock ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { selectedStock = stock }
                                        .background(
                                            if (selectedStock == stock) Color.White else Color.Transparent,
                                            RoundedCornerShape(4.dp)
                                        )
                                        .border(1.2.dp, Color.White, RoundedCornerShape(4.dp))
                                        .padding(vertical = 12.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = stock.displayName.uppercase(),
                                        color = if (selectedStock == stock) Color.Black else Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Black,
                                        letterSpacing = 0.5.sp,
                                        maxLines = 1
                                    )
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

    LaunchedEffect(resolutionMode, cameraSelector) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        
        // 修复 4K 模式：CameraX 期望的分辨率通常是传感器坐标系的（横向）
        // 且使用 FALLBACK_RULE_CLOSEST_LOWER 更加稳健，防止因为请求过大而导致绑定失败
        val resSelector = if (resolutionMode.targetSize != null) {
            ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy(
                    resolutionMode.targetSize, 
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER
                ))
                .build()
        } else {
            ResolutionSelector.Builder()
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .build()
        }
        
        val analysisBuilder = ImageAnalysis.Builder()
            .setResolutionSelector(resSelector)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)

        Camera2Interop.Extender(analysisBuilder).setCaptureRequestOption(
            CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
            Range(30, 60) // 允许在 30-60 之间波动，增加 4K 下的稳定性
        )

        val imageAnalysis = analysisBuilder.build()
        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            val rotation = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toBitmap()
            val finalTemp = if (currentIsAutoTemp) imageProcessor.estimateTemperature(bitmap) else currentTemperature
            val result = imageProcessor.processFrame(bitmap, currentExposure, finalTemp, currentStock, rotation, currentMaskColor)
            currentOnFrameProcessed(result, bitmap, finalTemp)
            imageProxy.close()
        }
        
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        } catch (e: Exception) { 
            Log.e("Camera", "Binding failed for ${resolutionMode.displayName}", e)
        }
    }
}

@Composable
fun VerticalControlBar(
    value: Float,
    onValueChange: (Float) -> Unit,
    range: ClosedFloatingPointRange<Float>,
    label: String,
    valueDisplay: String,
    height: androidx.compose.ui.unit.Dp,
    enabled: Boolean = true
) {
    Column(
        modifier = Modifier.width(80.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text = valueDisplay, color = if (enabled) Color.White else Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Black, maxLines = 1)
        Text(text = label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Spacer(modifier = Modifier.height(20.dp))
        Box(modifier = Modifier.height(height).width(80.dp), Alignment.Center) {
            Slider(
                value = value,
                onValueChange = onValueChange,
                valueRange = range,
                enabled = enabled,
                modifier = Modifier
                    .requiredWidth(height)
                    .graphicsLayer { rotationZ = 270f },
                colors = SliderDefaults.colors(
                    thumbColor = if (enabled) Color.White else Color.Transparent,
                    activeTrackColor = if (enabled) Color.White else Color.Gray.copy(alpha = 0.3f),
                    inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                )
            )
        }
    }
}
