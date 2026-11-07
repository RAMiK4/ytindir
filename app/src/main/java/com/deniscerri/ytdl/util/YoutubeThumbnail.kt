package com.deniscerri.ytdl.util

import android.content.Context
import android.media.MediaScannerConnection
import com.deniscerri.ytdl.util.Extensions.getIDFromYoutubeURL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * YTindir: the original thumbnail of a YouTube video, saved as YouTube's own JPG
 * (maxresdefault, else the largest one the video has), not a WebP converted to JPG.
 */
object YoutubeThumbnail {
    private val SIZES = listOf("maxresdefault", "sddefault", "hqdefault")

    /** Shown before the size is known: a maxresdefault JPG is usually 100-300 KB. */
    const val USUAL_SIZE_BYTES = 200L * 1024

    /**
     * Saves the thumbnail to [QuickFormats.THUMBNAIL_FOLDER] in [videoPath], named after [title]
     * (or the video's title on YouTube, if blank).
     *
     * @return The saved file's path.
     */
    suspend fun save(context: Context, url: String, title: String, videoPath: String): String {
        val id = url.getIDFromYoutubeURL() ?: throw IllegalArgumentException("Not a YouTube video: $url")
        val (bytes, name) = withContext(Dispatchers.IO) {
            val image = SIZES.firstNotNullOfOrNull { fetch("https://i.ytimg.com/vi/$id/$it.jpg") }
                ?: throw IllegalStateException("No thumbnail for $id")
            val videoTitle = title.ifBlank { fetchTitle(url) ?: id }
            image to FileNameCleaner.clean(videoTitle).take(150)
        }

        val folder = File(FileUtil.getCacheDownloadsPath(context), "thumbnail_${System.currentTimeMillis()}")
        withContext(Dispatchers.IO) {
            File(folder, QuickFormats.THUMBNAIL_FOLDER).mkdirs()
            File(folder, "${QuickFormats.THUMBNAIL_FOLDER}/$name.jpg").writeBytes(bytes)
        }
        val saved = FileUtil.moveFile(folder, context, videoPath, false) {}
            .firstOrNull { it.endsWith(".jpg") }
            ?: throw IllegalStateException("The thumbnail could not be saved")
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(saved), null, null) }
        return saved
    }

    /** Bytes of the JPG [save] would download, 0 if unknown. Network request: call off the main thread. */
    fun fetchSize(url: String): Long {
        val id = url.getIDFromYoutubeURL() ?: return 0
        for (size in SIZES) {
            var connection: HttpURLConnection? = null
            try {
                connection = URL("https://i.ytimg.com/vi/$id/$size.jpg").openConnection() as HttpURLConnection
                connection.requestMethod = "HEAD"
                connection.connectTimeout = 10_000
                connection.readTimeout = 10_000
                if (connection.responseCode == HttpURLConnection.HTTP_OK) return connection.contentLengthLong.coerceAtLeast(0)
            } catch (e: Exception) {
                return 0
            } finally {
                connection?.disconnect()
            }
        }
        return 0
    }

    private fun fetch(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            // a missing size answers 404 with a small placeholder image
            if (connection.responseCode != HttpURLConnection.HTTP_OK) null
            else connection.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }

    private fun fetchTitle(url: String): String? {
        var connection: HttpURLConnection? = null
        return try {
            val oembed = "https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(url, "UTF-8")
            connection = URL(oembed).openConnection() as HttpURLConnection
            connection.connectTimeout = 10_000
            connection.readTimeout = 10_000
            if (connection.responseCode != HttpURLConnection.HTTP_OK) return null
            val json = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(json).optString("title").takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            null
        } finally {
            connection?.disconnect()
        }
    }
}
