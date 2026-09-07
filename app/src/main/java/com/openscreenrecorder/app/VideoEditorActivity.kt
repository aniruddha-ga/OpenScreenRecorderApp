@file:OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)

package com.openscreenrecorder.app

import android.content.ContentValues
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.LayoutInflater
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.RotateRight
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import kotlin.math.roundToInt
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

class VideoEditorActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
    }

    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)

        val videoUriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
        val videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Video Editor"

        if (videoUriStr.isNullOrEmpty()) {
            Toast.makeText(this, "Invalid video source", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val videoUri = videoUriStr.toUri()

        setContent {
            OpenScreenRecorderTheme {
                VideoEditorScreen(
                    videoUri = videoUri,
                    videoTitle = videoTitle,
                    configManager = configManager,
                    onBackClick = { finish() },
                    onExportComplete = {
                        setResult(RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }
}

enum class VideoEditorTab {
    TRIM,
    CROP,
    ROTATE,
    SPEED,
    AUDIO,
    TEXT,
    MERGE
}

fun formatTimecodeMs(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

@Composable
fun VideoTimelineBar(
    currentPosMs: Long,
    durationMs: Long,
    cutStartMs: Float,
    cutEndMs: Float,
    cutRanges: List<VideoCutRange>,
    onSeekTo: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "Playhead: ${formatTimecodeMs(currentPosMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Duration: ${formatTimecodeMs(durationMs)}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.DarkGray.copy(alpha = 0.5f))
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (durationMs > 0) {
                            val fraction = (offset.x / size.width).coerceIn(0f, 1f)
                            onSeekTo((fraction * durationMs).toLong())
                        }
                    }
                }
        ) {
            val totalW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            val validDuration = durationMs.coerceAtLeast(1L).toFloat()

            // Cut Out Ranges Highlight (Red)
            cutRanges.forEach { range ->
                val startFrac = (range.startMs.toFloat() / validDuration).coerceIn(0f, 1f)
                val endFrac = (range.endMs.toFloat() / validDuration).coerceIn(0f, 1f)
                val leftPx = startFrac * totalW
                val widthPx = ((endFrac - startFrac) * totalW).coerceAtLeast(2f)

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .offset { IntOffset(leftPx.roundToInt(), 0) }
                        .width(with(LocalDensity.current) { widthPx.toDp() })
                        .background(Color.Red.copy(alpha = 0.5f))
                )
            }

            // Current Trim Selection Highlight (Cyan)
            if (cutEndMs > cutStartMs) {
                val trimStartFrac = (cutStartMs / validDuration).coerceIn(0f, 1f)
                val trimEndFrac = (cutEndMs / validDuration).coerceIn(0f, 1f)
                val leftPx = trimStartFrac * totalW
                val widthPx = ((trimEndFrac - trimStartFrac) * totalW).coerceAtLeast(2f)

                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .offset { IntOffset(leftPx.roundToInt(), 0) }
                        .width(with(LocalDensity.current) { widthPx.toDp() })
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f))
                        .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                )
            }

            // Yellow Playhead Cursor Line
            val playheadFrac = (currentPosMs.toFloat() / validDuration).coerceIn(0f, 1f)
            val playheadPx = playheadFrac * totalW
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(3.dp)
                    .offset { IntOffset(playheadPx.roundToInt(), 0) }
                    .background(Color.Yellow)
            )
        }
    }
}

