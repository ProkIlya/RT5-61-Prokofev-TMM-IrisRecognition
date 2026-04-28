package com.example.irisrecognition

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.RectF
import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.irisrecognition.databinding.ActivityMainBinding
import org.opencv.android.OpenCVLoader
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.min

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var faceMeshProcessor: FaceMeshProcessor
    private var irisRecognizer = IrisRecognizer()

    private var cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA
    private var cameraProvider: ProcessCameraProvider? = null
    private var isFrontCamera = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!OpenCVLoader.initDebug()) {
            Toast.makeText(this, "Ошибка загрузки OpenCV", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
        }

        cameraExecutor = Executors.newSingleThreadExecutor()
        faceMeshProcessor = FaceMeshProcessor(this) { result ->
            runOnUiThread {
                when (result) {
                    is FaceMeshProcessor.Result.FaceDetected -> {
                        val recognized = irisRecognizer.identify(result.irisCode)
                        binding.tvStatus.text = if (recognized) "Идентифицирован: Пользователь" else "Обнаружен неизвестный"

                        val viewWidth = binding.overlayView.width.toFloat()
                        val viewHeight = binding.overlayView.height.toFloat()
                        if (viewWidth <= 0f || viewHeight <= 0f) return@runOnUiThread

                        val leftRectRaw = result.leftEyeRect
                        val rightRectRaw = result.rightEyeRect

                        val frameWidth = result.frameWidth.toFloat()
                        val frameHeight = result.frameHeight.toFloat()

                        // Масштабирование FIT_CENTER (как у PreviewView)
                        val scale = min(viewWidth / frameWidth, viewHeight / frameHeight)
                        val scaledWidth = frameWidth * scale
                        val scaledHeight = frameHeight * scale
                        val offsetX = (viewWidth - scaledWidth) / 2f
                        val offsetY = (viewHeight - scaledHeight) / 2f

                        fun transformRect(rect: RectF): RectF {
                            return RectF(
                                offsetX + rect.left * scale,
                                offsetY + rect.top * scale,
                                offsetX + rect.right * scale,
                                offsetY + rect.bottom * scale
                            )
                        }

                        var leftRect = transformRect(leftRectRaw)
                        var rightRect = transformRect(rightRectRaw)

                        // Зеркалирование для фронтальной камеры – только если нужно
                        // Так как PreviewView обычно показывает зеркальное отражение,
                        // а MediaPipe даёт координаты в ориентации сенсора, нужно отразить.
                        if (isFrontCamera) {
                            leftRect = RectF(
                                viewWidth - leftRect.right,
                                leftRect.top,
                                viewWidth - leftRect.left,
                                leftRect.bottom
                            )
                            rightRect = RectF(
                                viewWidth - rightRect.right,
                                rightRect.top,
                                viewWidth - rightRect.left,
                                rightRect.bottom
                            )
                        }

                        binding.overlayView.updateEyeRects(leftRect, rightRect, recognized)
                    }
                    FaceMeshProcessor.Result.NoFace -> {
                        binding.tvStatus.text = "Лицо не найдено"
                        binding.overlayView.updateEyeRects(null, null, false)
                    }
                    is FaceMeshProcessor.Result.Error -> {
                        binding.tvStatus.text = "Ошибка: ${result.message}"
                        binding.overlayView.updateEyeRects(null, null, false)
                    }
                }
            }
        }

        binding.btnRegister.setOnClickListener {
            faceMeshProcessor.captureNextIrisCode { code ->
                irisRecognizer.register(code)
                runOnUiThread {
                    Toast.makeText(this, "Эталон зарегистрирован", Toast.LENGTH_SHORT).show()
                }
            }
        }

        binding.btnSwitchCamera.setOnClickListener {
            switchCamera()
        }
    }

    private fun switchCamera() {
        cameraProvider?.let { provider ->
            cameraSelector = if (cameraSelector == CameraSelector.DEFAULT_FRONT_CAMERA) {
                isFrontCamera = false
                CameraSelector.DEFAULT_BACK_CAMERA
            } else {
                isFrontCamera = true
                CameraSelector.DEFAULT_FRONT_CAMERA
            }
            bindCameraUseCases(provider)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(cameraProvider!!)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraUseCases(provider: ProcessCameraProvider) {
        provider.unbindAll()

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(binding.previewView.surfaceProvider)
        }

        val rotation = (applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager)
            .defaultDisplay.rotation

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888) // важно: YUV
            .setTargetRotation(rotation)
            .build()
            .also {
                it.setAnalyzer(cameraExecutor, faceMeshProcessor)
            }

        try {
            provider.bindToLifecycle(this, cameraSelector, preview, imageAnalysis)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Ошибка инициализации камеры: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startCamera()
            } else {
                Toast.makeText(this, "Разрешения не предоставлены", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        faceMeshProcessor.close()
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}