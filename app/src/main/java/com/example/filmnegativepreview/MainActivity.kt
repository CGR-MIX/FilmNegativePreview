package com.example.filmnegativepreview

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
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
    SD("SD (480p)", Size(480, 640)),
    HD("HD (720p)", Size(720, 1280)),
    FHD("FHD (1080p)", Size(1080, 1920)),
    UHD("4K (2160p)", Size(2160, 3840)),
    MAX("Native Max", null)
}

class MainActivity : ComponentActivity() {
    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        if (!OpenCVLoader.initDebug()) {
            Log.e("OpenCV", "OpenCV initialization failed")
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
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
    var selectedStock by remember { mutableStateOf(ImageProcessor.FilmStock.KODAK_PORTRA) }
    var processedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var rawBitmap by remember { mutableStateOf<Bitmap?>(null) } // 存储原始帧用于颜色采样
    
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
                            // 手动采样色罩颜色
                            rawBitmap?.let { bitmap ->
                                val x = (offset.x / size.width * bitmap.width).toInt().coerceIn(0, bitmap.width - 1)
                                val y = (offset.y / size.height * bitmap.height).toInt().coerceIn(0, bitmap.height - 1)
                                val pixel = bitmap.getPixel(x, y)
                                manualMaskColor = Scalar(
                                    android.graphics.Color.red(pixel).toDouble(),
                                    android.graphics.Color.green(pixel).toDouble(),
                                    android.graphics.Color.blue(pixel).toDouble()
                                )
                                Log.d("MaskSample", "Sampled color: $manualMaskColor")
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
                Button(
                    onClick = { showResolutionMenu = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Icon(Icons.Default.Settings, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(resolutionMode.displayName, fontSize = 12.sp)
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

            VerticalControlBar(
                value = exposure,
                onValueChange = { exposure = it },
                range = -3f..3f,
                label = "EV",
                valueDisplay = String.format("%+.1f", exposure),
                modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp),
                height = screenHeight * 0.6f
            )

            Column(
                modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                IconButton(
                    onClick = { isAutoTemp = !isAutoTemp },
                    modifier = Modifier.size(44.dp).background(if (isAutoTemp) Color.White else Color.Transparent, CircleShape).border(1.dp, Color.White, CircleShape)
                ) {
                    Icon(Icons.Default.Refresh, null, tint = if (isAutoTemp) Color.Black else Color.White)
                }
                Text("AUTO", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Black)
                Spacer(modifier = Modifier.height(20.dp))
                val kelvinValue = 10000 - (temperature * 8000).toInt()
                VerticalControlBar(
                    value = temperature,
                    onValueChange = { if (!isAutoTemp) temperature = it },
                    range = 0f..1f,
                    label = "TEMP",
                    valueDisplay = "${kelvinValue}K",
                    height = screenHeight * 0.5f,
                    enabled = !isAutoTemp
                )
            }

            // 底部底片选择
            Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color.Black.copy(alpha = 0.8f)).padding(bottom = 34.dp, top = 20.dp)) {
                Column {
                    Text("FILM STOCK", color = Color.White.copy(alpha = 0.5f), fontSize = 9.sp, letterSpacing = 3.sp, modifier = Modifier.padding(start = 24.dp, bottom = 12.dp), fontWeight = FontWeight.Black)
                    LazyRow(contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(ImageProcessor.FilmStock.values()) { stock ->
                            FilmChip(name = stock.displayName, isSelected = selectedStock == stock, onClick = { selectedStock = stock })
                        }
                    }
                }
            }
        }
    }
}

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
    val currentParams by rememberUpdatedState(Triple(exposure, temperature, stock))
    val currentMaskColor by rememberUpdatedState(manualMaskColor)

    LaunchedEffect(resolutionMode, cameraSelector) {
        val cameraProvider = ProcessCameraProvider.getInstance(context).get()
        val resSelector = if (resolutionMode.targetSize != null) {
            ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy(resolutionMode.targetSize, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER)).build()
        } else {
            ResolutionSelector.Builder().setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build()
        }
        val imageAnalysis = ImageAnalysis.Builder().setResolutionSelector(resSelector).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888).build()
        imageAnalysis.setAnalyzer(executor) { imageProxy ->
            val rotation = imageProxy.imageInfo.rotationDegrees
            val bitmap = imageProxy.toBitmap()
            val (exp, temp, stk) = currentParams
            val finalTemp = if (isAutoTemp) imageProcessor.estimateTemperature(bitmap) else temp
            val result = imageProcessor.processFrame(bitmap, exp, finalTemp, stk, rotation, currentMaskColor)
            onFrameProcessed(result, bitmap, finalTemp)
            imageProxy.close()
        }
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, imageAnalysis)
        } catch (e: Exception) { Log.e("Camera", "Failed", e) }
    }
}

@Composable
fun VerticalControlBar(value: Float, onValueChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, label: String, valueDisplay: String, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp, enabled: Boolean = true) {
    Column(modifier = modifier.width(70.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(valueDisplay, color = if (enabled) Color.White else Color.Gray, fontSize = 16.sp, fontWeight = FontWeight.Black)
        Text(label, color = Color.White.copy(alpha = 0.5f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(20.dp))
        Box(Modifier.height(height).width(70.dp), Alignment.Center) {
            Slider(value = value, onValueChange = onValueChange, valueRange = range, enabled = enabled, modifier = Modifier.requiredWidth(height).graphicsLayer { rotationZ = 270f }, colors = SliderDefaults.colors(thumbColor = if (enabled) Color.White else Color.Transparent, activeTrackColor = if (enabled) Color.White else Color.Gray.copy(alpha = 0.3f), inactiveTrackColor = Color.White.copy(alpha = 0.1f)))
        }
    }
}

@Composable
fun FilmChip(name: String, isSelected: Boolean, onClick: () -> Unit) {
    Box(Modifier.clickable { onClick() }.background(if (isSelected) Color.White else Color.Transparent, RoundedCornerShape(2.dp)).border(1.2.dp, if (isSelected) Color.White else Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)).padding(horizontal = 14.dp, vertical = 8.dp)) {
        Text(name.uppercase(), color = if (isSelected) Color.Black else Color.White, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
    }
}
