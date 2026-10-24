package com.deniscerri.ytdl.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Resources
import androidx.preference.PreferenceManager
import com.afollestad.materialdialogs.utils.MDUtil.getStringArray
import com.deniscerri.ytdl.R
import com.deniscerri.ytdl.database.models.DownloadItem
import com.deniscerri.ytdl.database.models.Format
import kotlin.math.min

class FormatUtil(private var context: Context) {
    private val sharedPreferences : SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
    private val videoFormatIDPreference : List<String> = sharedPreferences.getString("format_id", "").toString().split(",").filter { it.isNotEmpty() }
    private val audioFormatIDPreference : List<String> = sharedPreferences.getString("format_id_audio", "").toString().split(",").filter { it.isNotEmpty() }
    private val videoQualityPreference : String = sharedPreferences.getString("video_quality", "best").toString()
    private val audioLanguagePreference : String = sharedPreferences.getString("audio_language", "").toString()
    private val audioCodecPreference : String =  sharedPreferences.getString("audio_codec", "").toString()
    private var videoCodecPreference : String =  sharedPreferences.getString("video_codec", "").toString()
    private val audioContainerPreference : String = sharedPreferences.getString("audio_format", "").toString()
    private val videoContainerPreference : String = sharedPreferences.getString("video_format", "").toString()
    private val videoResolutionOrder = context.getStringArray(R.array.video_formats_values)
                                            .filter { it.contains("_") }
                                            .map{ it.split("_")[0].dropLast(1) }.toMutableList().apply {
                                                this.reverse()
                                            }

    @SuppressLint("RestrictedApi")
    fun getAudioFormatImportance(forVideoDownload: Boolean) : List<String> {
        val preferredFormatSizeAudio = sharedPreferences.getString("preferred_format_size_audio", "")
        val preferredFormatSizeGeneral = sharedPreferences.getString("preferred_format_size", "")
        val preferredFormatSize = if (!preferredFormatSizeAudio.isNullOrEmpty()) preferredFormatSizeAudio else preferredFormatSizeGeneral

        if (sharedPreferences.getBoolean("use_format_sorting", false)) {
            val itemValues = context.getStringArray(R.array.format_importance_audio_values).toMutableList()
            val orderPreferences = sharedPreferences.getString("format_importance_audio", itemValues.joinToString(","))!!.split(",").toMutableList()

            if (preferredFormatSize == "smallest") {
                orderPreferences.remove("file_size")
                orderPreferences.add(0,"smallsize")
            }

            if(!forVideoDownload) {
                val preferContainerOverCodec = sharedPreferences.getBoolean("prefer_container_over_codec_audio", false)
                if(preferContainerOverCodec) {
                    orderPreferences.remove("codec")
                }
            }

            return orderPreferences
        }else {
            val formatImportance = mutableListOf("id", "language", "codec", "container")
            if (preferredFormatSize == "smallest") {
                formatImportance.add("smallsize")
            }else if (preferredFormatSize == "largest") {
                formatImportance.add("file_size")
            }

            if(!forVideoDownload) {
                val preferContainerOverCodec = sharedPreferences.getBoolean("prefer_container_over_codec_audio", false)
                if(preferContainerOverCodec) {
                    formatImportance.remove("codec")
                }
            }

            val preferDRC = sharedPreferences.getBoolean("prefer_drc_audio", false)
            if (preferDRC) {
                formatImportance.add(0,"prefer_drc")
            }

            return formatImportance
        }
    }

    @SuppressLint("RestrictedApi")
    fun getVideoFormatImportance(downloadItemContext: DownloadItem? = null) : List<String> {
        val preferredFormatSize = sharedPreferences.getString("preferred_format_size", "")

        if (sharedPreferences.getBoolean("use_format_sorting", false)) {
            val itemValues = context.getStringArray(R.array.format_importance_video_values).toList()
            val orderPreferences = sharedPreferences.getString("format_importance_video", itemValues.joinToString(","))!!.split(",").toMutableList()

            if (preferredFormatSize == "smallest") {
                orderPreferences.remove("file_size")
                orderPreferences.add("smallsize")
            }

            return orderPreferences
        }else {
            val formatImportance = mutableListOf("id","resolution", "codec", "container")
            if (preferredFormatSize == "smallest") {
                formatImportance.add("smallsize")
            }else if (preferredFormatSize == "largest") {
                formatImportance.add("file_size")
            }

            if (downloadItemContext?.videoPreferences?.compatibilityMode == true) {
                videoCodecPreference = "avc|h264"
            }

            return formatImportance
        }
    }


