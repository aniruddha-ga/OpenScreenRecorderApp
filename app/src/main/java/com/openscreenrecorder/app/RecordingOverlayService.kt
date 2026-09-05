package com.openscreenrecorder.app

import android.animation.ValueAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.*
import android.util.TypedValue
import android.view.*
import android.view.animation.DecelerateInterpolator
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import com.google.android.material.color.DynamicColors
import com.openscreenrecorder.app.databinding.LayoutRecordingOverlayBinding
import kotlin.math.abs

class RecordingOverlayService : Service() {

    private var windowManager: WindowManager? = null
    private var binding: LayoutRecordingOverlayBinding? = null
    private var redDotAnimator: ValueAnimator? = null
    private var windowXAnimator: ValueAnimator? = null
    private lateinit var params: WindowManager.LayoutParams

    private var screenWidth = 0
    private var screenHeight = 0
    private var pausedElapsedMs = 0L
    private var isExpanded = true

    private val collapseHandler = Handler(Looper.getMainLooper())
    private val collapseRunnable = Runnable { collapseOverlay() }

    companion object {
        const val ACTION_REFRESH_THEME = "com.openscreenrecorder.app.ACTION_REFRESH_THEME"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_REFRESH_THEME) {
            refreshOverlay()
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        updateScreenBounds()
        createOverlay()
        startCollapseTimer()
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

    private fun createOverlay() {
        val themedContext = getThemedContext()
        binding = LayoutRecordingOverlayBinding.inflate(LayoutInflater.from(themedContext))
        val view: View = binding!!.root

        val onSurfaceVariantColor = TypedValue().let { tv ->
            themedContext.theme.resolveAttribute(R.attr.colorOnSurfaceVariant, tv, true)
            tv.data
        }
        binding?.dragHandle?.imageTintList = ColorStateList.valueOf(onSurfaceVariantColor)

        setupLayoutParams()
        setupTimer()
        setupBlinkingDot()
        setupButtons()
        applyDragLogic(view)

        view.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        params.x = (screenWidth - view.measuredWidth) / 2

        try {
            windowManager?.addView(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        view.alpha = 0f
        view.scaleX = 0.88f
        view.scaleY = 0.88f
        view.translationY = -18f
        view.animate()
            .alpha(0.95f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(220)
            .setInterpolator(DecelerateInterpolator(2f))
            .start()
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
            y = 100
        }
    }

    private fun applyDragLogic(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            resetCollapseTimer()
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
                        params.x = (initialX + dx).coerceIn(0, screenWidth - view.width)
                        params.y = (initialY + dy).coerceIn(0, screenHeight - view.height)
                        updateViewLayoutSafe()
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        if (!isExpanded) expandOverlay()
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun collapseOverlay() {
        if (!isExpanded || binding == null) return
        isExpanded = false
        updateScreenBounds()

        binding!!.controlsContainer.animate()
            .alpha(0f)
            .setDuration(160)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                binding?.controlsContainer?.visibility = View.GONE
                binding?.controlsContainer?.alpha = 1f
                binding?.root?.post {
                    val cardWidth = binding?.rootCard?.width ?: 120
                    val targetX = (screenWidth - cardWidth - 12).coerceAtLeast(0)
                    animateWindowPositionX(params.x, targetX)
                }
            }
            .start()
    }

    private fun expandOverlay() {
        if (isExpanded || binding == null) return
        isExpanded = true
        updateScreenBounds()

        val targetX = (params.x - 220).coerceAtLeast(12)
        animateWindowPositionX(params.x, targetX)

        binding!!.root.postDelayed({
            binding?.controlsContainer?.alpha = 0f
            binding?.controlsContainer?.visibility = View.VISIBLE
            binding?.controlsContainer?.animate()
                ?.alpha(1f)
                ?.setDuration(200)
                ?.setInterpolator(DecelerateInterpolator(2f))
                ?.start()
        }, 80)

        startCollapseTimer()
    }

    private fun animateWindowPositionX(startX: Int, endX: Int) {
        windowXAnimator?.cancel()
        windowXAnimator = ValueAnimator.ofInt(startX, endX).apply {
            duration = 220
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener { animation ->
                params.x = animation.animatedValue as Int
                updateViewLayoutSafe()
            }
            start()
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

    private fun startCollapseTimer() {
        collapseHandler.removeCallbacks(collapseRunnable)
        if (isExpanded) collapseHandler.postDelayed(collapseRunnable, 3000)
    }

    private fun resetCollapseTimer() = startCollapseTimer()

    private fun setupTimer() {
        binding?.timer?.apply {
            base = SystemClock.elapsedRealtime()
            start()
        }
    }

    private fun setupBlinkingDot() {
        redDotAnimator = ValueAnimator.ofFloat(1f, 0.25f).apply {
            duration = 850
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = DecelerateInterpolator()
            addUpdateListener { binding?.redDot?.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun setupButtons() {
        binding?.btnPause?.setOnClickListener {
            resetCollapseTimer()
            handlePauseResume()
        }
        binding?.btnStop?.setOnClickListener {
            sendAction(ScreenRecordService.ACTION_STOP)
            try {
                stopSelf()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handlePauseResume() {
        val timer = binding?.timer ?: return
        if (ScreenRecordService.isPaused) {
            sendAction(ScreenRecordService.ACTION_RESUME)
            binding?.btnPause?.setIconResource(R.drawable.ic_pause)
            timer.base = SystemClock.elapsedRealtime() - pausedElapsedMs
            timer.start()
            redDotAnimator?.start()
        } else {
            pausedElapsedMs = SystemClock.elapsedRealtime() - timer.base
            sendAction(ScreenRecordService.ACTION_PAUSE)
            binding?.btnPause?.setIconResource(R.drawable.ic_play)
            timer.stop()
            redDotAnimator?.cancel()
            binding?.redDot?.alpha = 1f
        }
    }

    private fun sendAction(action: String) {
        startService(Intent(this, ScreenRecordService::class.java).apply { this.action = action })
    }

    override fun onDestroy() {
        super.onDestroy()
        collapseHandler.removeCallbacks(collapseRunnable)
        redDotAnimator?.cancel()
        windowXAnimator?.cancel()
        binding?.root?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        binding = null
    }
}
