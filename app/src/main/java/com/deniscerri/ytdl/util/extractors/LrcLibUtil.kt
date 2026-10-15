package com.deniscerri.ytdl.util.extractors

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

object LrcLibUtil {
    private const val API = "https://lrclib.net/api"
    private const val DURATION_TOLERANCE = 5
    private val lrcStamp = Regex("""\[(\d+):(\d+(?:\.\d+)?)]""")
    private val junkBrackets = Regex(
        """\s*[(\[][^)\]]*(?:official|video|audio|lyric|visuali[sz]er|\bhd\b|\b4k\b|\bmv\b|remaster)[^)\]]*[)\]]""",
        RegexOption.IGNORE_CASE
    )

    data class LrcLine(val timeMs: Long, val text: String)

    data class Lyrics(
        val id: Long,
        val trackName: String,
        val artistName: String,
        val albumName: String,
        val durationSec: Int,
        val synced: List<LrcLine>,
        val plain: String
    ) {
        val isSynced get() = synced.isNotEmpty()
    }

    private fun get(path: String, params: Map<String, String?> = emptyMap()): String? {
        val query = params.filterValues { !it.isNullOrBlank() }
            .map { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }
            .joinToString("&")
        // YTindr: a failed request (400, timeout...) used to throw and skip every other attempt,
        // so lyrics always ended up "not found"
        return runCatching {
            val conn = URL("$API/$path" + if (query.isNotEmpty()) "?$query" else "").openConnection() as HttpURLConnection
            try {
                conn.setRequestProperty("User-Agent", "YTindr (https://github.com/RAMiK4/ytindr)")
                conn.connectTimeout = 8000
                conn.readTimeout = 10000
                if (conn.responseCode !in 200..299) null
                else conn.inputStream.bufferedReader().use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    fun parseLrc(text: String?): List<LrcLine> {
        if (text.isNullOrBlank()) return emptyList()
        val lines = mutableListOf<LrcLine>()
        text.lines().forEach { raw ->
            val stamps = lrcStamp.findAll(raw).toList()
            if (stamps.isEmpty()) return@forEach
            val content = raw.substring(stamps.last().range.last + 1).trim()
            stamps.forEach {
                val ms = ((it.groupValues[1].toLong() * 60 + it.groupValues[2].toDouble()) * 1000).toLong()
                lines.add(LrcLine(ms, content))
            }
        }
        return lines.sortedBy { it.timeMs }
    }

    private fun toLyrics(o: JSONObject): Lyrics? {
        if (o.optBoolean("instrumental")) return null
        val synced = parseLrc(o.optString("syncedLyrics").takeIf { !o.isNull("syncedLyrics") })
        val plain = o.optString("plainLyrics").takeIf { !o.isNull("plainLyrics") && it.isNotBlank() }
            ?: synced.joinToString("\n") { it.text }
        if (synced.isEmpty() && plain.isBlank()) return null
        return Lyrics(o.optLong("id"), o.optString("trackName"), o.optString("artistName"),
            o.optString("albumName").takeIf { !o.isNull("albumName") } ?: "",
            o.optDouble("duration", 0.0).toInt(), synced, plain)
    }

    private fun pick(results: JSONArray, durationSec: Int, tolerance: Int = DURATION_TOLERANCE): Lyrics? {
        return (0 until results.length())
            .map { results.getJSONObject(it) }
            .filter { durationSec <= 0 || abs(it.optDouble("duration") - durationSec) <= tolerance }
            .mapNotNull { o -> toLyrics(o)?.let { it to abs(o.optDouble("duration") - durationSec) } }
            .minWithOrNull(compareBy({ !it.first.isSynced }, { it.second }))
            ?.first
    }

    private val featPart = Regex("""\s*[(\[]\s*(?:feat|ft)\.?\s[^)\]]*[)\]]""", RegexOption.IGNORE_CASE)
    private val featTail = Regex("""\s+(?:feat|ft)\.?\s.*$""", RegexOption.IGNORE_CASE)

    /** "Shakira and 2 more" / "Shakira, Burna Boy" / "Artist - Topic" -> "Shakira" */
    fun cleanArtist(artist: String): String {
        var a = artist.replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
        a = a.replace(Regex("""\s+and\s+\d+\s+more$""", RegexOption.IGNORE_CASE), "")
        a = a.split(Regex("""\s*(?:,|&|\bx\b|\bfeat\.?|\bft\.?|\bve\b|\band\b)\s*""", RegexOption.IGNORE_CASE)).first()
        return a.trim()
    }

    private fun cleanTitle(track: String): String {
        var t = junkBrackets.replace(track, "")
        t = featPart.replace(t, "")
        t = t.substringBefore("|")
        t = featTail.replace(t, "")
        return t.trim().trim('-', ' ')
    }

    /**
     * Mirrors the matching in the Lyrics yt-dlp plugin so the preview is the same as the download.
     * Blocking, call from IO.
     */
    fun find(track: String, artist: String, album: String, durationSec: Int): Lyrics? {
        val channelArtist = cleanArtist(artist)
        // "Artist | Title" uploads: keep the part that is not the artist
        var source = track
        val parts = track.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size > 1 && channelArtist.isNotBlank() && cleanArtist(parts[0]).equals(channelArtist, ignoreCase = true)) {
            source = parts.drop(1).joinToString(" | ")
        }
        var title = cleanTitle(source)
        var titleArtist = ""
        if (title.contains(" - ")) {
            titleArtist = cleanArtist(title.substringBefore(" - "))
            title = cleanTitle(title.substringAfter(" - "))
        }
        val artists = listOf(titleArtist, channelArtist).filter { it.isNotBlank() }.distinct()

        artists.firstOrNull()?.let { a ->
            get("get", mapOf(
                "track_name" to title,
                "artist_name" to a,
                "album_name" to album,
                "duration" to durationSec.takeIf { it > 0 }?.toString()
            ))?.let { runCatching { toLyrics(JSONObject(it)) }.getOrNull() }?.let { return it }
        }

        val searches = artists.map { mapOf("track_name" to title, "artist_name" to it) } +
            artists.map { mapOf("q" to "$it $title") } +
            listOf(mapOf("q" to title))
        val responses = mutableListOf<JSONArray>()
        for (params in searches) {
            val arr = get("search", params)?.let { runCatching { JSONArray(it) }.getOrNull() } ?: continue
            pick(arr, durationSec)?.let { return it }
            responses.add(arr)
        }
        // video uploads are often longer than the song (intro, outro), so allow more slack
        for (arr in responses) {
            pick(arr, durationSec, 45)?.let { return it }
        }
        return null
    }

    fun byId(id: Long): Lyrics? = get("get/$id")?.let { toLyrics(JSONObject(it)) }

    /** Free text search used by the manual "search again" option, no duration filter. */
    fun search(query: String): List<Lyrics> {
        val arr = JSONArray(get("search", mapOf("q" to query)) ?: return emptyList())
        return (0 until arr.length()).mapNotNull { toLyrics(arr.getJSONObject(it)) }
    }
}