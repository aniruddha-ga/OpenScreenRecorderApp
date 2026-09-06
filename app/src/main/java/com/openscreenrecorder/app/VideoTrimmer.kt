package com.openscreenrecorder.app

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

object VideoTrimmer {

    private const val TAG = "VideoTrimmer"
    private const val DEFAULT_BUFFER_SIZE = 2 * 1024 * 1024 // 2MB buffer

    /**
     * Fast, lossless MP4 video trimming using MediaExtractor and MediaMuxer.
     *
     * @param context Application context
     * @param sourceUri Uri of the source video
     * @param outputPfd ParcelFileDescriptor for the output target file
     * @param startMs Start time in milliseconds
     * @param endMs End time in milliseconds
     * @param onProgress Callback invoked with progress float (0.0 to 1.0)
     * @return Result containing success state and error message if any
     */
    suspend fun trimVideo(
        context: Context,
        sourceUri: Uri,
        outputPfd: ParcelFileDescriptor,
        startMs: Long,
        endMs: Long,
        onProgress: (Float) -> Unit
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        var extractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            val startUs = (startMs * 1000L).coerceAtLeast(0L)
            val endUs = (endMs * 1000L)
            if (endUs <= startUs) {
                return@withContext Pair(false, "End time must be greater than start time.")
            }

            extractor = MediaExtractor()

            // Set data source for MediaExtractor
            val pfdIn = context.contentResolver.openFileDescriptor(sourceUri, "r")
                ?: return@withContext Pair(false, "Failed to open source file descriptor.")
            pfdIn.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            }

            val trackCount = extractor.trackCount
            if (trackCount == 0) {
                return@withContext Pair(false, "Source video contains no playable media tracks.")
            }

            muxer = MediaMuxer(outputPfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val indexMap = HashMap<Int, Int>()
            var videoTrackIndex = -1

            for (i in 0 until trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue

                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    extractor.selectTrack(i)
                    val muxerTrackIndex = muxer.addTrack(format)
                    indexMap[i] = muxerTrackIndex

                    if (mime.startsWith("video/") && videoTrackIndex < 0) {
                        videoTrackIndex = i
                    }
                }
            }

            if (indexMap.isEmpty()) {
                return@withContext Pair(false, "No valid audio or video tracks found.")
            }

            muxer.start()

            // Seek to the keyframe before startUs
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

            val buffer = ByteBuffer.allocateDirect(DEFAULT_BUFFER_SIZE)
            val bufferInfo = MediaCodec.BufferInfo()

            // Determine actual start timestamp after seek
            val firstPtsUs = extractor.sampleTime.coerceAtLeast(0L)
            val ptsOffset = firstPtsUs
            val totalDurationUs = (endUs - startUs).coerceAtLeast(1L)

            while (true) {
                val sampleTrackIndex = extractor.sampleTrackIndex
                if (sampleTrackIndex < 0) {
                    break // End of stream reached
                }

                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) {
                    break // Exceeded requested end timestamp
                }

                val muxerTrackIndex = indexMap[sampleTrackIndex]
                if (muxerTrackIndex != null) {
                    buffer.clear()
                    val sampleSize = extractor.readSampleData(buffer, 0)

                    if (sampleSize > 0) {
                        val flags = extractor.sampleFlags
                        var muxerFlags = 0
                        if ((flags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            muxerFlags = muxerFlags or MediaCodec.BUFFER_FLAG_KEY_FRAME
                        }

                        val adjustedPtsUs = (sampleTimeUs - ptsOffset).coerceAtLeast(0L)

                        bufferInfo.set(0, sampleSize, adjustedPtsUs, muxerFlags)
                        muxer.writeSampleData(muxerTrackIndex, buffer, bufferInfo)

                        // Report progress if video track
                        if (sampleTrackIndex == videoTrackIndex) {
                            val currentProgress = ((sampleTimeUs - startUs).toFloat() / totalDurationUs.toFloat()).coerceIn(0f, 1f)
                            onProgress(currentProgress)
                        }
                    }
                }

                if (!extractor.advance()) {
                    break
                }
            }

            onProgress(1.0f)
            Pair(true, null)

        } catch (e: Exception) {
            Log.e(TAG, "Trimming failed: ${e.message}", e)
            Pair(false, e.message ?: "An unexpected error occurred during video trimming.")
        } finally {
            try {
                muxer?.stop()
                muxer?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing muxer: ${e.message}")
            }
            try {
                extractor?.release()
            } catch (e: Exception) {
                Log.e(TAG, "Error releasing extractor: ${e.message}")
            }
        }
    }
}
