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
    // (every bracket escaped: Android's ICU regex engine rejects a bare "]" or "}")
    private val emptyBrackets = Regex("""\(\s*\)|\[\s*\]|\{\s*\}|【\s*】|「\s*」""")
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

    private val templateField = Regex("""%\((title|uploader)\)(?:\.(\d+)B|s)""")

    /**
     * Puts the cleaned title and author straight into a yt-dlp output template, so the file
     * gets a clean name from the start: "%(uploader).30B - %(title).170B" becomes
     * "Zeynep Bastık - Zeynep Bastık - Lan Lyric Video". Blank values keep their field.
     */
    fun fillTemplate(template: String, title: String, author: String): String {
        return template.replace(templateField) { m ->
            val value = when (m.groupValues[1]) {
                "title" -> title
                else -> author
            }
            if (value.isBlank()) return@replace m.value
            var cleaned = clean(value)
            m.groupValues[2].toIntOrNull()?.let { maxBytes -> cleaned = cleaned.takeBytes(maxBytes).trim() }
            cleaned.replace("%", "%%")
        }
    }

    private val titleField = Regex("""%\(title\)(?:\.\d+B|s)""")

    /** Puts [replacement], a yt-dlp output template of its own, in place of the template's title. */
    fun replaceTitleField(template: String, replacement: String): String =
        template.replace(titleField) { replacement }

    private fun String.takeBytes(maxBytes: Int): String {
        if (toByteArray(Charsets.UTF_8).size <= maxBytes) return this
        val sb = StringBuilder()
        var size = 0
        var i = 0
        while (i < length) {
            val cp = codePointAt(i)
            val chars = String(Character.toChars(cp))
            size += chars.toByteArray(Charsets.UTF_8).size
            if (size > maxBytes) break
            sb.append(chars)
            i += Character.charCount(cp)
        }
        return sb.toString()
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
    /**
     * Cut or cropped downloads get a number: "Title" becomes "Title_1", or "Title_2" when the
     * destination already has a "Title_1", even when it has no plain "Title". The files next to
     * a video that share its name (thumbnail, subtitles) get the same number.
     * [existingNames] are the file names already in the destination.
     */
    fun numberFolder(dir: File, existingNames: Collection<String>) {
        val taken = existingNames.map { it.substringBeforeLast('.') }.toMutableSet()
        val files = dir.walkTopDown().filter { it.isFile }.toList()
        // shortest names first: "Title" is numbered before (and together with) "Title.en"
        val stems = files.map { it.nameWithoutExtension }.distinct().sortedBy { it.length }
        val numbered = mutableListOf<String>()
        for (stem in stems) {
            if (numbered.any { stem.startsWith("$it.") }) continue
            var n = 1
            while ("${stem}_$n" in taken) n++
            val newStem = "${stem}_$n"
            taken.add(newStem)
            numbered.add(stem)
            files.filter { it.name.startsWith("$stem.") }.forEach {
                runCatching { it.renameTo(File(it.parentFile, newStem + it.name.removePrefix(stem))) }
            }
        }
    }

    /** [numberFolder] for a file already saved in its destination; returns its new path. */
    fun numberDownloadedFile(path: String): String {
        val file = File(path)
        val dir = file.parentFile ?: return path
        if (!file.exists()) return path
        val stem = file.nameWithoutExtension
        val taken = (dir.list()?.toList() ?: emptyList())
            .filterNot { it.startsWith("$stem.") }
            .map { it.substringBeforeLast('.') }
            .toSet()
        var n = 1
        while ("${stem}_$n" in taken) n++
        val newStem = "${stem}_$n"

        var result = path
        dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith("$stem.") }
            ?.forEach {
                val target = File(dir, newStem + it.name.removePrefix(stem))
                if (!target.exists() && it.renameTo(target) && it.absolutePath == file.absolutePath) {
                    result = target.absolutePath
                }
            }
        return result
    }
}
