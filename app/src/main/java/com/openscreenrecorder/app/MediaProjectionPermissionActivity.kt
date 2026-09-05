package com.openscreenrecorder.app

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import android.Manifest
import androidx.core.net.toUri

/*
 * Transparent gateway activity that validates all required permissions
 * before launching the screen recording service.
 */
class MediaProjectionPermissionActivity : Activity() {

    companion object {
        private const val TAG = "MediaProjectionActivity"
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val REQUEST_RUNTIME_PERMISSIONS = 1002
    }

    private var isProjectionRequestPending = false
    private lateinit var configManager: ConfigManager

    /*
     * Initializes the activity, reads intent extras, and starts the permission validation flow.
     */
    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        configManager = ConfigManager(this)
        handleIntentExtras(intent)
        validateAndProceed()
    }

    /*
     * Updates ConfigManager with audio settings passed from the calling component.
     */
    private fun handleIntentExtras(intent: Intent) {
        if (intent.hasExtra("RECORD_MIC")) {
            configManager.isMicEnabled = intent.getBooleanExtra("RECORD_MIC", true)
        }
        if (intent.hasExtra("RECORD_SYSTEM_AUDIO")) {
            configManager.isSystemAudioEnabled = intent.getBooleanExtra("RECORD_SYSTEM_AUDIO", true)
        }
    }

    /*
     * Validates permissions or links directly to the screen sharing projection window when launched from the floating window.
     */
    private fun validateAndProceed() {
        if (isProjectionRequestPending) return
        val startFromMain = intent.getBooleanExtra("START_FROM_MAIN", false)
        if (!startFromMain) {
            requestMediaProjection()
            return
        }
        if (!checkRuntimePermissions()) return
        if (!checkOverlayPermission()) return
        if (!checkWriteSettingsPermission()) return
        requestMediaProjection()
    }

    /*
     * Checks and requests standard runtime permissions (audio, notifications).
     */
    private fun checkRuntimePermissions(): Boolean {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        perms.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = perms.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_RUNTIME_PERMISSIONS)
            false
        } else {
            true
        }
    }

    /*
     * Checks overlay permission required for floating controls.
     */
    private fun checkOverlayPermission(): Boolean {
        return if (!Settings.canDrawOverlays(this)) {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri()))
            false
        } else {
            true
        }
    }

    /*
     * Checks write system settings permission needed for show-touches feature.
     */
    private fun checkWriteSettingsPermission(): Boolean {
        if (!configManager.showTouches) return true
        return if (!Settings.System.canWrite(this)) {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS,
                    "package:$packageName".toUri()))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to launch write settings: ${e.message}")
            }
            false
        } else {
            true
        }
    }

    /*
     * Requests screen capture authorization from the user.
     * Sets a flag to prevent duplicate dialogs.
     */
    private fun requestMediaProjection() {
        if (isProjectionRequestPending) return
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
        projectionManager?.let {
            isProjectionRequestPending = true
            val captureIntent =
                try {
                    val config = MediaProjectionConfig.createConfigForUserChoice()
                    it.createScreenCaptureIntent(config)
                } catch (_: Exception) {
                    it.createScreenCaptureIntent()
                }
            startActivityForResult(captureIntent, REQUEST_MEDIA_PROJECTION)
        } ?: finish()
    }

    override fun onResume() {
        super.onResume()
        if (!isProjectionRequestPending) {
            validateAndProceed()
        }
    }

    /*
     * Handles the result of runtime permission requests and re-validates.
     */
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_RUNTIME_PERMISSIONS) {
            validateAndProceed()
        }
    }

    /*
     * Handles the result of the media projection permission dialog.
     * Resets the pending flag and starts the recording service immediately while in the foreground.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION) {
            isProjectionRequestPending = false
            if (resultCode == RESULT_OK && data != null) {
                startRecordingService(resultCode, data)
            } else {
                if (Settings.canDrawOverlays(this)) {
                    try {
                        startService(Intent(this, FloatingStartService::class.java))
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to restore floating start service: ${e.message}")
                    }
                }
                finish()
            }
        }
    }

    /*
     * Starts the ScreenRecordService as a foreground service with the projection data while in foreground,
     * then navigates to home or moves task to back.
     */
    private fun startRecordingService(resultCode: Int, data: Intent) {
        try {
            stopService(Intent(this, FloatingStartService::class.java))
            stopService(Intent(this, RecordingOverlayService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop floating services: ${e.message}")
        }

        val serviceIntent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, resultCode)
            putExtra(ScreenRecordService.EXTRA_DATA, data)
        }

        try {
            startForegroundService(serviceIntent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }

        val startFromMain = intent.getBooleanExtra("START_FROM_MAIN", false)
        if (startFromMain) {
            try {
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                startActivity(homeIntent)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to navigate home: ${e.message}")
            }
        } else {
            try {
                moveTaskToBack(true)
            } catch (e: Exception) {
                Log.e(TAG, "moveTaskToBack failed: ${e.message}")
            }
        }
        finish()
    }
}
