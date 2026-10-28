package com.deniscerri.ytdl.util

/**
 * YTindir: a release's notes are written in English for GitHub, with translations hidden in it as
 * HTML comments (GitHub doesn't show them):
 *
 *     <!-- notes:tr
 *     ## ✨ Yeni Özellikler
 *     ...
 *     -->
 *
 * The app shows the translation for its language when there is one, the English notes otherwise.
 */
object ReleaseNotesLocalizer {

    private val block = Regex("""<!--\s*notes:([A-Za-z-]+)\s*\n(.*?)-->""", RegexOption.DOT_MATCHES_ALL)

    fun localize(body: String): String {
        val language = LocaleUtil.currentLocale().language.lowercase()
        val translations = block.findAll(body).associate { it.groupValues[1].lowercase() to it.groupValues[2].trim() }
        translations[language]?.let { return it }
        return body.replace(block, "").trim()
    }
}