@Composable
fun MusicTimelineTrack(
    musicUri: Uri,
    musicVolume: Float,
    musicStartMs: Float,
    musicEndMs: Float,
    loopMusic: Boolean,
    maxVideoDurationMs: Float,
    onMusicRangeChange: (Float, Float) -> Unit,
    onLoopToggle: (Boolean) -> Unit,
    onVolumeChange: (Float) -> Unit,
    onRemoveMusic: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text(
                        text = musicUri.lastPathSegment ?: "Background Audio",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = onRemoveMusic, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Delete, contentDescription = "Remove Music", tint = Color.Red)
                }
            }

            // Loop Music Track Chip & Time Range Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Range: ${formatTimecodeMs(musicStartMs.toLong())} - ${formatTimecodeMs(musicEndMs.toLong())}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )

                FilterChip(
                    selected = loopMusic,
                    onClick = { onLoopToggle(!loopMusic) },
                    label = { Text(if (loopMusic) "Loop Track" else "Loop (Off)") },
                    leadingIcon = { Icon(Icons.Default.Repeat, contentDescription = null, modifier = Modifier.size(16.dp)) }
                )
            }

            // Music Time Range Slider bounded to max video duration
            RangeSlider(
                value = musicStartMs..musicEndMs,
                onValueChange = { range ->
                    onMusicRangeChange(range.start, range.endInclusive)
                },
                valueRange = 0f..maxVideoDurationMs.coerceAtLeast(1000f),
                modifier = Modifier.fillMaxWidth()
            )

            // Visual Music Audio Waveform Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(20.dp)
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                repeat(28) { i ->
                    val heightFrac = remember(i) { (0.3f + (i * 17 % 7) * 0.1f).coerceIn(0.2f, 1f) }
                    Box(
                        modifier = Modifier
                            .width(3.dp)
                            .fillMaxHeight(heightFrac)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.85f), CircleShape)
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Music Vol: ${(musicVolume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                Slider(
                    value = musicVolume,
                    onValueChange = onVolumeChange,
                    valueRange = 0f..1f,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
fun VideoEditorTopBar(
    videoTitle: String,
    onBackClick: () -> Unit,
    onExportClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .height(64.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = videoTitle,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Button(
                onClick = onExportClick,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Export")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, UnstableApi::class)
@Composable
fun VideoEditorScreen(
    videoUri: Uri,
    videoTitle: String,
    configManager: ConfigManager,
    onBackClick: () -> Unit,
    onExportComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(VideoEditorTab.TRIM) }

    // Video Editing States
    var rotationDegrees by remember { mutableFloatStateOf(0f) }
    var speedMultiplier by remember { mutableFloatStateOf(1.0f) }
    var originalAudioVolume by remember { mutableFloatStateOf(1.0f) }

    var backgroundMusicUri by remember { mutableStateOf<Uri?>(null) }
    var backgroundMusicVolume by remember { mutableFloatStateOf(1.0f) }
    var musicStartMs by remember { mutableFloatStateOf(0f) }
    var musicEndMs by remember { mutableFloatStateOf(0f) }
    var loopBackgroundMusic by remember { mutableStateOf(true) }

    var voiceOverUri by remember { mutableStateOf<Uri?>(null) }

    var overlayText by remember { mutableStateOf("") }
    var overlayTextColor by remember { mutableStateOf(Color.White) }
    var textOffset by remember { mutableStateOf(Offset(0f, 0f)) }
    var textFontSizeSp by remember { mutableFloatStateOf(32f) }
    var selectedVideoFont by remember { mutableStateOf(AppFontFamily.SANS_SERIF) }

    var cutStartMs by remember { mutableFloatStateOf(0f) }
    var cutEndMs by remember { mutableFloatStateOf(1000f) }

    val cutRanges = remember { mutableStateListOf<VideoCutRange>() }
    val mergeClips = remember { mutableStateListOf<Uri>() }

    var isCropActive by remember { mutableStateOf(false) }
    var cropBoxLeft by remember { mutableFloatStateOf(0.1f) }
    var cropBoxTop by remember { mutableFloatStateOf(0.1f) }
    var cropBoxRight by remember { mutableFloatStateOf(0.9f) }
    var cropBoxBottom by remember { mutableFloatStateOf(0.9f) }

    val cropBoundsForExport = remember(isCropActive, cropBoxLeft, cropBoxTop, cropBoxRight, cropBoxBottom) {
        if (!isCropActive) null
        else {
            val nLeft = (cropBoxLeft * 2f - 1f).coerceIn(-1f, 1f)
            val nRight = (cropBoxRight * 2f - 1f).coerceIn(-1f, 1f)
            val nTop = (1f - cropBoxTop * 2f).coerceIn(-1f, 1f)
            val nBottom = (1f - cropBoxBottom * 2f).coerceIn(-1f, 1f)
            CropBounds(
                left = minOf(nLeft, nRight),
                top = maxOf(nTop, nBottom),
                right = maxOf(nLeft, nRight),
                bottom = minOf(nTop, nBottom)
            )
        }
    }

    // ExoPlayer Video Resolution & Preview State
    var nativeVideoWidth by remember { mutableIntStateOf(1080) }
    var nativeVideoHeight by remember { mutableIntStateOf(1920) }

    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(true) }

    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            addListener(object : Player.Listener {
                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width > 0 && videoSize.height > 0) {
                        nativeVideoWidth = videoSize.width
                        nativeVideoHeight = videoSize.height
                    }
                }
            })
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(exoPlayer) {
        onDispose {
            exoPlayer.release()
        }
    }

    // Monitor ExoPlayer position
    LaunchedEffect(exoPlayer) {
        while (true) {
            currentPosMs = exoPlayer.currentPosition.coerceAtLeast(0L)
            durationMs = exoPlayer.duration.coerceAtLeast(1L)
            isPlaying = exoPlayer.isPlaying
            delay(200.milliseconds)
        }
    }

    // Auto-initialize background music range when music or duration changes
    LaunchedEffect(durationMs, backgroundMusicUri) {
        if (backgroundMusicUri != null && musicEndMs == 0f && durationMs > 0L) {
            musicStartMs = 0f
            musicEndMs = durationMs.toFloat()
        }
    }

    // Secondary ExoPlayer for Live Background Music Preview Sync
    val musicPlayer = remember(backgroundMusicUri) {
        if (backgroundMusicUri == null) null
        else {
            ExoPlayer.Builder(context).build().apply {
                setMediaItem(MediaItem.fromUri(backgroundMusicUri!!))
                prepare()
            }
        }
    }

    DisposableEffect(musicPlayer) {
        onDispose {
            musicPlayer?.release()
        }
    }

    // Live Sync Background Audio with Video Preview
    LaunchedEffect(isPlaying, currentPosMs, backgroundMusicVolume, musicStartMs, musicEndMs, loopBackgroundMusic) {
        val player = musicPlayer ?: return@LaunchedEffect
        player.volume = backgroundMusicVolume

        val mStart = musicStartMs.toLong()
        val mEnd = if (musicEndMs > musicStartMs) musicEndMs.toLong() else (mStart + 1000L)
        val mDuration = mEnd - mStart

        if (isPlaying && currentPosMs in mStart..(if (loopBackgroundMusic) Long.MAX_VALUE else mEnd)) {
            val offsetInMusic = if (mDuration > 0) {
                if (loopBackgroundMusic) ((currentPosMs - mStart) % mDuration) else (currentPosMs - mStart)
            } else 0L

            if (!player.isPlaying) {
                player.seekTo(offsetInMusic.coerceAtLeast(0L))
                player.playWhenReady = true
                player.play()
            } else if (abs(player.currentPosition - offsetInMusic) > 300L) {
                player.seekTo(offsetInMusic.coerceAtLeast(0L))
            }
        } else {
            if (player.isPlaying) {
                player.pause()
            }
        }
    }

    // Launchers
    val musicPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            backgroundMusicUri = uri
            Toast.makeText(context, "Music added!", Toast.LENGTH_SHORT).show()
        }
    }

    val mergePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            mergeClips.add(uri)
            Toast.makeText(context, "Clip added for merging", Toast.LENGTH_SHORT).show()
        }
    }

    // Export Dialog State
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }

    fun startExport() {
        isExporting = true
        exportProgress = 0.05f

        scope.launch(Dispatchers.IO) {
            val fileName = "Edited_" + configManager.generateFileName()
            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Recordings")
            }

            val targetUri = context.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues
            )

            if (targetUri == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to create output file", Toast.LENGTH_SHORT).show()
                    isExporting = false
                }
                return@launch
            }

            val pfd = context.contentResolver.openFileDescriptor(targetUri, "rw")
            if (pfd == null) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Failed to open target file descriptor", Toast.LENGTH_SHORT).show()
                    isExporting = false
                }
                return@launch
            }

            val options = VideoEditOptions(
                cropBounds = cropBoundsForExport,
                rotationDegrees = rotationDegrees,
                speedMultiplier = speedMultiplier,
                backgroundMusicUri = backgroundMusicUri,
                backgroundMusicVolume = backgroundMusicVolume,
                musicStartMs = musicStartMs.toLong(),
                musicEndMs = musicEndMs.toLong(),
                loopBackgroundMusic = loopBackgroundMusic,
                originalAudioVolume = originalAudioVolume,
                voiceOverUri = voiceOverUri,
                overlayText = overlayText.ifBlank { null },
                overlayTextColor = overlayTextColor.toArgb(),
                cutRangesToRemove = cutRanges,
                mergeVideoUris = mergeClips
            )

            val (success, errorMsg) = VideoEditorProcessor.processVideo(
                context = context,
                inputUri = videoUri,
                outputPfd = pfd,
                options = options,
                onProgress = { progress ->
                    exportProgress = progress.coerceIn(0.05f, 0.99f)
                }
            )

            withContext(Dispatchers.Main) {
                isExporting = false
                if (success) {
                    Toast.makeText(context, "Video saved to gallery!", Toast.LENGTH_LONG).show()
                    onExportComplete()
                } else {
                    Toast.makeText(context, "Export failed: ${errorMsg ?: "Unknown error"}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            VideoEditorTopBar(
                videoTitle = videoTitle,
                onBackClick = onBackClick,
                onExportClick = { startExport() }
            )
        },
        bottomBar = {
            // Timeline & Tools Controls Card - Pinned firmly at the bottom of the screen!
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding(),
                shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Tool Tabs Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = activeTab == VideoEditorTab.TRIM,
                            onClick = { activeTab = VideoEditorTab.TRIM },
                            label = { Text("Cut / Trim") },
                            leadingIcon = { Icon(Icons.Default.ContentCut, contentDescription = null) }
                        )
                        FilterChip(
                            selected = activeTab == VideoEditorTab.CROP,
                            onClick = { activeTab = VideoEditorTab.CROP },
                            label = { Text("Crop") },
                            leadingIcon = { Icon(Icons.Default.Crop, contentDescription = null) }
                        )
                        FilterChip(
                            selected = activeTab == VideoEditorTab.ROTATE,
                            onClick = { activeTab = VideoEditorTab.ROTATE },
                            label = { Text("Rotate") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null) }
                        )
                        FilterChip(
                            selected = activeTab == VideoEditorTab.SPEED,
                            onClick = { activeTab = VideoEditorTab.SPEED },
                            label = { Text("Speed") },
                            leadingIcon = { Icon(Icons.Default.Speed, contentDescription = null) }
                        )
                        FilterChip(
                            selected = activeTab == VideoEditorTab.AUDIO,
                            onClick = { activeTab = VideoEditorTab.AUDIO },
                            label = { Text("Music / Voice") },
                            leadingIcon = { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null) }
                        )
                        FilterChip(
                            selected = activeTab == VideoEditorTab.TEXT,
                            onClick = { activeTab = VideoEditorTab.TEXT },
                            label = { Text("Text") },
                            leadingIcon = { Icon(Icons.Default.TextFields, contentDescription = null) }
                        )
                        FilterChip(
                            selected = activeTab == VideoEditorTab.MERGE,
                            onClick = { activeTab = VideoEditorTab.MERGE },
                            label = { Text("Merge Clips") },
                            leadingIcon = { Icon(Icons.Default.VideoLibrary, contentDescription = null) }
                        )
                    }

                    // Tab Specific Controls
                    when (activeTab) {
                        VideoEditorTab.TRIM -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Interactive Video Timeline & Cut Tool:", style = MaterialTheme.typography.titleSmall)

                                // Interactive Video Timeline Bar with Playhead
                                VideoTimelineBar(
                                    currentPosMs = currentPosMs,
                                    durationMs = durationMs,
                                    cutStartMs = cutStartMs,
                                    cutEndMs = cutEndMs,
                                    cutRanges = cutRanges,
                                    onSeekTo = { seekMs -> exoPlayer.seekTo(seekMs) }
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Cut Start: ${formatTimecodeMs(cutStartMs.toLong())}", style = MaterialTheme.typography.bodySmall)
                                    Text("Cut End: ${formatTimecodeMs(cutEndMs.toLong())}", style = MaterialTheme.typography.bodySmall)
                                }

                                RangeSlider(
                                    value = cutStartMs..cutEndMs,
                                    onValueChange = { range ->
                                        cutStartMs = range.start
                                        cutEndMs = range.endInclusive
                                        exoPlayer.seekTo(range.start.toLong())
                                    },
                                    valueRange = 0f..(durationMs.toFloat().coerceAtLeast(1000f)),
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Button(
                                    onClick = {
                                        if (cutEndMs > cutStartMs) {
                                            cutRanges.add(VideoCutRange(cutStartMs.toLong(), cutEndMs.toLong()))
                                            Toast.makeText(context, "Cut section added (${formatTimecodeMs(cutStartMs.toLong())} - ${formatTimecodeMs(cutEndMs.toLong())})", Toast.LENGTH_SHORT).show()
                                        }
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.Default.ContentCut, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Add Cut Section")
                                }

                                if (cutRanges.isNotEmpty()) {
                                    Text("Cut Out Sections (${cutRanges.size}):", style = MaterialTheme.typography.labelLarge)
                                    cutRanges.forEachIndexed { idx, range ->
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text("Cut #${idx + 1}: ${formatTimecodeMs(range.startMs)} - ${formatTimecodeMs(range.endMs)}")
                                            IconButton(onClick = { cutRanges.removeAt(idx) }) {
                                                Icon(Icons.Default.Delete, contentDescription = "Remove Cut", tint = Color.Red)
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        VideoEditorTab.CROP -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Interactive Crop Grid & Presets:", style = MaterialTheme.typography.titleSmall)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    FilterChip(
                                        selected = !isCropActive,
                                        onClick = { isCropActive = false },
                                        label = { Text("Original (Reset)") }
                                    )
                                    FilterChip(
                                        selected = isCropActive && cropBoxLeft == 0.1f && cropBoxTop == 0.1f,
                                        onClick = {
                                            isCropActive = true
                                            cropBoxLeft = 0.1f
                                            cropBoxTop = 0.1f
                                            cropBoxRight = 0.9f
                                            cropBoxBottom = 0.9f
                                        },
                                        label = { Text("Free Custom Crop") }
                                    )
                                    FilterChip(
                                        selected = isCropActive && (cropBoxRight - cropBoxLeft == cropBoxBottom - cropBoxTop),
                                        onClick = {
                                            isCropActive = true
                                            cropBoxLeft = 0.15f
                                            cropBoxTop = 0.15f
                                            cropBoxRight = 0.85f
                                            cropBoxBottom = 0.85f
                                        },
                                        label = { Text("1:1 Square") }
                                    )
                                    FilterChip(
                                        selected = isCropActive,
                                        onClick = {
                                            isCropActive = true
                                            cropBoxLeft = 0.05f
                                            cropBoxTop = 0.2f
                                            cropBoxRight = 0.95f
                                            cropBoxBottom = 0.8f
                                        },
                                        label = { Text("16:9 Widescreen") }
                                    )
                                    FilterChip(
                                        selected = isCropActive,
                                        onClick = {
                                            isCropActive = true
                                            cropBoxLeft = 0.2f
                                            cropBoxTop = 0.05f
                                            cropBoxRight = 0.8f
                                            cropBoxBottom = 0.95f
                                        },
                                        label = { Text("9:16 Portrait") }
                                    )
                                }

                                Text(
                                    text = "Tip: Drag Cyan corner handles to zoom in/out or resize crop. Drag inside box to move it!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        VideoEditorTab.ROTATE -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Video Orientation Rotation:", style = MaterialTheme.typography.titleSmall)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    listOf(0f, 90f, 180f, 270f).forEach { deg ->
                                        FilterChip(
                                            selected = rotationDegrees == deg,
                                            onClick = { rotationDegrees = deg },
                                            label = { Text("${deg.toInt()}°") }
                                        )
                                    }
                                }
                                Button(
                                    onClick = { rotationDegrees = (rotationDegrees + 90f) % 360f },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(Icons.AutoMirrored.Filled.RotateRight, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text("Rotate +90°")
                                }
                            }
                        }

                        VideoEditorTab.SPEED -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Playback Speed: ${speedMultiplier}x", style = MaterialTheme.typography.titleSmall)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f).forEach { spd ->
                                        FilterChip(
                                            selected = speedMultiplier == spd,
                                            onClick = {
                                                speedMultiplier = spd
                                                exoPlayer.playbackParameters = PlaybackParameters(spd)
                                                if (exoPlayer.playbackState == Player.STATE_ENDED) {
                                                    exoPlayer.seekTo(0L)
                                                }
                                                exoPlayer.playWhenReady = true
                                                exoPlayer.play()
                                                Toast.makeText(context, "Speed set to ${spd}x", Toast.LENGTH_SHORT).show()
                                            },
                                            label = { Text("${spd}x") }
                                        )
                                    }
                                }
                            }
                        }

                        VideoEditorTab.AUDIO -> {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Background Music:", style = MaterialTheme.typography.titleSmall)
                                    Button(onClick = { musicPickerLauncher.launch("audio/*") }) {
                                        Icon(Icons.Default.MusicNote, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text(if (backgroundMusicUri != null) "Change Music" else "Add Music")
                                    }
                                }

                                if (backgroundMusicUri != null) {
                                    MusicTimelineTrack(
                                        musicUri = backgroundMusicUri!!,
                                        musicVolume = backgroundMusicVolume,
                                        musicStartMs = musicStartMs,
                                        musicEndMs = musicEndMs,
                                        loopMusic = loopBackgroundMusic,
                                        maxVideoDurationMs = durationMs.toFloat(),
                                        onMusicRangeChange = { start, end ->
                                            musicStartMs = start
                                            musicEndMs = end
                                        },
                                        onLoopToggle = { loopBackgroundMusic = it },
                                        onVolumeChange = { backgroundMusicVolume = it },
                                        onRemoveMusic = { backgroundMusicUri = null }
                                    )
                                }

                                HorizontalDivider()

                                Text("Original Audio Volume: ${(originalAudioVolume * 100).toInt()}%", style = MaterialTheme.typography.titleSmall)
                                Slider(
                                    value = originalAudioVolume,
                                    onValueChange = {
                                        originalAudioVolume = it
                                        exoPlayer.volume = it
                                    },
                                    valueRange = 0f..1f
                                )
                            }
                        }

                        VideoEditorTab.TEXT -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedTextField(
                                    value = overlayText,
                                    onValueChange = { overlayText = it },
                                    label = { Text("Overlay Text") },
                                    placeholder = { Text("Enter text to overlay on video...") },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )

                                Text("Font Family:", style = MaterialTheme.typography.bodySmall)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    AppFontFamily.entries.forEach { font ->
                                        FilterChip(
                                            selected = selectedVideoFont == font,
                                            onClick = { selectedVideoFont = font },
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

                                Text("Font Size: ${textFontSizeSp.toInt()} sp", style = MaterialTheme.typography.bodySmall)
                                Slider(
                                    value = textFontSizeSp,
                                    onValueChange = { textFontSizeSp = it },
                                    valueRange = 20f..72f
                                )

                                Text("Text Color:", style = MaterialTheme.typography.bodySmall)
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    listOf(Color.White, Color.Yellow, Color.Red, Color.Cyan, Color.Green, Color.Magenta, Color.Black).forEach { col ->
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(col, CircleShape)
                                                .border(
                                                    if (overlayTextColor == col) 3.dp else 1.dp,
                                                    if (overlayTextColor == col) MaterialTheme.colorScheme.primary else Color.Gray,
                                                    CircleShape
                                                )
                                                .clip(CircleShape)
                                        ) {
                                            IconButton(onClick = { overlayTextColor = col }) {}
                                        }
                                    }
                                }

                                Text(
                                    text = "Tip: You can drag and position the text directly on the video preview screen!",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )

                                if (overlayText.isNotBlank()) {
                                    OutlinedButton(
                                        onClick = {
                                            overlayText = ""
                                            textOffset = Offset(0f, 0f)
                                            Toast.makeText(context, "Text overlay removed", Toast.LENGTH_SHORT).show()
                                        },
                                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.Red),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = null, tint = Color.Red)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Remove Text Overlay")
                                    }
                                }
                            }
                        }

                        VideoEditorTab.MERGE -> {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Merged Clips (${mergeClips.size}):", style = MaterialTheme.typography.titleSmall)
                                    Button(onClick = { mergePickerLauncher.launch("video/*") }) {
                                        Icon(Icons.Default.Add, contentDescription = null)
                                        Spacer(Modifier.width(4.dp))
                                        Text("Add Clip (+)")
                                    }
                                }

                                mergeClips.forEachIndexed { idx, clipUri ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("Clip #${idx + 1}: ${clipUri.lastPathSegment ?: "Video"}")
                                        IconButton(onClick = { mergeClips.removeAt(idx) }) {
                                            Icon(Icons.Default.Delete, contentDescription = "Remove Clip", tint = Color.Red)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .clipToBounds()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                val baseAspect = nativeVideoWidth.toFloat() / nativeVideoHeight.toFloat().coerceAtLeast(1f)
                val videoAspect = if (rotationDegrees == 90f || rotationDegrees == 270f) {
                    1f / baseAspect
                } else {
                    baseAspect
                }

                val maxW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                val maxH = constraints.maxHeight.toFloat().coerceAtLeast(1f)
                val containerAspect = maxW / maxH

                val boxModifier = if (videoAspect >= containerAspect) {
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(videoAspect)
                } else {
                    Modifier
                        .fillMaxHeight()
                        .aspectRatio(videoAspect)
                }

                // Video Frame Box - Constrained to Dynamic Max Fit Aspect Ratio
                Box(
                    modifier = boxModifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black),
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            val parent = FrameLayout(ctx)
                            val view = LayoutInflater.from(ctx).inflate(R.layout.player_view_texture, parent, false) as PlayerView
                            view.player = exoPlayer
                            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                            view
                        },
                        update = { view ->
                            view.rotation = rotationDegrees
                            view.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    // Live Interactive Crop Grid Box (Bounded EXACTLY to Video Frame)
                    if (isCropActive) {
                        BoxWithConstraints(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.55f))
                        ) {
                            val density = LocalDensity.current
                            val totalW = constraints.maxWidth.toFloat().coerceAtLeast(1f)
                            val totalH = constraints.maxHeight.toFloat().coerceAtLeast(1f)

                            val boxLeftPx = totalW * cropBoxLeft
                            val boxTopPx = totalH * cropBoxTop
                            val boxWidthPx = ((cropBoxRight - cropBoxLeft) * totalW).coerceAtLeast(60f)
                            val boxHeightPx = ((cropBoxBottom - cropBoxTop) * totalH).coerceAtLeast(60f)

                            Box(
                                modifier = Modifier
                                    .offset { IntOffset(boxLeftPx.roundToInt(), boxTopPx.roundToInt()) }
                                    .size(
                                        width = with(density) { boxWidthPx.toDp() },
                                        height = with(density) { boxHeightPx.toDp() }
                                    )
                                    .border(2.dp, Color.Cyan, RoundedCornerShape(4.dp))
                                    .background(Color.Transparent)
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val deltaX = dragAmount.x / totalW
                                            val deltaY = dragAmount.y / totalH
                                            val currentW = cropBoxRight - cropBoxLeft
                                            val currentH = cropBoxBottom - cropBoxTop

                                            val newLeft = (cropBoxLeft + deltaX).coerceIn(0f, 1f - currentW)
                                            val newTop = (cropBoxTop + deltaY).coerceIn(0f, 1f - currentH)
                                            cropBoxLeft = newLeft
                                            cropBoxTop = newTop
                                            cropBoxRight = newLeft + currentW
                                            cropBoxBottom = newTop + currentH
                                        }
                                    }
                            ) {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    HorizontalDivider(color = Color.Cyan.copy(alpha = 0.4f), thickness = 1.dp)
                                    Spacer(modifier = Modifier.weight(1f))
                                    HorizontalDivider(color = Color.Cyan.copy(alpha = 0.4f), thickness = 1.dp)
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                                Row(modifier = Modifier.fillMaxSize()) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    VerticalDivider(color = Color.Cyan.copy(alpha = 0.4f), thickness = 1.dp)
                                    Spacer(modifier = Modifier.weight(1f))
                                    VerticalDivider(color = Color.Cyan.copy(alpha = 0.4f), thickness = 1.dp)
                                    Spacer(modifier = Modifier.weight(1f))
                                }

                                // Top-Left Corner Handle
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopStart)
                                        .offset((-10).dp, (-10).dp)
                                        .size(24.dp)
                                        .background(Color.Cyan, CircleShape)
                                        .pointerInput(Unit) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                val deltaX = dragAmount.x / totalW
                                                val deltaY = dragAmount.y / totalH
                                                cropBoxLeft = (cropBoxLeft + deltaX).coerceIn(0f, cropBoxRight - 0.15f)
                                                cropBoxTop = (cropBoxTop + deltaY).coerceIn(0f, cropBoxBottom - 0.15f)
                                            }
                                        }
                                )

                                // Top-Right Corner Handle
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.TopEnd)
                                        .offset(10.dp, (-10).dp)
                                        .size(24.dp)
                                        .background(Color.Cyan, CircleShape)
                                        .pointerInput(Unit) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                val deltaX = dragAmount.x / totalW
                                                val deltaY = dragAmount.y / totalH
                                                cropBoxRight = (cropBoxRight + deltaX).coerceIn(cropBoxLeft + 0.15f, 1f)
                                                cropBoxTop = (cropBoxTop + deltaY).coerceIn(0f, cropBoxBottom - 0.15f)
                                            }
                                        }
                                )

                                // Bottom-Left Corner Handle
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomStart)
                                        .offset((-10).dp, 10.dp)
                                        .size(24.dp)
                                        .background(Color.Cyan, CircleShape)
                                        .pointerInput(Unit) {
                                            detectDragGestures { change, dragAmount ->
                                                change.consume()
                                                val deltaX = dragAmount.x / totalW
                                                val deltaY = dragAmount.y / totalH
                                                cropBoxLeft = (cropBoxLeft + deltaX).coerceIn(0f, cropBoxRight - 0.15f)
                                                cropBoxBottom = (cropBoxBottom + deltaY).coerceIn(cropBoxTop + 0.15f, 1f)
                                            }
                                        }
                                )

                                // Bottom-Right Corner Handle
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .offset(10.dp, 10.dp)
                                    .size(24.dp)
                                    .background(Color.Cyan, CircleShape)
                                    .pointerInput(Unit) {
                                        detectDragGestures { change, dragAmount ->
                                            change.consume()
                                            val deltaX = dragAmount.x / totalW
                                            val deltaY = dragAmount.y / totalH
                                            cropBoxRight = (cropBoxRight + deltaX).coerceIn(cropBoxLeft + 0.15f, 1f)
                                            cropBoxBottom = (cropBoxBottom + deltaY).coerceIn(cropBoxTop + 0.15f, 1f)
                                        }
                                    }
                            )
                        }
                    }
                }

                // Live Overlay Text Preview (Draggable & Movable)
                if (overlayText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .offset { IntOffset(textOffset.x.roundToInt(), textOffset.y.roundToInt()) }
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    textOffset += dragAmount
                                }
                            }
                            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                            .border(2.dp, Color.Cyan, RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = overlayText,
                                color = overlayTextColor,
                                style = MaterialTheme.typography.headlineMedium.copy(
                                    fontSize = textFontSizeSp.sp,
                                    fontFamily = when (selectedVideoFont) {
                                        AppFontFamily.SERIF -> FontFamily.Serif
                                        AppFontFamily.MONOSPACE -> FontFamily.Monospace
                                        AppFontFamily.CURSIVE -> FontFamily.Cursive
                                        else -> FontFamily.Default
                                    }
                                ),
                                fontWeight = FontWeight.Bold
                            )
                            IconButton(
                                onClick = {
                                    overlayText = ""
                                    textOffset = Offset(0f, 0f)
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove Text",
                                    tint = Color.Red,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }
                    }
                }

                // Play / Pause Overlay Button
                IconButton(
                    onClick = {
                        if (exoPlayer.playbackState == Player.STATE_ENDED || exoPlayer.currentPosition >= (durationMs - 200L).coerceAtLeast(0L)) {
                            exoPlayer.seekTo(0L)
                            exoPlayer.playWhenReady = true
                            exoPlayer.play()
                        } else if (exoPlayer.isPlaying) {
                            exoPlayer.pause()
                        } else {
                            exoPlayer.playWhenReady = true
                            exoPlayer.play()
                        }
                    },
                    modifier = Modifier
                        .size(56.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(
                        imageVector = if (isPlaying && exoPlayer.playbackState != Player.STATE_ENDED) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pause",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }
        }
    }

    if (isExporting) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Exporting Video...") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { exportProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("${(exportProgress * 100).toInt()}% completed")
                }
            },
            confirmButton = {}
        )
    }
}
}
