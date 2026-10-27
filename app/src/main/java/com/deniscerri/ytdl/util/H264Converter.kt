package com.deniscerri.ytdl.util

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import com.deniscerri.ytdl.core.RuntimeManager
import kotlinx.coroutines.CompletableDeferred
import java.io.File

/**
 * YTindir: VP9, AV1 and HEVC videos (Instagram, X, TikTok, Facebook, VK) converted to H.264 with
 * the phone's hardware encoder (Media3 Transformer), many times faster than ffmpeg's x264 on the
 * CPU. Only the video is encoded, at a bitrate above the source's so the quality stays; ffmpeg
 * then puts it back next to the file's own audio, subtitles, metadata and cover, which are copied
 * as they are. When anything fails, the original file stays as it was.
 */
object H264Converter {

    private val convertibleMimes = setOf(
        MediaFormat.MIMETYPE_VIDEO_VP9,
        MediaFormat.MIMETYPE_VIDEO_AV1,
        MediaFormat.MIMETYPE_VIDEO_HEVC,
        MediaFormat.MIMETYPE_VIDEO_VP8,
    )
    private val supportedExtensions = setOf("mp4", "m4v", "mov")

    /** What happened to a file, for the download log; null when it has nothing to convert. */
    class Result(val converted: Boolean, val note: String)

    private class VideoInfo(val mime: String, val width: Int, val height: Int, val fps: Float, val bitrate: Int)

