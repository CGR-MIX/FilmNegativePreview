package com.example.filmnegativepreview;  // 你的包名

import android.content.res.AssetFileDescriptor;
import android.util.Log;
import org.opencv.core.Mat;
import org.tensorflow.lite.Interpreter;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

public class TensorFlowHelper {

    private static final String TAG = "TensorFlowHelper";
    private Interpreter tflite;  // TensorFlow Lite 推理器

    // 加载模型
    public void loadModel(MainActivity activity) {
        try {
            tflite = new Interpreter(loadModelFile(activity));  // 加载 .tflite 模型文件
            Log.d(TAG, "Model loaded successfully.");
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, "Model loading failed.", e);
        }
    }

    // 从 assets 加载 TensorFlow Lite 模型文件
    private MappedByteBuffer loadModelFile(MainActivity activity) throws IOException {
        AssetFileDescriptor fileDescriptor = activity.getAssets().openFd("model.tflite");
        FileInputStream inputStream = fileDescriptor.createInputStream();
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map(FileChannel.MapMode.READ_ONLY, startOffset, declaredLength);
    }

    // 使用 TensorFlow Lite 模型对图像进行分类
    public void classifyImage(Mat inputImage) {
        // 将 Mat 转换为模型输入格式，这里假设输入图像是已预处理过的
        float[][] result = new float[1][NUM_CLASSES];  // NUM_CLASSES 是模型的类别数量

        // 执行推理，使用 inputImage 数据
        tflite.run(inputImageToFloatArray(inputImage), result);

        // 根据模型的输出结果（result）进行后续处理
        // 例如，根据 result 中的类别概率选择底片类型
        Log.d(TAG, "Model output: " + result[0][0]);  // 打印输出结果
    }

    // 假设你已经有一个方法将 Mat 转换为合适的 float 数组（具体根据模型输入要求）
    private float[] inputImageToFloatArray(Mat inputImage) {
        // 这里需要根据模型要求的输入格式进行转换，可能是 224x224 的图像矩阵等
        // 这只是一个示例，实际的转换取决于你的模型要求
        float[] imageData = new float[inputImage.rows() * inputImage.cols()];
        // 填充 imageData 数组
        return imageData;
    }

    // 释放 TensorFlow Lite 资源
    public void close() {
        if (tflite != null) {
            tflite.close();
        }
    }
}
