package com.tricore.demolab

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Lightweight Markdown renderer for assistant messages:
 * fenced code blocks, headings, lists, quotes, inline code, bold/italic, links.
 * Deliberately dependency-free (no WebView, no markdown library).
 */
@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier) {
    val uri = LocalUriHandler.current
    val normalized = normalizeMathDelimiters(text)
    val blocks = rememberBlocks(normalized)
    val kept = blocks.sumOf { it.text.length }
    if (normalized.length > 240 && kept < normalized.length * 0.55) {
        // parser swallowed content — never hide text, fall back to plain paragraphs
        Text(normalized, color = P.text, fontSize = 11.5.sp, lineHeight = 17.sp, modifier = modifier)
        return
    }
    Column(modifier) {
        blocks.forEach { block ->
            when (block.kind) {
                "code" -> {
                    val label = block.lang.ifBlank { "code" }
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF0A1220))
                            .padding(10.dp)
                    ) {
                        Text(label, color = P.dim, fontSize = 8.sp)
                        Spacer(Modifier.padding(top = 3.dp))
                        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            Text(
                                block.text, color = Color(0xFF9FE8B8),
                                fontSize = 10.sp, lineHeight = 14.sp,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                }
                "table" -> {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                    ) {
                        block.text.lines().filter { it.isNotBlank() }.forEachIndexed { i, line ->
                            val cells = line.trim().trim('|').split('|').map { it.trim() }
                            if (cells.all { c -> c.isEmpty() || c.all { it == '-' || it == ':' } }) return@forEachIndexed
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                                cells.forEach { c ->
                                    Text(
                                        c, color = if (i == 0) P.text else P.muted,
                                        fontSize = 9.5.sp, lineHeight = 13.sp,
                                        fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal,
                                        modifier = Modifier.weight(1f, fill = false).padding(end = 8.dp),
                                    )
                                }
                            }
                        }
                    }
                }
                "math" -> {
                    if (block.text.isNotBlank()) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 6.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color.White.copy(alpha = 0.045f))
                                .padding(vertical = 9.dp, horizontal = 8.dp)
                        ) {
                            MathText(block.text, size = 15f)
                        }
                    }
                }
                else -> {
                    if (block.text.contains("$")) {
                        val parts = block.text.split(Regex("(?=\$)|(?<=\$)"))
                        // simple: render text pieces and $...$ runs separately
                        val segments = mutableListOf<Pair<Boolean, String>>()
                        var inMath = false
                        val cur = StringBuilder()
                        for (ch in block.text) {
                            if (ch == '$') {
                                if (cur.isNotEmpty()) { segments.add(inMath to cur.toString()); cur.clear() }
                                inMath = !inMath
                                continue
                            }
                            cur.append(ch)
                        }
                        if (cur.isNotEmpty()) segments.add(inMath to cur.toString())
                        segments.forEach { (math, txt) ->
                            if (math) {
                                Text(
                                    latexInlineToUnicode(txt),
                                    color = P.text, fontSize = 11.5.sp,
                                    lineHeight = 17.sp,
                                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            } else if (txt.isNotBlank()) {
                                val fs = when (block.kind) {
                                    "h1" -> 14f
                                    "h2" -> 13f
                                    "h3" -> 12f
                                    "quote" -> 11f
                                    else -> 11.5f
                                }
                                Text(
                                    txt, color = if (block.kind == "quote") P.dim else P.text,
                                    fontSize = fs.sp, lineHeight = (fs * 1.5f).sp,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                        @Suppress("UNUSED_VARIABLE") val unused = parts
                        return@Column
                    }
                    val style = when (block.kind) {
                        "h1" -> 14.sp to FontWeight.Bold
                        "h2" -> 13.sp to FontWeight.SemiBold
                        "h3" -> 12.sp to FontWeight.SemiBold
                        else -> 11.5.sp to FontWeight.Normal
                    }
                    val prefix = when (block.kind) {
                        "bullet" -> "•  "
                        "num" -> "${block.number}.  "
                        "quote" -> "│  "
                        else -> ""
                    }
                    val color = when (block.kind) {
                        "quote" -> P.dim
                        else -> P.text
                    }
                    val annotated = inline(block.text, uri)
                    Text(
                        text = if (prefix.isEmpty()) annotated else AnnotatedString(prefix) + annotated,
                        color = color, fontSize = style.first, lineHeight = (style.first.value * 1.5f).sp,
                        fontWeight = style.second,
                        modifier = Modifier.fillMaxWidth().padding(vertical = if (block.kind.startsWith("h")) 4.dp else 2.dp),
                    )
                }
            }
        }
    }
}

