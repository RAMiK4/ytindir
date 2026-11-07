package com.deniscerri.ytdl.util

import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.enums.DownloadType
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
/**
 * A choice from the quick list for the card's audio or video tab: the format (a quick placeholder or one
 * of the link's real formats), the audio streams of a video choice and the file type of an audio choice.
 */
data class QuickPick(
    val type: DownloadType,
    val format: Format,
    val audioFormats: List<Format>? = null,
    val audioContainer: String? = null
)

object QuickFormats {
    const val CODEC_H264 = "H.264"
    const val CODEC_AV1_VP9 = "AV1/VP9"

    const val AUDIO_OPUS = "quick_opus"
    const val AUDIO_M4A = "quick_m4a"
    const val AUDIO_MP3 = "quick_mp3"
    const val THUMBNAIL = "quick_thumbnail"

    /** sub folder of the video folder the thumbnails go to */
    const val THUMBNAIL_FOLDER = "Thumbnails"

    private val VIDEO_HEIGHTS = listOf(2160, 1440, 1080, 720)
    private val GENERIC_HEIGHT = Regex("^(\\d+)p_ytdlnisgeneric$")
    private val NOTE_HEIGHT = Regex("(\\d{3,4})p")

    fun videoFormats(container: String): List<Format> = VIDEO_HEIGHTS.map { videoFormat(it, container) }

