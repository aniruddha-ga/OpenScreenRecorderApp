package com.openscreenrecorder.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.IBinder
import android.util.TypedValue
import android.view.*
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.color.DynamicColors
import com.openscreenrecorder.app.databinding.LayoutFloatingStartBinding
import kotlin.math.abs

class FloatingStartService : Service() {

    companion object {
        const val ACTION_FLOATING_STATE_CHANGED = "com.openscreenrecorder.app.ACTION_FLOATING_STATE_CHANGED"
        const val EXTRA_FLOATING_RUNNING = "extra_floating_running"
        const val ACTION_REFRESH_THEME = "com.openscreenrecorder.app.ACTION_REFRESH_THEME"
        @Volatile
        var isRunning = false
            private set
    }

    private var windowManager: WindowManager? = null
    private var binding: LayoutFloatingStartBinding? = null
    private lateinit var params: WindowManager.LayoutParams

    private var screenWidth = 0
    private var screenHeight = 0
    private lateinit var configManager: ConfigManager

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!isRunning) {
            isRunning = true
            sendStateBroadcast(true)
        }
        if (intent?.action == ACTION_REFRESH_THEME || binding == null) {
            refreshOverlay()
        } else {
            binding?.root?.let { view ->
                if (view.windowToken == null) {
                    try {
                        windowManager?.addView(view, params)
                    } catch (_: Exception) {}
                } else {
                    updateViewLayoutSafe()
                }
            }
        }
        return START_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        sendStateBroadcast(true)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        configManager = ConfigManager(this)
        updateScreenBounds()
        createOverlay()
    }

    private fun refreshOverlay() {
        if (binding == null) return
        val currentX = params.x
        val currentY = params.y
        binding?.root?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        binding = null
        createOverlay()
        params.x = currentX
        params.y = currentY
        updateViewLayoutSafe()
    }

    private fun updateScreenBounds() {
        val metrics = windowManager?.currentWindowMetrics
        screenWidth = metrics?.bounds?.width() ?: 1080
        screenHeight = metrics?.bounds?.height() ?: 1920
    }

    private fun getThemedContext(): Context {
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

    private fun createOverlay() {
        val themedContext = getThemedContext()
        binding = LayoutFloatingStartBinding.inflate(LayoutInflater.from(themedContext))
        val view: View = binding!!.root

        val onSurfaceVariantColor = TypedValue().let { tv ->
            themedContext.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true)
            tv.data
        }
        binding?.dragHandle?.imageTintList = ColorStateList.valueOf(onSurfaceVariantColor)

        setupLayoutParams()
        setupButtons()
        applyDragLogic(view)

        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        params.x = (screenWidth - view.measuredWidth) / 2
        params.y = (screenHeight - view.measuredHeight - 250).coerceAtLeast(0)

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setupLayoutParams() {
        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    private fun setupButtons() {
        binding?.btnStart?.setOnClickListener {
            launchPermissionActivity()
        }

        binding?.btnClose?.setOnClickListener {
            stopSelf()
        }
    }

    private fun launchPermissionActivity() {
        val intent = Intent(this, MediaProjectionPermissionActivity::class.java).apply {
            putExtra("RECORD_MIC", configManager.isMicEnabled)
            putExtra("RECORD_SYSTEM_AUDIO", configManager.isSystemAudioEnabled)
            putExtra("START_FROM_MAIN", false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
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
                        updateViewLayoutSafe()
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

    private fun updateViewLayoutSafe() {
        try {
            if (binding?.root?.windowToken != null) {
                windowManager?.updateViewLayout(binding?.root, params)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        sendStateBroadcast(false)
        binding?.root?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        binding = null
    }

    private fun sendStateBroadcast(running: Boolean) {
        try {
            sendBroadcast(Intent(ACTION_FLOATING_STATE_CHANGED).apply {
                putExtra(EXTRA_FLOATING_RUNNING, running)
            })
        } catch (_: Exception) {}
    }
}
