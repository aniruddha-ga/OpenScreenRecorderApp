package com.openscreenrecorder.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.util.Size
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.activity.compose.setContent
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.milliseconds

class MainActivity : AppCompatActivity() {

    companion object {
        private const val REQUEST_PERMISSIONS_CODE = 2001
    }

    private lateinit var configManager: ConfigManager
    private lateinit var deleteLauncher: ActivityResultLauncher<IntentSenderRequest>

    private var videosState = mutableStateOf<List<VideoFile>>(emptyList())
    private var isRecordingState = mutableStateOf(ScreenRecordService.isRecording)
    private var isFloatingActiveState = mutableStateOf(FloatingStartService.isRunning)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val systemStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ScreenRecordService.ACTION_STATE_CHANGED -> {
                    isRecordingState.value = ScreenRecordService.isRecording
                    if (ScreenRecordService.isRecording) {
                        isFloatingActiveState.value = false
                    } else {
                        loadVideos()
                        mainHandler.postDelayed({ loadVideos() }, 600)
                        mainHandler.postDelayed({ loadVideos() }, 1500)
                    }
                }
                FloatingStartService.ACTION_FLOATING_STATE_CHANGED -> {
                    val running = intent.getBooleanExtra(FloatingStartService.EXTRA_FLOATING_RUNNING, FloatingStartService.isRunning)
                    isFloatingActiveState.value = running
                }
            }
        }
    }

    private val videoObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            super.onChange(selfChange)
            loadVideos()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)

        deleteLauncher = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show()
                loadVideos()
            } else {
                Toast.makeText(this, getString(R.string.VideoAdapter_toast_delete_failed), Toast.LENGTH_SHORT).show()
            }
        }

        setContent {
            OpenScreenRecorderTheme {
                MainScreen(
                    videos = videosState.value,
                    isRecording = isRecordingState.value,
                    isFloatingActive = isFloatingActiveState.value,
                    onRecordClick = { handleRecordAction() },
                    onFloatingToggle = { enabled -> handleFloatingToggle(enabled) },
                    onSettingsClick = {
                        startActivity(Intent(this, SettingsActivity::class.java))
                    },
                    onVideoClick = { uri -> openVideo(uri) },
                    onRenameVideo = { video, newName -> renameVideo(video, newName) },
                    onDeleteVideo = { video -> deleteVideo(video) }
                )
            }
        }

        registerSystemObservers()
        performFullPermissionCheck()
    }

    private fun openVideo(uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "video/mp4")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.VideoAdapter_toast_no_player) + e.message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renameVideo(video: VideoFile, newName: String) {
        val cleanName = if (newName.endsWith(".mp4", ignoreCase = true)) newName else "$newName.mp4"
        val values = ContentValues().apply { put(MediaStore.Video.Media.DISPLAY_NAME, cleanName) }
        try {
            val isDocument = DocumentsContract.isDocumentUri(this, video.uri) ||
                    (video.uri.scheme == "content" && video.uri.authority?.contains("documents") == true)

            if (isDocument) {
                val doc = DocumentFile.fromSingleUri(this, video.uri)
                if (doc?.renameTo(cleanName) == true) {
                    Toast.makeText(this, "Recording renamed", Toast.LENGTH_SHORT).show()
                    loadVideos()
                } else {
                    Toast.makeText(this, getString(R.string.VideoAdapter_toast_rename_failed), Toast.LENGTH_SHORT).show()
                }
                return
            }

            val updated = contentResolver.update(video.uri, values, null, null)
            if (updated > 0) {
                Toast.makeText(this, "Recording renamed", Toast.LENGTH_SHORT).show()
                loadVideos()
            } else {
                Toast.makeText(this, getString(R.string.VideoAdapter_toast_rename_failed), Toast.LENGTH_SHORT).show()
            }
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.VideoAdapter_toast_rename_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun deleteVideo(video: VideoFile) {
        val isDocument = DocumentsContract.isDocumentUri(this, video.uri) ||
                (video.uri.scheme == "content" && video.uri.authority?.contains("documents") == true)

        if (isDocument) {
            var deleted = false
            try {
                deleted = DocumentsContract.deleteDocument(contentResolver, video.uri)
            } catch (_: Exception) {}

            if (!deleted) {
                try {
                    deleted = DocumentFile.fromSingleUri(this, video.uri)?.delete() == true
                } catch (_: Exception) {}
            }

            if (deleted) {
                Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show()
                loadVideos()
            } else {
                Toast.makeText(this, getString(R.string.VideoAdapter_toast_delete_failed), Toast.LENGTH_SHORT).show()
            }
            return
        }

        try {
            val rowsDeleted = contentResolver.delete(video.uri, null, null)
            if (rowsDeleted > 0) {
                Toast.makeText(this, "Recording deleted", Toast.LENGTH_SHORT).show()
                loadVideos()
            } else {
                launchMediaStoreDeleteRequest(video.uri)
            }
        } catch (_: SecurityException) {
            launchMediaStoreDeleteRequest(video.uri)
        } catch (_: Exception) {
            launchMediaStoreDeleteRequest(video.uri)
        }
    }

    private fun launchMediaStoreDeleteRequest(uri: Uri) {
        try {
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(uri))
            deleteLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } catch (_: Exception) {
            Toast.makeText(this, getString(R.string.VideoAdapter_toast_delete_failed), Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleFloatingToggle(enabled: Boolean) {
        if (ScreenRecordService.isRecording) return
        if (enabled) {
            if (Settings.canDrawOverlays(this)) {
                try {
                    startService(Intent(this, FloatingStartService::class.java))
                    isFloatingActiveState.value = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
            }
        } else {
            try {
                stopService(Intent(this, FloatingStartService::class.java))
                stopService(Intent(this, RecordingOverlayService::class.java))
                isFloatingActiveState.value = false
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun performFullPermissionCheck() {
        checkAndRequestRuntimePermissions()
        if (!ScreenRecordService.isRecording) {
            if (configManager.isFloatingAutoLaunchEnabled && Settings.canDrawOverlays(this)) {
                try {
                    startService(Intent(this, FloatingStartService::class.java))
                    isFloatingActiveState.value = true
                } catch (e: Exception) {
                    e.printStackTrace()
                    isFloatingActiveState.value = FloatingStartService.isRunning
                }
            } else {
                isFloatingActiveState.value = FloatingStartService.isRunning
            }
        } else {
            isFloatingActiveState.value = FloatingStartService.isRunning
        }
        loadVideos()
    }

    private fun checkAndRequestRuntimePermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        )

        val hasVideoAccess = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED

        val missing = permissions.filter {
            if (it == Manifest.permission.READ_MEDIA_VIDEO || it == Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) {
                !hasVideoAccess
            } else {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
        }

        return if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_PERMISSIONS_CODE)
            false
        } else {
            true
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            if (!ScreenRecordService.isRecording && configManager.isFloatingAutoLaunchEnabled && Settings.canDrawOverlays(this)) {
                try {
                    startService(Intent(this, FloatingStartService::class.java))
                    isFloatingActiveState.value = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    private fun handleRecordAction() {
        if (ScreenRecordService.isRecording) {
            startService(Intent(this, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            })
        } else {
            val intent = Intent(this, MediaProjectionPermissionActivity::class.java).apply {
                putExtra("RECORD_MIC", configManager.isMicEnabled)
                putExtra("RECORD_SYSTEM_AUDIO", configManager.isSystemAudioEnabled)
                putExtra("START_FROM_MAIN", true)
            }
            startActivity(intent)
        }
    }

    @SuppressLint("Range")
    private fun loadVideos() {
        lifecycleScope.launch(Dispatchers.IO) {
            val videos = mutableListOf<VideoFile>()
            val customUriStr = configManager.recordingDirUri

            if (!customUriStr.isNullOrEmpty()) {
                try {
                    val treeUri = customUriStr.toUri()
                    val parentDoc = DocumentFile.fromTreeUri(this@MainActivity, treeUri)
                    parentDoc?.listFiles()?.forEach { fileDoc ->
                        if (fileDoc.isFile && (fileDoc.name?.endsWith(".mp4", ignoreCase = true) == true)) {
                            val name = fileDoc.name ?: "Recording.mp4"
                            val uri = fileDoc.uri
                            val size = fileDoc.length()
                            val dateAdded = fileDoc.lastModified() / 1000L
                            var duration = 0L

                            try {
                                val mmr = MediaMetadataRetriever()
                                mmr.setDataSource(this@MainActivity, uri)
                                val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                duration = durStr?.toLongOrNull() ?: 0L
                                mmr.release()
                            } catch (_: Exception) {}

                            if (size > 0L) {
                                videos.add(VideoFile(uri.hashCode().toLong(), uri, name, duration, size, dateAdded))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("MainActivity", "Failed to query custom folder: ${e.message}")
                }
            }

            val projection = arrayOf(
                MediaStore.Video.Media._ID,
                MediaStore.Video.Media.DISPLAY_NAME,
                MediaStore.Video.Media.DURATION,
                MediaStore.Video.Media.SIZE,
                MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.RELATIVE_PATH
            )

            val selection = "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?"
            val selectionArgs = arrayOf("DCIM/Recordings%")

            try {
                contentResolver.query(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    projection,
                    selection,
                    selectionArgs,
                    "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID))
                        val name = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)) ?: ""
                        var duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION))
                        val size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE))
                        val dateAdded = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED))

                        if (size > 0L) {
                            val uri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())

                            if (duration <= 0L) {
                                try {
                                    val pfd = contentResolver.openFileDescriptor(uri, "r")
                                    pfd?.use { descriptor ->
                                        val mmr = MediaMetadataRetriever()
                                        try {
                                            mmr.setDataSource(descriptor.fileDescriptor)
                                            val durStr = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                                            duration = durStr?.toLongOrNull() ?: 0L
                                        } finally {
                                            mmr.release()
                                        }
                                    }
                                } catch (_: Exception) {}
                            }

                            if (videos.none { it.name == name || it.uri == uri }) {
                                videos.add(VideoFile(id, uri, name, duration, size, dateAdded))
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "MediaStore query failed: ${e.message}")
            }

            videos.sortByDescending { it.dateAdded }

            withContext(Dispatchers.Main) {
                videosState.value = videos
            }
        }
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerSystemObservers() {
        contentResolver.registerContentObserver(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, videoObserver
        )
        val filter = IntentFilter().apply {
            addAction(ScreenRecordService.ACTION_STATE_CHANGED)
            addAction(FloatingStartService.ACTION_FLOATING_STATE_CHANGED)
        }
        registerReceiver(systemStateReceiver, filter, RECEIVER_EXPORTED)
    }

    override fun onResume() {
        super.onResume()
        isRecordingState.value = ScreenRecordService.isRecording
        if (ScreenRecordService.isRecording) {
            try {
                stopService(Intent(this, FloatingStartService::class.java))
                stopService(Intent(this, RecordingOverlayService::class.java))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            isFloatingActiveState.value = false
        } else {
            if (configManager.isFloatingAutoLaunchEnabled && Settings.canDrawOverlays(this)) {
                try {
                    startService(Intent(this, FloatingStartService::class.java))
                    isFloatingActiveState.value = true
                } catch (e: Exception) {
                    e.printStackTrace()
                    isFloatingActiveState.value = FloatingStartService.isRunning
                }
            } else {
                isFloatingActiveState.value = FloatingStartService.isRunning
            }
        }
        loadVideos()
        mainHandler.postDelayed({ loadVideos() }, 800)
    }

    override fun onDestroy() {
        super.onDestroy()
        contentResolver.unregisterContentObserver(videoObserver)
        unregisterReceiver(systemStateReceiver)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    videos: List<VideoFile>,
    isRecording: Boolean,
    isFloatingActive: Boolean,
    onRecordClick: () -> Unit,
    onFloatingToggle: (Boolean) -> Unit,
    onSettingsClick: () -> Unit,
    onVideoClick: (Uri) -> Unit,
    onRenameVideo: (VideoFile, String) -> Unit,
    onDeleteVideo: (VideoFile) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = "Open Screen Recorder") },
                actions = {
                    IconButton(
                        onClick = { if (!isRecording) onFloatingToggle(!isFloatingActive) },
                        enabled = !isRecording
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_floating),
                            contentDescription = "Floating Window",
                            tint = if (isFloatingActive && !isRecording) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onSettingsClick) {
                        Icon(painter = painterResource(id = R.drawable.ic_settings), contentDescription = "Settings")
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (videos.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f),
                                shape = CircleShape
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_screen_record),
                            contentDescription = null,
                            modifier = Modifier.size(44.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = "No Recordings Yet",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Tap the record button below to start capturing your screen",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                LazyVerticalStaggeredGrid(
                    columns = StaggeredGridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        top = 16.dp,
                        end = 16.dp,
                        bottom = 108.dp
                    ),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalItemSpacing = 16.dp
                ) {
                    items(videos, key = { it.id }) { video ->
                        VideoItemComposable(
                            video = video,
                            onClick = { onVideoClick(video.uri) },
                            onRename = { newName -> onRenameVideo(video, newName) },
                            onDelete = { onDeleteVideo(video) }
                        )
                    }
                }
            }

            IconButton(
                onClick = onRecordClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp)
                    .size(72.dp)
                    .clip(CircleShape)
            ) {
                Icon(
                    painter = painterResource(id = if (isRecording) R.drawable.ic_stop else R.drawable.ic_screen_record),
                    contentDescription = if (isRecording) "Stop Recording" else "Start Recording",
                    modifier = Modifier.size(48.dp),
                    tint = if (isRecording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
fun VideoItemComposable(
    video: VideoFile,
    onClick: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var renameText by remember { mutableStateOf(video.name.removeSuffix(".mp4")) }

    val thumbnailBitmap by produceState<Bitmap?>(initialValue = null, video.uri, video.size) {
        var bmp: Bitmap?
        repeat(4) {
            bmp = withContext(Dispatchers.IO) {
                var b: Bitmap? = null
                try {
                    b = context.contentResolver.loadThumbnail(video.uri, Size(320, 320), null)
                } catch (_: Exception) {}

                if (b == null) {
                    try {
                        val pfd = context.contentResolver.openFileDescriptor(video.uri, "r")
                        pfd?.use { descriptor ->
                            val mmr = MediaMetadataRetriever()
                            try {
                                mmr.setDataSource(descriptor.fileDescriptor)
                                b = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                    ?: mmr.frameAtTime
                            } finally {
                                mmr.release()
                            }
                        }
                    } catch (_: Exception) {}
                }

                if (b == null) {
                    try {
                        val mmr = MediaMetadataRetriever()
                        try {
                            mmr.setDataSource(context, video.uri)
                            b = mmr.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?: mmr.frameAtTime
                        } finally {
                            mmr.release()
                        }
                    } catch (_: Exception) {}
                }

                b
            }

            if (bmp != null) {
                value = bmp
                return@produceState
            }
            delay(500.milliseconds)
        }
    }

    val sizeMb = video.size / (1024f * 1024f)
    val duration = String.format(Locale.US, "%02d:%02d", (video.duration / 1000) / 60, (video.duration / 1000) % 60)
    val date = SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(video.dateAdded * 1000L))

    val aspectRatio = remember(thumbnailBitmap) {
        if (thumbnailBitmap != null && thumbnailBitmap!!.height > 0) {
            (thumbnailBitmap!!.width.toFloat() / thumbnailBitmap!!.height.toFloat()).coerceIn(0.6f, 1.8f)
        } else {
            16f / 9f
        }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.Transparent
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        ) {
            if (thumbnailBitmap != null) {
                Image(
                    bitmap = thumbnailBitmap!!.asImageBitmap(),
                    contentDescription = "Video Thumbnail",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.45f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_play),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color.White
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = 40.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_screen_record),
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(
                            color = Color.Black.copy(alpha = 0.5f),
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = "More Options",
                            tint = Color.White,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    modifier = Modifier
                        .widthIn(min = 145.dp)
                        .background(Color.Black, RoundedCornerShape(14.dp))
                        .border(BorderStroke(1.dp, Color(0xFF2C2C2C)), RoundedCornerShape(14.dp)),
                    shape = RoundedCornerShape(14.dp),
                    containerColor = Color.Black
                ) {
                    DropdownMenuItem(
                        text = { Text("Rename", color = Color.White, style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            menuExpanded = false
                            showRenameDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    )
                    HorizontalDivider(color = Color(0xFF222222), thickness = 1.dp)
                    DropdownMenuItem(
                        text = { Text("Share", color = Color.White, style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = { Icon(Icons.Default.Share, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        onClick = {
                            menuExpanded = false
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "video/mp4"
                                putExtra(Intent.EXTRA_STREAM, video.uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share Video"))
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    )
                    HorizontalDivider(color = Color(0xFF222222), thickness = 1.dp)
                    DropdownMenuItem(
                        text = { Text("Delete", color = Color(0xFFFF5252), style = MaterialTheme.typography.bodyMedium) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = null,
                                tint = Color(0xFFFF5252)
                            )
                        },
                        onClick = {
                            menuExpanded = false
                            showDeleteDialog = true
                        },
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // Bottom Overlaid Gradient Scrim containing Title, Date, Size, and Duration Badge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
                    .padding(start = 8.dp, end = 8.dp, top = 22.dp, bottom = 8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Bottom,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = video.name.removeSuffix(".mp4"),
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(iterations = Int.MAX_VALUE)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "$date • ${String.format(Locale.US, "%.1f MB", sizeMb)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.8f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Box(
                        modifier = Modifier
                            .background(
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = duration,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            title = { Text("Rename Video") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = MaterialTheme.colorScheme.onSurface,
                        unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outline
                    )
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showRenameDialog = false
                    if (renameText.isNotBlank()) {
                        onRename(renameText)
                    }
                }) {
                    Text("Rename", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            modifier = Modifier.border(
                BorderStroke(1.dp, if (isSystemInDarkTheme()) Color(0xFF2C2C2C) else Color(0xFFE0E0E0)),
                RoundedCornerShape(24.dp)
            ),
            shape = RoundedCornerShape(24.dp),
            containerColor = if (isSystemInDarkTheme()) Color.Black else Color.White,
            tonalElevation = 0.dp,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Recording") },
            text = { Text("Are you sure you want to delete this recording?") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    onDelete()
                }) {
                    Text("Delete", color = Color(0xFFFF2222), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            modifier = Modifier.border(
                BorderStroke(1.dp, if (isSystemInDarkTheme()) Color(0xFF2C2C2C) else Color(0xFFE0E0E0)),
                RoundedCornerShape(24.dp)
            ),
            shape = RoundedCornerShape(24.dp),
            containerColor = if (isSystemInDarkTheme()) Color.Black else Color.White,
            tonalElevation = 0.dp,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
