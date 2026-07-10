package au.com.chrismckechnie.hermesmobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownParserTest {
    @Test
    fun `extracts fenced code blocks with language`() {
        val blocks = parseMarkdownBlocks("Run this:\n```bash\ncurl -X POST http://host\n```\nDone.")

        assertEquals(3, blocks.size)
        assertEquals(MarkdownBlock.Paragraph("Run this:"), blocks[0])
        assertEquals(MarkdownBlock.Code("bash", "curl -X POST http://host"), blocks[1])
        assertEquals(MarkdownBlock.Paragraph("Done."), blocks[2])
    }

    @Test
    fun `unclosed fence keeps content as code`() {
        val blocks = parseMarkdownBlocks("```python\nprint('hi')")

        assertEquals(listOf<MarkdownBlock>(MarkdownBlock.Code("python", "print('hi')")), blocks)
    }

    @Test
    fun `parses headings and bullets`() {
        val blocks = parseMarkdownBlocks("## Plan\n- first\n- second\ntext")

        assertEquals(MarkdownBlock.Heading(2, "Plan"), blocks[0])
        assertEquals(MarkdownBlock.Bullet("first"), blocks[1])
        assertEquals(MarkdownBlock.Bullet("second"), blocks[2])
        assertEquals(MarkdownBlock.Paragraph("text"), blocks[3])
    }

    @Test
    fun `blank lines split paragraphs`() {
        val blocks = parseMarkdownBlocks("one\ntwo\n\nthree")

        assertEquals(listOf<MarkdownBlock>(MarkdownBlock.Paragraph("one\ntwo"), MarkdownBlock.Paragraph("three")), blocks)
    }

    @Test
    fun `inline tokens for bold code and links`() {
        val tokens = parseInlineMarkdown("Use `ls` and **be careful** with [docs](https://example.com) now")

        assertEquals("Use ", tokens[0].text)
        assertTrue(tokens[1].code)
        assertEquals("ls", tokens[1].text)
        assertTrue(tokens[3].bold)
        assertEquals("be careful", tokens[3].text)
        assertEquals("https://example.com", tokens[5].linkUrl)
        assertEquals("docs", tokens[5].text)
        assertEquals(" now", tokens[6].text)
    }

    @Test
    fun `plain text passes through as single token`() {
        val tokens = parseInlineMarkdown("nothing fancy here")

        assertEquals(1, tokens.size)
        assertEquals("nothing fancy here", tokens.single().text)
    }
}
