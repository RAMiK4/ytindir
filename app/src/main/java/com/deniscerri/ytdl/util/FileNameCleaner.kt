package com.deniscerri.ytdl.util

import java.io.File

/**
 * YTindir: keeps downloaded file names readable and valid on every file manager, Windows included.
 * "Zeynep Bastık - Lan ｜ Lyric Video 🎵" becomes "Zeynep Bastık - Lan Lyric Video".
 */
object FileNameCleaner {

    // characters Windows (and many file managers) refuse, the full-width look-alikes yt-dlp
    // puts in their place, and commas
    private val forbiddenChars = Regex("[\\\\/:*?\"<>|,\\u0000-\\u001F\\u007F＼⧸⧹：？＂＊＜＞｜，]")
    private val emptyBrackets = Regex("""\(\s*\)|\[\s*]|\{\s*}|【\s*】|「\s*」""")
    private val repeatedSeparators = Regex("""\s*([-–—~_])(?:\s*\1)+\s*""")
    private val danglingSeparators = Regex("""^[\s\-–—~_.•·]+|[\s\-–—~_.•·]+$""")
    private val whitespace = Regex("""\s+""")
    private val reservedNames = Regex("^(CON|PRN|AUX|NUL|COM[0-9]|LPT[0-9])$", RegexOption.IGNORE_CASE)

    private fun isEmojiOrJunk(codePoint: Int): Boolean {
        return when (codePoint) {
            0x200D, 0x20E3, 0xFE0E, 0xFE0F -> true                 // joiners, keycaps, variation selectors
            in 0x1F3FB..0x1F3FF, in 0xE0020..0xE007F -> true       // skin tones, flag tags
            else -> when (Character.getType(codePoint).toByte()) {
                // emojis, stars, music notes, arrows... (© ® ° stay)
                Character.OTHER_SYMBOL -> codePoint >= 0x2190
                Character.PRIVATE_USE, Character.UNASSIGNED, Character.SURROGATE -> true
                else -> false
            }
        }
    }

    /** Cleans a name without its extension. */
    fun clean(name: String): String {
        val noEmojis = buildString {
            var i = 0
            while (i < name.length) {
                val cp = name.codePointAt(i)
                if (isEmojiOrJunk(cp)) append(' ') else appendCodePoint(cp)
                i += Character.charCount(cp)
            }
        }

        var result = noEmojis.replace(forbiddenChars, " ")
        result = result.replace(whitespace, " ")
        result = result.replace(emptyBrackets, " ")
        result = result.replace(repeatedSeparators) { " ${it.groupValues[1]} " }
        result = result.replace(whitespace, " ")
        result = result.replace(danglingSeparators, "")

        if (result.matches(reservedNames)) result += "_"
        return result.ifBlank { "download" }
    }

    /** Cleans a full file name, keeping its extension(s) like ".mp4" or ".en.vtt" as they are. */
    fun cleanFileName(fileName: String, stem: String = fileName.substringBeforeLast('.', fileName)): String {
        val rest = fileName.removePrefix(stem)
        return clean(stem) + rest
    }

    private fun uniqueTarget(dir: File, name: String, ext: String): File {
        var target = File(dir, name + ext)
        var counter = 1
        while (target.exists()) {
            target = File(dir, "$name ($counter)$ext")
            counter++
        }
        return target
    }

    private fun rename(file: File, newName: String): File {
        if (newName == file.name) return file
        val stem = newName.substringBeforeLast('.', newName)
        val target = uniqueTarget(file.parentFile!!, stem, newName.removePrefix(stem))
        return if (file.renameTo(target)) target else file
    }

    /** Cleans every file and folder name inside [dir] (not [dir] itself). */
    fun cleanFolder(dir: File) {
        dir.walkBottomUp()
            .filter { it != dir }
            .forEach {
                runCatching {
                    rename(it, if (it.isDirectory) clean(it.name) else cleanFileName(it.name))
                }
            }
    }

    /**
     * Cleans the name of a downloaded file and of the files next to it that share its name,
     * like its thumbnail or subtitles. Returns the new path of [path].
     */
    fun cleanDownloadedFile(path: String): String {
        val file = File(path)
        val dir = file.parentFile ?: return path
        if (!file.exists()) return path
        val stem = file.nameWithoutExtension
        val cleanStem = clean(stem)
        if (cleanStem == stem) return path

        var result = path
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$stem.") }
            ?.forEach {
                runCatching {
                    val renamed = rename(it, cleanFileName(it.name, stem))
                    if (it.absolutePath == file.absolutePath) result = renamed.absolutePath
                }
            }
        return result
    }
}
