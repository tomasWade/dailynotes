package com.dailynotes.app.diary

import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class DiaryEntry(
    val fileName: String,
    val date: LocalDate,
    val preview: String,
    val firstImage: String?,
    val truncated: Boolean,
)

/**
 * 日记文件纯逻辑解析：
 * - 文件名 diary/YYYY-MM-DD.md（本地日期）
 * - 预览：图片语法折叠为 [图]，剥行内 md 标记，按 code points 截 300（中文/emoji 安全）
 * - 首图：正文中第一个 attachments 引用（根相对路径）
 */
object DiaryParser {

    const val PREVIEW_CODEPOINTS = 300

    private val DATE_RE = Regex("""^(\d{4})-(\d{2})-(\d{2})\.md$""")
    private val IMAGE_RE = Regex("""!\[[^\]]*\]\(([^)\s]+)\)""")
    private val LINK_RE = Regex("""\[([^\]]*)\]\([^)]*\)""")

    private val WEEKDAYS = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    fun isDiary(fileName: String): Boolean = DATE_RE.matches(fileName)

    fun parseDate(fileName: String): LocalDate? =
        DATE_RE.find(fileName)?.destructured?.let { (y, m, d) ->
            runCatching { LocalDate.of(y.toInt(), m.toInt(), d.toInt()) }.getOrNull()
        }

    fun fileNameOf(date: LocalDate): String =
        date.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".md"

    fun dateLabel(date: LocalDate): String {
        val today = LocalDate.now()
        val base = "%d月%d日 · %s".format(date.monthValue, date.dayOfMonth, WEEKDAYS[date.dayOfWeek.value - 1])
        return when (date) {
            today -> "今天 · $base"
            today.minusDays(1) -> "昨天 · $base"
            else -> base
        }
    }

    fun parse(fileName: String, content: String): DiaryEntry? {
        val date = parseDate(fileName) ?: return null
        val firstImage = IMAGE_RE.findAll(content)
            .map { it.groupValues[1] }
            .firstOrNull { "attachments/" in it }
            ?.let(::toRootRelative)

        val cleaned = stripMarkdown(content)
        val (preview, truncated) = truncateCodePoints(cleaned, PREVIEW_CODEPOINTS)
        return DiaryEntry(fileName, date, preview, firstImage, truncated)
    }

    /** 正文里引用形如 ../attachments/2026-10/x.jpg 或 attachments/...，统一为根相对路径 */
    fun toRootRelative(ref: String): String =
        ref.removePrefix("../").removePrefix("./").trimStart('/')

    /** 剥离用于预览的 md 标记：图片→[图]、链接保留文字、去强调/代码/标题符号、空白合一 */
    fun stripMarkdown(src: String): String {
        var s = src.replace(IMAGE_RE, "[图]")
        s = s.replace(LINK_RE, "$1")
        s = s.replace(Regex("""^\s{0,3}#+\s*""", RegexOption.MULTILINE), "")
        s = s.replace(Regex("""[*_`>#~-]"""), "")
        s = s.replace(Regex("""\s+"""), " ")
        return s.trim()
    }

    /** code points 截断（不拆 emoji 代理对），返回截断文本与是否截断 */
    fun truncateCodePoints(src: String, limit: Int): Pair<String, Boolean> {
        if (src.isEmpty()) return "" to false
        val sb = StringBuilder()
        var count = 0
        var truncated = false
        for (cp in src.codePoints()) {
            if (count >= limit) {
                truncated = true
                break
            }
            sb.appendCodePoint(cp)
            count++
        }
        return sb.toString() to truncated
    }

    /** 正文里引用的附件（根相对路径，去重）；非 attachments 的链接忽略 */
    fun attachmentRefs(text: String): List<String> =
        Regex("""\]\(([^)]*attachments/[^)\s]+)\)""")
            .findAll(text)
            .map { toRootRelative(it.groupValues[1]) }
            .distinct()
            .toList()

    /** 连续写作天数：今天没写则从昨天起算（都断了返回 0） */
    fun streak(dates: Set<LocalDate>): Int {
        var d = LocalDate.now()
        if (d !in dates) d = d.minusDays(1)
        var n = 0
        while (d in dates) {
            n++
            d = d.minusDays(1)
        }
        return n
    }
}
