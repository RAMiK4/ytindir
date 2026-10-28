package com.deniscerri.ytdl.util

import android.content.Context
import com.deniscerri.ytdl.R

/**
 * YTindir: a short, plain explanation of what a yt-dlp error means and what to do about it,
 * shown above the error itself (in the error dialog and the failed download notification), since
 * "HTTP Error 403" or "Failed to parse JSON" says little to most people. Null when there's no hint.
 */
object ErrorHints {

    private class Rule(val hint: Int, val test: (String, SocialMediaUtil.Site?) -> Boolean)

    private fun has(message: String, vararg parts: String) = parts.any { message.contains(it, ignoreCase = true) }

    private val rules = listOf(
        Rule(R.string.hint_instagram_stories_login) { m, _ ->
            has(m, "Instagram stories can only be downloaded")
        },
        Rule(R.string.hint_instagram_logged_out) { m, site ->
            site == SocialMediaUtil.Site.INSTAGRAM && has(m, "Failed to parse JSON", "JSONDecodeError")
        },
        Rule(R.string.hint_youtube_bot) { m, _ ->
            has(m, "confirm you’re not a bot", "confirm you're not a bot", "confirm that you're not a bot")
        },
        Rule(R.string.hint_age_restricted) { m, _ ->
            has(m, "confirm your age", "age-restricted", "age restricted", "inappropriate for some users")
        },
        Rule(R.string.hint_private) { m, _ ->
            has(m, "private video", "this video is private", "is private", "account is private", "private account")
        },
        Rule(R.string.hint_login_required) { m, _ ->
            has(m, "You need to log in", "login required", "log in to access", "requires authentication",
                "Sign in to", "--cookies-from-browser or --cookies")
        },
        Rule(R.string.hint_geo_blocked) { m, _ ->
            has(m, "not available in your country", "geo restriction", "geo-restricted", "not available from your location", "blocked it in your country")
        },
        Rule(R.string.hint_format_unavailable) { m, _ ->
            has(m, "Requested format is not available")
        },
        Rule(R.string.hint_unavailable) { m, _ ->
            has(m, "Video unavailable", "has been removed", "no longer available", "does not exist", "This post is unavailable",
                "HTTP Error 404", "video is not available", "content isn't available")
        },
        Rule(R.string.hint_rate_limited) { m, _ ->
            has(m, "HTTP Error 429", "Too Many Requests", "rate-limit", "rate limit")
        },
        Rule(R.string.hint_forbidden) { m, _ ->
            has(m, "HTTP Error 403", "Forbidden")
        },
        Rule(R.string.hint_unsupported) { m, _ ->
            has(m, "Unsupported URL", "is not a valid URL")
        },
        Rule(R.string.hint_no_space) { m, _ ->
            has(m, "No space left", "ENOSPC")
        },
        Rule(R.string.hint_network) { m, _ ->
            has(m, "Unable to download webpage", "timed out", "Failed to resolve", "Name or service not known",
                "No address associated", "Network is unreachable", "Connection reset", "Connection refused",
                "Unable to connect to proxy", "Temporary failure in name resolution", "SSL")
        },
        Rule(R.string.hint_extractor_broken) { m, _ ->
            has(m, "Unable to extract", "Failed to parse JSON", "please report this issue")
        },
    )

    fun hintFor(context: Context, message: String?, url: String? = null): String? {
        if (message.isNullOrBlank()) return null
        val site = SocialMediaUtil.siteOf(url) ?: SocialMediaUtil.siteOf(
            Regex("""https?://\S+""").find(message)?.value
        ) ?: if (message.contains("[Instagram", ignoreCase = true) || message.contains("[instagram", ignoreCase = true)) SocialMediaUtil.Site.INSTAGRAM else null
        val rule = rules.firstOrNull { it.test(message, site) } ?: return null
        return context.getString(rule.hint)
    }

    /** The hint above the error, or the error as it is when there's no hint. */
    fun withHint(context: Context, message: String, url: String? = null): String {
        val hint = hintFor(context, message, url) ?: return message
        return "$hint\n\n$message"
    }
}
