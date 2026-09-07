package com.openscreenrecorder.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import java.util.concurrent.atomic.AtomicBoolean
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.TrendingFlat
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import androidx.core.net.toUri
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ScreenshotEditorActivity : ComponentActivity() {

    companion object {
        private const val TAG = "ScreenshotEditor"
        const val EXTRA_IMAGE_URI = "extra_image_uri"
        const val EXTRA_MODE = "extra_mode"
        const val MODE_EDIT = "MODE_EDIT"
        const val MODE_CAPTURE = "MODE_CAPTURE"
        const val MODE_SCROLLING = "MODE_SCROLLING"
    }

    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var windowManager: WindowManager? = null

    private var screenWidth = 1080
    private var screenHeight = 1920
    private var screenDensityDpi = 320

    private val capturedFrames = mutableListOf<Bitmap>()
    private var currentBitmapState = mutableStateOf<Bitmap?>(null)
    private var isScrollingOverlayActive = mutableStateOf(false)
    private var scrollingOverlayView: View? = null

    private val projectionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            setupMediaProjection(result.resultCode, result.data!!)
        } else {
            Toast.makeText(this, "Screen capture permission denied", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = windowManager?.currentWindowMetrics
        screenWidth = metrics?.bounds?.width() ?: 1080
        screenHeight = metrics?.bounds?.height() ?: 1920
        screenDensityDpi = resources.displayMetrics.densityDpi

        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        val uriStr = intent.getStringExtra(EXTRA_IMAGE_URI)
        val mode = intent.getStringExtra(EXTRA_MODE) ?: MODE_CAPTURE
        @Suppress("DEPRECATION")
        val projectionData = intent.getParcelableExtra<Intent>("PROJECTION_DATA")
        val resultCode = intent.getIntExtra("PROJECTION_RESULT_CODE", RESULT_OK)

        if (!uriStr.isNullOrEmpty()) {
            loadBitmapFromUri(uriStr.toUri())
        } else if (projectionData != null) {
            setupMediaProjection(resultCode, projectionData)
        } else if (mode == MODE_SCROLLING) {
            requestScreenCapturePermission()
        } else {
            requestScreenCapturePermission()
        }

        setContent {
            OpenScreenRecorderTheme {
                val bitmap = currentBitmapState.value
                if (bitmap != null) {
                    ScreenshotEditorScreen(
                        initialBitmap = bitmap,
                        onBackClick = { finish() },
                        onSaveScreenshot = { editedBitmap ->
                            saveBitmapToGallery(editedBitmap)
                        },
                        onStartScrollingMode = {
                            if (mediaProjection == null) {
                                requestScreenCapturePermission()
                            } else {
                                startScrollingOverlay()
                            }
                        }
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }

    private fun loadBitmapFromUri(uri: Uri) {
        try {
            contentResolver.openInputStream(uri)?.use { stream ->
                val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
                val bmp = BitmapFactory.decodeStream(stream, null, options)
                if (bmp != null) {
                    currentBitmapState.value = bmp
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bitmap from uri: ${e.message}")
        }
    }

    private fun requestScreenCapturePermission() {
        val captureIntent = try {
            val config = MediaProjectionConfig.createConfigForUserChoice()
            mediaProjectionManager?.createScreenCaptureIntent(config)
        } catch (_: Exception) {
            mediaProjectionManager?.createScreenCaptureIntent()
        }
        captureIntent?.let { projectionLauncher.launch(it) }
    }

    private fun setupMediaProjection(resultCode: Int, data: Intent) {
        try {
            mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
            mediaProjection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() {
                    Log.d(TAG, "MediaProjection stopped")
                }
            }, Handler(Looper.getMainLooper()))
            val mode = intent.getStringExtra(EXTRA_MODE)
            if (mode == MODE_SCROLLING) {
                startScrollingOverlay()
            } else {
                captureSingleFrameAndEdit()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up media projection: ${e.message}")
        }
    }

    private fun captureSingleFrameAndEdit() {
        moveTaskToBack(true)
        Handler(Looper.getMainLooper()).postDelayed({
            captureScreenFrame { bitmap ->
                if (bitmap != null) {
                    currentBitmapState.value = bitmap
                    val intent = Intent(this, ScreenshotEditorActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(this, "Failed to capture screenshot", Toast.LENGTH_SHORT).show()
                    finish()
                }
                stopMediaProjection()
            }
        }, 300)
    }

    private fun captureScreenFrame(onCaptured: (Bitmap?) -> Unit) {
        val handlerThread = HandlerThread("ScreenshotCaptureThread").apply { start() }
        val bgHandler = Handler(handlerThread.looper)
        val isCaptured = AtomicBoolean(false)

        try {
            val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            val cleanup = Runnable {
                try { reader.close() } catch (_: Exception) {}
                try { virtualDisplay?.release(); virtualDisplay = null } catch (_: Exception) {}
                try { handlerThread.quitSafely() } catch (_: Exception) {}
            }

            bgHandler.postDelayed({
                if (!isCaptured.get()) {
                    Log.w(TAG, "Screenshot capture timed out")
                    isCaptured.set(true)
                    cleanup.run()
                    Handler(Looper.getMainLooper()).post { onCaptured(null) }
                }
            }, 1500)

            reader.setOnImageAvailableListener({ r ->
                if (isCaptured.getAndSet(true)) return@setOnImageAvailableListener
                var capturedBitmap: Bitmap? = null
                try {
                    val image: Image? = try { r.acquireLatestImage() } catch (_: Exception) { null }
                    if (image != null) {
                        capturedBitmap = processCapturedImage(image, screenWidth, screenHeight)
                        try { image.close() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to capture screen frame: ${e.message}")
                } finally {
                    cleanup.run()
                    Handler(Looper.getMainLooper()).post {
                        onCaptured(capturedBitmap)
                    }
                }
            }, bgHandler)

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenshotEditorDisplay",
                screenWidth,
                screenHeight,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                bgHandler
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error creating virtual display: ${e.message}")
            try { handlerThread.quitSafely() } catch (_: Exception) {}
            onCaptured(null)
        }
    }

    private fun processCapturedImage(image: Image, targetWidth: Int, targetHeight: Int): Bitmap? {
        val planes = image.planes
        if (planes.isEmpty()) return null
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        if (pixelStride <= 0) return null

        val widthInPixels = rowStride / pixelStride
        val rawBitmap = createBitmap(widthInPixels, targetHeight)
        rawBitmap.copyPixelsFromBuffer(buffer)

        val cropWidth = widthInPixels.coerceAtMost(targetWidth)
        val bitmapToProcess = if (cropWidth < widthInPixels) {
            val cropped = Bitmap.createBitmap(rawBitmap, 0, 0, cropWidth, targetHeight)
            rawBitmap.recycle()
            cropped
        } else {
            rawBitmap
        }

        val pixels = IntArray(cropWidth * targetHeight)
        bitmapToProcess.getPixels(pixels, 0, cropWidth, 0, 0, cropWidth, targetHeight)

        var maxRgb = 0
        for (i in pixels.indices) {
            val pixel = pixels[i]
            val a = (pixel ushr 24) and 0xFF
            var r = (pixel ushr 16) and 0xFF
            var g = (pixel ushr 8) and 0xFF
            var b = pixel and 0xFF

            if (a in 1..254) {
                val unpremulScale = 255f / a
                r = (r * unpremulScale).toInt().coerceAtMost(255)
                g = (g * unpremulScale).toInt().coerceAtMost(255)
                b = (b * unpremulScale).toInt().coerceAtMost(255)
            }

            if (r > maxRgb) maxRgb = r
            if (g > maxRgb) maxRgb = g
            if (b > maxRgb) maxRgb = b

            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }

        if (maxRgb in 1..249) {
            val gain = (255.0f / maxRgb.coerceAtLeast(60)).coerceAtMost(2.5f)
            if (gain > 1.01f) {
                for (i in pixels.indices) {
                    val pixel = pixels[i]
                    val r = (((pixel ushr 16) and 0xFF) * gain).toInt().coerceAtMost(255)
                    val g = (((pixel ushr 8) and 0xFF) * gain).toInt().coerceAtMost(255)
                    val b = ((pixel and 0xFF) * gain).toInt().coerceAtMost(255)
                    pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }

        bitmapToProcess.setPixels(pixels, 0, cropWidth, 0, 0, cropWidth, targetHeight)
        bitmapToProcess.setHasAlpha(false)
        return bitmapToProcess
    }

    @SuppressLint("SetTextI18n")
    private fun startScrollingOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Overlay permission required for Scrolling Screenshot", Toast.LENGTH_LONG).show()
            return
        }

        isScrollingOverlayActive.value = true
        moveTaskToBack(true)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(24, 16, 24, 16)
            setBackgroundColor(0xEE121212.toInt())
        }

        val btnCapture = Button(this).apply {
            text = "Capture Page (+)"
            setOnClickListener {
                visibility = View.INVISIBLE
                Handler(Looper.getMainLooper()).postDelayed({
                    captureScreenFrame { bmp ->
                        visibility = View.VISIBLE
                        if (bmp != null) {
                            capturedFrames.add(bmp)
                            Toast.makeText(applicationContext, "Page ${capturedFrames.size} captured! Scroll down for next page.", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(applicationContext, "Failed to capture page", Toast.LENGTH_SHORT).show()
                        }
                    }
                }, 200)
            }
        }

        val btnFinish = Button(this).apply {
            text = "Done & Edit"
            setOnClickListener {
                dismissScrollingOverlay()
                if (capturedFrames.isNotEmpty()) {
                    val stitched = stitchBitmaps(capturedFrames)
                    currentBitmapState.value = stitched
                    val intent = Intent(applicationContext, ScreenshotEditorActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(intent)
                } else {
                    Toast.makeText(applicationContext, "No pages captured", Toast.LENGTH_SHORT).show()
                }
            }
        }

        layout.addView(btnCapture)
        layout.addView(btnFinish)
        scrollingOverlayView = layout

        val overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            y = 120
        }

        try {
            windowManager?.addView(scrollingOverlayView, overlayParams)
        } catch (e: Exception) {
            Log.e(TAG, "Error adding scrolling overlay: ${e.message}")
        }
    }

    private fun dismissScrollingOverlay() {
        scrollingOverlayView?.let {
            try { windowManager?.removeView(it) } catch (_: Exception) {}
        }
        scrollingOverlayView = null
        isScrollingOverlayActive.value = false
        stopMediaProjection()
    }

    private fun stitchBitmaps(frames: List<Bitmap>): Bitmap {
        if (frames.isEmpty()) return createBitmap(1080, 1920)
        if (frames.size == 1) return frames[0]

        val maxWidth = frames.maxOf { it.width }
        val totalHeight = frames.sumOf { it.height }

        val stitched = createBitmap(maxWidth, totalHeight)
        val canvas = Canvas(stitched)
        var currentY = 0f

        for (frame in frames) {
            canvas.drawBitmap(frame, 0f, currentY, null)
            currentY += frame.height
        }
        return stitched
    }

    private fun saveBitmapToGallery(bitmap: Bitmap) {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "Screenshot_Edit_$timestamp.png"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Recordings")
        }

        try {
            val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                contentResolver.openOutputStream(uri)?.use { out: OutputStream ->
                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                }
                contentResolver.notifyChange(uri, null)
                Toast.makeText(this, "Saved to Gallery!", Toast.LENGTH_SHORT).show()
                finish()
            } else {
                Toast.makeText(this, "Failed to save screenshot", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save bitmap: ${e.message}")
            Toast.makeText(this, "Error saving screenshot", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopMediaProjection() {
        try { mediaProjection?.stop(); mediaProjection = null } catch (_: Exception) {}
    }

    override fun onDestroy() {
        super.onDestroy()
        dismissScrollingOverlay()
        stopMediaProjection()
    }
}

enum class AnnotationTool {
    PEN,
    ARROW,
    RECTANGLE,
    CIRCLE,
    TEXT,
    ERASER,
    CROP
}

enum class AppFontFamily(val displayName: String, val typefaceName: String, val style: Int) {
    SANS_SERIF("Sans-Serif", "sans-serif", Typeface.NORMAL),
    SERIF("Serif", "serif", Typeface.NORMAL),
    MONOSPACE("Monospace", "monospace", Typeface.NORMAL),
    CURSIVE("Cursive", "cursive", Typeface.BOLD),
    CASUAL("Casual", "casual", Typeface.NORMAL),
    BOLD("Bold", "sans-serif", Typeface.BOLD),
    CONDENSED("Condensed", "sans-serif-condensed", Typeface.BOLD);

    fun getTypeface(): Typeface {
        return Typeface.create(typefaceName, style)
    }
}

fun getFontTypeface(fontName: String): Typeface {
    return try {
        AppFontFamily.valueOf(fontName).getTypeface()
    } catch (_: Exception) {
        Typeface.DEFAULT
    }
}

data class DrawnAnnotation(
    val tool: AnnotationTool,
    val path: Path,
    var startOffset: Offset,
    var endOffset: Offset,
    val color: Color,
    val strokeWidth: Float,
    val textNote: String = "",
    val fontName: String = AppFontFamily.SANS_SERIF.name,
    val fontSize: Float = 48f
)

fun findTextAnnotationIndexAt(
    annotations: List<DrawnAnnotation>,
    offset: Offset
): Int {
    val paint = Paint().apply { isAntiAlias = true }
    for (i in annotations.indices.reversed()) {
        val item = annotations[i]
        if (item.tool == AnnotationTool.TEXT && item.textNote.isNotBlank()) {
            paint.textSize = item.fontSize
            paint.typeface = getFontTypeface(item.fontName)
            val bounds = Rect()
            paint.getTextBounds(item.textNote, 0, item.textNote.length, bounds)
            val textWidth = paint.measureText(item.textNote).coerceAtLeast(40f)
            val textHeight = bounds.height().toFloat().coerceAtLeast(item.fontSize)

            val left = item.startOffset.x - 24f
            val top = item.startOffset.y - textHeight - 24f
            val right = item.startOffset.x + textWidth + 24f
            val bottom = item.startOffset.y + 24f

            if (offset.x in left..right && offset.y in top..bottom) {
                return i
            }
        }
    }
    return -1
}

fun eraseAnnotationAt(
    annotations: SnapshotStateList<DrawnAnnotation>,
    undoStack: SnapshotStateList<DrawnAnnotation>,
    touchOffset: Offset,
    eraserRadius: Float
): Boolean {
    var erasedAny = false
    val paint = Paint().apply { isAntiAlias = true }

    for (i in annotations.indices.reversed()) {
        val item = annotations[i]
        var hit = false

        when (item.tool) {
            AnnotationTool.TEXT -> {
                if (item.textNote.isNotBlank()) {
                    paint.textSize = item.fontSize
                    paint.typeface = getFontTypeface(item.fontName)
                    val bounds = Rect()
                    paint.getTextBounds(item.textNote, 0, item.textNote.length, bounds)
                    val textWidth = paint.measureText(item.textNote).coerceAtLeast(40f)
                    val textHeight = bounds.height().toFloat().coerceAtLeast(item.fontSize)

                    val left = item.startOffset.x - eraserRadius
                    val top = item.startOffset.y - textHeight - eraserRadius
                    val right = item.startOffset.x + textWidth + eraserRadius
                    val bottom = item.startOffset.y + eraserRadius

                    if (touchOffset.x in left..right && touchOffset.y in top..bottom) {
                        hit = true
                    }
                }
            }
            AnnotationTool.RECTANGLE, AnnotationTool.CIRCLE -> {
                val left = minOf(item.startOffset.x, item.endOffset.x) - eraserRadius
                val top = minOf(item.startOffset.y, item.endOffset.y) - eraserRadius
                val right = maxOf(item.startOffset.x, item.endOffset.x) + eraserRadius
                val bottom = maxOf(item.startOffset.y, item.endOffset.y) + eraserRadius

                if (touchOffset.x in left..right && touchOffset.y in top..bottom) {
                    hit = true
                }
            }
            AnnotationTool.ARROW -> {
                val dist = distanceToSegment(touchOffset, item.startOffset, item.endOffset)
                if (dist <= eraserRadius + item.strokeWidth) {
                    hit = true
                }
            }
            AnnotationTool.PEN, AnnotationTool.ERASER -> {
                val bounds = RectF()
                item.path.asAndroidPath().computeBounds(bounds, true)
                if (touchOffset.x >= bounds.left - eraserRadius && touchOffset.x <= bounds.right + eraserRadius &&
                    touchOffset.y >= bounds.top - eraserRadius && touchOffset.y <= bounds.bottom + eraserRadius) {
                    hit = true
                }
            }
            else -> {}
        }

        if (hit) {
            val removed = annotations.removeAt(i)
            undoStack.add(removed)
            erasedAny = true
        }
    }
    return erasedAny
}

private fun distanceToSegment(p: Offset, v: Offset, w: Offset): Float {
    val l2 = (w.x - v.x) * (w.x - v.x) + (w.y - v.y) * (w.y - v.y)
    if (l2 == 0f) return (p - v).getDistance()
    var t = ((p.x - v.x) * (w.x - v.x) + (p.y - v.y) * (w.y - v.y)) / l2
    t = t.coerceIn(0f, 1f)
    val projection = Offset(v.x + t * (w.x - v.x), v.y + t * (w.y - v.y))
    return (p - projection).getDistance()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScreenshotEditorScreen(
    initialBitmap: Bitmap,
    onBackClick: () -> Unit,
    onSaveScreenshot: (Bitmap) -> Unit,
    onStartScrollingMode: () -> Unit
) {
    val context = LocalContext.current
    var currentBitmap by remember { mutableStateOf(initialBitmap) }
    var activeTool by remember { mutableStateOf(AnnotationTool.PEN) }
    var activeColor by remember { mutableStateOf(Color.Red) }
    var strokeWidth by remember { mutableFloatStateOf(10f) }

    val annotations = remember { mutableStateListOf<DrawnAnnotation>() }
    val undoStack = remember { mutableStateListOf<DrawnAnnotation>() }

    var currentPath by remember { mutableStateOf<Path?>(null) }
    var dragStart by remember { mutableStateOf(Offset.Zero) }
    var dragEnd by remember { mutableStateOf(Offset.Zero) }

    var textInputText by remember { mutableStateOf("") }
    var showTextDialog by remember { mutableStateOf(false) }

    var draggingTextIndex by remember { mutableIntStateOf(-1) }
    var editingTextIndex by remember { mutableIntStateOf(-1) }
    var selectedTextIndex by remember { mutableIntStateOf(-1) }
    var pendingTextPlacementOffset by remember { mutableStateOf(Offset(200f, 300f)) }
    var selectedFont by remember { mutableStateOf(AppFontFamily.SANS_SERIF) }
    var selectedFontSize by remember { mutableFloatStateOf(48f) }
    var eraserPosition by remember { mutableStateOf<Offset?>(null) }

    // Crop bounds
    var cropLeft by remember { mutableFloatStateOf(0f) }
    var cropTop by remember { mutableFloatStateOf(0f) }
    var cropRight by remember { mutableFloatStateOf(0f) }
    var cropBottom by remember { mutableFloatStateOf(0f) }

    val colors = listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.White, Color.Black)

    fun applyCrop() {
        if (cropRight <= cropLeft || cropBottom <= cropTop) return
        try {
            val bmpW = currentBitmap.width.toFloat()
            val bmpH = currentBitmap.height.toFloat()

            val x = cropLeft.coerceIn(0f, bmpW - 10f).toInt()
            val y = cropTop.coerceIn(0f, bmpH - 10f).toInt()
            val w = (cropRight - cropLeft).coerceIn(10f, bmpW - x).toInt()
            val h = (cropBottom - cropTop).coerceIn(10f, bmpH - y).toInt()

            val cropped = Bitmap.createBitmap(currentBitmap, x, y, w, h)
            currentBitmap = cropped
            annotations.clear()
            undoStack.clear()
            Toast.makeText(context, "Image cropped", Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Crop failed", Toast.LENGTH_SHORT).show()
        }
    }

    fun renderFinalBitmap(): Bitmap {
        val result = createBitmap(currentBitmap.width, currentBitmap.height)
        val canvas = Canvas(result)
        canvas.drawBitmap(currentBitmap, 0f, 0f, null)

        val paint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        val textPaint = Paint().apply {
            isAntiAlias = true
            style = Paint.Style.FILL
            textSize = 42f
            typeface = Typeface.DEFAULT_BOLD
        }

        for (item in annotations) {
            paint.color = item.color.toArgb()
            paint.strokeWidth = item.strokeWidth

            when (item.tool) {
                AnnotationTool.PEN, AnnotationTool.ERASER -> {
                    if (item.tool == AnnotationTool.ERASER) {
                        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
                    } else {
                        paint.xfermode = null
                    }
                    canvas.drawPath(item.path.asAndroidPath(), paint)
                }
                AnnotationTool.ARROW -> {
                    paint.xfermode = null
                    canvas.drawPath(buildArrowAndroidPath(item.startOffset, item.endOffset, item.strokeWidth), paint)
                }
                AnnotationTool.RECTANGLE -> {
                    paint.xfermode = null
                    val rect = RectF(
                        minOf(item.startOffset.x, item.endOffset.x),
                        minOf(item.startOffset.y, item.endOffset.y),
                        maxOf(item.startOffset.x, item.endOffset.x),
                        maxOf(item.startOffset.y, item.endOffset.y)
                    )
                    canvas.drawRect(rect, paint)
                }
                AnnotationTool.CIRCLE -> {
                    paint.xfermode = null
                    val rect = RectF(
                        minOf(item.startOffset.x, item.endOffset.x),
                        minOf(item.startOffset.y, item.endOffset.y),
                        maxOf(item.startOffset.x, item.endOffset.x),
                        maxOf(item.startOffset.y, item.endOffset.y)
                    )
                    canvas.drawOval(rect, paint)
                }
                AnnotationTool.TEXT -> {
                    textPaint.color = item.color.toArgb()
                    textPaint.textSize = item.fontSize
                    textPaint.typeface = getFontTypeface(item.fontName)
                    canvas.drawText(item.textNote, item.startOffset.x, item.startOffset.y, textPaint)
                }
                else -> {}
            }
        }
        return result
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Screenshot Editor") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        if (annotations.isNotEmpty()) {
                            val removed = annotations.removeAt(annotations.size - 1)
                            undoStack.add(removed)
                        }
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Undo, contentDescription = "Undo")
                    }
                    IconButton(onClick = {
                        if (undoStack.isNotEmpty()) {
                            val restored = undoStack.removeAt(undoStack.size - 1)
                            annotations.add(restored)
                        }
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.Redo, contentDescription = "Redo")
                    }
                    IconButton(onClick = onStartScrollingMode) {
                        Icon(imageVector = Icons.Default.VerticalAlignBottom, contentDescription = "Scrolling Screenshot")
                    }
                    IconButton(onClick = {
                        val finalBmp = renderFinalBitmap()
                        onSaveScreenshot(finalBmp)
                    }) {
                        Icon(imageVector = Icons.Default.Save, contentDescription = "Save", tint = MaterialTheme.colorScheme.primary)
                    }
                }
            )
        },
        bottomBar = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Color Palette
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    colors.forEach { col ->
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(col, CircleShape)
                                .border(
                                    if (activeColor == col) 3.dp else 1.dp,
                                    if (activeColor == col) MaterialTheme.colorScheme.primary else Color.Gray,
                                    CircleShape
                                )
                                .pointerInput(Unit) {
                                    detectDragGestures { _, _ -> }
                                }
                        ) {
                            TextButton(onClick = { activeColor = col }) {}
                        }
                    }
                }

                // Tools row (Horizontally Scrollable)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    FilterChip(
                        selected = activeTool == AnnotationTool.PEN,
                        onClick = { activeTool = AnnotationTool.PEN },
                        label = { Text("Pen") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) }
                    )
                    FilterChip(
                        selected = activeTool == AnnotationTool.ARROW,
                        onClick = { activeTool = AnnotationTool.ARROW },
                        label = { Text("Arrow") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.TrendingFlat, contentDescription = null) }
                    )
                    FilterChip(
                        selected = activeTool == AnnotationTool.RECTANGLE,
                        onClick = { activeTool = AnnotationTool.RECTANGLE },
                        label = { Text("Rect") },
                        leadingIcon = { Icon(Icons.Default.CropSquare, contentDescription = null) }
                    )
                    FilterChip(
                        selected = activeTool == AnnotationTool.CIRCLE,
                        onClick = { activeTool = AnnotationTool.CIRCLE },
                        label = { Text("Circle") },
                        leadingIcon = { Icon(Icons.Default.RadioButtonUnchecked, contentDescription = null) }
                    )
                    FilterChip(
                        selected = activeTool == AnnotationTool.TEXT,
                        onClick = {
                            activeTool = AnnotationTool.TEXT
                            editingTextIndex = -1
                            textInputText = ""
                            pendingTextPlacementOffset = Offset(200f, 300f)
                            selectedFont = AppFontFamily.SANS_SERIF
                            selectedFontSize = 48f
                            showTextDialog = true
                        },
                        label = { Text("Text") },
                        leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) }
                    )
                    FilterChip(
                        selected = activeTool == AnnotationTool.ERASER,
                        onClick = { activeTool = AnnotationTool.ERASER },
                        label = { Text("Eraser") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) }
                    )
                    FilterChip(
                        selected = activeTool == AnnotationTool.CROP,
                        onClick = {
                            activeTool = AnnotationTool.CROP
                            cropLeft = 0f
                            cropTop = 0f
                            cropRight = currentBitmap.width.toFloat()
                            cropBottom = currentBitmap.height.toFloat()
                        },
                        label = { Text("Crop") },
                        leadingIcon = { Icon(Icons.Default.Crop, contentDescription = null) }
                    )
                }

                if (activeTool == AnnotationTool.CROP) {
                    Button(
                        onClick = { applyCrop() },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("Apply Crop")
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            val imageBitmap = remember(currentBitmap) { currentBitmap.asImageBitmap() }

            Canvas(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStart = offset
                                dragEnd = offset
                                if (activeTool == AnnotationTool.ERASER) {
                                    eraserPosition = offset
                                    selectedTextIndex = -1
                                    eraseAnnotationAt(annotations, undoStack, offset, (strokeWidth * 3.5f).coerceAtLeast(36f))
                                } else {
                                    eraserPosition = null
                                    val hitIndex = findTextAnnotationIndexAt(annotations, offset)
                                    if (hitIndex != -1) {
                                        draggingTextIndex = hitIndex
                                        selectedTextIndex = hitIndex
                                        val item = annotations[hitIndex]
                                        activeColor = item.color
                                    } else {
                                        draggingTextIndex = -1
                                        selectedTextIndex = -1
                                        if (activeTool == AnnotationTool.PEN) {
                                            val p = Path().apply { moveTo(offset.x, offset.y) }
                                            currentPath = p
                                        } else if (activeTool == AnnotationTool.TEXT) {
                                            pendingTextPlacementOffset = offset
                                            editingTextIndex = -1
                                            textInputText = ""
                                            selectedFont = AppFontFamily.SANS_SERIF
                                            selectedFontSize = 48f
                                            showTextDialog = true
                                        }
                                    }
                                }
                            },
                            onDrag = { change, dragAmount ->
                                dragEnd = change.position
                                if (activeTool == AnnotationTool.ERASER) {
                                    eraserPosition = change.position
                                    eraseAnnotationAt(annotations, undoStack, change.position, (strokeWidth * 3.5f).coerceAtLeast(36f))
                                } else if (draggingTextIndex != -1 && draggingTextIndex in annotations.indices) {
                                    val item = annotations[draggingTextIndex]
                                    val newPos = Offset(item.startOffset.x + dragAmount.x, item.startOffset.y + dragAmount.y)
                                    annotations[draggingTextIndex] = item.copy(startOffset = newPos)
                                } else if (activeTool == AnnotationTool.PEN) {
                                    currentPath?.lineTo(change.position.x, change.position.y)
                                }
                            },
                            onDragEnd = {
                                eraserPosition = null
                                if (draggingTextIndex != -1) {
                                    draggingTextIndex = -1
                                } else if (activeTool == AnnotationTool.PEN) {
                                    currentPath?.let { p ->
                                        annotations.add(
                                            DrawnAnnotation(
                                                tool = activeTool,
                                                path = p,
                                                startOffset = dragStart,
                                                endOffset = dragEnd,
                                                color = activeColor,
                                                strokeWidth = strokeWidth
                                            )
                                        )
                                    }
                                    currentPath = null
                                } else if (activeTool == AnnotationTool.ARROW || activeTool == AnnotationTool.RECTANGLE || activeTool == AnnotationTool.CIRCLE) {
                                    annotations.add(
                                        DrawnAnnotation(
                                            tool = activeTool,
                                            path = Path(),
                                            startOffset = dragStart,
                                            endOffset = dragEnd,
                                            color = activeColor,
                                            strokeWidth = strokeWidth
                                        )
                                    )
                                }
                            }
                        )
                    }
            ) {
                drawImage(imageBitmap)

                // Render saved annotations
                annotations.forEachIndexed { index, item ->
                    when (item.tool) {
                        AnnotationTool.PEN -> {
                            drawPath(
                                path = item.path,
                                color = item.color,
                                style = Stroke(width = item.strokeWidth, cap = StrokeCap.Round)
                            )
                        }
                        AnnotationTool.ARROW -> {
                            drawArrow(item.startOffset, item.endOffset, item.color, item.strokeWidth)
                        }
                        AnnotationTool.RECTANGLE -> {
                            val topLeft = Offset(
                                minOf(item.startOffset.x, item.endOffset.x),
                                minOf(item.startOffset.y, item.endOffset.y)
                            )
                            val rectSize = Size(
                                abs(item.endOffset.x - item.startOffset.x),
                                abs(item.endOffset.y - item.startOffset.y)
                            )
                            drawRect(color = item.color, topLeft = topLeft, size = rectSize, style = Stroke(item.strokeWidth))
                        }
                        AnnotationTool.CIRCLE -> {
                            val topLeft = Offset(
                                minOf(item.startOffset.x, item.endOffset.x),
                                minOf(item.startOffset.y, item.endOffset.y)
                            )
                            val rectSize = Size(
                                abs(item.endOffset.x - item.startOffset.x),
                                abs(item.endOffset.y - item.startOffset.y)
                            )
                            drawOval(color = item.color, topLeft = topLeft, size = rectSize, style = Stroke(item.strokeWidth))
                        }
                        AnnotationTool.TEXT -> {
                            val paint = Paint().apply {
                                color = item.color.toArgb()
                                textSize = item.fontSize
                                typeface = getFontTypeface(item.fontName)
                                isAntiAlias = true
                            }
                            drawContext.canvas.nativeCanvas.drawText(
                                item.textNote,
                                item.startOffset.x,
                                item.startOffset.y,
                                paint
                            )

                            if (index == draggingTextIndex || index == editingTextIndex || index == selectedTextIndex) {
                                val textWidth = paint.measureText(item.textNote).coerceAtLeast(30f)
                                val bounds = Rect()
                                paint.getTextBounds(item.textNote, 0, item.textNote.length, bounds)
                                val rectTopLeft = Offset(item.startOffset.x - 8f, item.startOffset.y - bounds.height() - 8f)
                                val rectSize = Size(textWidth + 16f, bounds.height() + 16f)
                                drawRect(
                                    color = Color.Cyan,
                                    topLeft = rectTopLeft,
                                    size = rectSize,
                                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                                )
                            }
                        }
                        else -> {}
                    }
                }

                // Render current dragging path or shape
                currentPath?.let { p ->
                    drawPath(path = p, color = activeColor, style = Stroke(width = strokeWidth, cap = StrokeCap.Round))
                }

                if (activeTool == AnnotationTool.ARROW && dragStart != dragEnd) {
                    drawArrow(dragStart, dragEnd, activeColor, strokeWidth)
                } else if (activeTool == AnnotationTool.RECTANGLE && dragStart != dragEnd) {
                    val topLeft = Offset(minOf(dragStart.x, dragEnd.x), minOf(dragStart.y, dragEnd.y))
                    val rectSize = Size(abs(dragEnd.x - dragStart.x), abs(dragEnd.y - dragStart.y))
                    drawRect(color = activeColor, topLeft = topLeft, size = rectSize, style = Stroke(strokeWidth))
                } else if (activeTool == AnnotationTool.CIRCLE && dragStart != dragEnd) {
                    val topLeft = Offset(minOf(dragStart.x, dragEnd.x), minOf(dragStart.y, dragEnd.y))
                    val rectSize = Size(abs(dragEnd.x - dragStart.x), abs(dragEnd.y - dragStart.y))
                    drawOval(color = activeColor, topLeft = topLeft, size = rectSize, style = Stroke(strokeWidth))
                }

                // Render live Eraser Ring
                eraserPosition?.let { pos ->
                    val radius = (strokeWidth * 3.5f).coerceAtLeast(36f)
                    drawCircle(
                        color = Color.White.copy(alpha = 0.35f),
                        radius = radius,
                        center = pos
                    )
                    drawCircle(
                        color = Color.Red,
                        radius = radius,
                        center = pos,
                        style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f))
                    )
                }
            }

            // Floating control bar for selected text annotation
            if (selectedTextIndex in annotations.indices && annotations[selectedTextIndex].tool == AnnotationTool.TEXT) {
                val selectedItem = annotations[selectedTextIndex]
                Card(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 16.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
                    elevation = CardDefaults.cardElevation(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Text: \"${selectedItem.textNote.take(10)}${if (selectedItem.textNote.length > 10) "..." else ""}\"",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        // Edit Button
                        IconButton(
                            onClick = {
                                editingTextIndex = selectedTextIndex
                                textInputText = selectedItem.textNote
                                selectedFont = try { AppFontFamily.valueOf(selectedItem.fontName) } catch (_: Exception) { AppFontFamily.SANS_SERIF }
                                selectedFontSize = selectedItem.fontSize
                                activeColor = selectedItem.color
                                showTextDialog = true
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = "Edit Text",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }

                        // Delete Button
                        IconButton(
                            onClick = {
                                if (selectedTextIndex in annotations.indices) {
                                    val removed = annotations.removeAt(selectedTextIndex)
                                    undoStack.add(removed)
                                    selectedTextIndex = -1
                                    draggingTextIndex = -1
                                }
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = "Delete Text",
                                tint = MaterialTheme.colorScheme.error
                            )
                        }

                        // Close/Deselect Button
                        IconButton(
                            onClick = { selectedTextIndex = -1 },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Deselect",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text(if (editingTextIndex != -1) "Edit Text Annotation" else "Add Text Annotation") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedTextField(
                        value = textInputText,
                        onValueChange = { textInputText = it },
                        placeholder = { Text("Enter text note...") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    Text(
                        text = "Font Family:",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        AppFontFamily.entries.forEach { font ->
                            FilterChip(
                                selected = selectedFont == font,
                                onClick = { selectedFont = font },
                                label = {
                                    Text(
                                        text = font.displayName,
                                        fontWeight = if (font.style == Typeface.BOLD) FontWeight.Bold else FontWeight.Normal,
                                        fontFamily = when (font) {
                                            AppFontFamily.SERIF -> FontFamily.Serif
                                            AppFontFamily.MONOSPACE -> FontFamily.Monospace
                                            AppFontFamily.CURSIVE -> FontFamily.Cursive
                                            else -> FontFamily.Default
                                        }
                                    )
                                }
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Font Size: ${selectedFontSize.toInt()} sp",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    Slider(
                        value = selectedFontSize,
                        onValueChange = { selectedFontSize = it },
                        valueRange = 24f..96f,
                        steps = 12,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (textInputText.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surfaceVariant,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Canvas(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(50.dp)
                            ) {
                                val paint = Paint().apply {
                                    color = activeColor.toArgb()
                                    textSize = selectedFontSize
                                    typeface = selectedFont.getTypeface()
                                    isAntiAlias = true
                                    textAlign = Paint.Align.CENTER
                                }
                                drawContext.canvas.nativeCanvas.drawText(
                                    textInputText,
                                    size.width / 2f,
                                    size.height / 2f + selectedFontSize / 3f,
                                    paint
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (textInputText.isNotBlank()) {
                        if (editingTextIndex in annotations.indices) {
                            val item = annotations[editingTextIndex]
                            annotations[editingTextIndex] = item.copy(
                                textNote = textInputText,
                                fontName = selectedFont.name,
                                fontSize = selectedFontSize,
                                color = activeColor
                            )
                        } else {
                            annotations.add(
                                DrawnAnnotation(
                                    tool = AnnotationTool.TEXT,
                                    path = Path(),
                                    startOffset = pendingTextPlacementOffset,
                                    endOffset = Offset.Zero,
                                    color = activeColor,
                                    strokeWidth = strokeWidth,
                                    textNote = textInputText,
                                    fontName = selectedFont.name,
                                    fontSize = selectedFontSize
                                )
                            )
                        }
                        textInputText = ""
                    }
                    showTextDialog = false
                }) {
                    Text(if (editingTextIndex != -1) "Save" else "Add")
                }
            },
            dismissButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (editingTextIndex in annotations.indices) {
                        TextButton(
                            onClick = {
                                if (editingTextIndex in annotations.indices) {
                                    val removed = annotations.removeAt(editingTextIndex)
                                    undoStack.add(removed)
                                    selectedTextIndex = -1
                                    editingTextIndex = -1
                                }
                                showTextDialog = false
                            }
                        ) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { showTextDialog = false }) {
                        Text("Cancel")
                    }
                }
            }
        )
    }
}

private fun DrawScope.drawArrow(start: Offset, end: Offset, color: Color, strokeWidth: Float) {
    drawLine(color = color, start = start, end = end, strokeWidth = strokeWidth, cap = StrokeCap.Round)

    val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val arrowSize = (strokeWidth * 3.5f).coerceAtLeast(30f)

    val x3 = (end.x - arrowSize * cos(angle - Math.PI / 6)).toFloat()
    val y3 = (end.y - arrowSize * sin(angle - Math.PI / 6)).toFloat()

    val x4 = (end.x - arrowSize * cos(angle + Math.PI / 6)).toFloat()
    val y4 = (end.y - arrowSize * sin(angle + Math.PI / 6)).toFloat()

    drawLine(color = color, start = end, end = Offset(x3, y3), strokeWidth = strokeWidth, cap = StrokeCap.Round)
    drawLine(color = color, start = end, end = Offset(x4, y4), strokeWidth = strokeWidth, cap = StrokeCap.Round)
}

private fun buildArrowAndroidPath(start: Offset, end: Offset, strokeWidth: Float): android.graphics.Path {
    val path = android.graphics.Path()
    path.moveTo(start.x, start.y)
    path.lineTo(end.x, end.y)

    val angle = atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble())
    val arrowSize = (strokeWidth * 3.5f).coerceAtLeast(30f)

    val x3 = (end.x - arrowSize * cos(angle - Math.PI / 6)).toFloat()
    val y3 = (end.y - arrowSize * sin(angle - Math.PI / 6)).toFloat()

    val x4 = (end.x - arrowSize * cos(angle + Math.PI / 6)).toFloat()
    val y4 = (end.y - arrowSize * sin(angle + Math.PI / 6)).toFloat()

    path.moveTo(end.x, end.y)
    path.lineTo(x3, y3)
    path.moveTo(end.x, end.y)
    path.lineTo(x4, y4)

    return path
}