    private fun isDirectAudio(f: Format): Boolean {
        val url = f.url.orEmpty()
        if (url.contains("m3u8") || url.contains("/manifest/")) return false
        val codec = f.acodec.trim().lowercase()
        return codec.isNotEmpty() && codec != "none" && codec != "?" && codec != "null"
    }

    @SuppressLint("RestrictedApi")
    fun sortAudioFormats(formats: List<Format>, forVideoDownload: Boolean = false, url: String? = null) : List<Format> {
        val orderPreferences = getAudioFormatImportance(forVideoDownload)
        // YTindir: the audio of an mp4 video is AAC, the codec mp4 plays with everywhere
        val codecPreference = if (forVideoDownload && videoContainerPreference.equals("mp4", ignoreCase = true)) {
            "m4a|mp4a|aac"
        } else audioCodecPreference
        // YTindir: "default/original" only means something when the video has several audio languages
        val multipleLanguages = formats.mapNotNull { it.lang?.takeIf { l -> l.isNotBlank() } }.distinct().size > 1

        val comparator = Comparator<Format> { a, b ->
            // YTindir: prefer plain https streams with a known codec over HLS (m3u8) ones, which have
            // no codec or size and break cutting; otherwise the chosen codec and container were ignored
            val direct = isDirectAudio(b).compareTo(isDirectAudio(a))
            if (direct != 0) return@Comparator direct

            if ("prefer_drc" in orderPreferences) {
                val comparison = (b.format_note.contains("drc", ignoreCase = true)).compareTo(
                    a.format_note.contains("drc", ignoreCase = true)
                )

                if (comparison != 0) return@Comparator comparison
            }

            for (order in orderPreferences) {
                val comparison = when (order) {
                    "smallsize" -> {
                        (a.filesize).compareTo(b.filesize)
                    }
                    "file_size" -> {
                        b.filesize.compareTo(a.filesize)
                    }
                    "id" -> {
                        if (audioFormatIDPreference.isNotEmpty()) {
                            (audioFormatIDPreference.contains(b.format_id)).compareTo(
                                audioFormatIDPreference.contains(a.format_id)
                            )
                        } else 0
                    }
                    "language" -> {
                        val defaults = listOf("original", "default")

                        if (audioLanguagePreference.isBlank()) {
                            if (!multipleLanguages) 0
                            else (defaults.any { b.format_note.contains(it, true) }).compareTo(
                                defaults.any { a.format_note.contains(it, true) }
                            )
                        } else {
                            val res = (b.lang?.contains(audioLanguagePreference) == true).compareTo(
                                a.lang?.contains(
                                    audioLanguagePreference
                                ) == true
                            )
                            if(res == 0) {
                                (defaults.any { b.format_note.contains(it, true) }).compareTo(
                                    defaults.any { a.format_note.contains(it, true) }
                                )
                            }else {
                                res
                            }
                        }
                    }
                    "codec" -> {
                        if (codecPreference.isNotBlank()) {
                            ("^(${codecPreference}).*$".toRegex(RegexOption.IGNORE_CASE)
                                .matches(b.acodec))
                                .compareTo(
                                    "^(${codecPreference}).*$".toRegex(RegexOption.IGNORE_CASE)
                                        .matches(a.acodec)
                                )
                        } else 0
                    }

                    "container" -> {
                        if (audioContainerPreference.isNotBlank()) {
                            (audioContainerPreference == b.container).compareTo(
                                audioContainerPreference == a.container
                            )
                        } else 0
                    }
                    else -> 0
                }
                if (comparison != 0) return@Comparator comparison
            }
            return@Comparator 0
        }
        // YTindir: Instagram, X, TikTok, Facebook and VK: among otherwise equal audio, the best one
        if (SocialMediaUtil.siteOf(url) != null) {
            return formats.sortedWith(comparator.thenByDescending { it.bitrateValue() }.thenByDescending { it.filesize })
        }
        return formats.sortedWith(comparator)
    }