private data class Block(
    val kind: String,
    val text: String,
    val lang: String = "",
    val number: Int = 1,
)

private fun rememberBlocks(text: String): List<Block> = buildList {
    val lines = text.replace("\r\n", "\n").split("\n")
    var i = 0
    val para = StringBuilder()
    fun flush() {
        if (para.isNotBlank()) add(Block("p", para.toString().trim()))
        para.clear()
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            isMathBlock(line) -> {
                flush()
                var body = mathBlockContent(line)
                i++
                // multi-line $$ ... $$
                if (!line.trim().endsWith("$$") && !line.trim().endsWith("\\]")) {
                    val sb = StringBuilder(body)
                    while (i < lines.size) {
                        val l = lines[i]
                        i++
                        if (l.trim().endsWith("$$") || l.trim().endsWith("\\]")) {
                            sb.append(" ").append(mathBlockContent(l)); break
                        }
                        sb.append(" ").append(l)
                    }
                    body = sb.toString()
                }
                add(Block("math", body))
            }
            line.trimStart().startsWith("```") -> {
                flush()
                val lang = line.trim().removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].trimStart().startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                add(Block("code", code.toString().trimEnd(), lang))
            }
            line.startsWith("|") && i + 1 < lines.size && lines[i + 1].contains("-") -> {
                flush()
                val table = StringBuilder()
                while (i < lines.size && lines[i].startsWith("|")) { table.appendLine(lines[i]); i++ }
                add(Block("table", table.toString().trimEnd()))
                continue
            }
            line.startsWith("### ") -> { flush(); add(Block("h3", line.removePrefix("### "))) }
            line.startsWith("## ") -> { flush(); add(Block("h2", line.removePrefix("## "))) }
            line.startsWith("# ") -> { flush(); add(Block("h1", line.removePrefix("# "))) }
            line.startsWith("> ") || line == ">" -> { flush(); add(Block("quote", line.removePrefix("> "))) }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") ->
                { flush(); add(Block("bullet", line.trimStart().substring(2))) }
            Regex("^\\s*\\d+[.)]\\s+.*").matches(line) -> {
                flush()
                val n = Regex("^\\s*(\\d+)").find(line)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                add(Block("num", line.replaceFirst(Regex("^\\s*\\d+[.)]\\s+"), ""), number = n))
            }
            line.isBlank() -> flush()
            else -> { if (para.isNotEmpty()) para.append("\n"); para.append(line) }
        }
        i++
    }
    flush()
}

private fun inline(text: String, uri: androidx.compose.ui.platform.UriHandler): AnnotatedString =
    buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            when {
                text.startsWith("`", i) -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) {
                        pushStyle(SpanStyle(fontFamily = FontFamily.Monospace,
                            background = Color(0xFF13202E), color = Color(0xFF9FE8B8)))
                        append(text.substring(i + 1, end))
                        pop()
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i + 2) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold)); append(text.substring(i + 2, end)); pop()
                        i = end + 2
                    } else { append(text[i]); i++ }
                }
                text.startsWith("[", i) -> {
                    val close = text.indexOf("](", i)
                    val end = if (close > 0) text.indexOf(')', close + 2) else -1
                    if (close > 0 && end > close) {
                        val label = text.substring(i + 1, close)
                        pushStyle(SpanStyle(color = P.ai, textDecoration = TextDecoration.Underline)); append(label); pop()
                        i = end + 1
                    } else { append(text[i]); i++ }
                }
                else -> { append(text[i]); i++ }
            }
        }
    }
