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
 * YTindir: Instagram, X, TikTok, Facebook and VK helpers: picking their best quality by default,
 * short file names like "fifaworldcup_DdhJKSdzuMT" and TikTok's original (source) video.
 * (VK keeps its own titles: they are real video titles, as on YouTube.)
 */
object SocialMediaUtil {

    enum class Site { INSTAGRAM, X, TIKTOK, FACEBOOK, VK }

    /** The TikTok original quality "format", resolved through tikwm.com when the download starts. */
    const val TIKTOK_ORIGINAL_FORMAT_ID = "original"
    private const val OLD_TIKTOK_ORIGINAL_FORMAT_ID = "tikwm-original"

    fun isTikTokOriginal(formatId: String?) =
        formatId == TIKTOK_ORIGINAL_FORMAT_ID || formatId == OLD_TIKTOK_ORIGINAL_FORMAT_ID

    private val instagramRegex = Regex("""^(?:https?://)?(?:[\w-]+\.)*(?:instagram\.com|instagr\.am)/""", RegexOption.IGNORE_CASE)
    private val xRegex = Regex("""^(?:https?://)?(?:[\w-]+\.)*(?:twitter\.com|x\.com|fxtwitter\.com|vxtwitter\.com|fixupx\.com)/""", RegexOption.IGNORE_CASE)
    private val tiktokRegex = Regex("""^(?:https?://)?(?:[\w-]+\.)*tiktok\.com/""", RegexOption.IGNORE_CASE)
    private val facebookRegex = Regex("""^(?:https?://)?(?:[\w-]+\.)*(?:facebook\.com|fb\.com|fb\.watch)/""", RegexOption.IGNORE_CASE)
    private val vkRegex = Regex("""^(?:https?://)?(?:[\w-]+\.)*(?:vk\.com|vk\.ru|vkvideo\.ru)/""", RegexOption.IGNORE_CASE)

    fun siteOf(url: String?): Site? {
        if (url.isNullOrBlank()) return null
        val u = url.trim()
        return when {
            instagramRegex.containsMatchIn(u) -> Site.INSTAGRAM
            xRegex.containsMatchIn(u) -> Site.X
            tiktokRegex.containsMatchIn(u) -> Site.TIKTOK
            facebookRegex.containsMatchIn(u) -> Site.FACEBOOK
            vkRegex.containsMatchIn(u) -> Site.VK
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
            e.startsWith("facebook") -> Site.FACEBOOK
            // (not "vkplay", a streaming site of its own)
            e == "vk" || e.startsWith("vkwallpost") -> Site.VK
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
            // facebook.com/<page>/videos/<id>, /reel/<id>, /watch/?v=<id>, video.php?v=<id>
            Site.FACEBOOK -> Regex("""(?:/videos/(?:[^/?#]+/)?|/reel/|[?&]v=)(\d+)""")
            Site.VK -> Regex("""video(-?\d+_\d+)""")
        }
        return regex.find(url)?.groupValues?.get(1).orEmpty()
    }

    // facebook.com/<page>/videos/<id>: the page's user name, when the link has it
    private val facebookVanityRegex = Regex("""facebook\.com/([\w.-]+)/videos/""", RegexOption.IGNORE_CASE)
    private val facebookNotNames = setOf("watch", "reel", "reels", "share", "video", "videos", "groups", "events", "pg", "people", "story.php", "permalink.php")

    private fun facebookUserName(url: String): String {
        val name = facebookVanityRegex.find(url)?.groupValues?.get(1).orEmpty()
        return name.takeIf { it.isNotBlank() && it.lowercase() !in facebookNotNames && !it.all(Char::isDigit) }.orEmpty()
    }

    /**
     * A short, meaningful title for posts whose real title is just "Video by …" or a long caption:
     * the account's user name, then the post's id, e.g. "fifaworldcup_DdhJKSdzuMT".
     */
    fun fileTitle(site: Site, json: JSONObject, url: String, fallbackAuthor: String): String? {
        if (site == Site.VK) return null
        val handles = when (site) {
            // Instagram: channel is the user name, uploader the full name, uploader_id a number
            Site.INSTAGRAM -> json.text("channel", "uploader_id")
            // X: uploader_id is the @handle, uploader the display name
            Site.X -> json.text("uploader_id", "channel_id")
            // TikTok: uploader is the @handle, channel the nickname, uploader_id a number
            Site.TIKTOK -> json.text("uploader", "creator")
            // Facebook: no @handle, so the page name in the link, otherwise the name it shows
            Site.FACEBOOK -> facebookUserName(url).ifBlank { json.text("uploader", "channel") }
            Site.VK -> ""
        }.removePrefix("@")
        val handle = handles.takeIf { it.isNotBlank() && !it.all(Char::isDigit) }
            ?: fallbackAuthor.ifBlank { json.text("uploader", "channel") }

        val id = when (site) {
            Site.INSTAGRAM -> idFromUrl(site, url).ifBlank { json.text("display_id", "id") }
            Site.X -> json.text("display_id").ifBlank { idFromUrl(site, url) }.ifBlank { json.text("id") }
            Site.TIKTOK -> json.text("id").ifBlank { idFromUrl(site, url) }
            Site.FACEBOOK -> json.text("id").ifBlank { idFromUrl(site, url) }
            Site.VK -> ""
        }

        if (handle.isBlank() && id.isBlank()) return null
        // one of a post's several videos (an Instagram carousel): _1, _2…, so they don't end up
        // as "name", "name (1)", "name (2)"
        val index = json.optInt("playlist_index", 0)
        val count = maxOf(json.optInt("n_entries", 0), json.optInt("playlist_count", 0))
        val number = if (index > 0 && count > 1) "_$index" else ""
        return listOf(handle, id).filter { it.isNotBlank() }.joinToString("_") + number
    }