    /**
     * YTindir: Instagram, X, TikTok, Facebook and VK posts default to their best quality, whatever the
     * resolution cap or codec settings: TikTok's original upload, Instagram's and Facebook's best
     * dash stream, X's best hls stream, VK's best hls or dash stream. A format id set in the settings still comes first.
     */
    private fun sortSocialVideoFormats(formats: List<Format>, site: SocialMediaUtil.Site): List<Format> {
        val preferredProtocol: (Format) -> Boolean = when (site) {
            SocialMediaUtil.Site.INSTAGRAM -> { f -> f.format_id.startsWith("dash-") }
            // X: the hls stream of the same resolution; its http files fail to download and
            // report much larger sizes than they are
            SocialMediaUtil.Site.X -> { f -> f.format_id.startsWith("hls-") }
            // "download" is TikTok's watermarked file
            SocialMediaUtil.Site.TIKTOK -> { f -> !f.format_id.startsWith("download") }
            // Facebook: its dash streams, over the "sd" and "hd" files
            SocialMediaUtil.Site.FACEBOOK -> { f -> f.format_note.contains("DASH", ignoreCase = true) }
            // VK: its hls and dash streams, over the "url…" mp4 files
            SocialMediaUtil.Site.VK -> { f -> f.format_id.startsWith("hls") || f.format_id.startsWith("dash") }
        }
        // Instagram's and Facebook's dash, VK's hls/dash and TikTok's non watermarked files win over any resolution,
        // X's hls only over the http file of the same resolution
        val protocolFirst = site != SocialMediaUtil.Site.X
        return formats.sortedWith(
            compareByDescending<Format> { videoFormatIDPreference.contains(it.format_id) }
                .thenByDescending { SocialMediaUtil.isTikTokOriginal(it.format_id) }
                .thenByDescending { protocolFirst && preferredProtocol(it) }
                .thenByDescending { it.shortSide() }
                .thenByDescending { preferredProtocol(it) }
                .thenByDescending { it.bitrateValue() }
                .thenByDescending { it.filesize }
                .thenByDescending { it.fpsValue() }
        )
    }

    @SuppressLint("RestrictedApi")
    fun sortVideoFormats(formats: List<Format>, downloadItemContext: DownloadItem? = null, url: String? = null): List<Format> {
        SocialMediaUtil.siteOf(url ?: downloadItemContext?.url)?.let { site ->
            if (videoQualityPreference != "worst") return sortSocialVideoFormats(formats, site)
        }
        val orderPreferences = getVideoFormatImportance(downloadItemContext)

        val fieldSorter = object : Comparator<Format> {
            override fun compare(a: Format, b: Format): Int {
                for (order in orderPreferences) {
                    val comparison = when (order) {
                        "smallsize" -> {
                            val result = a.filesize.compareTo(b.filesize)
                            result
                        }
                        "file_size" -> {
                            val result = b.filesize.compareTo(a.filesize)
                            result
                        }
                        "id" -> {
                            if (videoFormatIDPreference.isEmpty()) {
                                0
                            }else {
                                videoFormatIDPreference.contains(b.format_id).compareTo(videoFormatIDPreference.contains(a.format_id))
                            }
                        }
                        "codec" -> {
                            if (videoCodecPreference.isBlank()) {
                                0
                            }else {
                                val first = videoCodecPreference.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(b.vcodec.uppercase())
                                val second = videoCodecPreference.toRegex(RegexOption.IGNORE_CASE).containsMatchIn(a.vcodec.uppercase())
                                first.compareTo(second)
                            }
                        }
                        "resolution" -> {
                            when (videoQualityPreference) {
                                "worst" -> {
                                    val containsWorst = b.format_note.contains("worst", ignoreCase = true)
                                        .compareTo(a.format_note.contains("worst", ignoreCase = true))

                                    val worstFilesize = a.filesize.compareTo(b.filesize)

                                    min(containsWorst, worstFilesize)
                                }
                                "best" -> {
                                    b.format_note.contains("best", ignoreCase = true)
                                        .compareTo(a.format_note.contains("best", ignoreCase = true))
                                }
                                else -> {
                                    // YTindir: a resolution setting (e.g. 1080p) is a cap: the highest
                                    // resolution at or under it wins, otherwise the closest one above it
                                    val target = Regex("(\\d{3,4})p").find(videoQualityPreference)?.groupValues?.get(1)?.toIntOrNull()
                                    if (target == null) 0 else {
                                        val ah = a.shortSide()
                                        val bh = b.shortSide()
                                        val aFits = ah in 1..target
                                        val bFits = bh in 1..target
                                        when {
                                            aFits != bFits -> bFits.compareTo(aFits)
                                            aFits -> bh.compareTo(ah)
                                            else -> ah.compareTo(bh)
                                        }
                                    }
                                }
                            }
                        }
                        "no_audio" -> {
                            (b.acodec == "none" || b.acodec == "").compareTo(a.acodec == "none" || a.acodec == "")
                        }
                        "container" -> {
                            if (videoContainerPreference.isBlank()) {
                                0
                            }else {
                                videoContainerPreference.equals(b.container, ignoreCase = true)
                                    .compareTo(videoContainerPreference.equals(a.container, ignoreCase = true))
                            }
                        }
                        else -> 0
                    }
                    if (comparison != 0) return comparison
                }
                // YTindir: between otherwise equal formats take the larger picture, then the
                // highest frame rate the video has (60, else 50, else 30...)
                val height = b.heightValue().compareTo(a.heightValue())
                if (height != 0) return height
                return b.fpsValue().compareTo(a.fpsValue())
            }
        }
        return formats.sortedWith(fieldSorter)
    }

