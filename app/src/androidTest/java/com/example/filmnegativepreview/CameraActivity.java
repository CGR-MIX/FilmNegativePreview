package com.example.FilmNegativePreview;  // 这里是你的应用包名

import android.hardware.camera2.*;
import android.os.Bundle;
import android.view.Surface;
import android.view.SurfaceView;
import android.widget.ImageView;
import androidx.appcompat.app.AppCompatActivity;

public class CameraActivity extends AppCompatActivity {

    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSession;
    private SurfaceView previewSurface; // 用于显示相机预览的视图
    private ImageView processedImageView; // 用于显示处理后的图像（反转后的图像）

    private static final String TAG = "CameraActivity";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        previewSurface = findViewById(R.id.previewSurface); // 获取 SurfaceView 控件
        processedImageView = findViewById(R.id.processedImageView); // 获取 ImageView 用于展示处理后的图像

        openCamera(); // 打开相机并开始预览
    }

    // 打开相机
    private void openCamera() {
        // 获取 CameraManager
        CameraManager cameraManager = (CameraManager) getSystemService(CAMERA_SERVICE);

        try {
            String cameraId = cameraManager.getCameraIdList()[0]; // 获取默认的后置相机 ID
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(cameraId);

            // 请求相机权限
            if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager.openCamera(cameraId, cameraStateCallback, null);
            } else {
                requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 1001);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 相机打开回调
    private CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            // 相机成功打开
            cameraDevice = camera;
            startPreview(); // 开始相机预览
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            cameraDevice.close();
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            cameraDevice.close();
        }
    };

    // 开始相机预览
    private void startPreview() {
        try {
            Surface surface = previewSurface.getHolder().getSurface(); // 获取 Surface 用于预览
            final CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);

            // 创建相机捕获会话
            cameraDevice.createCaptureSession(
                    Arrays.asList(surface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            if (cameraDevice == null) return;
                            cameraCaptureSession = session;
                            try {
                                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                                cameraCaptureSession.setRepeatingRequest(captureRequestBuilder.build(), null, null);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            // 配置失败
                        }
                    },
                    null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 打开相机时要确保权限已经授予
        if (checkSelfPermission(android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            openCamera();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        if (cameraDevice != null) {
            cameraDevice.close(); // 释放相机资源
        }
    }
}
public class CameraActivity extends AppCompatActivity {

    private TensorFlowHelper tensorFlowHelper;  // 创建 TensorFlowHelper 实例

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_camera);

        // 初始化 TensorFlowHelper
        tensorFlowHelper = new TensorFlowHelper();
        tensorFlowHelper.loadModel(this);  // 加载模型

        // 在这里，你可以调用相机和处理每一帧图像
    }

    private void captureImage() {
        // 假设你从 Camera2 API 获取到图像并转换为 Mat
        Mat inputImage = ...  // 获取图像
        tensorFlowHelper.classifyImage(inputImage);  // 使用 TensorFlow Lite 进行分类
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (tensorFlowHelper != null) {
            tensorFlowHelper.close();  // 释放资源
        }
    }
}
