package com.openscreenrecorder.app

import android.content.Context
import android.graphics.*
import android.os.SystemClock
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.toColorInt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class ToolMode {
        PEN,
        ERASER,
        LINE,
        ARROW,
        RECTANGLE,
        CIRCLE,
        TEXT,
        LASER_POINTER
    }

    enum class WhiteboardMode {
        TRANSPARENT,
        WHITE,
        DARK
    }

    sealed class DrawingItem {
        data class PathItem(
            val path: Path,
            val color: Int,
            val strokeWidth: Float,
            val isEraser: Boolean
        ) : DrawingItem()

        data class TextItem(
            val text: String,
            val x: Float,
            val y: Float,
            val color: Int,
            val textSize: Float
        ) : DrawingItem()
    }

    private data class LaserPoint(val x: Float, val y: Float, val timeMs: Long)

    private val items = mutableListOf<DrawingItem>()
    private val undoStack = mutableListOf<DrawingItem>()

    var activeToolMode: ToolMode = ToolMode.PEN
    var whiteboardMode: WhiteboardMode = WhiteboardMode.TRANSPARENT

    private var currentPath: Path? = null
    private var startX = 0f
    private var startY = 0f

    private var currentColor = Color.RED
    private var currentStrokeWidth = 12f

    private val laserPoints = mutableListOf<LaserPoint>()

    private val drawPaint = Paint().apply {
        isAntiAlias = true
        isDither = true
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val clearPaint = Paint().apply {
        isAntiAlias = true
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }

    private val textPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        typeface = Typeface.DEFAULT_BOLD
    }

    private val laserHaloPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.RED
        alpha = 140
    }

    private val laserCorePaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.FILL
        color = Color.WHITE
    }

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
    }

    fun setBrushColor(color: Int) {
        currentColor = color
        if (activeToolMode == ToolMode.ERASER) {
            activeToolMode = ToolMode.PEN
        }
        invalidate()
    }

    fun setBrushWidth(width: Float) {
        currentStrokeWidth = width
    }

    fun setEraserMode(enabled: Boolean) {
        activeToolMode = if (enabled) ToolMode.ERASER else ToolMode.PEN
    }

    fun isEraser(): Boolean = activeToolMode == ToolMode.ERASER

    fun addTextAnnotation(text: String, x: Float, y: Float) {
        if (text.isNotBlank()) {
            items.add(DrawingItem.TextItem(text, x, y, currentColor, currentStrokeWidth * 3.5f))
            undoStack.clear()
            invalidate()
        }
    }

    fun undo() {
        if (items.isNotEmpty()) {
            val removed = items.removeAt(items.size - 1)
            undoStack.add(removed)
            invalidate()
        }
    }

    fun redo() {
        if (undoStack.isNotEmpty()) {
            val restored = undoStack.removeAt(undoStack.size - 1)
            items.add(restored)
            invalidate()
        }
    }

    fun clear() {
        items.clear()
        undoStack.clear()
        laserPoints.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Draw Whiteboard Background if enabled
        when (whiteboardMode) {
            WhiteboardMode.WHITE -> canvas.drawColor(Color.WHITE)
            WhiteboardMode.DARK -> canvas.drawColor("#121212".toColorInt())
            WhiteboardMode.TRANSPARENT -> {}
        }

        // Render saved items
        for (item in items) {
            when (item) {
                is DrawingItem.PathItem -> {
                    if (item.isEraser) {
                        clearPaint.strokeWidth = item.strokeWidth * 2.5f
                        canvas.drawPath(item.path, clearPaint)
                    } else {
                        drawPaint.color = item.color
                        drawPaint.strokeWidth = item.strokeWidth
                        canvas.drawPath(item.path, drawPaint)
                    }
                }
                is DrawingItem.TextItem -> {
                    textPaint.color = item.color
                    textPaint.textSize = item.textSize.coerceAtLeast(36f)
                    canvas.drawText(item.text, item.x, item.y, textPaint)
                }
            }
        }

        // Render current in-progress shape or path
        currentPath?.let { path ->
            if (activeToolMode == ToolMode.ERASER) {
                clearPaint.strokeWidth = currentStrokeWidth * 2.5f
                canvas.drawPath(path, clearPaint)
            } else {
                drawPaint.color = currentColor
                drawPaint.strokeWidth = currentStrokeWidth
                canvas.drawPath(path, drawPaint)
            }
        }

        // Render Laser Pointer & Fading Trail
        if (laserPoints.isNotEmpty()) {
            val now = SystemClock.uptimeMillis()
            laserPoints.removeAll { now - it.timeMs > 600L }

            for (p in laserPoints) {
                val age = now - p.timeMs
                val alphaPercent = (1.0f - (age.toFloat() / 600f)).coerceIn(0f, 1f)
                laserHaloPaint.alpha = (160 * alphaPercent).toInt()
                laserHaloPaint.color = currentColor

                canvas.drawCircle(p.x, p.y, 22f * alphaPercent + 8f, laserHaloPaint)
                canvas.drawCircle(p.x, p.y, 8f * alphaPercent + 3f, laserCorePaint)
            }

            if (laserPoints.isNotEmpty()) {
                postInvalidateDelayed(16)
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        if (activeToolMode == ToolMode.LASER_POINTER) {
            when (event.action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    laserPoints.add(LaserPoint(x, y, SystemClock.uptimeMillis()))
                    invalidate()
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    performClick()
                    invalidate()
                    return true
                }
            }
            return true
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                val newPath = Path().apply { moveTo(x, y) }
                currentPath = newPath
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                when (activeToolMode) {
                    ToolMode.PEN, ToolMode.ERASER -> {
                        currentPath?.lineTo(x, y)
                    }
                    ToolMode.LINE -> {
                        val path = Path().apply {
                            moveTo(startX, startY)
                            lineTo(x, y)
                        }
                        currentPath = path
                    }
                    ToolMode.ARROW -> {
                        currentPath = buildArrowPath(startX, startY, x, y)
                    }
                    ToolMode.RECTANGLE -> {
                        val path = Path().apply {
                            addRect(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y), Path.Direction.CW)
                        }
                        currentPath = path
                    }
                    ToolMode.CIRCLE -> {
                        val path = Path().apply {
                            addOval(RectF(minOf(startX, x), minOf(startY, y), maxOf(startX, x), maxOf(startY, y)), Path.Direction.CW)
                        }
                        currentPath = path
                    }
                    ToolMode.TEXT, ToolMode.LASER_POINTER -> {}
                }
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP -> {
                currentPath?.let { path ->
                    items.add(DrawingItem.PathItem(path, currentColor, currentStrokeWidth, activeToolMode == ToolMode.ERASER))
                    undoStack.clear()
                }
                currentPath = null
                performClick()
                invalidate()
                return true
            }
            else -> return false
        }
    }

    private fun buildArrowPath(x1: Float, y1: Float, x2: Float, y2: Float): Path {
        val path = Path()
        path.moveTo(x1, y1)
        path.lineTo(x2, y2)

        val angle = atan2((y2 - y1).toDouble(), (x2 - x1).toDouble())
        val arrowHeadSize = (currentStrokeWidth * 3.5f).coerceAtLeast(30f)

        val x3 = (x2 - arrowHeadSize * cos(angle - Math.PI / 6)).toFloat()
        val y3 = (y2 - arrowHeadSize * sin(angle - Math.PI / 6)).toFloat()

        val x4 = (x2 - arrowHeadSize * cos(angle + Math.PI / 6)).toFloat()
        val y4 = (y2 - arrowHeadSize * sin(angle + Math.PI / 6)).toFloat()

        path.moveTo(x2, y2)
        path.lineTo(x3, y3)
        path.moveTo(x2, y2)
        path.lineTo(x4, y4)

        return path
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