    fun getGenericAudioFormats(resources: Resources) : MutableList<Format>{
        val audioFormatIDPreference = sharedPreferences.getString("format_id_audio", "").toString().split(",").filter { it.isNotEmpty() }
        val audioFormats = resources.getStringArray(R.array.audio_formats)
        val audioFormatsValues = resources.getStringArray(R.array.audio_formats_values)
        val formats = mutableListOf<Format>()
        val containerPreference = sharedPreferences.getString("audio_format", "")
        val acodecPreference = sharedPreferences.getString("audio_codec", "")!!.run {
            if (this.isEmpty()){
                resources.getString(R.string.defaultValue)
            }else{
                val audioCodecs = resources.getStringArray(R.array.audio_codec)
                val audioCodecsValues = resources.getStringArray(R.array.audio_codec_values)
                audioCodecs[audioCodecsValues.indexOf(this)]
            }
        }
        audioFormats.forEachIndexed { idx, it -> formats.add(Format(audioFormatsValues[idx], containerPreference!!,"",acodecPreference!!, "",0, it)) }
        audioFormatIDPreference.forEach { formats.add(Format(it, containerPreference!!,"",resources.getString(R.string.preferred_format_id), "",1, it)) }
        return formats
    }

    fun getGenericVideoFormats(resources: Resources) : MutableList<Format>{
        val formatIDPreference = sharedPreferences.getString("format_id", "").toString().split(",").filter { it.isNotEmpty() }
        val videoFormatsValues = resources.getStringArray(R.array.video_formats_values)
        val videoFormats = resources.getStringArray(R.array.video_formats)
        val formats = mutableListOf<Format>()
        val containerPreference = sharedPreferences.getString("video_format", "")
        // YTindir: mp4 videos get AAC audio, whatever the audio codec setting says
        val videoAudioCodec = if (containerPreference.equals("mp4", ignoreCase = true)) "m4a|mp4a|aac"
            else sharedPreferences.getString("audio_codec", "")!!
        val audioCodecPreference = videoAudioCodec.run {
            if (this.isNotEmpty()){
                val audioCodecs = resources.getStringArray(R.array.audio_codec)
                val audioCodecsValues = resources.getStringArray(R.array.audio_codec_values)
                audioCodecs[audioCodecsValues.indexOf(this)]
            }else this
        }
        val videoCodecPreference = sharedPreferences.getString("video_codec", "")!!.run {
            if (this.isEmpty()){
                resources.getString(R.string.defaultValue)
            }else{
                val videoCodecs = resources.getStringArray(R.array.video_codec)
                val videoCodecsValues = resources.getStringArray(R.array.video_codec_values)
                videoCodecs[videoCodecsValues.indexOf(this)]
            }
        }
        videoFormatsValues.forEachIndexed { index, it ->  formats.add(Format(it, containerPreference!!,videoCodecPreference,audioCodecPreference, "",0, videoFormats[index])) }
        formatIDPreference.forEach { formats.add(Format(it, containerPreference!!,resources.getString(R.string.preferred_format_id),"", "",1, it)) }
        return formats
    }

    //OLD CODE JUST FOR STORAGE / REFERENCE