    private fun videoInfo(file: File): VideoInfo? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            val format = (0 until extractor.trackCount)
                .map { extractor.getTrackFormat(it) }
                .firstOrNull { f ->
                    f.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") &&
                        // a cover picture is not the video
                        !f.getString(MediaFormat.KEY_MIME).orEmpty().contains("jpeg") &&
                        !f.getString(MediaFormat.KEY_MIME).orEmpty().contains("png")
                } ?: return@runCatching null
            fun int(key: String) = if (format.containsKey(key)) runCatching { format.getInteger(key) }.getOrDefault(0) else 0
            val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L
            val fps = if (format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                runCatching { format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                    .getOrElse { runCatching { format.getFloat(MediaFormat.KEY_FRAME_RATE) }.getOrDefault(30f) }
            } else 30f
            // most files don't say their bitrate: the whole file's, which is a little more than the video's
            val bitrate = int(MediaFormat.KEY_BIT_RATE).takeIf { it > 0 }
                ?: if (durationUs > 0) (file.length() * 8 * 1_000_000 / durationUs).toInt() else 0
            VideoInfo(format.getString(MediaFormat.KEY_MIME)!!, int(MediaFormat.KEY_WIDTH), int(MediaFormat.KEY_HEIGHT), fps, bitrate)
        } finally {
            extractor.release()
        }
    }.getOrNull()

    /**
     * H.264 needs a little more bits than VP9 / AV1 / HEVC for the same picture: 1.5× the source's,
     * never more than ~0.1 bit per pixel (a sharp picture already) and never under 1.5 Mbps, so a
     * light 1.2 Mbps source doesn't become a 6 Mbps file. Unknown source bitrate: ~0.06 bit per pixel.
     */
    private fun targetBitrate(info: VideoInfo): Int {
        val pixelsPerSecond = info.width.toLong() * info.height * info.fps.coerceIn(24f, 60f)
        val ceiling = (pixelsPerSecond * 0.10).toLong().coerceAtLeast(2_000_000L)
        val wanted = if (info.bitrate > 0) info.bitrate.toLong() * 3 / 2 else (pixelsPerSecond * 0.06).toLong()
        return wanted.coerceIn(1_500_000L, maxOf(ceiling, 1_500_000L)).coerceAtMost(60_000_000L).toInt()
    }

    fun needsConversion(file: File): Boolean {
        if (!file.isFile || file.extension.lowercase() !in supportedExtensions) return false
        return videoInfo(file)?.mime in convertibleMimes
    }

    /** Converts [file]'s video to H.264 in place; [onProgress] gets 0–100. Null when it's already H.264. */
    suspend fun convertIfNeeded(context: Context, file: File, onProgress: (Int) -> Unit): Result? {
        if (!file.isFile || file.extension.lowercase() !in supportedExtensions) return null
        val info = videoInfo(file) ?: return null
        if (info.mime !in convertibleMimes) return null
        if (!RuntimeManager.ffmpegLocation.isAvailable) return Result(false, "Video: kept as it is (ffmpeg not available)")

        val codec = info.mime.substringAfter("video/").uppercase().replace("X-VND.ON2.", "")
        val video = File(file.parentFile, "${file.nameWithoutExtension}.h264.tmp.mp4")
        val output = File(file.parentFile, "${file.nameWithoutExtension}.h264.tmp.mp4.out.mp4")
        val started = System.currentTimeMillis()
        return try {
            val bitrate = targetBitrate(info)
            encode(context, file, video, bitrate, onProgress)
            remux(file, video, output)
            if (!output.isFile || output.length() == 0L) error("ffmpeg wrote no file")
            if (!file.delete() || !output.renameTo(file)) error("couldn't replace the file")
            val seconds = (System.currentTimeMillis() - started) / 1000
            Result(true, "Video: $codec converted to H.264 (${bitrate / 1000} kbps, ${seconds}s)")
        } catch (e: Throwable) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result(false, "Video: $codec kept, H.264 conversion failed (${e.message ?: e.javaClass.simpleName})")
        } finally {
            video.delete()
            output.delete()
        }
    }

    @OptIn(UnstableApi::class)
    private suspend fun encode(context: Context, src: File, out: File, bitrate: Int, onProgress: (Int) -> Unit) {
        out.delete()
        val thread = HandlerThread("YTindir-H264").apply { start() }
        val handler = Handler(thread.looper)
        val done = CompletableDeferred<Unit>()
        var transformer: Transformer? = null
        val progress = ProgressHolder()
        val poll = object : Runnable {
            override fun run() {
                val t = transformer ?: return
                if (t.getProgress(progress) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(progress.progress)
                if (!done.isCompleted) handler.postDelayed(this, 500)
            }
        }
        handler.post {
            runCatching {
                val encoders = DefaultEncoderFactory.Builder(context.applicationContext)
                    .setRequestedVideoEncoderSettings(VideoEncoderSettings.Builder().setBitrate(bitrate).build())
                    .setEnableFallback(true)
                    .build()
                val t = Transformer.Builder(context.applicationContext)
                    .setLooper(thread.looper)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(encoders)
                    .addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            done.complete(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            done.completeExceptionally(exportException)
                        }
                    })
                    .build()
                transformer = t
                val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(src)))
                    .setRemoveAudio(true)
                    .build()
                t.start(item, out.absolutePath)
                handler.postDelayed(poll, 500)
            }.onFailure { done.completeExceptionally(it) }
        }
        try {
            try {
                done.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                // the download was cancelled: stop the encoder too
                handler.post { transformer?.cancel() }
                throw e
            }
            onProgress(100)
        } finally {
            // a pending cancel() still runs before the thread ends
            handler.removeCallbacks(poll)
            thread.quitSafely()
        }
        if (!out.isFile || out.length() == 0L) error("the encoder wrote no file")
    }

    /** [video]'s H.264 stream in place of [original]'s video: audio, subtitles, metadata and cover copied as they are. */
    private fun remux(original: File, video: File, output: File) {
        val command = mutableListOf(
            RuntimeManager.ffmpegLocation.executable.absolutePath,
            "-hide_banner", "-loglevel", "error", "-y",
            "-i", original.absolutePath,
            "-i", video.absolutePath,
            // the new video first, then everything of the original but its video (a cover picture stays)
            "-map", "1:V:0", "-map", "0", "-map", "-0:V",
            "-c", "copy",
            "-map_metadata", "0",
            "-movflags", "+faststart",
            "-f", "mp4",
            output.absolutePath
        )
        RuntimeManager.executeImpl(command)
    }
}
