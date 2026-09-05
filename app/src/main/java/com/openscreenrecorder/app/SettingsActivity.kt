package com.openscreenrecorder.app

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
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
    var showTouches by remember { mutableStateOf(configManager.showTouches) }
    var recordingOverlayEnabled by remember { mutableStateOf(configManager.isRecordingOverlayEnabled) }
    var floatingAutoLaunchEnabled by remember { mutableStateOf(configManager.isFloatingAutoLaunchEnabled) }
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
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Recording Controls Overlay", color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Show floating timer and pause/stop controls during recording",
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

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Show Touches", color = MaterialTheme.colorScheme.onSurface)
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "Display visual touch feedback on screen during recording",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = showTouches,
                            onCheckedChange = { checked ->
                                if (checked && !Settings.System.canWrite(context)) {
                                    try {
                                        context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, "package:${context.packageName}".toUri()))
                                        Toast.makeText(context, "Please allow 'Write System Settings' permission for Show Touches", Toast.LENGTH_LONG).show()
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "Could not open Write Settings screen", Toast.LENGTH_SHORT).show()
                                    }
                                }
                                showTouches = checked
                                configManager.showTouches = checked
                            }
                        )
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
}
