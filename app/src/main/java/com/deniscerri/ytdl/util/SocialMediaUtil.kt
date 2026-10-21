package com.deniscerri.ytdl.util

import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * YTindir: Instagram, X and TikTok helpers: picking their best quality by default,
 * short file names like "fifaworldcup_DdhJKSdzuMT" and TikTok's original (source) video.
 */
object SocialMediaUtil {

    enum class Site { INSTAGRAM, X, TIKTOK }

    /** The TikTok original quality "format", resolved through tikwm.com when the download starts. */
    const val TIKTOK_ORIGINAL_FORMAT_ID = "original"
    private const val OLD_TIKTOK_ORIGINAL_FORMAT_ID = "tikwm-original"

    fun isTikTokOriginal(formatId: String?) =
        formatId == TIKTOK_ORIGINAL_FORMAT_ID || formatId == OLD_TIKTOK_ORIGINAL_FORMAT_ID

    private val instagramRegex = Regex("""^(?:https?://)?(?:[\w-]+\.)*(?:instagram\.com|instagr\.am)/""", RegexOption.IGNORE_CASE)
    private val xRegex = Regex("""^(?:https?://)?(?:[\w-]+\.)*(?:twitter\.com|x\.com|fxtwitter\.com|vxtwitter\.com|fixupx\.com)/""", RegexOption.IGNORE_CASE)
    private val tiktokRegex = Regex("""^(?:https?://)?(?:[\w-]+\.)*tiktok\.com/""", RegexOption.IGNORE_CASE)

    fun siteOf(url: String?): Site? {
        if (url.isNullOrBlank()) return null
        val u = url.trim()
        return when {
            instagramRegex.containsMatchIn(u) -> Site.INSTAGRAM
            xRegex.containsMatchIn(u) -> Site.X
            tiktokRegex.containsMatchIn(u) -> Site.TIKTOK
            else -> null
        }
    }

    fun siteOf(url: String?, extractor: String?): Site? {
        siteOf(url)?.let { return it }
        val e = extractor.orEmpty().lowercase()
        return when {
            e.startsWith("instagram") -> Site.INSTAGRAM
            e.startsWith("twitter") -> Site.X
            e.startsWith("tiktok") -> Site.TIKTOK
            else -> null
        }
    }

    private fun JSONObject.text(vararg keys: String): String {
        for (k in keys) {
            val v = optString(k, "").trim()
            if (v.isNotBlank() && v != "null" && v != "NA") return v
        }
        return ""
    }

    private fun idFromUrl(site: Site, url: String): String {
        val regex = when (site) {
            Site.INSTAGRAM -> Regex("""/(?:p|reels?|tv|stories/[^/]+)/([\w-]+)""")
            Site.X -> Regex("""/status(?:es)?/(\d+)""")
            Site.TIKTOK -> Regex("""/(?:video|photo)/(\d+)""")
        }
        return regex.find(url)?.groupValues?.get(1).orEmpty()
    }

    /**
     * A short, meaningful title for posts whose real title is just "Video by …" or a long caption:
     * the account's user name, then the post's id, e.g. "fifaworldcup_DdhJKSdzuMT".
     */
    fun fileTitle(site: Site, json: JSONObject, url: String, fallbackAuthor: String): String? {
        val handles = when (site) {
            // Instagram: channel is the user name, uploader the full name, uploader_id a number
            Site.INSTAGRAM -> json.text("channel", "uploader_id")
            // X: uploader_id is the @handle, uploader the display name
            Site.X -> json.text("uploader_id", "channel_id")
            // TikTok: uploader is the @handle, channel the nickname, uploader_id a number
            Site.TIKTOK -> json.text("uploader", "creator")
        }.removePrefix("@")
        val handle = handles.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
            ?: fallbackAuthor.ifBlank { json.text("uploader", "channel") }

        val id = when (site) {
            Site.INSTAGRAM -> idFromUrl(site, url).ifBlank { json.text("display_id", "id") }
            Site.X -> json.text("display_id").ifBlank { idFromUrl(site, url) }.ifBlank { json.text("id") }
            Site.TIKTOK -> json.text("id").ifBlank { idFromUrl(site, url) }
        }

        if (handle.isBlank() && id.isBlank()) return null
        return listOf(handle, id).filter { it.isNotBlank() }.joinToString("_")
    }

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    private const val TIKWM = "https://www.tikwm.com"

    private fun tikwmRequest(url: String) = Request.Builder()
        .url(url)
        .header("User-Agent", "Mozilla/5.0 (Android 14; Mobile; rv:141.0) Gecko/141.0 Firefox/141.0")
        .header("Accept", "application/json, text/javascript, */*; q=0.01")
        .header("Origin", TIKWM)
        .header("Referer", "$TIKWM/originalDownloader.html")
        .header("X-Requested-With", "XMLHttpRequest")

    private fun getJson(request: Request): JSONObject? = runCatching {
        client.newCall(request).execute().use { r ->
            if (!r.isSuccessful) null else JSONObject(r.body.string())
        }
    }.getOrNull()

    private fun JSONObject.playUrl(): String {
        val url = text("play_url", "url", "hdplay", "play")
        return when {
            url.isBlank() -> ""
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$TIKWM$url"
            else -> url
        }
    }

    /**
     * The link of a TikTok video's original upload, the same one tikwm.com/originalDownloader.html
     * gives. When tikwm can't make one, its HD link; null only when both fail (the download then
     * falls back to yt-dlp, which TikTok sometimes answers with "HTTP Error 403").
     */
    suspend fun getTikTokOriginalUrl(videoUrl: String): String? {
        // the original is sometimes not ready on the first try
        repeat(2) {
            getTikTokSourceUrl(videoUrl)?.let { return it }
        }
        return getTikTokHdUrl(videoUrl)
    }

    private fun getTikTokHdUrl(videoUrl: String): String? = runCatching {
        val url = "$TIKWM/api/?hd=1&url=" + java.net.URLEncoder.encode(videoUrl, "UTF-8")
        val json = getJson(tikwmRequest(url).get().build()) ?: return null
        if (json.optInt("code", -1) != 0) return null
        val data = json.optJSONObject("data") ?: return null
        data.text("hdplay", "play").let {
            when {
                it.isBlank() -> null
                it.startsWith("/") -> "$TIKWM$it"
                else -> it
            }
        }
    }.getOrNull()

    private suspend fun getTikTokSourceUrl(videoUrl: String): String? {
        try {
            val submit = tikwmRequest("$TIKWM/api/video/task/submit")
                .post(FormBody.Builder().add("url", videoUrl).add("web", "1").build())
                .build()
            val submitted = getJson(submit) ?: return null
            if (submitted.optInt("code", -1) != 0) return null
            val taskId = submitted.optJSONObject("data")?.text("task_id")
                ?.ifBlank { null } ?: submitted.text("task_id").ifBlank { return null }

            // the original is prepared on tikwm's side; it usually takes a few seconds
            repeat(30) {
                delay(2000)
                currentCoroutineContext().ensureActive()
                val result = getJson(tikwmRequest("$TIKWM/api/video/task/result?task_id=$taskId").get().build())
                    ?: return@repeat
                if (result.optInt("code", -1) != 0) return@repeat
                val data = result.optJSONObject("data") ?: return@repeat
                when (data.optInt("status", -1)) {
                    2 -> {
                        val url = (data.optJSONObject("detail") ?: data).playUrl().ifBlank { data.playUrl() }
                        return url.ifBlank { null }
                    }
                    3 -> return null
                }
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("SocialMediaUtil", "tikwm: ${e.message}")
        }
        return null
    }
}
