package com.dailynotes.app.diary

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DiaryParserTest {

    @Test
    fun `文件名日期解析`() {
        assertTrue(DiaryParser.isDiary("2026-10-21.md"))
        assertFalse(DiaryParser.isDiary("2026-10-2.md"))
        assertFalse(DiaryParser.isDiary("note.md"))
        assertNull(DiaryParser.parseDate("note.md"))
        assertEquals(LocalDate.of(2026, 10, 21), DiaryParser.parseDate("2026-10-21.md"))
        // 2026-13-99 日期非法应返回 null 而不是崩溃
        assertNull(DiaryParser.parseDate("2026-13-99.md"))
    }

    @Test
    fun `纯中文截断三百字`() {
        val content = "记".repeat(400)
        val e = DiaryParser.parse("2026-10-21.md", content)!!
        assertEquals(300, e.preview.codePoints().count())
        assertTrue(e.truncated)
    }

    @Test
    fun `emoji 不被拆成乱码`() {
        // "😀" 是一个 code point（两个 char）
        val content = buildString { repeat(350) { append("😀") } }
        val e = DiaryParser.parse("2026-10-21.md", content)!!
        assertEquals(300, e.preview.codePoints().count())
        assertTrue(e.preview.endsWith("😀"), "末尾应是完整 emoji，不应出现半个代理对")
    }

    @Test
    fun `短文不截断`() {
        val e = DiaryParser.parse("2026-10-21.md", "今天很好")!!
        assertEquals("今天很好", e.preview)
        assertFalse(e.truncated)
    }

    @Test
    fun `图片语法折叠为图标记`() {
        val content = "开头\n![照片](../attachments/2026-10/a.jpg)\n结尾"
        val e = DiaryParser.parse("2026-10-21.md", content)!!
        assertEquals("开头 [图] 结尾", e.preview)
        assertEquals("attachments/2026-10/a.jpg", e.firstImage)
    }

    @Test
    fun `多图取首图且路径归一`() {
        val content = "![x](attachments/2026-09/b.png) 文字 ![y](../attachments/2026-10/c.jpg)"
        val e = DiaryParser.parse("2026-10-21.md", content)!!
        assertEquals("attachments/2026-09/b.png", e.firstImage)
    }

    @Test
    fun `外链图片不算首图`() {
        val content = "![外链](https://example.com/a.png)"
        val e = DiaryParser.parse("2026-10-21.md", content)!!
        assertNull(e.firstImage)
    }

    @Test
    fun `链接剥壳保留文字`() {
        val e = DiaryParser.parse("2026-10-21.md", "见 [文档](https://a.b/c) 了")!!
        assertEquals("见 文档 了", e.preview)
    }

    @Test
    fun `标题与强调符号被剥掉`() {
        val e = DiaryParser.parse("2026-10-21.md", "# 标题\n**加粗**和*斜体*以及`代码`")!!
        assertEquals("标题 加粗和斜体以及代码", e.preview)
    }

    @Test
    fun `空文件预览为空`() {
        val e = DiaryParser.parse("2026-10-21.md", "")!!
        assertEquals("", e.preview)
        assertFalse(e.truncated)
    }

    @Test
    fun `非法文件名返回 null`() {
        assertNull(DiaryParser.parse("随便.md", "内容"))
    }

    @Test
    fun `连续天数今天写了`() {
        val today = LocalDate.now()
        val dates = setOf(today, today.minusDays(1), today.minusDays(2))
        assertEquals(3, DiaryParser.streak(dates))
    }

    @Test
    fun `连续天数今天没写从昨天起算`() {
        val today = LocalDate.now()
        val dates = setOf(today.minusDays(1), today.minusDays(2))
        assertEquals(2, DiaryParser.streak(dates))
    }

    @Test
    fun `连续天数断了就是零`() {
        assertEquals(0, DiaryParser.streak(emptySet()))
        assertEquals(0, DiaryParser.streak(setOf(LocalDate.now().minusDays(3))))
    }
}
