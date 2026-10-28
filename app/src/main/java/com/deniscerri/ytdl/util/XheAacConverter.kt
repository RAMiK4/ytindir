package com.deniscerri.ytdl.util

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.deniscerri.ytdl.core.RuntimeManager
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale

/**
 * YTindir: Instagram's (and Facebook's) best audio is xHE-AAC, which some players and apps can't
 * play and the bundled ffmpeg can't decode. Android itself decodes it (since Android 9), so the
 * audio is decoded here, normalised to -16 LKFS the way Instagram plays it, encoded to AAC-LC
 * (192 kbps for stereo) and put back next to the untouched video with ffmpeg, which keeps the
 * file's metadata and cover. When anything fails, the original file stays as it was.
 */
object XheAacConverter {

    private const val USAC_OBJECT_TYPE = 42
    // AAC-LC encoder delay (priming) of Android's encoder: 2048 samples, cut through an edit list
    private const val ENCODER_DELAY_SAMPLES = 2048
    private const val TIMEOUT_US = 10_000L
    private const val MAX_RUNTIME_MS = 15 * 60 * 1000L
    private val supportedExtensions = setOf("mp4", "m4v", "mov", "mkv")

    /** What happened to a file, for the download log; null when it has no xHE-AAC audio. */
    class Result(val converted: Boolean, val note: String)

    private fun audioObjectType(csd: ByteBuffer): Int {
        val b = csd.duplicate()
        b.rewind()
        if (b.remaining() < 2) return -1
        val b0 = b.get().toInt() and 0xFF
        val b1 = b.get().toInt() and 0xFF
        val type = b0 shr 3
        // 31 = escape: the real type is 32 + the next 6 bits
        return if (type == 31) 32 + (((b0 and 0x07) shl 3) or (b1 shr 5)) else type
    }

    private fun isXheAac(format: MediaFormat): Boolean {
        if (format.getString(MediaFormat.KEY_MIME) != MediaFormat.MIMETYPE_AUDIO_AAC) return false
        if (format.containsKey(MediaFormat.KEY_AAC_PROFILE) &&
            runCatching { format.getInteger(MediaFormat.KEY_AAC_PROFILE) }.getOrNull() == USAC_OBJECT_TYPE) return true
        val csd = format.getByteBuffer("csd-0") ?: return false
        return audioObjectType(csd) == USAC_OBJECT_TYPE
    }

