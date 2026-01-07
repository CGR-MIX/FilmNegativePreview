package com.example.filmnegativepreview

import android.graphics.Bitmap
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class ImageProcessor {

    enum class FilmStock(val displayName: String, val rGain: Double, val gGain: Double, val bGain: Double) {
        COLOR("Color", 1.0, 1.0, 1.0),
        BW("Black & White", 1.0, 1.0, 1.0),
        NORMAL("Normal View", 1.0, 1.0, 1.0)
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

    /**
     * 探测胶片轮廓点
     */
    fun findFilmContour(src: Mat): Array<Point>? {
        val gray = Mat()
        Imgproc.cvtColor(src, gray, Imgproc.COLOR_RGB2GRAY)
        Imgproc.GaussianBlur(gray, gray, Size(5.0, 5.0), 0.0)
        
        val thresh = Mat()
        Imgproc.Canny(gray, thresh, 50.0, 150.0)
        
        val contours = mutableListOf<MatOfPoint>()
        val hierarchy = Mat()
        Imgproc.findContours(thresh, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
        
        var maxArea = 0.0
        var bestPoints: Array<Point>? = null
        
        for (contour in contours) {
            val area = Imgproc.contourArea(contour)
            if (area > (src.rows() * src.cols() * 0.05)) { 
                val contour2f = MatOfPoint2f(*contour.toArray())
                val peri = Imgproc.arcLength(contour2f, true)
                val approx = MatOfPoint2f()
                Imgproc.approxPolyDP(contour2f, approx, 0.02 * peri, true)
                
                if (approx.total() == 4L && area > maxArea) {
                    maxArea = area
                    bestPoints = approx.toArray()
                }
                approx.release()
                contour2f.release()
            }
        }
        
        gray.release()
        thresh.release()
        hierarchy.release()
        contours.forEach { it.release() }
        
        return bestPoints
    }

    /**
     * 绘制识别框
     */
    fun drawFilmBox(src: Mat, points: Array<Point>) {
        for (i in 0..3) {
            Imgproc.line(src, points[i], points[(i + 1) % 4], Scalar(0.0, 255.0, 0.0), 6)
        }
    }

    /**
     * 透视变换裁剪
     */
    fun warpFilm(src: Mat, points: Array<Point>): Mat {
        val result = Mat()
        val sortedPoints = sortPoints(points)
        val targetWidth = 1500.0
        val targetHeight = 1000.0
        val destPoints = MatOfPoint2f(
            Point(0.0, 0.0),
            Point(targetWidth, 0.0),
            Point(targetWidth, targetHeight),
            Point(0.0, targetHeight)
        )
        val srcPoints = MatOfPoint2f(*sortedPoints)
        val perspectiveMatrix = Imgproc.getPerspectiveTransform(srcPoints, destPoints)
        Imgproc.warpPerspective(src, result, perspectiveMatrix, Size(targetWidth, targetHeight))
        
        perspectiveMatrix.release()
        srcPoints.release()
        destPoints.release()
        return result
    }

    private fun sortPoints(pts: Array<Point>): Array<Point> {
        val sorted = pts.sortedBy { it.x + it.y } 
        val tl = sorted[0]
        val br = sorted[3]
        val remaining = pts.filter { it != tl && it != br }
        if (remaining.size < 2) return pts // 降级处理
        val tr = if (remaining[0].x > remaining[1].x) remaining[0] else remaining[1]
        val bl = if (remaining[0].x > remaining[1].x) remaining[1] else remaining[0]
        return arrayOf(tl, tr, br, bl)
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
        manualMaskColor: Scalar? = null,
        detectedPoints: Array<Point>? = null,
        doWarp: Boolean = false 
    ): Bitmap {
        var src = Mat()
        Utils.bitmapToMat(bitmap, src)

        when (rotation) {
            90 -> Core.rotate(src, src, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, src, Core.ROTATE_180)
            270 -> Core.rotate(src, src, Core.ROTATE_90_COUNTERCLOCKWISE)
        }

        Imgproc.cvtColor(src, src, Imgproc.COLOR_RGBA2RGB)

        if (stock == FilmStock.NORMAL) {
            detectedPoints?.let { drawFilmBox(src, it) }
            val resultBitmap = Bitmap.createBitmap(src.cols(), src.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(src, resultBitmap)
            src.release()
            return resultBitmap
        }

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

        // 绘制识别框（预览模式）
        if (!doWarp) {
            detectedPoints?.let { drawFilmBox(src, it) }
        }

        // 拍照时执行裁剪
        if (doWarp && detectedPoints != null) {
            val warped = warpFilm(src, detectedPoints)
            src.release()
            src = warped
        }

        val resultBitmap = Bitmap.createBitmap(src.cols(), src.rows(), Bitmap.Config.ARGB_8888)
        Utils.matToBitmap(src, resultBitmap)
        
        src.release()
        channels.forEach { it.release() }
        
        return resultBitmap
    }
}