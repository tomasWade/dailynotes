package com.dailynotes.app.note

import com.dailynotes.app.diary.DiaryParser
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class NoteEntry(
    val fileName: String,
    val preview: String,
    val firstImage: String?,
    val truncated: Boolean,
)

/**
 * 速记笔记：notes/YYYYMMDDTHHMMSS-xxxx.md，每条一个文件。
 * 预览/首图逻辑与日记一致（复用 DiaryParser 的纯函数）。
 */
object NoteParser {

    private val NAME_RE = Regex("""^\d{8}T\d{6}-[0-9a-f]{4}\.md$""")

    fun isNote(fileName: String): Boolean = NAME_RE.matches(fileName)

    /** 生成本地时间命名的新笔记文件名（同秒碰撞由 4 位随机后缀兜底） */
    fun generateName(now: LocalDateTime = LocalDateTime.now()): String {
        val stamp = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss"))
        val rand = List(4) { "0123456789abcdef".random() }.joinToString("")
        return "$stamp-$rand.md"
    }

    fun parse(fileName: String, content: String): NoteEntry? {
        if (!isNote(fileName)) return null
        val firstImage = Regex("""!\[[^\]]*\]\(([^)\s]+)\)""")
            .findAll(content)
            .map { it.groupValues[1] }
            .firstOrNull { "attachments/" in it }
            ?.let(DiaryParser::toRootRelative)
        val cleaned = DiaryParser.stripMarkdown(content)
        val (preview, truncated) = DiaryParser.truncateCodePoints(cleaned, DiaryParser.PREVIEW_CODEPOINTS)
        return NoteEntry(fileName, preview, firstImage, truncated)
    }
}
