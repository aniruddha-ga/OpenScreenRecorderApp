package com.openscreenrecorder.app

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
import android.os.Looper
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
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
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

        if (!uriStr.isNullOrEmpty()) {
            loadBitmapFromUri(uriStr.toUri())
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
        captureScreenFrame { bitmap ->
            if (bitmap != null) {
                currentBitmapState.value = bitmap
            } else {
                Toast.makeText(this, "Failed to capture screenshot", Toast.LENGTH_SHORT).show()
            }
            stopMediaProjection()
        }
    }

    private fun captureScreenFrame(onCaptured: (Bitmap?) -> Unit) {
        try {
            val reader = ImageReader.newInstance(screenWidth, screenHeight, PixelFormat.RGBA_8888, 2)
            imageReader = reader

            virtualDisplay = mediaProjection?.createVirtualDisplay(
                "ScreenshotEditorDisplay",
                screenWidth,
                screenHeight,
                screenDensityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader.surface,
                null,
                null
            )

            Handler(Looper.getMainLooper()).postDelayed({
                var capturedBitmap: Bitmap? = null
                try {
                    val image: Image? = reader.acquireLatestImage()
                    if (image != null) {
                        val planes = image.planes
                        val buffer = planes[0].buffer
                        val pixelStride = planes[0].pixelStride
                        val rowStride = planes[0].rowStride

                        val widthInPixels = rowStride / pixelStride
                        val bmp = createBitmap(widthInPixels, screenHeight)
                        bmp.copyPixelsFromBuffer(buffer)

                        capturedBitmap = if (widthInPixels > screenWidth) {
                            val cropped = Bitmap.createBitmap(bmp, 0, 0, screenWidth, screenHeight)
                            bmp.recycle()
                            cropped
                        } else {
                            bmp
                        }
                        image.close()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to process image frame: ${e.message}")
                } finally {
                    try { reader.close() } catch (_: Exception) {}
                    try { virtualDisplay?.release() } catch (_: Exception) {}
                    onCaptured(capturedBitmap)
                }
            }, 300)
        } catch (e: Exception) {
            Log.e(TAG, "Error creating virtual display: ${e.message}")
            onCaptured(null)
        }
    }

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

data class DrawnAnnotation(
    val tool: AnnotationTool,
    val path: Path,
    val startOffset: Offset,
    val endOffset: Offset,
    val color: Color,
    val strokeWidth: Float,
    val textNote: String = ""
)

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

                // Tools row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceAround,
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
                            showTextDialog = true
                        },
                        label = { Text("Text") },
                        leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) }
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
                    .pointerInput(activeTool) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragStart = offset
                                dragEnd = offset
                                if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.ERASER) {
                                    val p = Path().apply { moveTo(offset.x, offset.y) }
                                    currentPath = p
                                }
                            },
                            onDrag = { change, _ ->
                                dragEnd = change.position
                                if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.ERASER) {
                                    currentPath?.lineTo(change.position.x, change.position.y)
                                }
                            },
                            onDragEnd = {
                                if (activeTool == AnnotationTool.PEN || activeTool == AnnotationTool.ERASER) {
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
                val scale = minOf(size.width / currentBitmap.width, size.height / currentBitmap.height)
                drawImage(imageBitmap)

                // Render saved annotations
                for (item in annotations) {
                    when (item.tool) {
                        AnnotationTool.PEN, AnnotationTool.ERASER -> {
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
                            drawContext.canvas.nativeCanvas.drawText(
                                item.textNote,
                                item.startOffset.x,
                                item.startOffset.y,
                                Paint().apply {
                                    color = item.color.toArgb()
                                    textSize = 42f
                                    isAntiAlias = true
                                }
                            )
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
            }
        }
    }

    if (showTextDialog) {
        AlertDialog(
            onDismissRequest = { showTextDialog = false },
            title = { Text("Add Text Annotation") },
            text = {
                OutlinedTextField(
                    value = textInputText,
                    onValueChange = { textInputText = it },
                    placeholder = { Text("Enter text note...") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (textInputText.isNotBlank()) {
                        annotations.add(
                            DrawnAnnotation(
                                tool = AnnotationTool.TEXT,
                                path = Path(),
                                startOffset = Offset(100f, 200f),
                                endOffset = Offset.Zero,
                                color = activeColor,
                                strokeWidth = strokeWidth,
                                textNote = textInputText
                            )
                        )
                        textInputText = ""
                    }
                    showTextDialog = false
                }) {
                    Text("Add")
                }
            },
            dismissButton = {
                TextButton(onClick = { showTextDialog = false }) {
                    Text("Cancel")
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