    private fun findXheTrack(file: File): Pair<Int, MediaFormat>? = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount)
                .map { it to extractor.getTrackFormat(it) }
                .firstOrNull { isXheAac(it.second) }
        } finally {
            extractor.release()
        }
    }.getOrNull()

    private fun durationUs(file: File): Long = runCatching {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).maxOfOrNull {
                val f = extractor.getTrackFormat(it)
                if (f.containsKey(MediaFormat.KEY_DURATION)) f.getLong(MediaFormat.KEY_DURATION) else 0L
            } ?: 0L
        } finally {
            extractor.release()
        }
    }.getOrDefault(0L)

    /**
     * Puts [audioSource]'s xHE-AAC audio (Instagram's app audio, downloaded on its own), converted to
     * AAC-LC, in place of [video]'s audio. Null when [audioSource] has no xHE-AAC or doesn't match.
     */
    fun replaceAudio(video: File, audioSource: File): Result? {
        if (!video.isFile || video.extension.lowercase() !in supportedExtensions) return null
        val (trackIndex, trackFormat) = findXheTrack(audioSource) ?: return null
        // the same post: lengths within a second of each other
        val videoLength = durationUs(video)
        val audioLength = durationUs(audioSource)
        if (videoLength > 0 && audioLength > 0 && kotlin.math.abs(videoLength - audioLength) > 1_000_000L) {
            return Result(false, "Audio: Instagram app audio skipped (length doesn't match the video)")
        }
        return convert(video, audioSource, trackIndex, trackFormat, "Instagram app audio (xHE-AAC) converted to AAC-LC")
    }

    /**
     * YTindir: audio downloads (mp3) of Instagram posts made from the app's higher quality xHE-AAC
     * audio instead of the website's HE-AAC: Android decodes it (normalised the same way), ffmpeg
     * encodes the mp3 at [bitrate] and copies the original mp3's tags, lyrics and cover. Null when
     * [appAudio] has no AAC track or doesn't match [mp3].
     */
    fun replaceMp3Audio(mp3: File, appAudio: File, bitrate: String): Result? {
        if (!mp3.isFile || mp3.extension.lowercase() != "mp3") return null
        val (trackIndex, trackFormat) = findXheTrack(appAudio) ?: return null
        val mp3Length = durationUs(mp3)
        val audioLength = durationUs(appAudio)
        if (mp3Length > 0 && audioLength > 0 && kotlin.math.abs(mp3Length - audioLength) > 1_500_000L) {
            return Result(false, "Audio: Instagram app audio skipped (length doesn't match)")
        }
        if (!RuntimeManager.ffmpegLocation.isAvailable) return Result(false, "Audio: website audio kept (ffmpeg not available)")
        val wav = File(mp3.parentFile, "${mp3.nameWithoutExtension}.app.tmp.wav")
        val output = File(mp3.parentFile, "${mp3.nameWithoutExtension}.app.tmp.mp3")
        return try {
            decodeToWav(appAudio, trackIndex, trackFormat, wav)
            val command = listOf(
                RuntimeManager.ffmpegLocation.executable.absolutePath,
                "-hide_banner", "-loglevel", "error", "-y",
                "-i", wav.absolutePath,
                "-i", mp3.absolutePath,
                "-map", "0:a:0", "-map", "1:v?",
                "-map_metadata", "1",
                "-c:a", "libmp3lame", "-b:a", bitrate,
                "-c:v", "copy",
                "-id3v2_version", "3",
                output.absolutePath
            )
            RuntimeManager.executeImpl(command)
            if (!output.isFile || output.length() == 0L) error("ffmpeg wrote no file")
            if (!mp3.delete() || !output.renameTo(mp3)) error("couldn't replace the file")
            Result(true, "Audio: mp3 made from Instagram's app audio (xHE-AAC, $bitrate)")
        } catch (e: Throwable) {
            output.delete()
            Result(false, "Audio: website audio kept, app audio failed (${e.message ?: e.javaClass.simpleName})")
        } finally {
            wav.delete()
        }
    }

    /** Decodes the xHE-AAC track to a 16-bit PCM WAV file, normalised like [transcode]. */
    private fun decodeToWav(src: File, trackIndex: Int, inputFormat: MediaFormat, out: File) {
        val startTime = System.currentTimeMillis()
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        val raf = java.io.RandomAccessFile(out, "rw")
        try {
            raf.setLength(0)
            raf.write(ByteArray(44)) // header, written at the end
            extractor.setDataSource(src.absolutePath)
            extractor.selectTrack(trackIndex)
            inputFormat.setInteger("aac-target-ref-level", 64)
            inputFormat.setInteger("aac-drc-effect-type", 0)
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            val info = MediaCodec.BufferInfo()
            var sampleRate = 0
            var channels = 0
            var dataBytes = 0L
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (System.currentTimeMillis() - startTime > MAX_RUNTIME_MS) error("took too long")
                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buffer = decoder.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val format = decoder.outputFormat
                    if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                        format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                        error("unexpected PCM encoding")
                    }
                    sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                } else if (o >= 0) {
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(o)!!
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        val bytes = ByteArray(info.size).also { buffer.get(it) }
                        raf.write(bytes)
                        dataBytes += bytes.size
                    }
                    decoder.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                }
            }
            if (dataBytes == 0L || sampleRate == 0 || channels == 0) error("the decoder gave no audio")

            // RIFF/WAVE header for 16-bit PCM
            val header = ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt((36 + dataBytes).toInt()); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(channels.toShort())
                putInt(sampleRate); putInt(sampleRate * channels * 2); putShort((channels * 2).toShort()); putShort(16)
                put("data".toByteArray()); putInt(dataBytes.toInt())
            }
            raf.seek(0)
            raf.write(header.array())
        } finally {
            runCatching { raf.close() }
            runCatching { extractor.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
        }
    }

    /** Converts [file]'s xHE-AAC audio to AAC-LC in place. Returns null when there's nothing to do. */
    fun convertIfNeeded(file: File): Result? {
        if (!file.isFile || file.extension.lowercase() !in supportedExtensions) return null
        val (trackIndex, trackFormat) = findXheTrack(file) ?: return null
        return convert(file, file, trackIndex, trackFormat, "xHE-AAC converted to AAC-LC")
    }

    private fun convert(file: File, audioSource: File, trackIndex: Int, trackFormat: MediaFormat, done: String): Result {
        if (!RuntimeManager.ffmpegLocation.isAvailable) {
            return Result(false, "Audio: xHE-AAC kept (ffmpeg not available)")
        }

        val m4a = File(file.parentFile, "${file.nameWithoutExtension}.aac-lc.tmp.m4a")
        val output = File(file.parentFile, "${file.nameWithoutExtension}.aac-lc.tmp.${file.extension}")
        return try {
            val sampleRate = transcode(audioSource, trackIndex, trackFormat, m4a)
            remux(file, m4a, sampleRate, output)
            if (!output.isFile || output.length() == 0L) error("ffmpeg wrote no file")
            if (!file.delete() || !output.renameTo(file)) error("couldn't replace the file")
            Result(true, "Audio: $done")
        } catch (e: Throwable) {
            output.delete()
            Result(false, "Audio: xHE-AAC kept, conversion failed (${e.message ?: e.javaClass.simpleName})")
        } finally {
            m4a.delete()
        }
    }

    private fun bitrateFor(channels: Int) = when {
        channels <= 1 -> 128_000
        channels == 2 -> 192_000
        else -> 64_000 * channels
    }

    private fun createEncoder(sampleRate: Int, channels: Int): MediaCodec {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrateFor(channels))
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 256 * 1024)
        }
        // Android's own encoder first: its delay is the one cut above
        val encoder = runCatching { MediaCodec.createByCodecName("c2.android.aac.encoder") }.getOrNull()
            ?: MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        try {
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
        } catch (e: Throwable) {
            encoder.release()
            throw e
        }
        return encoder
    }

    /** Decodes the xHE-AAC track and encodes it to AAC-LC in [out]; returns the sample rate. */
    private fun transcode(src: File, trackIndex: Int, inputFormat: MediaFormat, out: File): Int {
        val startTime = System.currentTimeMillis()
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        try {
            extractor.setDataSource(src.absolutePath)
            extractor.selectTrack(trackIndex)

            // loudness normalised to -16 LKFS (64 = -16 dB), no extra compression; the decoder's
            // limiter still keeps it from clipping
            inputFormat.setInteger("aac-target-ref-level", 64)
            inputFormat.setInteger("aac-drc-effect-type", 0)
            decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            decoder.configure(inputFormat, null, null, 0)
            decoder.start()

            muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            var muxerTrack = -1

            val info = MediaCodec.BufferInfo()
            var sampleRate = 0
            var channels = 0
            var samplesFed = 0L
            var inputDone = false
            var decoderDone = false
            var encoderEosQueued = false
            var encoderDone = false
            var pending: ByteArray? = null
            var pendingOffset = 0

            while (!encoderDone) {
                if (System.currentTimeMillis() - startTime > MAX_RUNTIME_MS) error("took too long")

                if (!inputDone) {
                    val i = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buffer = decoder.getInputBuffer(i)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(i, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            decoder.queueInputBuffer(i, 0, size, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }

                // one decoded chunk at a time, taken once the encoder has used the last one
                if (!decoderDone && pending == null) {
                    val o = decoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val format = decoder.outputFormat
                        if (format.containsKey(MediaFormat.KEY_PCM_ENCODING) &&
                            format.getInteger(MediaFormat.KEY_PCM_ENCODING) != AudioFormat.ENCODING_PCM_16BIT) {
                            error("unexpected PCM encoding")
                        }
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        if (encoder == null) encoder = createEncoder(sampleRate, channels)
                    } else if (o >= 0) {
                        if (info.size > 0) {
                            val buffer = decoder.getOutputBuffer(o)!!
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            pending = ByteArray(info.size).also { buffer.get(it) }
                            pendingOffset = 0
                        }
                        decoder.releaseOutputBuffer(o, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) decoderDone = true
                    }
                }

                val enc = encoder
                if (enc == null) {
                    if (decoderDone) error("the decoder gave no audio")
                    continue
                }

                val chunk = pending
                if (chunk != null || (decoderDone && !encoderEosQueued)) {
                    val i = enc.dequeueInputBuffer(TIMEOUT_US)
                    if (i >= 0) {
                        val buffer = enc.getInputBuffer(i)!!
                        buffer.clear()
                        val pts = samplesFed * 1_000_000L / sampleRate
                        if (chunk == null) {
                            enc.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            encoderEosQueued = true
                        } else {
                            val frameBytes = 2 * channels
                            var n = minOf(buffer.remaining(), chunk.size - pendingOffset)
                            n -= n % frameBytes
                            if (n <= 0) error("encoder buffer too small")
                            buffer.put(chunk, pendingOffset, n)
                            samplesFed += n / frameBytes
                            pendingOffset += n
                            if (pendingOffset >= chunk.size) pending = null
                            enc.queueInputBuffer(i, 0, n, pts, 0)
                        }
                    }
                }

                val o = enc.dequeueOutputBuffer(info, if (encoderEosQueued) TIMEOUT_US else 0)
                if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerTrack = muxer.addTrack(enc.outputFormat)
                    muxer.start()
                    muxerStarted = true
                } else if (o >= 0) {
                    val buffer = enc.getOutputBuffer(o)!!
                    if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxerStarted) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(muxerTrack, buffer, info)
                    }
                    enc.releaseOutputBuffer(o, false)
                    if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) encoderDone = true
                }
            }

            if (!muxerStarted || samplesFed == 0L) error("no audio was encoded")
            return sampleRate
        } finally {
            runCatching { extractor.release() }
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder?.stop() }
            runCatching { encoder?.release() }
            if (muxerStarted) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
        }
    }

    /** The original file with its audio replaced by [m4a]: video, metadata and cover copied as they are. */
    private fun remux(original: File, m4a: File, sampleRate: Int, output: File) {
        val delay = String.format(Locale.US, "%.6f", ENCODER_DELAY_SAMPLES.toDouble() / sampleRate)
        val command = mutableListOf(
            RuntimeManager.ffmpegLocation.executable.absolutePath,
            "-hide_banner", "-loglevel", "error", "-y",
            "-i", original.absolutePath,
            // the encoder's priming samples start before 0: the file's edit list skips them
            "-itsoffset", "-$delay",
            "-i", m4a.absolutePath,
            "-map", "0", "-map", "-0:a", "-map", "1:a:0",
            "-c", "copy",
            "-map_metadata", "0"
        )
        if (original.extension.lowercase() != "mkv") command.addAll(listOf("-movflags", "+faststart"))
        command.add(output.absolutePath)
        RuntimeManager.executeImpl(command)
    }
}