    /*
    * fun getPreferredAudioRequirements(): MutableList<(Format) -> Int> {
        val requirements: MutableList<(Format) -> Int> = mutableListOf()

        val itemValues = resources.getStringArray(R.array.format_importance_audio_values).toSet()
        val prefAudio = sharedPreferences.getString("format_importance_audio", itemValues.joinToString(","))!!

        prefAudio.split(",").forEachIndexed { idx, s ->
            val importance = (itemValues.size - idx) * 10
            when(s) {
                "id" -> {
                    requirements.add {it: Format -> if (audioFormatIDPreference.contains(it.format_id)) importance else 0}
                }
                "language" -> {
                    sharedPreferences.getString("audio_language", "")?.apply {
                        if (this.isNotBlank()){
                            requirements.add { it: Format -> if (it.lang?.contains(this) == true) importance else 0 }
                        }
                    }
                }
                "codec" -> {
                    requirements.add {it: Format -> if ("^(${audioCodec}).+$".toRegex(RegexOption.IGNORE_CASE).matches(it.acodec)) importance else 0}
                }
                "container" -> {
                    requirements.add {it: Format -> if (it.container == audioContainer) importance else 0 }
                }
            }
        }

        return requirements
    }

    //requirement and importance
    @SuppressLint("RestrictedApi")
    fun getPreferredVideoRequirements(): MutableList<(Format) -> Int> {
        val requirements: MutableList<(Format) -> Int> = mutableListOf()

        val itemValues = resources.getStringArray(R.array.format_importance_video_values).toSet()
        val prefVideo = sharedPreferences.getString("format_importance_video", itemValues.joinToString(","))!!

        prefVideo.split(",").forEachIndexed { idx, s ->
            var importance = (itemValues.size - idx) * 10

            when(s) {
                "id" -> {
                    requirements.add { it: Format -> if (formatIDPreference.contains(it.format_id)) importance else 0 }
                }
                "resolution" -> {
                    context.getStringArray(R.array.video_formats_values)
                        .filter { it.contains("_") }
                        .map{ it.split("_")[0].dropLast(1)
                        }.toMutableList().apply {
                            when(videoQualityPreference) {
                                "worst" -> {
                                    requirements.add { it: Format -> if (it.format_note.contains("worst", ignoreCase = true)) (importance) else 0 }
                                }
                                "best" -> {
                                    requirements.add { it: Format -> if (it.format_note.contains("best", ignoreCase = true)) (importance) else 0 }
                                }
                                else -> {
                                    val preferenceIndex = this.indexOfFirst { videoQualityPreference.contains(it) }
                                    val preference = this[preferenceIndex]
                                    for(i in 0..preferenceIndex){
                                        removeAt(0)
                                    }
                                    add(0, preference)
                                    forEachIndexed { index, res ->
                                        requirements.add { it: Format -> if (it.format_note.contains(res, ignoreCase = true)) (importance - index - 1) else 0 }
                                    }
                                }
                            }

                        }
                }
                "codec" -> {
                    requirements.add { it: Format -> if ("^(${videoCodec})(.+)?$".toRegex(RegexOption.IGNORE_CASE).matches(it.vcodec)) importance else 0 }
                }
                "no_audio" -> {
                    requirements.add { it: Format -> if (it.acodec == "none" || it.acodec == "") importance else 0 }
                }
                "container" -> {
                    requirements.add { it: Format ->
                        if (videoContainer == "mp4")
                            if (it.container.equals("mpeg_4", true)) importance else 0
                        else
                            if (it.container.equals(videoContainer, true)) importance else 0
                    }
                }
            }
        }

        return  requirements
    }
    * */


    private fun Format.heightValue(): Int =
        height ?: Regex("(\\d{3,4})p").find(format_note)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    // the "p" of a resolution is its short side: a 1080x1920 vertical video is 1080p
    private fun Format.shortSide(): Int {
        val w = width ?: 0
        val h = height ?: 0
        return if (w > 0 && h > 0) minOf(w, h) else heightValue()
    }

    private fun Format.bitrateValue(): Double =
        tbr?.lowercase()?.removeSuffix("k")?.trim()?.toDoubleOrNull() ?: 0.0

    private fun Format.fpsValue(): Float =
        fps?.toFloatOrNull() ?: Regex("\\d{3,4}p(\\d{2,3})").find(format_note)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
}
