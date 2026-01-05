package com.example.filmnegativepreview

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class ImageProcessor {

    enum class FilmStock(val displayName: String, val rGain: Double, val gGain: Double, val bGain: Double) {
        COLOR("Color", 1.0, 1.0, 1.0),
        BW("Black & White", 1.0, 1.0, 1.0),
        NORMAL("Normal View", 1.0, 1.0, 1.0) // 新增：正常取景模式
    }

    private fun removeColorMask(src: Mat, manualMaskColor: Scalar? = null) {
        val channels = mutableListOf<Mat>()
        Core.split(src, channels)

        if (manualMaskColor != null) {
            for (i in 0..2) {
                val maskVal = manualMaskColor.`val`[i].coerceAtLeast(1.0)
                channels[i].convertTo(channels[i], -1, 255.0 / maskVal, 0.0)
            }
        } else {
            for (i in 0..2) {
                val res = Core.minMaxLoc(channels[i])
                val minVal = res.minVal
                val maxVal = res.maxVal

                if (maxVal > minVal) {
                    channels[i].convertTo(
                        channels[i],
                        -1,
                        255.0 / (maxVal - minVal),
                        -minVal * 255.0 / (maxVal - minVal)
                    )
                }
            }
        }
        Core.merge(channels, src)
        channels.forEach { it.release() }
    }

    fun estimateTemperature(bitmap: Bitmap): Float {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)
        val channels = mutableListOf<Mat>()
        Core.split(src, channels)
        
        val rMean = Core.mean(channels[0]).`val`[0]
        val bMean = Core.mean(channels[2]).`val`[0]
        
        src.release()
        channels.forEach { it.release() }

        val ratio = (rMean / (bMean + 1e-5)).coerceIn(0.5, 2.0)
        return ((ratio - 0.5) / 1.5).toFloat().coerceIn(0f, 1f)
    }

    fun processFrame(
        bitmap: Bitmap, 
        exposure: Float, 
        temp: Float, 
        stock: FilmStock, 
        rotation: Int,
        manualMaskColor: Scalar? = null
    ): Bitmap {
        val src = Mat()
        Utils.bitmapToMat(bitmap, src)

        // 1. 物理旋转
        when (rotation) {
            90 -> Core.rotate(src, src, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, src, Core.ROTATE_180)
            270 -> Core.rotate(src, src, Core.ROTATE_90_COUNTERCLOCKWISE)
        }

        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)

        // 如果是正常取景模式，跳过底片处理逻辑
        if (stock == FilmStock.NORMAL) {
            val resultBitmap = Bitmap.createBitmap(src.cols(), src.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(src, resultBitmap)
            src.release()
            return resultBitmap
        }

        // --- 以下为底片处理逻辑 ---
        removeColorMask(src, manualMaskColor)
        Core.bitwise_not(src, src)

        val exposureFactor = Math.pow(2.0, exposure.toDouble())
        src.convertTo(src, -1, exposureFactor, 0.0)

        val channels = mutableListOf<Mat>()
        Core.split(src, channels)
        
        val warmFactor = temp * 2.0
        val coolFactor = (1.0 - temp) * 2.0
        
        channels[0].convertTo(channels[0], -1, stock.rGain * warmFactor, 0.0)
        channels[1].convertTo(channels[1], -1, stock.gGain, 0.0)
        channels[2].convertTo(channels[2], -1, stock.bGain * coolFactor, 0.0)

        Core.merge(channels, src)

        if (stock == FilmStock.BW) {
            Imgproc.cvtColor(src, src, Imgproc.COLOR_RGB2GRAY)
            Imgproc.cvtColor(src, src, Imgproc.COLOR_GRAY2RGB)
        }

        src.convertTo(src, -1, 1.1, 10.0)

        val resultBitmap = Bitmap.createBitmap(src.cols(), src.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(src, resultBitmap)
        
        src.release()
        channels.forEach { it.release() }
        
        return resultBitmap
    }
}