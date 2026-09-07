package com.openscreenrecorder.app

import android.Manifest
import android.app.AlarmManager
import android.app.DatePickerDialog
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForwardIos
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.color.DynamicColors
import androidx.core.net.toUri

class SettingsActivity : ComponentActivity() {

    private lateinit var configManager: ConfigManager
    private lateinit var folderPickerLauncher: ActivityResultLauncher<Intent>
    private var storageUriState = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configManager = ConfigManager(this)
        storageUriState.value = configManager.recordingDirUri

        folderPickerLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK && result.data != null) {
                val uri = result.data?.data
                uri?.let {
                    val takeFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    contentResolver.takePersistableUriPermission(it, takeFlags)
                    configManager.recordingDirUri = it.toString()
                    storageUriState.value = it.toString()
                    Toast.makeText(this, "Save location updated", Toast.LENGTH_SHORT).show()
                }
            }
        }

        setContent {
            OpenScreenRecorderTheme {
                SettingsScreen(
                    configManager = configManager,
                    storageUri = storageUriState.value,
                    isDynamicAvailable = DynamicColors.isDynamicColorAvailable(),
                    onBackClick = { finish() },
                    onStorageClick = {
                        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                        folderPickerLauncher.launch(intent)
                    },
                    onAboutClick = {
                        startActivity(Intent(this, AboutActivity::class.java))
                    },
                    onDynamicColorChanged = {
                        refreshFloatingWindowTheme()
                        recreate()
                    },
                    onThemeModeChanged = { mode ->
                        AppCompatDelegate.setDefaultNightMode(mode)
                        refreshFloatingWindowTheme()
                        recreate()
                    }
                )
            }
        }
    }

    private fun refreshFloatingWindowTheme() {
        if (FloatingStartService.isRunning) {
            try {
                startService(Intent(this, FloatingStartService::class.java).apply {
                    action = FloatingStartService.ACTION_REFRESH_THEME
                })
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    configManager: ConfigManager,
    storageUri: String?,
    isDynamicAvailable: Boolean,
    onBackClick: () -> Unit,
    onStorageClick: () -> Unit,
    onAboutClick: () -> Unit,
    onDynamicColorChanged: () -> Unit,
    onThemeModeChanged: (Int) -> Unit
) {
    val context = LocalContext.current
    var micEnabled by remember { mutableStateOf(configManager.isMicEnabled) }
    var systemAudioEnabled by remember { mutableStateOf(configManager.isSystemAudioEnabled) }
    val isSystemShowTouchesEnabled = remember(context) {
        try {
            Settings.System.getInt(context.contentResolver, "show_touches", 0) == 1
        } catch (_: Exception) {
            false
        }
    }
    var showTouchesGuideDialog by remember { mutableStateOf(false) }
    var recordingOverlayEnabled by remember { mutableStateOf(configManager.isRecordingOverlayEnabled) }
    var floatingAutoLaunchEnabled by remember { mutableStateOf(configManager.isFloatingAutoLaunchEnabled) }
    var isBrushEnabled by remember { mutableStateOf(configManager.isBrushEnabled) }
    var isCameraEnabled by remember { mutableStateOf(configManager.isCameraEnabled) }
    var isScreenshotEnabled by remember { mutableStateOf(configManager.isScreenshotEnabled) }
    var isScreenshotWithDrawing by remember { mutableStateOf(configManager.isScreenshotWithDrawing) }
    var autoStopTimerSecs by remember { mutableIntStateOf(configManager.autoStopTimerSeconds) }
    var isAutoStartRecordingEnabled by remember { mutableStateOf(configManager.isAutoStartRecordingEnabled) }
    var scheduledTimeMs by remember { mutableLongStateOf(configManager.scheduledRecordingTimeMs) }
    var isScheduledRecordingEnabled by remember { mutableStateOf(configManager.isScheduledRecordingEnabled) }
    var autoStopDropdownExpanded by remember { mutableStateOf(false) }
    var dynamicColors by remember { mutableStateOf(configManager.isDynamicColorsEnabled) }
    var videoQuality by remember { mutableStateOf(configManager.videoQuality) }
    var themeMode by remember { mutableStateOf(configManager.themeMode) }
    var fileNamePrefix by remember { mutableStateOf(configManager.fileNamePrefix) }
    var lastSavedPrefix by remember { mutableStateOf(configManager.fileNamePrefix) }
    var dateFormatPattern by remember { mutableStateOf(configManager.dateFormatPattern) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    val illegalFileNameChars = remember { charArrayOf('/', '\\', ':', '*', '?', '"', '<', '>', '|') }

    val applyPrefixChange = {
        val newPrefix = fileNamePrefix.trim()
        val illegalChar = newPrefix.firstOrNull { it in illegalFileNameChars || it < ' ' }
        if (illegalChar != null) {
            Toast.makeText(context, "Symbol '$illegalChar' is not allowed in file prefix", Toast.LENGTH_LONG).show()
        } else if (newPrefix != lastSavedPrefix) {
            try {
                configManager.fileNamePrefix = newPrefix
                if (configManager.fileNamePrefix == newPrefix) {
                    lastSavedPrefix = newPrefix
                    if (newPrefix.startsWith(".")) {
                        Toast.makeText(
                            context,
                            "Prefix set: $newPrefix (Files starting with '.' will be hidden from Gallery)",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(context, "Prefix set: $newPrefix", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(context, "Failed to set prefix", Toast.LENGTH_SHORT).show()
                }
            } catch (_: Exception) {
                Toast.makeText(context, "Failed to set prefix", Toast.LENGTH_SHORT).show()
            }
            keyboardController?.hide()
            focusManager.clearFocus()
        } else {
            keyboardController?.hide()
            focusManager.clearFocus()
        }
    }

    val datePatterns = listOf(
        ConfigManager.DEFAULT_DATE_FORMAT_PATTERN to "yyyyMMdd_HHmmss (Default)",
        "yyyy-MM-dd_HH-mm-ss" to "yyyy-MM-dd_HH-mm-ss",
        "dd-MM-yyyy_HH-mm-ss" to "dd-MM-yyyy_HH-mm-ss",
        "ddMMyyyy_HHmmss" to "ddMMyyyy_HHmmss",
        "yyyy.MM.dd_HH.mm.ss" to "yyyy.MM.dd_HH.mm.ss"
    )

    val fileNamePreview = remember(fileNamePrefix, dateFormatPattern) {
        configManager.getFileNamePreview(fileNamePrefix, dateFormatPattern)
    }

    val storageSummary = remember(storageUri) {
        if (!storageUri.isNullOrEmpty()) {
            try {
                val docFile = DocumentFile.fromTreeUri(context, storageUri.toUri())
                docFile?.name ?: storageUri
            } catch (_: Exception) {
                storageUri
            }
        } else {
            "DCIM/Recordings (Default)"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(imageVector = Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Appearance (Dynamic Colors & App Theme)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Appearance", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    
                    if (isDynamicAvailable) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Dynamic Colors", color = MaterialTheme.colorScheme.onSurface)
                            Switch(
                                checked = dynamicColors,
                                onCheckedChange = {
                                    dynamicColors = it
                                    configManager.isDynamicColorsEnabled = it
                                    onDynamicColorChanged()
                                }
                            )
                        }
                    }

                    Text("Theme Mode", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(
                            ConfigManager.THEME_SYSTEM to "System",
                            ConfigManager.THEME_LIGHT to "Light",
                            ConfigManager.THEME_DARK to "Dark"
                        ).forEach { (mode, label) ->
                            val isSelected = themeMode == mode
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    themeMode = mode
                                    configManager.themeMode = mode
                                    onThemeModeChanged(configManager.getThemeModeValue())
                                },
                                label = { Text(label) },
                                shape = if (isSelected) CircleShape else RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }
            }

            // 2. Audio
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Audio", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Record Microphone", color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = micEnabled,
                            onCheckedChange = {
                                micEnabled = it
                                configManager.isMicEnabled = it
                            }
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Record System Audio", color = MaterialTheme.colorScheme.onSurface)
                        Switch(
                            checked = systemAudioEnabled,
                            onCheckedChange = {
                                systemAudioEnabled = it
                                configManager.isSystemAudioEnabled = it
                            }
                        )
                    }
                }
            }

            // 3. Video (Video Quality)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Video Quality", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    val qualities = configManager.getAvailableQualityOptions()
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        qualities.forEach { q ->
                            val label = if (q == ConfigManager.QUALITY_MAX) configManager.getMaxQualityLabel() else q.uppercase()
                            val isSelected = videoQuality == q
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    videoQuality = q
                                    configManager.videoQuality = q
                                },
                                label = { Text(label) },
                                shape = if (isSelected) CircleShape else RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }
                }
            }

            // File Naming Convention Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("File Naming Convention", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

                    Text("Custom File Prefix", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    OutlinedTextField(
                        value = fileNamePrefix,
                        onValueChange = { input ->
                            val illegal = input.firstOrNull { it in illegalFileNameChars || it < ' ' }
                            if (illegal != null) {
                                Toast.makeText(context, "Symbol '$illegal' is not allowed in file name", Toast.LENGTH_SHORT).show()
                            } else {
                                if (input.startsWith(".") && !fileNamePrefix.startsWith(".")) {
                                    Toast.makeText(context, "Prefix starting with '.' will hide recordings from Gallery", Toast.LENGTH_SHORT).show()
                                }
                                fileNamePrefix = input
                            }
                        },
                        placeholder = { Text("e.g. Screen_Record_") },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = { applyPrefixChange() }
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = MaterialTheme.colorScheme.onSurface,
                            unfocusedTextColor = MaterialTheme.colorScheme.onSurface,
                            focusedBorderColor = MaterialTheme.colorScheme.primary,
                            unfocusedBorderColor = MaterialTheme.colorScheme.outline
                        )
                    )

                    Text("Date & Time Format", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    @OptIn(ExperimentalLayoutApi::class)
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        datePatterns.forEach { (pattern, label) ->
                            val isSelected = dateFormatPattern == pattern
                            FilterChip(
                                selected = isSelected,
                                onClick = {
                                    dateFormatPattern = pattern
                                    configManager.dateFormatPattern = pattern
                                },
                                label = { Text(label) },
                                shape = if (isSelected) CircleShape else RoundedCornerShape(12.dp),
                                colors = FilterChipDefaults.filterChipColors(
                                    containerColor = Color.Transparent,
                                    selectedContainerColor = MaterialTheme.colorScheme.primary,
                                    labelColor = MaterialTheme.colorScheme.onSurface,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )
                        }
                    }

                    Surface(
                        color = Color.Transparent,
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Preview:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = fileNamePreview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            // Advanced Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Advanced", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Start Floating Window", color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Automatically open floating start window when launching or resuming app",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = floatingAutoLaunchEnabled,
                            onCheckedChange = { checked ->
                                floatingAutoLaunchEnabled = checked
                                configManager.isFloatingAutoLaunchEnabled = checked
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 1.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showTouchesGuideDialog = true }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Show Touches (Touch Feedback)",
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Display visual touch points on screen. Tap to view guide & enable in Developer Options.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Surface(
                            onClick = { showTouchesGuideDialog = true },
                            shape = RoundedCornerShape(12.dp),
                            color = if (isSystemShowTouchesEnabled)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surfaceVariant,
                            border = BorderStroke(
                                1.dp,
                                if (isSystemShowTouchesEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = if (isSystemShowTouchesEnabled) "Active" else "Guide",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (isSystemShowTouchesEnabled)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Icon(
                                    imageVector = Icons.AutoMirrored.Filled.ArrowForwardIos,
                                    contentDescription = null,
                                    modifier = Modifier.size(12.dp),
                                    tint = if (isSystemShowTouchesEnabled)
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                    else
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // Recording Overlay & On-Screen Tools Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Recording Overlay & On-Screen Tools", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

                    // Master Switch: Recording Controls Overlay
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Recording Controls Overlay", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Show floating timer and recording controls overlay during recording",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = recordingOverlayEnabled,
                            onCheckedChange = { checked ->
                                recordingOverlayEnabled = checked
                                configManager.isRecordingOverlayEnabled = checked
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 1.dp)

                    // Sub-Tools (controlled by master switch)
                    Column(
                        modifier = Modifier.padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Text(
                            text = "Overlay Tools & Shortcuts",
                            style = MaterialTheme.typography.labelLarge,
                            color = if (recordingOverlayEnabled) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Screen Brush",
                                    color = if (recordingOverlayEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Screen drawing and annotation toolbar button",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (recordingOverlayEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            }
                            Switch(
                                checked = isBrushEnabled,
                                enabled = recordingOverlayEnabled,
                                onCheckedChange = { checked ->
                                    isBrushEnabled = checked
                                    configManager.isBrushEnabled = checked
                                }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 1.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Camera Facecam",
                                    color = if (recordingOverlayEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Floating camera facecam preview button",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (recordingOverlayEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            }
                            Switch(
                                checked = isCameraEnabled,
                                enabled = recordingOverlayEnabled,
                                onCheckedChange = { checked ->
                                    if (checked && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                        ActivityCompat.requestPermissions(context as ComponentActivity, arrayOf(Manifest.permission.CAMERA), 1003)
                                    }
                                    isCameraEnabled = checked
                                    configManager.isCameraEnabled = checked
                                }
                            )
                        }

                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f), thickness = 1.dp)

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Screenshot Shortcut",
                                    color = if (recordingOverlayEnabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "Instant screenshot capture button",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (recordingOverlayEnabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                )
                            }
                            Switch(
                                checked = isScreenshotEnabled,
                                enabled = recordingOverlayEnabled,
                                onCheckedChange = { checked ->
                                    isScreenshotEnabled = checked
                                    configManager.isScreenshotEnabled = checked
                                }
                            )
                        }

                        if (isScreenshotEnabled && recordingOverlayEnabled) {
                            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Include Drawings in Screenshots",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = if (isScreenshotWithDrawing) "With Drawing (Hides brush controls & timings)" else "Without Drawing (Hides drawings, brush controls & timings)",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = isScreenshotWithDrawing,
                                    onCheckedChange = { checked ->
                                        isScreenshotWithDrawing = checked
                                        configManager.isScreenshotWithDrawing = checked
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // Automation & Timer Settings Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Automation & Timer Settings", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)

                    // 1. Auto-Stop Timer
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Stop Timer", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Automatically stop recording after selected duration",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Box {
                            val timerOptions = mapOf(
                                0 to "Disabled",
                                60 to "1 Min",
                                300 to "5 Mins",
                                600 to "10 Mins",
                                1800 to "30 Mins",
                                3600 to "1 Hour"
                            )
                            TextButton(onClick = { autoStopDropdownExpanded = true }) {
                                Text(timerOptions[autoStopTimerSecs] ?: "${autoStopTimerSecs / 60} Mins")
                            }
                            DropdownMenu(
                                expanded = autoStopDropdownExpanded,
                                onDismissRequest = { autoStopDropdownExpanded = false }
                            ) {
                                timerOptions.forEach { (secs, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        onClick = {
                                            autoStopTimerSecs = secs
                                            configManager.autoStopTimerSeconds = secs
                                            autoStopDropdownExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 1.dp)

                    // 2. Auto-Start Recording
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-Start Recording", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Launch recording request automatically on app launch",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = isAutoStartRecordingEnabled,
                            onCheckedChange = { checked ->
                                isAutoStartRecordingEnabled = checked
                                configManager.isAutoStartRecordingEnabled = checked
                            }
                        )
                    }

                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), thickness = 1.dp)

                    // 3. Scheduled Recording
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Scheduled Recording", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                        val scheduleFormatter = remember { SimpleDateFormat("EEE, dd MMM yyyy 'at' hh:mm a", Locale.getDefault()) }
                        val formattedSchedule = if (isScheduledRecordingEnabled && scheduledTimeMs > System.currentTimeMillis()) {
                            "Scheduled for ${scheduleFormatter.format(Date(scheduledTimeMs))}"
                        } else {
                            "No recording scheduled"
                        }
                        Text(
                            text = formattedSchedule,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.padding(top = 4.dp)
                        ) {
                            Button(
                                onClick = {
                                    if (!checkAndRequestPermissionsForScheduling(context)) {
                                        return@Button
                                    }
                                    val cal = Calendar.getInstance()
                                    DatePickerDialog(
                                        context,
                                        { _, year, month, dayOfMonth ->
                                            cal.set(Calendar.YEAR, year)
                                            cal.set(Calendar.MONTH, month)
                                            cal.set(Calendar.DAY_OF_MONTH, dayOfMonth)
                                            TimePickerDialog(
                                                context,
                                                { _, hourOfDay, minute ->
                                                    cal.set(Calendar.HOUR_OF_DAY, hourOfDay)
                                                    cal.set(Calendar.MINUTE, minute)
                                                    cal.set(Calendar.SECOND, 0)
                                                    val targetMs = cal.timeInMillis
                                                    if (targetMs > System.currentTimeMillis()) {
                                                        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                                                        val intent = Intent(context, RecordingSchedulerReceiver::class.java).apply {
                                                            action = RecordingSchedulerReceiver.ACTION_SCHEDULED_RECORDING
                                                        }
                                                        val pendingIntent = PendingIntent.getBroadcast(
                                                            context,
                                                            0,
                                                            intent,
                                                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                                        )
                                                        val showIntent = PendingIntent.getActivity(
                                                            context,
                                                            0,
                                                            Intent(context, SettingsActivity::class.java),
                                                            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                                        )
                                                        try {
                                                            if (!alarmManager.canScheduleExactAlarms()) {
                                                                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pendingIntent)
                                                            } else {
                                                                alarmManager.setAlarmClock(
                                                                    AlarmManager.AlarmClockInfo(targetMs, showIntent),
                                                                    pendingIntent
                                                                )
                                                            }
                                                        } catch (_: SecurityException) {
                                                            try {
                                                                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pendingIntent)
                                                            } catch (e2: Exception) {
                                                                Log.e("SettingsActivity", "Failed to schedule fallback alarm: ${e2.message}")
                                                            }
                                                        } catch (e: Exception) {
                                                            Log.e("SettingsActivity", "Failed to schedule alarm clock: ${e.message}")
                                                            try {
                                                                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, targetMs, pendingIntent)
                                                            } catch (e2: Exception) {
                                                                Log.e("SettingsActivity", "Failed to schedule fallback alarm: ${e2.message}")
                                                            }
                                                        }
                                                        scheduledTimeMs = targetMs
                                                        isScheduledRecordingEnabled = true
                                                        configManager.scheduledRecordingTimeMs = targetMs
                                                        configManager.isScheduledRecordingEnabled = true
                                                        Toast.makeText(context, "Recording scheduled successfully!", Toast.LENGTH_SHORT).show()
                                                    } else {
                                                        Toast.makeText(context, "Please pick a future time", Toast.LENGTH_SHORT).show()
                                                    }
                                                },
                                                cal.get(Calendar.HOUR_OF_DAY),
                                                cal.get(Calendar.MINUTE),
                                                false
                                            ).show()
                                        },
                                        cal.get(Calendar.YEAR),
                                        cal.get(Calendar.MONTH),
                                        cal.get(Calendar.DAY_OF_MONTH)
                                    ).show()
                                }
                            ) {
                                Text("Pick Date & Time")
                            }

                            if (isScheduledRecordingEnabled && scheduledTimeMs > 0L) {
                                OutlinedButton(onClick = {
                                    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
                                    val intent = Intent(context, RecordingSchedulerReceiver::class.java).apply {
                                        action = RecordingSchedulerReceiver.ACTION_SCHEDULED_RECORDING
                                    }
                                    val pendingIntent = PendingIntent.getBroadcast(
                                        context,
                                        0,
                                        intent,
                                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                    )
                                    alarmManager.cancel(pendingIntent)
                                    scheduledTimeMs = 0L
                                    isScheduledRecordingEnabled = false
                                    configManager.scheduledRecordingTimeMs = 0L
                                    configManager.isScheduledRecordingEnabled = false
                                    Toast.makeText(context, "Schedule cancelled", Toast.LENGTH_SHORT).show()
                                }) {
                                    Text("Cancel Schedule")
                                }
                            }
                        }
                    }
                }
            }

            // Storage Location Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onStorageClick),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Storage Location", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = storageSummary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // 4. Information (About Card)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onAboutClick),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Transparent),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("About App", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    Text(
                        text = "Version, licenses and developer info",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))
        }
    }

    if (showTouchesGuideDialog) {
        AlertDialog(
            onDismissRequest = { showTouchesGuideDialog = false },
            icon = {
                Icon(
                    imageVector = Icons.Default.TouchApp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
            },
            title = {
                Text(
                    text = "Enable Show Touches (Show Taps)",
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Android requires enabling 'Show taps' in Developer Options to render touch feedback on screen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Step 1: Enable Developer Options",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "1. Go to Settings > About Phone\n2. Tap 'Build Number' 7 times continuously until Developer Mode is activated.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "Step 2: Turn ON Show Taps",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "1. Open Developer Options\n2. Scroll down to the Input section\n3. Switch ON 'Show taps' / 'Show touches'.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTouchesGuideDialog = false
                        try {
                            val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Could not open Settings", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Open Developer Options")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        showTouchesGuideDialog = false
                        try {
                            val intent = Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
                            context.startActivity(intent)
                        } catch (_: Exception) {
                            try {
                                val intent = Intent(Settings.ACTION_SETTINGS)
                                context.startActivity(intent)
                            } catch (_: Exception) {
                                Toast.makeText(context, "Could not open About Phone", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }
                ) {
                    Text("Open About Phone")
                }
            }
        )
    }
}

private fun checkAndRequestPermissionsForScheduling(context: Context): Boolean {
    // 1. Notification Permission
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        if (context is ComponentActivity) {
            ActivityCompat.requestPermissions(
                context,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1004
            )
        }
        Toast.makeText(context, "Please allow Notification permission for scheduled recording alerts", Toast.LENGTH_LONG).show()
        return false
    }

    // 2. Overlay Permission
    if (!Settings.canDrawOverlays(context)) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:${context.packageName}".toUri())
            context.startActivity(intent)
            Toast.makeText(context, "Please allow 'Display over other apps' permission for scheduled recording", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Please enable 'Display over other apps' in Settings", Toast.LENGTH_LONG).show()
        }
        return false
    }

    // 3. Exact Alarm Permission
    val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    if (!alarmManager.canScheduleExactAlarms()) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri())
            context.startActivity(intent)
            Toast.makeText(context, "Please allow 'Alarms & reminders' permission for exact scheduling", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Please enable 'Alarms & reminders' in Settings", Toast.LENGTH_LONG).show()
        }
        return false
    }

    // 4. Full Screen Intent Permission
    val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    if (!notificationManager.canUseFullScreenIntent()) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, "package:${context.packageName}".toUri())
            context.startActivity(intent)
            Toast.makeText(context, "Please allow 'Full Screen Intents' permission for automatic scheduled launch", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(context, "Please enable 'Full Screen Intents' in Settings", Toast.LENGTH_LONG).show()
        }
        return false
    }

    return true
}
