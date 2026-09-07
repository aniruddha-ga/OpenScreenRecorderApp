package com.openscreenrecorder.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import java.lang.ref.WeakReference
import android.util.Log
import android.view.*
import android.widget.FrameLayout
import android.widget.ImageButton
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import androidx.core.graphics.toColorInt
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import kotlin.math.abs

class CameraOverlayService : Service() {

    companion object {
        private const val TAG = "CameraOverlayService"
        const val ACTION_STOP_CAMERA = "com.openscreenrecorder.app.ACTION_STOP_CAMERA"

        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var serviceRef: WeakReference<CameraOverlayService>? = null

        fun prepareForScreenshot() {
            val service = serviceRef?.get() ?: return
            service.mainHandler.post {
                service.overlayCard?.visibility = View.INVISIBLE
            }
        }

        fun restoreAfterScreenshot() {
            val service = serviceRef?.get() ?: return
            service.mainHandler.post {
                service.overlayCard?.visibility = View.VISIBLE
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    private var windowManager: WindowManager? = null
    private var overlayCard: MaterialCardView? = null
    private var textureView: TextureView? = null
    private lateinit var params: WindowManager.LayoutParams

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var cameraManager: CameraManager? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var isFrontCamera = true
    private var screenWidth = 0
    private var screenHeight = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceRef = WeakReference(this)
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager

        val metrics = windowManager?.currentWindowMetrics
        screenWidth = metrics?.bounds?.width() ?: 1080
        screenHeight = metrics?.bounds?.height() ?: 1920

        startCameraThread()
        createCameraOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_CAMERA) {
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun startCameraThread() {
        cameraThread = HandlerThread("CameraOverlayThread").apply {
            start()
            cameraHandler = Handler(looper)
        }
    }

    private fun stopCameraThread() {
        cameraThread?.quitSafely()
        try {
            cameraThread?.join()
            cameraThread = null
            cameraHandler = null
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping camera thread: ${e.message}")
        }
    }

    private fun getThemedContext(): Context {
        val configManager = ConfigManager(this)
        val themeMode = configManager.getThemeModeValue()
        val isDark = when (themeMode) {
            AppCompatDelegate.MODE_NIGHT_YES -> true
            AppCompatDelegate.MODE_NIGHT_NO -> false
            else -> (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        }

        val overrideConfig = Configuration(resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    (if (isDark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO)
        }

        val baseContext = ContextThemeWrapper(createConfigurationContext(overrideConfig), R.style.Theme_OpenScreenRecorder)
        return if (configManager.isDynamicColorsEnabled && DynamicColors.isDynamicColorAvailable()) {
            DynamicColors.wrapContextIfAvailable(baseContext)
        } else {
            baseContext
        }
    }

    private fun createCameraOverlay() {
        val themedContext = getThemedContext()
        val windowSize = (140 * resources.displayMetrics.density).toInt()

        val card = MaterialCardView(themedContext).apply {
            radius = (windowSize / 2f)
            cardElevation = 12f * resources.displayMetrics.density
            setCardBackgroundColor(Color.BLACK)
            strokeWidth = (2f * resources.displayMetrics.density).toInt()
            strokeColor = "#44FFFFFF".toColorInt()
        }

        val container = FrameLayout(themedContext).apply {
            layoutParams = FrameLayout.LayoutParams(windowSize, windowSize)
        }

        textureView = TextureView(themedContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                    openCamera()
                }

                override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {}

                override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                    closeCamera()
                    return true
                }

                override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
            }
        }
        container.addView(textureView)

        // Close button overlay
        val btnClose = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            setBackgroundColor("#88000000".toColorInt())
            val btnSize = (28 * resources.displayMetrics.density).toInt()
            layoutParams = FrameLayout.LayoutParams(btnSize, btnSize).apply {
                gravity = Gravity.TOP or Gravity.END
                setMargins(12, 12, 12, 12)
            }
            setOnClickListener {
                stopSelf()
            }
        }
        container.addView(btnClose)

        card.addView(container)
        overlayCard = card

        params = WindowManager.LayoutParams(
            windowSize,
            windowSize,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = screenWidth - windowSize - 40
            y = 200
        }

        applyDragLogic(overlayCard!!)

        try {
            windowManager?.addView(overlayCard, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add camera overlay to WindowManager: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Camera permission not granted for facecam overlay")
            stopSelf()
            return
        }

        try {
            val cameraIdList = cameraManager?.cameraIdList ?: return
            var targetCameraId: String? = null

            val targetFacing = if (isFrontCamera) {
                CameraCharacteristics.LENS_FACING_FRONT
            } else {
                CameraCharacteristics.LENS_FACING_BACK
            }

            for (id in cameraIdList) {
                val characteristics = cameraManager?.getCameraCharacteristics(id)
                val facing = characteristics?.get(CameraCharacteristics.LENS_FACING)
                if (facing == targetFacing) {
                    targetCameraId = id
                    break
                }
            }

            if (targetCameraId == null && cameraIdList.isNotEmpty()) {
                targetCameraId = cameraIdList[0]
            }

            if (targetCameraId != null) {
                cameraManager?.openCamera(targetCameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        startCameraPreview()
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                    }
                }, cameraHandler)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera: ${e.message}")
        }
    }

    private fun startCameraPreview() {
        val texture = textureView?.surfaceTexture ?: return
        val camera = cameraDevice ?: return

        try {
            texture.setDefaultBufferSize(640, 480)
            val surface = Surface(texture)

            val previewRequestBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
            }

            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    if (cameraDevice == null) return
                    captureSession = session
                    try {
                        previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                        captureSession?.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start repeating camera request: ${e.message}")
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Camera session configuration failed")
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting camera preview: ${e.message}")
        }
    }

    private fun closeCamera() {
        try {
            captureSession?.close()
            captureSession = null
            cameraDevice?.close()
            cameraDevice = null
        } catch (e: Exception) {
            Log.e(TAG, "Error closing camera: ${e.message}")
        }
    }

    private fun applyDragLogic(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (event.rawX - touchX).toInt()
                    val dy = (event.rawY - touchY).toInt()
                    if (abs(dx) > touchSlop || abs(dy) > touchSlop) {
                        isDragging = true
                    }
                    if (isDragging) {
                        params.x = (initialX + dx).coerceIn(0, screenWidth - view.width)
                        params.y = (initialY + dy).coerceIn(0, screenHeight - view.height)
                        try {
                            windowManager?.updateViewLayout(overlayCard, params)
                        } catch (_: Exception) {}
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceRef = null
        isRunning = false
        closeCamera()
        stopCameraThread()
        overlayCard?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        overlayCard = null
    }
}
