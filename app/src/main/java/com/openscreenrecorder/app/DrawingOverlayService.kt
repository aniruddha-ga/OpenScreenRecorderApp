package com.openscreenrecorder.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.lang.ref.WeakReference
import android.view.*
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.graphics.toColorInt
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.DynamicColors
import kotlin.math.abs

class DrawingOverlayService : Service() {

    companion object {
        const val ACTION_STOP_DRAWING = "com.openscreenrecorder.app.ACTION_STOP_DRAWING"
        @Volatile
        var isRunning = false
            private set

        @Volatile
        private var serviceRef: WeakReference<DrawingOverlayService>? = null

        fun prepareForScreenshot(includeDrawing: Boolean) {
            val service = serviceRef?.get() ?: return
            service.mainHandler.post {
                service.toolbarView?.visibility = View.INVISIBLE
                service.textDialogView?.visibility = View.INVISIBLE
                service.paletteSubBar?.visibility = View.GONE
                if (!includeDrawing) {
                    service.drawingView?.visibility = View.INVISIBLE
                }
            }
        }

        fun restoreAfterScreenshot() {
            val service = serviceRef?.get() ?: return
            service.mainHandler.post {
                service.toolbarView?.visibility = View.VISIBLE
                service.drawingView?.visibility = View.VISIBLE
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var drawingView: DrawingView? = null
    private var mainCardView: MaterialCardView? = null
    private var toolbarView: View? = null
    private var textDialogView: View? = null
    private var paletteSubBar: LinearLayout? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private lateinit var canvasParams: WindowManager.LayoutParams
    private lateinit var toolbarParams: WindowManager.LayoutParams

    private var screenWidth = 0
    private var screenHeight = 0
    private var currentShapeIndex = 0
    private var currentSizeIndex = 1 // Default Medium (14dp)
    private var isPassthroughActive = false
    private var isPaletteVisible = false

    private val shapeModes = listOf(
        DrawingView.ToolMode.ARROW,
        DrawingView.ToolMode.RECTANGLE,
        DrawingView.ToolMode.CIRCLE,
        DrawingView.ToolMode.LINE
    )

    private val brushSizes = listOf(6f, 14f, 24f, 36f)
    private val brushSizeNames = listOf("Thin", "Medium", "Thick", "Extra Thick")

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceRef = WeakReference(this)
        isRunning = true
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val metrics = windowManager?.currentWindowMetrics
        screenWidth = metrics?.bounds?.width() ?: 1080
        screenHeight = metrics?.bounds?.height() ?: 1920

        createCanvasOverlay()
        createToolbarOverlay()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_DRAWING) {
            stopSelf()
        }
        return START_NOT_STICKY
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

    private fun createCanvasOverlay() {
        val themedContext = getThemedContext()
        drawingView = DrawingView(themedContext)

        canvasParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        try {
            windowManager?.addView(drawingView, canvasParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun createToolbarOverlay() {
        val themedContext = getThemedContext()

        val rootContainer = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        // Color Palette Sub-Bar
        paletteSubBar = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(12, 8, 12, 8)
            visibility = View.GONE
            background = GradientDrawable().apply {
                setColor("#CC1A1A1A".toColorInt())
                cornerRadius = 24f * resources.displayMetrics.density
                setStroke(1, "#44FFFFFF".toColorInt())
            }
        }

        val paletteColors = listOf(
            "#FF2222".toColorInt(),
            "#FF9800".toColorInt(),
            "#FFEB3B".toColorInt(),
            "#4CAF50".toColorInt(),
            "#2196F3".toColorInt(),
            "#9C27B0".toColorInt(),
            Color.WHITE,
            Color.BLACK
        )

        paletteColors.forEach { color ->
            val colorDot = View(themedContext).apply {
                val size = (24 * resources.displayMetrics.density).toInt()
                val margin = (4 * resources.displayMetrics.density).toInt()
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    setMargins(margin, margin, margin, margin)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color)
                    setStroke(2, Color.WHITE)
                }
                setOnClickListener {
                    drawingView?.setBrushColor(color)
                    paletteSubBar?.visibility = View.GONE
                    isPaletteVisible = false
                }
            }
            paletteSubBar?.addView(colorDot)
        }

        rootContainer.addView(paletteSubBar)

        val card = MaterialCardView(themedContext).apply {
            radius = 32f * resources.displayMetrics.density
            cardElevation = 12f * resources.displayMetrics.density
            setCardBackgroundColor("#EE121212".toColorInt())
            strokeWidth = (1.5f * resources.displayMetrics.density).toInt()
            strokeColor = "#33FFFFFF".toColorInt()
        }
        mainCardView = card

        val layout = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(14, 8, 14, 8)
        }

        // Color Palette toggle button
        val btnPalette = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_palette)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.btn_color_palette)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                isPaletteVisible = !isPaletteVisible
                paletteSubBar?.visibility = if (isPaletteVisible) View.VISIBLE else View.GONE
            }
        }
        layout.addView(btnPalette)

        // Brush Thickness button
        val btnBrushSize = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_line_weight)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.btn_brush_size)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                currentSizeIndex = (currentSizeIndex + 1) % brushSizes.size
                val newSize = brushSizes[currentSizeIndex]
                drawingView?.setBrushWidth(newSize)
                Toast.makeText(applicationContext, "Brush: ${brushSizeNames[currentSizeIndex]}", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnBrushSize)

        // Pen / Brush Mode button
        val btnPen = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_brush)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                drawingView?.activeToolMode = DrawingView.ToolMode.PEN
                Toast.makeText(applicationContext, "Pen Mode", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnPen)

        // Shapes selector button
        val btnShapes = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_arrow)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                val mode = shapeModes[currentShapeIndex % shapeModes.size]
                currentShapeIndex++
                drawingView?.activeToolMode = mode
                when (mode) {
                    DrawingView.ToolMode.ARROW -> setImageResource(R.drawable.ic_arrow)
                    DrawingView.ToolMode.RECTANGLE -> setImageResource(R.drawable.ic_rectangle)
                    DrawingView.ToolMode.CIRCLE -> setImageResource(R.drawable.ic_circle)
                    else -> setImageResource(R.drawable.ic_arrow)
                }
                Toast.makeText(applicationContext, "Shape: ${mode.name}", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnShapes)

        // Text Overlay button
        val btnText = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_text)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                showTextInputDialog()
            }
        }
        layout.addView(btnText)

        // Laser Pointer button
        val btnLaser = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_laser)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                drawingView?.activeToolMode = DrawingView.ToolMode.LASER_POINTER
                Toast.makeText(applicationContext, "Laser Pointer Active", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnLaser)

        // Whiteboard Mode button
        val btnWhiteboard = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_whiteboard)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                val currentWb = drawingView?.whiteboardMode ?: DrawingView.WhiteboardMode.TRANSPARENT
                val nextWb = when (currentWb) {
                    DrawingView.WhiteboardMode.TRANSPARENT -> DrawingView.WhiteboardMode.WHITE
                    DrawingView.WhiteboardMode.WHITE -> DrawingView.WhiteboardMode.DARK
                    DrawingView.WhiteboardMode.DARK -> DrawingView.WhiteboardMode.TRANSPARENT
                }
                drawingView?.whiteboardMode = nextWb
                drawingView?.invalidate()
                Toast.makeText(applicationContext, "Whiteboard: ${nextWb.name}", Toast.LENGTH_SHORT).show()
            }
        }
        layout.addView(btnWhiteboard)

        // Eraser button
        val btnEraser = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                val currentEraser = drawingView?.isEraser() ?: false
                drawingView?.setEraserMode(!currentEraser)
                setColorFilter(if (!currentEraser) Color.YELLOW else Color.WHITE)
            }
        }
        layout.addView(btnEraser)

        // Undo button
        val btnUndo = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_play)
            rotation = 180f
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                drawingView?.undo()
            }
        }
        layout.addView(btnUndo)

        // Redo button
        val btnRedo = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_redo)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                drawingView?.redo()
            }
        }
        layout.addView(btnRedo)

        // Clear button
        val btnClear = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_stop)
            setColorFilter(Color.RED)
            setBackgroundColor(Color.TRANSPARENT)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                drawingView?.clear()
            }
        }
        layout.addView(btnClear)

        // Interact / Touch Passthrough toggle button
        val btnPassthrough = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_touch_passthrough)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.btn_touch_passthrough)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                toggleTouchPassthrough()
                setColorFilter(if (isPassthroughActive) Color.GREEN else Color.WHITE)
            }
        }
        layout.addView(btnPassthrough)

        // Exit / Close drawing area button
        val btnExit = ImageButton(themedContext).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter("#FF5252".toColorInt())
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = getString(R.string.btn_exit_drawing)
            val size = (30 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply { setMargins(4, 0, 4, 0) }
            setOnClickListener {
                stopSelf()
            }
        }
        layout.addView(btnExit)

        card.addView(layout)
        rootContainer.addView(card)
        toolbarView = rootContainer

        toolbarParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        applyDragLogic(toolbarView!!)

        try {
            windowManager?.addView(toolbarView, toolbarParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun toggleTouchPassthrough() {
        if (drawingView == null) return
        isPassthroughActive = !isPassthroughActive
        if (isPassthroughActive) {
            canvasParams.flags = canvasParams.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            Toast.makeText(applicationContext, "Interact Mode: Touch apps underneath", Toast.LENGTH_SHORT).show()
        } else {
            canvasParams.flags = canvasParams.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
            Toast.makeText(applicationContext, "Drawing Mode: Draw on screen", Toast.LENGTH_SHORT).show()
        }
        try {
            windowManager?.updateViewLayout(drawingView, canvasParams)
        } catch (_: Exception) {}
    }

    private fun showTextInputDialog() {
        dismissTextDialog()

        val themedContext = getThemedContext()
        val card = MaterialCardView(themedContext).apply {
            radius = 20f * resources.displayMetrics.density
            setCardBackgroundColor("#222222".toColorInt())
            strokeWidth = (1f * resources.displayMetrics.density).toInt()
            strokeColor = "#44FFFFFF".toColorInt()
        }

        val layout = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 24, 32, 24)
        }

        val editText = EditText(themedContext).apply {
            hint = "Type text note..."
            setHintTextColor(Color.GRAY)
            setTextColor(Color.WHITE)
            textSize = 16f
            setSingleLine()
        }

        val btnAdd = Button(themedContext).apply {
            setText(R.string.btn_text)
            setOnClickListener {
                val txt = editText.text.toString().trim()
                if (txt.isNotEmpty()) {
                    drawingView?.addTextAnnotation(txt, screenWidth / 3f, screenHeight / 3f)
                }
                dismissTextDialog()
            }
        }

        layout.addView(editText)
        layout.addView(btnAdd)
        card.addView(layout)
        textDialogView = card

        val dialogParams = WindowManager.LayoutParams(
            (280 * resources.displayMetrics.density).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        try {
            windowManager?.addView(textDialogView, dialogParams)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun dismissTextDialog() {
        textDialogView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        textDialogView = null
    }

    private fun applyDragLogic(view: View) {
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var initialX = 0; var initialY = 0; var touchX = 0f; var touchY = 0f
        var isDragging = false

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = toolbarParams.x
                    initialY = toolbarParams.y
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
                        toolbarParams.x = initialX + dx
                        toolbarParams.y = initialY - dy
                        try {
                            windowManager?.updateViewLayout(toolbarView, toolbarParams)
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
        dismissTextDialog()
        drawingView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        toolbarView?.let {
            try {
                windowManager?.removeView(it)
            } catch (_: Exception) {}
        }
        drawingView = null
        toolbarView = null
    }
}