    /** [codec]: [CODEC_H264] or [CODEC_AV1_VP9], null for the usual one of the height */
    fun videoFormat(height: Int, container: String, codec: String? = null) = Format(
        format_id = "${height}p_ytdlnisgeneric",
        container = container,
        vcodec = codec ?: if (height > 1080) CODEC_AV1_VP9 else CODEC_H264,
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

    /** "4K", "2K", "FHD", "HD"... for a video height, null below 720p */
    fun badge(height: Int?): String? = when {
        height == null -> null
        height >= 4320 -> "8K"
        height >= 2160 -> "4K"
        height >= 1440 -> "2K"
        height >= 1080 -> "FHD"
        height >= 720 -> "HD"
        else -> null
    }

    /**
     * A thumbnail-only download of [item]'s link: a command download (yt-dlp options in the format note),
     * saved to [THUMBNAIL_FOLDER] in [videoPath].
     */
    fun thumbnailItem(item: DownloadItem, videoPath: String, thumbnailFormat: String): DownloadItem =
        com.google.gson.Gson().let { gson -> gson.fromJson(gson.toJson(item), DownloadItem::class.java) }.apply {
            id = 0
            type = DownloadType.command
            format = Format(format_id = THUMBNAIL, format_note = thumbnailCommand(thumbnailFormat))
            container = ""
            downloadSections = ""
            extraCommands = ""
            customFileNameTemplate = ""
            SaveThumb = false
            allFormats = mutableListOf()
            downloadPath = videoPath
            status = com.deniscerri.ytdl.database.repository.DownloadRepository.Status.Queued.toString()
            downloadStartTime = 0
            logID = null
        }

    /** The yt-dlp options of a thumbnail-only download, saved to [THUMBNAIL_FOLDER] in the video folder */
    fun thumbnailCommand(thumbnailFormat: String): String {
        val convert = thumbnailFormat.ifBlank { "jpg" }
        return "--skip-download --ignore-no-formats-error --write-thumbnail --convert-thumbnails $convert " +
            "-o \"thumbnail:$THUMBNAIL_FOLDER/%(title).150B [%(id)s].%(ext)s\""
    }

    // ---- the link's real formats, once they are read ----

    private val REAL_HEIGHTS = listOf(4320, 2160, 1440, 1080, 720)

    private fun Format.isVideo() = vcodec.isNotBlank() && vcodec != "none"
    private fun Format.isAudio() = !isVideo() && acodec.isNotBlank() && acodec != "none"
    private fun Format.fpsValue() = fps?.toFloatOrNull() ?: 0f
    private fun Format.tbrValue() = tbr?.toFloatOrNull() ?: 0f
    private fun Format.isH264() = vcodec.startsWith("avc") || vcodec.startsWith("h264")
    private fun Format.isAv1() = vcodec.startsWith("av01")
    private fun Format.isVp9() = vcodec.startsWith("vp9") || vcodec.startsWith("vp09")
    // HDR / 10 bit streams look washed out on most screens: VP9 profile 2, 10 bit AV1
    private fun Format.isHdr() = vcodec.startsWith("vp09.02") || (isAv1() && vcodec.split(".").getOrNull(3) == "10")

    /**
     * One real format per quality, like the quick list: 1080p and below in H.264 (another codec only
     * when the link has no H.264 there), 2K and above in AV1, else VP9; SDR, highest frame rate first.
     */
    fun realVideoChoices(formats: List<Format>): List<Format> {
        val videos = formats.filter { it.isVideo() && !it.format_id.contains("ytdlnisgeneric") }
        return REAL_HEIGHTS.mapNotNull { height ->
            val candidates = videos.filter { heightOf(it) == height }
            if (candidates.isEmpty()) return@mapNotNull null
            val codecRank: (Format) -> Int = if (height <= 1080) {
                { f -> if (f.isH264()) 0 else if (f.isAv1()) 1 else if (f.isVp9()) 2 else 3 }
            } else {
                { f -> if (f.isAv1()) 0 else if (f.isVp9()) 1 else if (f.isH264()) 2 else 3 }
            }
            candidates.sortedWith(
                compareBy<Format> { codecRank(it) }
                    .thenBy { if (it.isHdr()) 1 else 0 }
                    .thenByDescending { it.fpsValue() }
                    .thenBy { if (it.filesize > 0) 0 else 1 }
                    .thenByDescending { it.tbrValue() }
            ).first()
        }
    }

    /** the link's best Opus and AAC (M4A) audio streams, in that order where they exist */
    fun realAudioChoices(formats: List<Format>, sort: (List<Format>) -> List<Format>): List<Pair<String, Format>> {
        val audio = formats.filter { it.isAudio() && !it.format_id.endsWith("-drc") }
        val opus = sort(audio.filter { it.acodec.startsWith("opus") }).firstOrNull()
        val aac = sort(audio.filter { it.acodec.startsWith("mp4a") || it.acodec.startsWith("aac") }).firstOrNull()
        return listOfNotNull(opus?.let { "opus" to it }, aac?.let { "m4a" to it })
    }

    fun codecLabel(format: Format): String = when {
        format.vcodec == CODEC_H264 || format.isH264() -> "H.264"
        format.vcodec == CODEC_AV1_VP9 -> "AV1 / VP9"
        format.isAv1() -> "AV1"
        format.isVp9() -> "VP9"
        format.vcodec.startsWith("hev") || format.vcodec.startsWith("hvc") -> "HEVC"
        else -> format.vcodec.substringBefore(".").uppercase()
    }

    fun fpsLabel(format: Format): String? = format.fpsValue().takeIf { it > 31 }?.let { "${it.toInt()} fps" }

    fun kbpsLabel(format: Format): String? = format.tbrValue().takeIf { it > 0 }?.let { "${it.toInt()} kbps" }

    /** What a quality is good for, next to FHD, the recommended one */
    fun hint(height: Int): Int = when {
        height >= 2160 -> R.string.quick_hint_best_quality
        height >= 1440 -> R.string.quick_hint_better_quality
        height >= 1080 -> R.string.quick_hint_recommended
        else -> R.string.quick_hint_smaller_file
    }

    fun fps(format: Format): Int? = format.fpsValue().toInt().takeIf { it > 0 }

    fun kbps(format: Format): Int? = format.tbrValue().takeIf { it > 0 }?.let { Math.round(it) }

    /** YouTube's AAC audio (itag 140) */
    const val USUAL_AAC_KBPS = 128
    const val MP3_KBPS = 192

    /**
     * The size of a [MP3_KBPS] MP3 of the link: its length from the AAC stream (size and bitrate),
     * else from the card's duration ("1:02:03"), null if neither is known.
     */
    fun mp3SizeEstimate(aac: Format?, duration: String): Long? {
        val seconds = aac?.let { f ->
            val kbps = f.tbrValue()
            if (f.filesize > 0 && kbps > 0) f.filesize * 8 / (kbps * 1000) else null
        }?.toDouble() ?: duration.split(":").mapNotNull { it.trim().toIntOrNull() }
            .takeIf { it.isNotEmpty() && duration.isNotBlank() }
            ?.fold(0) { total, part -> total * 60 + part }?.toDouble()
        return seconds?.takeIf { it > 0 }?.let { (it * MP3_KBPS * 1000 / 8).toLong() }
    }
}
