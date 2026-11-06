package com.deniscerri.ytdl.util

import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format

/**
 * YTindir: the choices of the quick download card, the card that opens before the formats were read.
 *
 * Videos keep the generic "<height>p_ytdlnisgeneric" ids (the resolution cap the rest of the app
 * already knows); their vcodec field says which codec the download looks for:
 * 720p and 1080p in H.264, which plays everywhere, 1440p (2K) and 2160p (4K) in the better of
 * AV1 and VP9 (YouTube has no H.264 above 1080p). Audio choices extract the stream as Opus, M4A
 * or MP3, and the thumbnail is a download of its own.
 */
object QuickFormats {
    const val CODEC_H264 = "H.264"
    const val CODEC_AV1_VP9 = "AV1/VP9"

    const val AUDIO_OPUS = "quick_opus"
    const val AUDIO_M4A = "quick_m4a"
    const val AUDIO_MP3 = "quick_mp3"
    const val THUMBNAIL = "quick_thumbnail"

    /** sub folder of the video folder the thumbnails go to */
    const val THUMBNAIL_FOLDER = "Thumbnails"

    private val VIDEO_HEIGHTS = listOf(2160, 1440, 1080, 720, 480)
    private val GENERIC_HEIGHT = Regex("^(\\d+)p_ytdlnisgeneric$")
    private val NOTE_HEIGHT = Regex("(\\d{3,4})p")

    fun videoFormats(container: String): List<Format> = VIDEO_HEIGHTS.map { videoFormat(it, container) }

    private fun videoFormat(height: Int, container: String) = Format(
        format_id = "${height}p_ytdlnisgeneric",
        container = container,
        vcodec = if (height > 1080) CODEC_AV1_VP9 else CODEC_H264,
        acodec = "",
        format_note = "${height}p"
    )

    fun audioFormats(): List<Format> = listOf(
        Format(format_id = AUDIO_OPUS, container = "opus", acodec = "opus", format_note = "Opus"),
        Format(format_id = AUDIO_M4A, container = "m4a", acodec = "aac", format_note = "M4A"),
        Format(format_id = AUDIO_MP3, container = "mp3", acodec = "mp3", format_note = "MP3")
    )

    private val PLACEHOLDER_IDS = listOf("best", "worst", "ba", "wa", "bv", "")

    /** true while the item's formats were not read (a quick download): only placeholders */
    fun hasNoRealFormats(item: DownloadItem): Boolean = item.allFormats.all {
        it.format_id.contains("ytdlnisgeneric") || it.format_id in PLACEHOLDER_IDS || isQuickAudio(it)
    }

    fun isQuickAudio(format: Format?) = format?.format_id in listOf(AUDIO_OPUS, AUDIO_M4A, AUDIO_MP3)

    fun isQuickVideo(format: Format?): Boolean =
        format != null && GENERIC_HEIGHT.matches(format.format_id) &&
            (format.vcodec == CODEC_H264 || format.vcodec == CODEC_AV1_VP9)

    /** The quick choice for a generic resolution (e.g. the default ~1080p), or null. */
    fun toQuickVideo(format: Format, container: String): Format? {
        if (isQuickVideo(format)) return format
        val height = GENERIC_HEIGHT.find(format.format_id)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return if (height in VIDEO_HEIGHTS) videoFormat(height, container) else null
    }

    /** yt-dlp's name of the codec a quick video choice looks for, null for other formats */
    fun ytdlpVideoCodec(format: Format): String? {
        if (!isQuickVideo(format)) return null
        return if (format.vcodec == CODEC_H264) "h264" else "av01"
    }

    /** A -f filter that keeps only the quick choice's codec */
    fun videoCodecFilter(format: Format): String? {
        if (!isQuickVideo(format)) return null
        return if (format.vcodec == CODEC_H264) "[vcodec~='^(avc|h264)']" else "[vcodec~='^(av01|vp0?9)']"
    }

    /** yt-dlp's name of the audio codec a quick audio choice looks for ("" keeps the setting) */
    fun ytdlpAudioCodec(format: Format): String = when (format.format_id) {
        AUDIO_OPUS -> "opus"
        AUDIO_M4A -> "aac"
        else -> ""
    }

    fun heightOf(format: Format): Int? {
        GENERIC_HEIGHT.find(format.format_id)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
        if (format.vcodec.isBlank() || format.vcodec == "none") return null
        val height = format.height
        val width = format.width
        if (height != null && height > 0) {
            // portrait videos: the short side is the quality
            return if (width != null && width in 1 until height) width else height
        }
        return NOTE_HEIGHT.find(format.format_note)?.groupValues?.get(1)?.toIntOrNull()
    }

    /** "4K", "2K", "HD"... for a video height, null below 720p */
    fun badge(height: Int?): String? = when {
        height == null -> null
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 720 -> "HD"
        else -> null
    }

    /** The yt-dlp options of a thumbnail-only download, saved to [THUMBNAIL_FOLDER] in the video folder */
    fun thumbnailCommand(thumbnailFormat: String): String {
        val convert = thumbnailFormat.ifBlank { "jpg" }
        return "--skip-download --ignore-no-formats-error --write-thumbnail --convert-thumbnails $convert " +
            "-o \"thumbnail:$THUMBNAIL_FOLDER/%(title).150B [%(id)s].%(ext)s\""
    }
}
