package com.openscreenrecorder.app

import android.app.ActivityOptions
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat

/*
 * Receiver triggered by AlarmManager for scheduled screen recordings.
 */
class RecordingSchedulerReceiver : BroadcastReceiver() {

    companion object {
        const val TAG = "RecordingScheduler"
        const val ACTION_SCHEDULED_RECORDING = "com.openscreenrecorder.app.ACTION_SCHEDULED_RECORDING"
        const val CHANNEL_ID = "scheduled_recording_channel"
        const val NOTIFICATION_ID = 1002
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCHEDULED_RECORDING) return

        Log.d(TAG, "Scheduled recording alarm triggered!")

        try {
            val configManager = ConfigManager(context)
            configManager.isScheduledRecordingEnabled = false
            configManager.scheduledRecordingTimeMs = 0L

            if (ScreenRecordService.isRecording) {
                Log.d(TAG, "Recording is already active. Ignoring scheduled alarm.")
                return
            }

            val activityIntent = Intent(context, MediaProjectionPermissionActivity::class.java).apply {
                putExtra("START_FROM_MAIN", true)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                1002,
                activityIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            createNotificationChannel(context)

            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val canUseFullScreen = notificationManager.canUseFullScreenIntent()

            val notificationBuilder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle("Scheduled Screen Recording")
                .setContentText("Tap to begin your scheduled screen recording.")
                .setSmallIcon(R.drawable.ic_schedule)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)

            if (canUseFullScreen) {
                notificationBuilder.setFullScreenIntent(pendingIntent, true)
            }

            notificationManager.notify(NOTIFICATION_ID, notificationBuilder.build())

            @Suppress("DEPRECATION")
            val options = ActivityOptions.makeBasic().apply {
                setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            }

            try {
                context.startActivity(activityIntent, options.toBundle())
            } catch (e: Exception) {
                Log.e(TAG, "Could not launch activity directly from background: ${e.message}")
                try {
                    pendingIntent.send(context, 0, null, null, null, null, options.toBundle())
                } catch (e2: Exception) {
                    Log.e(TAG, "Could not send pending intent: ${e2.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling scheduled recording alarm: ${e.message}", e)
        }
    }

    private fun createNotificationChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Scheduled Recording Alerts",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Channel for scheduled screen recording notifications and alerts"
            setBypassDnd(true)
            enableVibration(true)
            setShowBadge(true)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }
}
