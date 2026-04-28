package com.example.irisrecognition

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RectF
import android.util.Log
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import org.opencv.android.Utils
import org.opencv.core.*
import org.opencv.imgproc.Imgproc

class FaceMeshProcessor(
    private val context: Context,
    private val listener: (Result) -> Unit
) : ImageAnalysis.Analyzer {

    private var faceLandmarker: FaceLandmarker? = null
    private var captureIrisCallback: ((IrisCode) -> Unit)? = null

    // Проверенные индексы для глаз (MediaPipe Face Landmarker)
    private val leftEyeIndices = listOf(
        33, 7, 163, 144, 145, 153, 154, 155, 133, 173,
        157, 158, 159, 160, 161, 246
    )
    private val rightEyeIndices = listOf(
        362, 382, 381, 380, 374, 373, 390, 249, 263, 466,
        388, 387, 386, 385, 384, 398
    )

    sealed class Result {
        data class FaceDetected(
            val irisCode: IrisCode,
            val leftEyeRect: RectF,
            val rightEyeRect: RectF,
            val frameWidth: Int,
            val frameHeight: Int
        ) : Result()
        object NoFace : Result()
        data class Error(val message: String) : Result()
    }

    init {
        setupFaceLandmarker()
    }

    private fun setupFaceLandmarker() {
        val modelPath = "mediapipe/face_landmarker.task"
        val options = FaceLandmarker.FaceLandmarkerOptions.builder()
            .setBaseOptions(
                BaseOptions.builder()
                    .setModelAssetPath(modelPath)
                    .setDelegate(Delegate.GPU)
                    .build()
            )
            .setRunningMode(RunningMode.IMAGE)
            .setNumFaces(1)
            .setMinFaceDetectionConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        faceLandmarker = FaceLandmarker.createFromOptions(context, options)
    }

    @OptIn(ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val landmarker = faceLandmarker ?: run {
            imageProxy.close()
            return
        }

        try {
            val bitmap = imageProxyToBitmap(imageProxy)
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            imageProxy.close()

            if (bitmap == null) {
                listener(Result.Error("Failed to convert image to bitmap"))
                return
            }

            val rotatedBitmap = if (rotationDegrees != 0) {
                rotateBitmap(bitmap, rotationDegrees)
            } else {
                bitmap
            }

            val mpImage = BitmapImageBuilder(rotatedBitmap).build()
            val result = landmarker.detect(mpImage)

            if (result.faceLandmarks().isEmpty()) {
                listener(Result.NoFace)
                return
            }

            val landmarks = result.faceLandmarks()[0]

            val leftEyePoints = leftEyeIndices.map { landmarks[it] }
            val rightEyePoints = rightEyeIndices.map { landmarks[it] }

            val leftEyeRect = computeEyeRect(leftEyePoints, rotatedBitmap.width, rotatedBitmap.height)
            val rightEyeRect = computeEyeRect(rightEyePoints, rotatedBitmap.width, rotatedBitmap.height)

            Log.d("FaceMesh", "Left rect: $leftEyeRect, Right rect: $rightEyeRect")

            val irisCode = computeIrisCode(rotatedBitmap, leftEyePoints, rightEyePoints)

            captureIrisCallback?.invoke(irisCode)
            captureIrisCallback = null

            listener(Result.FaceDetected(irisCode, leftEyeRect, rightEyeRect, rotatedBitmap.width, rotatedBitmap.height))

        } catch (e: Exception) {
            Log.e("FaceMeshProcessor", "Error", e)
            imageProxy.close()
            listener(Result.Error(e.message ?: "Unknown error"))
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val image = imageProxy.image ?: return null
        val planes = image.planes
        val yBuffer = ByteArray(planes[0].buffer.remaining()).also { planes[0].buffer.get(it) }
        val uBuffer = ByteArray(planes[1].buffer.remaining()).also { planes[1].buffer.get(it) }
        val vBuffer = ByteArray(planes[2].buffer.remaining()).also { planes[2].buffer.get(it) }

        return yuvToBitmap(yBuffer, uBuffer, vBuffer, image.width, image.height)
    }

    private fun yuvToBitmap(y: ByteArray, u: ByteArray, v: ByteArray, width: Int, height: Int): Bitmap {
        val rgb = IntArray(width * height)
        var index = 0
        for (row in 0 until height) {
            for (col in 0 until width) {
                val yIndex = row * width + col
                val uvIndex = (row / 2) * (width / 2) + (col / 2)
                val Y = y[yIndex].toInt() and 0xFF
                val U = u[uvIndex].toInt() and 0xFF
                val V = v[uvIndex].toInt() and 0xFF

                var R = (Y + 1.402 * (V - 128)).toInt()
                var G = (Y - 0.344 * (U - 128) - 0.714 * (V - 128)).toInt()
                var B = (Y + 1.772 * (U - 128)).toInt()

                R = R.coerceIn(0, 255)
                G = G.coerceIn(0, 255)
                B = B.coerceIn(0, 255)

                rgb[index++] = (0xFF shl 24) or (R shl 16) or (G shl 8) or B
            }
        }
        return Bitmap.createBitmap(rgb, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun rotateBitmap(bitmap: Bitmap, degrees: Int): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(degrees.toFloat())
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun computeEyeRect(points: List<NormalizedLandmark>, imgWidth: Int, imgHeight: Int): RectF {
        val xs = points.map { it.x() * imgWidth }
        val ys = points.map { it.y() * imgHeight }
        var left = xs.minOrNull() ?: 0f
        var right = xs.maxOrNull() ?: 0f
        var top = ys.minOrNull() ?: 0f
        var bottom = ys.maxOrNull() ?: 0f

        left = left.coerceIn(0f, imgWidth.toFloat())
        right = right.coerceIn(0f, imgWidth.toFloat())
        top = top.coerceIn(0f, imgHeight.toFloat())
        bottom = bottom.coerceIn(0f, imgHeight.toFloat())

        if (left >= right) right = left + 1f
        if (top >= bottom) bottom = top + 1f

        return RectF(left, top, right, bottom)
    }

    private fun computeIrisCode(bitmap: Bitmap, leftEye: List<NormalizedLandmark>, rightEye: List<NormalizedLandmark>): IrisCode {
        val mat = Mat()
        Utils.bitmapToMat(bitmap, mat)
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGBA2GRAY)

        val leftIrisRegion = extractIrisRegion(mat, leftEye, bitmap.width, bitmap.height)
        val rightIrisRegion = extractIrisRegion(mat, rightEye, bitmap.width, bitmap.height)

        val leftHash = computeGaborHash(leftIrisRegion)
        val rightHash = computeGaborHash(rightIrisRegion)

        return IrisCode(leftHash, rightHash)
    }

    private fun extractIrisRegion(grayMat: Mat, points: List<NormalizedLandmark>, imgWidth: Int, imgHeight: Int): Mat {
        val xs = points.map { (it.x() * imgWidth).toInt() }
        val ys = points.map { (it.y() * imgHeight).toInt() }
        val left = xs.minOrNull()?.coerceAtLeast(0) ?: return Mat()
        val right = xs.maxOrNull()?.coerceAtMost(imgWidth - 1) ?: return Mat()
        val top = ys.minOrNull()?.coerceAtLeast(0) ?: return Mat()
        val bottom = ys.maxOrNull()?.coerceAtMost(imgHeight - 1) ?: return Mat()
        val width = right - left
        val height = bottom - top
        if (width <= 0 || height <= 0) return Mat()
        return Mat(grayMat, Rect(left, top, width, height)).clone()
    }

    private fun computeGaborHash(irisMat: Mat): ByteArray {
        if (irisMat.empty() || irisMat.width() == 0 || irisMat.height() == 0) return ByteArray(0)
        val resized = Mat()
        Imgproc.resize(irisMat, resized, Size(20.0, 240.0))
        val thresh = Mat()
        Imgproc.adaptiveThreshold(resized, thresh, 255.0, Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY, 11, 2.0)
        // Конвертируем в 8-bit, если ещё не
        if (thresh.type() != CvType.CV_8UC1) {
            thresh.convertTo(thresh, CvType.CV_8UC1)
        }
        val bytes = ByteArray(thresh.total().toInt())
        thresh.get(0, 0, bytes)
        return bytes
    }

    fun captureNextIrisCode(callback: (IrisCode) -> Unit) {
        captureIrisCallback = callback
    }

    fun close() {
        faceLandmarker?.close()
        faceLandmarker = null
    }
}