package com.tricore.demolab

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 逐行安全渲染：每一行都必须以某种形式显示出来，绝不吞内容。
 * 公式只在“单行完整闭合”时识别；跨行公式找不到闭合就按原文显示。
 */
private sealed interface Line {
    data class Plain(val text: String, val kind: String = "p") : Line
    data class Code(val lang: String, val body: String) : Line
    data class Formula(val latex: String) : Line
    data object Gap : Line
}

private fun parseLines(raw: String): List<Line> {
    val out = mutableListOf<Line>()
    val lines = normalizeMathDelimiters(raw).split("\n")
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trim()

        // fenced code
        if (t.startsWith("```")) {
            val lang = t.removePrefix("```").trim()
            i++
            val body = StringBuilder()
            while (i < lines.size && !lines[i].trim().startsWith("```")) {
                body.appendLine(lines[i]); i++
            }
            if (i < lines.size) i++ // closing fence
            out.add(Line.Code(lang, body.toString().trimEnd()))
            continue
        }

        // display formula, single line and closed
        if (t.startsWith("$$") && t.endsWith("$$") && t.length > 2) {
            out.add(Line.Formula(t.removePrefix("$$").removeSuffix("$$")))
            i++
            continue
        }

        // bracketed LaTeX that fits on one line: [ ... ]
        if (t.length > 2 && t.startsWith("[") && t.endsWith("]") && t.contains("\\")) {
            out.add(Line.Formula(t.substring(1, t.length - 1)))
            i++
            continue
        }

        // multi-line $$ block: only when a closing $$ exists before any blank line
        if (t == "$$") {
            var j = i + 1
            var close = -1
            while (j < lines.size) {
                val l = lines[j].trim()
                if (l.isEmpty()) break
                if (l == "$$") { close = j; break }
                if (l.endsWith("$$")) { close = j; break }
                j++
            }
            if (close > 0) {
                val body = StringBuilder()
                for (k in i + 1 until close) body.appendLine(lines[k])
                var tail = lines[close].trim()
                if (tail != "$$") body.appendLine(tail.removeSuffix("$$").trim())
                out.add(Line.Formula(body.toString().trim()))
                i = close + 1
                continue
            }
            // 没有闭合：当作普通文字，保证不丢
            out.add(Line.Plain("$$"))
            i++
            continue
        }

        if (t.isEmpty()) { out.add(Line.Gap); i++; continue }

        val kind = when {
            t.startsWith("### ") -> "h3"
            t.startsWith("## ") -> "h2"
            t.startsWith("# ") -> "h1"
            t.startsWith("> ") -> "quote"
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("·") -> "bullet"
            Regex("^\\d+[.)、]\\s+.*").matches(t) -> "num"
            else -> "p"
        }
        val body = when (kind) {
            "h1" -> t.removePrefix("# ")
            "h2" -> t.removePrefix("## ")
            "h3" -> t.removePrefix("### ")
            "quote" -> t.removePrefix("> ")
            "bullet" -> t.removePrefix("- ").removePrefix("* ").removePrefix("·").trim()
            "num" -> t.replaceFirst(Regex("^(\\d+)[.)、]\\s+"), "$1. ")
            else -> t
        }
        out.add(Line.Plain(body, kind))
        i++
    }
    return out
}

@Composable
fun SafeMarkdownText(raw: String, modifier: Modifier = Modifier) {
    val lines = remember(raw) { parseLines(raw) }
    Column(modifier) {
        lines.forEach { line ->
            when (line) {
                is Line.Gap -> Spacer(Modifier.height(5.dp))
                is Line.Code -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color(0xFF0A1220))
                        .padding(9.dp)
                ) {
                    Column {
                        if (line.lang.isNotBlank()) {
                            Text(line.lang, color = P.dim, fontSize = 8.sp)
                            Spacer(Modifier.height(3.dp))
                        }
                        Box(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
                            Text(line.body, color = Color(0xFF9FE8B8), fontSize = 10.sp,
                                lineHeight = 14.sp, fontFamily = FontFamily.Monospace)
                        }
                    }
                }
                is Line.Formula -> Box(
                    Modifier.fillMaxWidth().padding(vertical = 5.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Color.White.copy(alpha = 0.045f))
                        .padding(vertical = 8.dp, horizontal = 6.dp)
                ) { MathText(line.latex, size = 14.5f) }
                is Line.Plain -> InlineLine(line)
            }
        }
    }
}

@Composable
private fun InlineLine(line: Line.Plain) {
    val (size, weight) = when (line.kind) {
        "h1" -> 14.5f to FontWeight.Bold
        "h2" -> 13.5f to FontWeight.SemiBold
        "h3" -> 12.5f to FontWeight.SemiBold
        else -> 11.5f to FontWeight.Normal
    }
    val prefix = when (line.kind) {
        "bullet" -> "•  "
        "quote" -> "│  "
        else -> ""
    }
    val text = prefix + inlineHighlight(line.text)
    Text(
        text,
        color = if (line.kind == "quote") P.dim else P.text,
        fontSize = size.sp,
        lineHeight = (size * 1.55f).sp,
        fontWeight = weight,
        fontStyle = FontStyle.Normal,
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
    )
}

/**
 * 行内公式：$...$ / \(...\) / [ ... ] 提取为 Unicode 文本混排；其余标记去掉但保留所有文字。
 */
private fun inlineHighlight(src: String): String {
    val sb = StringBuilder()
    var i = 0
    while (i < src.length) {
        // $ ... $
        if (src[i] == '$') {
            val end = src.indexOf('$', i + 1)
            if (end > i + 1) {
                sb.append(latexInlineToUnicode(src.substring(i + 1, end)))
                i = end + 1
                continue
            }
        }
        // [ ... ] containing a backslash
        if (src[i] == '[') {
            val end = src.indexOf(']', i + 1)
            if (end > i + 1 && src.substring(i + 1, end).contains('\\')) {
                sb.append(latexInlineToUnicode(src.substring(i + 1, end)))
                i = end + 1
                continue
            }
        }
        // ** bold markers, `code` ticks: drop the markers, keep the words
        if (src.startsWith("**", i)) { i += 2; continue }
        if (src[i] == '`') { i += 1; continue }
        sb.append(src[i])
        i++
    }
    return sb.toString()
}
