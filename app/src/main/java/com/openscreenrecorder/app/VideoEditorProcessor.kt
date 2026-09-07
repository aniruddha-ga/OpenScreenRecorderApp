package com.openscreenrecorder.app

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.text.SpannableString
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Crop
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.ScaleAndRotateTransformation
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.coroutines.resume

data class VideoCutRange(val startMs: Long, val endMs: Long)

data class CropBounds(val left: Float = -1f, val top: Float = 1f, val right: Float = 1f, val bottom: Float = -1f)

data class VideoEditOptions(
    val cropBounds: CropBounds? = null,
    val rotationDegrees: Float = 0f,        // 0, 90, 180, 270
    val speedMultiplier: Float = 1.0f,     // 0.25..2.0
    val backgroundMusicUri: Uri? = null,
    val backgroundMusicVolume: Float = 1.0f,
    val musicStartMs: Long = 0L,
    val musicEndMs: Long = 0L,
    val loopBackgroundMusic: Boolean = true,
    val originalAudioVolume: Float = 1.0f,
    val voiceOverUri: Uri? = null,
    val overlayText: String? = null,
    val overlayTextColor: Int = Color.WHITE,
    val overlayTextSizeSp: Float = 32f,
    val cutRangesToRemove: List<VideoCutRange> = emptyList(), // time ranges to CUT OUT
    val mergeVideoUris: List<Uri> = emptyList()               // additional clips to append
)

@OptIn(UnstableApi::class)
object VideoEditorProcessor {

    private const val TAG = "VideoEditorProcessor"

