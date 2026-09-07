package com.openscreenrecorder.app

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * Quick Settings Tile service for taking a quick screenshot.
 */
class ScreenshotTileService : TileService() {

    override fun onStartListening() {
        super.onStartListening()
        updateTileState()
    }

    override fun onClick() {
        super.onClick()
        try {
            if (ScreenRecordService.isRecording) {
                val screenshotIntent = Intent(this, ScreenRecordService::class.java).apply {
                    action = ScreenRecordService.ACTION_TAKE_SCREENSHOT
                }
                startService(screenshotIntent)
            } else {
                launchScreenshotPermissionActivity()
            }
        } catch (e: Exception) {
            Log.e("ScreenshotTileService", "Error handling click: ${e.message}")
            updateTileState()
        }
    }

    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun launchScreenshotPermissionActivity() {
        val intent = Intent(this, MediaProjectionPermissionActivity::class.java).apply {
            putExtra("IS_SCREENSHOT", true)
            putExtra("START_FROM_MAIN", false)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            startActivityAndCollapse(pendingIntent)
        } catch (e: Exception) {
            Log.e("ScreenshotTileService", "Failed to collapse and start: ${e.message}")
        }
    }

    private fun updateTileState() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.label = getString(R.string.ScreenshotTileService_tile_label)
        tile.updateTile()
    }
}
