package com.openscreenrecorder.app

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class PlayerActivity : ComponentActivity() {

    companion object {
        const val EXTRA_VIDEO_URI = "extra_video_uri"
        const val EXTRA_VIDEO_TITLE = "extra_video_title"
    }

    private var exoPlayer: ExoPlayer? = null
    private lateinit var configManager: ConfigManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)

        val videoUriStr = intent.getStringExtra(EXTRA_VIDEO_URI)
        val videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE) ?: "Video Player"

        if (videoUriStr.isNullOrEmpty()) {
            Toast.makeText(this, "Invalid video path", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val videoUri = videoUriStr.toUri()

        setContent {
            OpenScreenRecorderTheme {
                PlayerScreen(
                    videoUri = videoUri,
                    videoTitle = videoTitle,
                    configManager = configManager,
                    onBackClick = { finish() },
                    onTrimExportComplete = {
                        setResult(RESULT_OK)
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        exoPlayer?.release()
        exoPlayer = null
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    videoUri: Uri,
    videoTitle: String,
    configManager: ConfigManager,
    onBackClick: () -> Unit,
    onTrimExportComplete: () -> Unit
) {
    val context = LocalContext.current
    var isTrimMode by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(true) }
    var currentPosMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }

    var trimRange by remember { mutableStateOf(0f..1000f) }
    var isTrimInitialized by remember { mutableStateOf(false) }

    var showSaveDialog by remember { mutableStateOf(false) }
    var exportFileName by remember {
        mutableStateOf("Trim_" + (videoTitle.removeSuffix(".mp4").ifBlank { "Video" }))
    }
    var isExporting by remember { mutableStateOf(false) }
    var exportProgress by remember { mutableFloatStateOf(0f) }

    val exoPlayer = remember(videoUri) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(videoUri))
            prepare()
            playWhenReady = true
        }
    }

    val togglePlayPause = {
        if (exoPlayer.isPlaying) {
            exoPlayer.pause()
        } else {
            val startPos = if (isTrimMode && isTrimInitialized) trimRange.start.toLong() else 0L
            val endPos = if (isTrimMode && isTrimInitialized) trimRange.endInclusive.toLong() else durationMs

            val isAtOrNearEnd = exoPlayer.playbackState == Player.STATE_ENDED ||
                    (endPos > 0 && exoPlayer.currentPosition >= endPos - 200L)

            if (isAtOrNearEnd) {
                exoPlayer.seekTo(startPos)
            }
            exoPlayer.play()
        }
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    val dur = exoPlayer.duration.coerceAtLeast(0L)
                    durationMs = dur
                    if (!isTrimInitialized && dur > 0) {
                        trimRange = 0f..dur.toFloat()
                        isTrimInitialized = true
                    }
                } else if (playbackState == Player.STATE_ENDED) {
                    isPlaying = false
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.release()
        }
    }

    // Position ticker and trim looping handler
    LaunchedEffect(exoPlayer, isTrimMode, trimRange) {
        while (true) {
            val pos = exoPlayer.currentPosition
            currentPosMs = pos

            if (isTrimMode && isTrimInitialized) {
                val endPoint = trimRange.endInclusive.toLong()
                val startPoint = trimRange.start.toLong()

                if (pos >= endPoint) {
                    exoPlayer.seekTo(startPoint)
                }
            }
            delay(100)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (isTrimMode) "Trim Video" else videoTitle,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isTrimMode) {
                            isTrimMode = false
                        } else {
                            onBackClick()
                        }
                    }) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (!isTrimMode) {
                        IconButton(onClick = {
                            isTrimMode = true
                            if (durationMs > 0 && !isTrimInitialized) {
                                trimRange = 0f..durationMs.toFloat()
                                isTrimInitialized = true
                            }
                        }) {
                            Icon(imageVector = Icons.Default.ContentCut, contentDescription = "Trim Video")
                        }
                    } else {
                        Button(
                            onClick = { showSaveDialog = true },
                            modifier = Modifier.padding(end = 8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Icon(imageVector = Icons.Default.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("Save Trim")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .background(Color.Black)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Video Player Area
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black)
                        .clickable { togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    AndroidView(
                        factory = { ctx ->
                            PlayerView(ctx).apply {
                                player = exoPlayer
                                useController = false // Custom overlays below
                            }
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(0.dp))
                    )
                }

                // Controls Panel
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 8.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (!isTrimMode) {
                        // Normal Playback Controls
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = formatTimeMs(currentPosMs),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = formatTimeMs(durationMs),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Slider(
                                value = currentPosMs.toFloat().coerceIn(0f, maxOf(1f, durationMs.toFloat())),
                                onValueChange = { newPos ->
                                    val target = newPos.toLong()
                                    currentPosMs = target
                                    exoPlayer.seekTo(target)
                                },
                                valueRange = 0f..maxOf(1f, durationMs.toFloat()),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { togglePlayPause() },
                                    modifier = Modifier
                                        .size(56.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(32.dp)
                                    )
                                }
                            }
                        }
                    } else {
                        // Trim Range Controls
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text(
                                text = "Select Trim Range",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.primary
                            )

                            val startMs = trimRange.start.toLong()
                            val endMs = trimRange.endInclusive.toLong()
                            val selectedDurationSec = (endMs - startMs) / 1000f

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column {
                                    Text("Start Time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatTimeMs(startMs), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("Clip Duration", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(String.format(Locale.US, "%.1f sec", selectedDurationSec), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("End Time", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(formatTimeMs(endMs), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                                }
                            }

                            RangeSlider(
                                value = trimRange,
                                onValueChange = { range ->
                                    val oldStart = trimRange.start
                                    val oldEnd = trimRange.endInclusive
                                    trimRange = range

                                    // If start handle was moved, seek player to start handle
                                    if (range.start != oldStart) {
                                        exoPlayer.seekTo(range.start.toLong())
                                    } else if (range.endInclusive != oldEnd) {
                                        // If end handle was moved, seek player to end handle
                                        exoPlayer.seekTo((range.endInclusive - 500f).coerceAtLeast(range.start).toLong())
                                    }
                                },
                                valueRange = 0f..maxOf(1f, durationMs.toFloat()),
                                modifier = Modifier.fillMaxWidth()
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.Center,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(
                                    onClick = { togglePlayPause() },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .background(MaterialTheme.colorScheme.secondaryContainer, CircleShape)
                                ) {
                                    Icon(
                                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                        contentDescription = if (isPlaying) "Pause" else "Play",
                                        tint = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier.size(28.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Export Loading Dialog
            if (isExporting) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.75f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(
                                progress = { exportProgress },
                                modifier = Modifier.size(56.dp)
                            )
                            Text(
                                text = "Exporting Trimmed Video...",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "${(exportProgress * 100).toInt()}%",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("Save Trimmed Video") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a filename for the trimmed video clip:", style = MaterialTheme.typography.bodyMedium)
                    OutlinedTextField(
                        value = exportFileName,
                        onValueChange = { exportFileName = it },
                        singleLine = true,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSaveDialog = false
                        isExporting = true
                        exportProgress = 0f

                        (context as ComponentActivity).lifecycleScope.launch {
                            val cleanName = if (exportFileName.endsWith(".mp4", ignoreCase = true)) {
                                exportFileName
                            } else {
                                "${exportFileName.trim()}.mp4"
                            }

                            val result = executeVideoTrim(
                                context = context,
                                configManager = configManager,
                                sourceUri = videoUri,
                                fileName = cleanName,
                                startMs = trimRange.start.toLong(),
                                endMs = trimRange.endInclusive.toLong(),
                                onProgress = { p -> exportProgress = p }
                            )

                            isExporting = false

                            if (result.first) {
                                Toast.makeText(context, "Trimmed video saved successfully", Toast.LENGTH_LONG).show()
                                onTrimExportComplete()
                                onBackClick()
                            } else {
                                Toast.makeText(context, "Trimming failed: ${result.second}", Toast.LENGTH_LONG).show()
                            }
                        }
                    }
                ) {
                    Text("Save", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            shape = RoundedCornerShape(24.dp)
        )
    }
}

private suspend fun executeVideoTrim(
    context: Context,
    configManager: ConfigManager,
    sourceUri: Uri,
    fileName: String,
    startMs: Long,
    endMs: Long,
    onProgress: (Float) -> Unit
): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
    val customUriStr = configManager.recordingDirUri
    var outputUri: Uri? = null
    var savedSuccessfully = false

    if (!customUriStr.isNullOrEmpty()) {
        try {
            val treeUri = customUriStr.toUri()
            val parentDoc = DocumentFile.fromTreeUri(context, treeUri)
            val fileDoc = parentDoc?.createFile("video/mp4", fileName)
            fileDoc?.uri?.let { uri ->
                outputUri = uri
                val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                if (pfd != null) {
                    pfd.use { descriptor ->
                        val trimResult = VideoTrimmer.trimVideo(context, sourceUri, descriptor, startMs, endMs, onProgress)
                        savedSuccessfully = trimResult.first
                        if (!savedSuccessfully) {
                            return@withContext trimResult
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Fallback to MediaStore
        }
    }

    if (!savedSuccessfully) {
        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "DCIM/Recordings")
        }
        val uri = context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, contentValues)
            ?: return@withContext Pair(false, "Failed to create target file in MediaStore.")
        
        outputUri = uri
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: return@withContext Pair(false, "Failed to open target file descriptor.")

        pfd.use { descriptor ->
            val trimResult = VideoTrimmer.trimVideo(context, sourceUri, descriptor, startMs, endMs, onProgress)
            if (!trimResult.first) {
                context.contentResolver.delete(uri, null, null)
                return@withContext trimResult
            }
        }
    }

    outputUri?.let { uri ->
        try {
            MediaScannerConnection.scanFile(context, arrayOf(uri.path), arrayOf("video/mp4"), null)
            context.contentResolver.notifyChange(uri, null)
        } catch (_: Exception) {}
    }

    Pair(true, null)
}

private fun formatTimeMs(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSec / 60
    val seconds = totalSec % 60
    return String.format(Locale.US, "%02d:%02d", minutes, seconds)
}
