package au.com.chrismckechnie.hermesmobile

// ponytail: minimal markdown support (fences, headings, bullets, bold/italic/inline
// code/links). Swap for a full renderer library if tables/images become necessary.

sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock
    data class Heading(val level: Int, val text: String) : MarkdownBlock
    data class Bullet(val text: String) : MarkdownBlock
    data class Code(val language: String?, val code: String) : MarkdownBlock
}

fun parseMarkdownBlocks(raw: String): List<MarkdownBlock> {
    val blocks = mutableListOf<MarkdownBlock>()
    val paragraph = StringBuilder()
    var codeLanguage: String? = null
    var code: StringBuilder? = null

    fun flushParagraph() {
        val text = paragraph.toString().trim()
        paragraph.clear()
        if (text.isNotEmpty()) blocks += MarkdownBlock.Paragraph(text)
    }

    raw.lines().forEach { line ->
        val fence = line.trimStart()
        when {
            fence.startsWith("```") -> {
                val open = code == null
                if (open) {
                    flushParagraph()
                    codeLanguage = fence.removePrefix("```").trim().ifBlank { null }
                    code = StringBuilder()
                } else {
                    blocks += MarkdownBlock.Code(codeLanguage, code.toString().trimEnd('\n'))
                    code = null
                    codeLanguage = null
                }
            }
            code != null -> code?.append(line)?.append('\n')
            line.isBlank() -> flushParagraph()
            line.matches(Regex("^#{1,6}\\s+.*")) -> {
                flushParagraph()
                val level = line.takeWhile { it == '#' }.length
                blocks += MarkdownBlock.Heading(level, line.dropWhile { it == '#' }.trim())
            }
            line.matches(Regex("^\\s*[-*]\\s+.*")) -> {
                flushParagraph()
                blocks += MarkdownBlock.Bullet(line.trimStart().drop(1).trim())
            }
            else -> {
                if (paragraph.isNotEmpty()) paragraph.append('\n')
                paragraph.append(line)
            }
        }
    }
    // Unclosed fence: keep the content as code rather than dropping it.
    code?.let { blocks += MarkdownBlock.Code(codeLanguage, it.toString().trimEnd('\n')) }
    flushParagraph()
    return blocks
}

data class InlineToken(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val linkUrl: String? = null,
)

private val inlinePattern = Regex(
    "(`[^`]+`)" +                       // `code`
        "|(\\*\\*[^*]+\\*\\*)" +        // **bold**
        "|(\\*[^*\\s][^*]*\\*)" +       // *italic*
        "|(\\[[^\\]]+\\]\\([^)\\s]+\\))" // [label](url)
)

fun parseInlineMarkdown(text: String): List<InlineToken> {
    val tokens = mutableListOf<InlineToken>()
    var cursor = 0
    inlinePattern.findAll(text).forEach { match ->
        if (match.range.first > cursor) tokens += InlineToken(text.substring(cursor, match.range.first))
        val value = match.value
        tokens += when {
            value.startsWith("`") -> InlineToken(value.trim('`'), code = true)
            value.startsWith("**") -> InlineToken(value.removeSurrounding("**"), bold = true)
            value.startsWith("[") -> {
                val label = value.substringAfter('[').substringBefore(']')
                val url = value.substringAfter('(').substringBeforeLast(')')
                InlineToken(label, linkUrl = url)
            }
            else -> InlineToken(value.removeSurrounding("*"), italic = true)
        }
        cursor = match.range.last + 1
    }
    if (cursor < text.length) tokens += InlineToken(text.substring(cursor))
    return tokens
}
