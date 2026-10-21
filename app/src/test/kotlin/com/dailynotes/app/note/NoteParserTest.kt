package com.dailynotes.app.note

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NoteParserTest {

    @Test
    fun `文件名格式校验`() {
        assertTrue(NoteParser.isNote("20261021T183015-a1b2.md"))
        assertFalse(NoteParser.isNote("2026-10-21.md"))      // 那是日记
        assertFalse(NoteParser.isNote("随便.md"))
        assertFalse(NoteParser.isNote("20261021T183015-A1B2.md"))  // 大写 hex 不合法
        assertFalse(NoteParser.isNote("20261021T183015-a1b2.txt"))
    }

    @Test
    fun `生成名符合格式且可排序`() {
        val a = NoteParser.generateName()
        assertTrue(NoteParser.isNote(a), "生成名应通过校验: $a")
        // 字符串序 = 时间序（前 15 位是时间戳）
        assertTrue("20260101T000000-0000.md" < "20261231T235959-ffff.md")
    }

    @Test
    fun `解析出预览与首图`() {
        val e = NoteParser.parse(
            "20261021T183015-a1b2.md",
            "买牛奶 ![小票](../attachments/2026-10/x.jpg)",
        )!!
        assertEquals("买牛奶 [图]", e.preview)
        assertEquals("attachments/2026-10/x.jpg", e.firstImage)
    }

    @Test
    fun `非法文件名返回 null`() {
        assertNull(NoteParser.parse("其他.md", "内容"))
    }

    @Test
    fun `长文截断标记`() {
        val e = NoteParser.parse("20261021T183015-a1b2.md", "字".repeat(500))!!
        assertTrue(e.truncated)
        assertEquals(300, e.preview.codePoints().count())
    }
}