    /**
     * Executes all configured video edits (crop, rotate, speed, merge, music, voice-over, text, multi-cut)
     * using Media3 Transformer & Effect pipeline.
     */
    suspend fun processVideo(
        context: Context,
        inputUri: Uri,
        outputPfd: ParcelFileDescriptor,
        options: VideoEditOptions,
        onProgress: (Float) -> Unit
    ): Pair<Boolean, String?> = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            var tempOutputFile: File? = null
            try {
                val effects = mutableListOf<Effect>()

                // 1. Rotation Effect
                if (options.rotationDegrees != 0f) {
                    val rotateEffect = ScaleAndRotateTransformation.Builder()
                        .setRotationDegrees(options.rotationDegrees)
                        .build()
                    effects.add(rotateEffect)
                }

                // 2. Crop Effect
                options.cropBounds?.let { crop ->
                    val cropEffect = Crop(crop.left, crop.right, crop.bottom, crop.top)
                    effects.add(cropEffect)
                }

                // 3. Text Overlay Effect
                if (!options.overlayText.isNullOrBlank()) {
                    val textOverlay = TextOverlay.createStaticTextOverlay(
                        SpannableString(options.overlayText)
                    )
                    effects.add(OverlayEffect(ImmutableList.of<TextureOverlay>(textOverlay)))
                }

                // Build Primary Video MediaItems (handling multi-range removal)
                val primaryMediaItems = mutableListOf<MediaItem>()

                if (options.cutRangesToRemove.isEmpty()) {
                    primaryMediaItems.add(MediaItem.fromUri(inputUri))
                } else {
                    val keptRanges = computeKeptRanges(options.cutRangesToRemove)
                    for (range in keptRanges) {
                        val clippingConfig = MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(range.startMs)
                            .setEndPositionMs(range.endMs)
                            .build()
                        val item = MediaItem.Builder()
                            .setUri(inputUri)
                            .setClippingConfiguration(clippingConfig)
                            .build()
                        primaryMediaItems.add(item)
                    }
                }

                // Merge additional clips if provided
                for (mergeUri in options.mergeVideoUris) {
                    primaryMediaItems.add(MediaItem.fromUri(mergeUri))
                }

                // Build EditedMediaItems
                val editedPrimaryItems = primaryMediaItems.map { mediaItem ->
                    val builder = EditedMediaItem.Builder(mediaItem)
                        .setEffects(Effects(emptyList(), effects))
                        .setRemoveAudio(options.originalAudioVolume == 0f)
                    if (options.speedMultiplier != 1.0f) {
                        try {
                            val speedProvider = object : SpeedProvider {
                                override fun getSpeed(timeUs: Long): Float = options.speedMultiplier
                                override fun getNextSpeedChangeTimeUs(timeUs: Long): Long = C.TIME_UNSET
                            }
                            builder.setSpeed(speedProvider)
                        } catch (_: Exception) {}
                    }
                    builder.build()
                }

                @Suppress("DEPRECATION")
                val primarySequence = EditedMediaItemSequence.Builder(editedPrimaryItems).build()
                val sequences = mutableListOf(primarySequence)

                // 4. Background Music Sequence
                options.backgroundMusicUri?.let { musicUri ->
                    val musicStartMs = options.musicStartMs.coerceAtLeast(0L)
                    val musicEndMs = if (options.musicEndMs > musicStartMs) options.musicEndMs else (musicStartMs + 10000L)
                    val musicClipDuration = (musicEndMs - musicStartMs).coerceAtLeast(1000L)

                    val musicItems = mutableListOf<EditedMediaItem>()

                    if (options.loopBackgroundMusic) {
                        var currentDuration = 0L
                        val maxTargetDurationMs = 300_000L
                        while (currentDuration < maxTargetDurationMs) {
                            val clipConfig = MediaItem.ClippingConfiguration.Builder()
                                .setStartPositionMs(musicStartMs)
                                .setEndPositionMs(musicEndMs)
                                .build()

                            val item = MediaItem.Builder()
                                .setUri(musicUri)
                                .setClippingConfiguration(clipConfig)
                                .build()

                            musicItems.add(EditedMediaItem.Builder(item).build())
                            currentDuration += musicClipDuration
                        }
                    } else {
                        val clipConfig = MediaItem.ClippingConfiguration.Builder()
                            .setStartPositionMs(musicStartMs)
                            .setEndPositionMs(musicEndMs)
                            .build()

                        val item = MediaItem.Builder()
                            .setUri(musicUri)
                            .setClippingConfiguration(clipConfig)
                            .build()

                        musicItems.add(EditedMediaItem.Builder(item).build())
                    }

                    @Suppress("DEPRECATION")
                    val musicSequence = EditedMediaItemSequence.Builder(musicItems).build()
                    sequences.add(musicSequence)
                }

                // 5. Voice-over Sequence
                options.voiceOverUri?.let { voiceUri ->
                    val voiceItem = MediaItem.fromUri(voiceUri)
                    val editedVoice = EditedMediaItem.Builder(voiceItem).build()
                    @Suppress("DEPRECATION")
                    val voiceSequence = EditedMediaItemSequence.Builder(listOf(editedVoice)).build()
                    sequences.add(voiceSequence)
                }

                val composition = Composition.Builder(sequences).build()

                tempOutputFile = File(context.cacheDir, "temp_edited_video_${System.currentTimeMillis()}.mp4")
                val finalTempFile = tempOutputFile

                val transformer = Transformer.Builder(context)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            try {
                                if (finalTempFile.exists()) {
                                    FileInputStream(finalTempFile).use { input ->
                                        FileOutputStream(outputPfd.fileDescriptor).use { output ->
                                            input.copyTo(output)
                                        }
                                    }
                                    finalTempFile.delete()
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Failed to copy output file: ${e.message}")
                            }
                            onProgress(1.0f)
                            if (continuation.isActive) continuation.resume(Pair(true, null))
                        }

                        override fun onError(
                            composition: Composition,
                            exportResult: ExportResult,
                            exportException: ExportException
                        ) {
                            Log.e(TAG, "Export error: ${exportException.message}", exportException)
                            try { finalTempFile.delete() } catch (_: Exception) {}
                            if (continuation.isActive) continuation.resume(Pair(false, exportException.message ?: "Export failed"))
                        }
                    })
                    .build()

                transformer.start(composition, tempOutputFile.absolutePath)

                continuation.invokeOnCancellation {
                    try { transformer.cancel() } catch (_: Exception) {}
                    try { finalTempFile.delete() } catch (_: Exception) {}
                }

            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize video processing: ${e.message}", e)
                try { tempOutputFile?.delete() } catch (_: Exception) {}
                if (continuation.isActive) continuation.resume(Pair(false, e.message ?: "Failed to process video"))
            }
        }
    }

    private fun computeKeptRanges(cutRanges: List<VideoCutRange>): List<VideoCutRange> {
        val sortedCuts = cutRanges.sortedBy { it.startMs }
        val kept = mutableListOf<VideoCutRange>()
        var lastEnd = 0L

        for (cut in sortedCuts) {
            if (cut.startMs > lastEnd) {
                kept.add(VideoCutRange(lastEnd, cut.startMs))
            }
            lastEnd = maxOf(lastEnd, cut.endMs)
        }
        kept.add(VideoCutRange(lastEnd, Long.MAX_VALUE))
        return kept
    }
}