    /**
     * [fileTitle] for a download that starts before the post was read (a quick download):
     * a yt-dlp output template that gives the same "username_postid" name, instead of
     * "Video by …" or a whole tweet. Null when the site keeps yt-dlp's own title.
     */
    fun outputTitleTemplate(url: String): String? {
        val site = siteOf(url) ?: return null
        val urlId = idFromUrl(site, url)
        // "handle_" only when yt-dlp knows the handle
        val handle = when (site) {
            Site.INSTAGRAM -> "%(channel,uploader_id&{}_|)s"
            Site.X -> "%(uploader_id&{}_|)s"
            Site.TIKTOK -> "%(uploader,creator&{}_|)s"
            Site.FACEBOOK -> facebookUserName(url).takeIf { it.isNotBlank() }?.let { "${it.replace("%", "%%")}_" }
                ?: "%(uploader,channel&{}_|)s"
            Site.VK -> return null
        }
        val id = when (site) {
            // (a shared Facebook link has no id in it; the post's own id matches /videos/<id>)
            Site.FACEBOOK -> "%(id)s"
            Site.TIKTOK -> urlId.ifBlank { "%(id)s" }
            else -> urlId.ifBlank { "%(display_id,id)s" }
        }
        // posts with several videos (an Instagram carousel, a tweet with two videos) get _1, _2…
        return handle + id + "%(playlist_index&_{}|)s"
    }

    /**
     * Instagram's app API (and Facebook) also offer AV1, HEVC and 10-bit VP9 (profile 2, HDR) streams,
     * whose colours come out wrong in many players: H.264 and 8-bit VP9 come first, as on the website.
     */
    fun isPlainVideoCodec(vcodec: String?): Boolean {
        val v = vcodec.orEmpty().lowercase()
        if (v.isBlank() || v == "none") return true
        return listOf("av01", "av1", "vp09.02", "vp09.03", "hvc1", "hev1", "hevc", "h265", "dvh").none { v.startsWith(it) }
    }

    /** A yt-dlp format filter with the same rule as [isPlainVideoCodec]. */
    const val PLAIN_VIDEO_FILTER = "[vcodec!^=av01][vcodec!^=vp09.02][vcodec!^=vp09.03][vcodec!^=hvc1][vcodec!^=hev1][vcodec!^=dvh]"

    /** Whether a Netscape cookies file holds a logged-in Instagram session. */
    fun hasInstagramSession(cookieFile: java.io.File): Boolean = runCatching {
        cookieFile.useLines { lines ->
            lines.any { l ->
                val parts = l.split('\t')
                parts.size >= 7 && parts[0].contains("instagram.com") && parts[5] == "sessionid" && parts[6].isNotBlank()
            }
        }
    }.getOrDefault(false)

    private fun instagramSessionId(cookieFile: java.io.File): String? = runCatching {
        cookieFile.useLines { lines ->
            lines.map { it.split('\t') }
                .firstOrNull { p -> p.size >= 7 && p[0].contains("instagram.com") && p[5] == "sessionid" && p[6].isNotBlank() }
                ?.get(6)
        }
    }.getOrNull()

    /*
     * YTindir: an Instagram account logged out (on the phone, the website or by Instagram itself)
     * leaves its sessionid in the saved cookies. Instagram then answers yt-dlp with its login page
     * and every post fails with "Failed to parse JSON". Such a session is marked here once a post
     * works without the cookies, so Instagram posts are read as a logged-out visitor (the website
     * version) until the session changes (a new login) or a few hours pass.
     */
    private const val STALE_SESSION_PREF = "instagram_stale_session"
    private const val STALE_SESSION_HOURS = 12L

    private fun sessionKey(sessionId: String) = sessionId.hashCode().toString(16)

    /** Whether Instagram posts should skip the cookies because their session no longer works. */
    fun isInstagramSessionStale(prefs: android.content.SharedPreferences, cookieFile: java.io.File): Boolean {
        val saved = prefs.getString(STALE_SESSION_PREF, null)?.split('|') ?: return false
        if (saved.size != 2) return false
        val sessionId = instagramSessionId(cookieFile) ?: return false
        val fresh = System.currentTimeMillis() - (saved[1].toLongOrNull() ?: 0L) < TimeUnit.HOURS.toMillis(STALE_SESSION_HOURS)
        return fresh && saved[0] == sessionKey(sessionId)
    }

    fun setInstagramSessionStale(prefs: android.content.SharedPreferences, cookieFile: java.io.File, stale: Boolean) {
        val sessionId = instagramSessionId(cookieFile)
        if (!stale || sessionId == null) {
            prefs.edit().remove(STALE_SESSION_PREF).apply()
            return
        }
        prefs.edit().putString(STALE_SESSION_PREF, "${sessionKey(sessionId)}|${System.currentTimeMillis()}").apply()
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

    /** Where a TikTok download comes from, and a line about it for the download log. */
    class TikTokSource(val url: String?, val note: String)

    /**
     * The link of a TikTok video's original upload, the same one tikwm.com/originalDownloader.html
     * gives. When tikwm can't make one, its HD link; no link only when both fail (the download then
     * falls back to yt-dlp, which TikTok sometimes answers with "HTTP Error 403").
     */
    suspend fun getTikTokOriginal(videoUrl: String): TikTokSource {
        val original = getTikTokSourceUrl(videoUrl)
        if (original.first != null) return TikTokSource(original.first, "TikTok: original file from tikwm.com")
        val hd = getTikTokHdUrl(videoUrl)
        if (hd != null) return TikTokSource(hd, "TikTok: original not available (${original.second}), tikwm.com HD file instead")
        return TikTokSource(null, "TikTok: tikwm.com failed (${original.second}), downloading with yt-dlp")
    }

    /** What tikwm.com knows about a TikTok post, for when yt-dlp can't read it (TikTok's HTTP 403). */
    class TikTokInfo(
        val id: String,
        val handle: String,
        val nickname: String,
        val caption: String,
        val cover: String,
        val durationSeconds: Int,
        val hdUrl: String?,
    ) {
        /** The same "username_postid" title [fileTitle] gives. */
        val fileTitle: String get() = listOf(handle, id).filter { it.isNotBlank() }.joinToString("_")
    }

    fun getTikTokInfo(videoUrl: String): TikTokInfo? = runCatching {
        val url = "$TIKWM/api/?hd=1&url=" + java.net.URLEncoder.encode(videoUrl, "UTF-8")
        val json = getJson(tikwmRequest(url).get().build()) ?: return null
        if (json.optInt("code", -1) != 0) return null
        val data = json.optJSONObject("data") ?: return null
        val author = data.optJSONObject("author")
        val hd = data.text("hdplay", "play").let {
            when {
                it.isBlank() -> null
                it.startsWith("/") -> "$TIKWM$it"
                else -> it
            }
        }
        val cover = data.text("origin_cover", "cover").let { if (it.startsWith("/")) "$TIKWM$it" else it }
        TikTokInfo(
            id = data.text("id").ifBlank { idFromUrl(Site.TIKTOK, videoUrl) },
            handle = author?.text("unique_id").orEmpty(),
            nickname = author?.text("nickname").orEmpty(),
            caption = data.text("title"),
            cover = cover,
            durationSeconds = data.optInt("duration", 0),
            hdUrl = hd,
        )
    }.getOrNull()

    private fun getTikTokHdUrl(videoUrl: String): String? = getTikTokInfo(videoUrl)?.hdUrl

    // the link, or why there is none
    private suspend fun getTikTokSourceUrl(videoUrl: String): Pair<String?, String> {
        try {
            val submit = tikwmRequest("$TIKWM/api/video/task/submit")
                .post(FormBody.Builder().add("url", videoUrl).add("web", "1").build())
                .build()
            val submitted = getJson(submit) ?: return null to "no answer"
            if (submitted.optInt("code", -1) != 0) return null to "refused: ${submitted.text("msg")}"
            val taskId = submitted.optJSONObject("data")?.text("task_id")
                ?.ifBlank { null } ?: submitted.text("task_id").ifBlank { return null to "no task" }

            // the original is prepared on tikwm's side; a large video can take a minute or more
            repeat(90) {
                delay(2000)
                currentCoroutineContext().ensureActive()
                val result = getJson(tikwmRequest("$TIKWM/api/video/task/result?task_id=$taskId").get().build())
                    ?: return@repeat
                if (result.optInt("code", -1) != 0) return@repeat
                val data = result.optJSONObject("data") ?: return@repeat
                when (data.optInt("status", -1)) {
                    2 -> {
                        val url = (data.optJSONObject("detail") ?: data).playUrl().ifBlank { data.playUrl() }
                        return url.ifBlank { null } to "no link in the answer"
                    }
                    3 -> return null to "tikwm could not prepare it"
                }
            }
            return null to "not ready after 3 minutes"
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Log.e("SocialMediaUtil", "tikwm: ${e.message}")
            return null to (e.message ?: e.javaClass.simpleName)
        }
    }
}
