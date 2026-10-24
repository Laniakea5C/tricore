package com.tricore.demolab

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Native LaTeX subset renderer (no WebView, no images, no dependencies).
 * Blocks: frac, sqrt, ^{}, _{}, greek/symbols, sum/prod/int/lim with stacked
 * limits, and matrix / pmatrix / bmatrix / vmatrix / cases environments.
 * Inline: converted to unicode by latexInlineToUnicode so it flows in text.
 */
private sealed interface MNode {
    data class Txt(val s: String, val italic: Boolean = false) : MNode
    data class Row(val items: List<MNode>) : MNode
    data class Sup(val base: MNode, val exp: MNode) : MNode
    data class Sub(val base: MNode, val sub: MNode) : MNode
    data class Frac(val num: MNode, val den: MNode) : MNode
    data class Sqrt(val body: MNode) : MNode
    data class TextBox(val s: String) : MNode
    data class Big(val sym: String, var low: MNode? = null, var up: MNode? = null) : MNode
    data class Matrix(val rows: List<List<MNode>>, val style: String) : MNode
    data class Cases(val rows: List<List<MNode>>) : MNode
}

private val SYMBOLS = mapOf(
    "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε",
    "varepsilon" to "ε", "zeta" to "ζ", "eta" to "η", "theta" to "θ", "iota" to "ι",
    "kappa" to "κ", "lambda" to "λ", "mu" to "μ", "nu" to "ν", "xi" to "ξ", "pi" to "π",
    "rho" to "ρ", "sigma" to "σ", "tau" to "τ", "upsilon" to "υ", "phi" to "φ",
    "varphi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
    "Gamma" to "Γ", "Delta" to "Δ", "Theta" to "Θ", "Lambda" to "Λ", "Xi" to "Ξ",
    "Pi" to "Π", "Sigma" to "Σ", "Phi" to "Φ", "Psi" to "Ψ", "Omega" to "Ω",
    "times" to "×", "cdot" to "·", "div" to "÷", "pm" to "±", "mp" to "∓",
    "le" to "≤", "leq" to "≤", "ge" to "≥", "geq" to "≥", "neq" to "≠", "ne" to "≠",
    "approx" to "≈", "equiv" to "≡", "sim" to "∼", "propto" to "∝",
    "infty" to "∞", "partial" to "∂", "nabla" to "∇",
    "to" to "→", "rightarrow" to "→", "leftarrow" to "←", "Rightarrow" to "⇒",
    "Leftarrow" to "⇐", "iff" to "⇔", "leftrightarrow" to "↔",
    "in" to "∈", "notin" to "∉", "subset" to "⊂", "subseteq" to "⊆",
    "cup" to "∪", "cap" to "∩", "forall" to "∀", "exists" to "∃", "emptyset" to "∅",
    "angle" to "∠", "perp" to "⊥", "parallel" to "∥", "degree" to "°",
    "ldots" to "…", "cdots" to "⋯", "dots" to "…", "vdots" to "⋮", "ddots" to "⋱",
    "quad" to "  ", "qquad" to "    ", " " to " ", "," to " ", ";" to " ", ":" to " ",
    "!" to "", "{" to "{", "}" to "}", "%" to "%", "&" to "&", "#" to "#", "_" to "_",
    "sin" to "sin", "cos" to "cos", "tan" to "tan", "cot" to "cot", "sec" to "sec",
    "log" to "log", "ln" to "ln", "exp" to "exp", "lim" to "lim", "max" to "max",
    "min" to "min", "arg" to "arg", "det" to "det", "gcd" to "gcd",
    "displaystyle" to "", "textstyle" to "", "scriptstyle" to "", "scriptscriptstyle" to "",
    "limits" to "", "nolimits" to "", "mathstrut" to "", "left" to "", "right" to "",
    "big" to "", "Big" to "", "bigg" to "", "Bigg" to "", "bigl" to "", "bigr" to "",
)

private val BIG_OPS = mapOf(
    "sum" to "∑", "prod" to "∏", "coprod" to "∐", "int" to "∫", "iint" to "∬",
    "iiint" to "∭", "oint" to "∮", "bigcup" to "⋃", "bigcap" to "⋂",
    "lim" to "lim", "max" to "max", "min" to "min", "sup" to "sup", "inf" to "inf",
    "argmax" to "argmax", "argmin" to "argmin",
)

private class MathParser(private val src: String) {
    private var i = 0

    fun parse(): MNode = row(stop = null)

    private fun row(stop: Char?): MNode {
        val items = mutableListOf<MNode>()
        while (i < src.length) {
            val c = src[i]
            if (stop != null && c == stop) { i++; break }
            if (c == '}') break
            when {
                c == '\\' -> {
                    val node = command()
                    if (node != null) items.add(node)
                }
                c == '{' -> { i++; items.add(row('}')) }
                c == '^' -> {
                    i++
                    val exp = scriptArg()
                    val base = items.removeLastOrNull()
                    if (base is MNode.Big) { base.up = exp; items.add(base) }
                    else items.add(MNode.Sup(base ?: MNode.Txt(""), exp))
                }
                c == '_' -> {
                    i++
                    val sub = scriptArg()
                    val base = items.removeLastOrNull()
                    if (base is MNode.Big) { base.low = sub; items.add(base) }
                    else items.add(MNode.Sub(base ?: MNode.Txt(""), sub))
                }
                c.isWhitespace() -> { items.add(MNode.Txt(" ")); i++ }
                else -> {
                    val start = i
                    while (i < src.length && !"\\^_{} ".contains(src[i])) i++
                    items.add(MNode.Txt(src.substring(start, i), italic = src[start].isLetter()))
                }
            }
        }
        return if (items.size == 1) items[0] else MNode.Row(items)
    }

    private fun scriptArg(): MNode {
        if (i >= src.length) return MNode.Txt("")
        return if (src[i] == '{') { i++; row('}') } else { MNode.Txt(src[i++].toString(), italic = true) }
    }

    private fun command(): MNode? {
        i++
        val start = i
        while (i < src.length && src[i].isLetter()) i++
        val name = src.substring(start, i)
        return when (name) {
            "frac", "dfrac", "tfrac" -> MNode.Frac(group(), group())
            "sqrt" -> {
                if (i < src.length && src[i] == '[') {
                    while (i < src.length && src[i] != ']') i++
                    if (i < src.length) i++
                }
                MNode.Sqrt(group())
            }
            "text", "mathrm", "operatorname", "textbf" -> MNode.TextBox(rawGroup())
            "begin" -> environment(rawGroup().trim())
            "end" -> null
            "left", "right" -> {
                if (i < src.length && "()[]|.".contains(src[i])) {
                    val ch = src[i]; i++
                    if (ch == '.') null else MNode.Txt(ch.toString())
                } else null
            }
            "" -> null
            else -> {
                if (BIG_OPS.containsKey(name)) MNode.Big(BIG_OPS.getValue(name))
                else {
                    val sym = SYMBOLS[name]
                    if (sym != null) {
                        if (sym.isEmpty()) null else MNode.Txt(sym)
                    } else MNode.Txt(name)
                }
            }
        }
    }

    private fun environment(env: String): MNode? {
        val endTag = "\\end{$env}"
        val endIdx = src.indexOf(endTag, i)
        if (endIdx < 0) return MNode.Txt(env)
        val raw = src.substring(i, endIdx)
        i = endIdx + endTag.length
        val rows = raw.split("\\\\").filter { it.isNotBlank() }.map { line ->
            line.split("&").map { cell -> MathParser(cell.trim()).parse() }
        }
        return when (env) {
            "cases" -> MNode.Cases(rows)
            "matrix", "pmatrix", "bmatrix", "vmatrix", "Bmatrix", "array" -> MNode.Matrix(rows, env)
            else -> MNode.TextBox(raw)
        }
    }

    private fun group(): MNode {
        if (i < src.length && src[i] == '{') { i++; return row('}') }
        if (i < src.length) return MNode.Txt(src[i++].toString())
        return MNode.Txt("")
    }

    private fun rawGroup(): String {
        if (i < src.length && src[i] == '{') {
            i++
            val start = i
            var depth = 1
            while (i < src.length && depth > 0) {
                if (src[i] == '{') depth++
                if (src[i] == '}') depth--
                if (depth > 0) i++
            }
            val s = src.substring(start, i)
            if (i < src.length) i++
            return s
        }
        return ""
    }
}

private fun <T> MutableList<T>.removeLastOrNull(): T? =
    if (isEmpty()) null else removeAt(size - 1)

@Composable
fun MathText(latex: String, modifier: Modifier = Modifier, size: Float = 14f, display: Boolean = true) {
    val node = remember(latex, display) {
        runCatching { MathParser(latex.trim()).parse() }.getOrElse { MNode.Txt(latex) }
    }
    val blank = (node is MNode.Txt && node.s.isBlank()) || latex.isBlank()
    if (blank && latex.isNotBlank()) {
        Text(latex, color = P.muted, fontSize = (size * 0.85f).sp)
        return
    }
    if (display) {
        Box(
            modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            contentAlignment = Alignment.Center
        ) { MathNodeView(node, size, true) }
    } else {
        MathNodeView(node, size, false)
    }
}

@Composable
private fun MathNodeView(node: MNode, size: Float, display: Boolean = true) {
    when (node) {
        is MNode.Txt -> Text(
            node.s, color = P.text, fontSize = size.sp,
            fontStyle = if (node.italic) FontStyle.Italic else FontStyle.Normal,
            lineHeight = (size * 1.35f).sp,
        )
        is MNode.TextBox -> Text(node.s, color = P.text, fontSize = size.sp)
        is MNode.Row -> Row(verticalAlignment = Alignment.CenterVertically) {
            node.items.forEach { MathNodeView(it, size, display) }
        }
        is MNode.Sup -> Row(verticalAlignment = Alignment.Top) {
            MathNodeView(node.base, size, display)
            Box(Modifier.offset(y = (-size * 0.35f).dp)) { MathNodeView(node.exp, size * 0.66f, display) }
        }
        is MNode.Sub -> Row(verticalAlignment = Alignment.Bottom) {
            MathNodeView(node.base, size, display)
            Box(Modifier.offset(y = (size * 0.18f).dp)) { MathNodeView(node.sub, size * 0.66f, display) }
        }
        is MNode.Frac -> Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 2.dp)
        ) {
            Box(Modifier.padding(bottom = 1.dp)) { MathNodeView(node.num, size * 0.82f, display) }
            Box(Modifier.fillMaxWidth().height(1.dp).background(P.text.copy(alpha = 0.75f)))
            Box(Modifier.padding(top = 1.dp)) { MathNodeView(node.den, size * 0.82f, display) }
        }
        is MNode.Sqrt -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("√", color = P.text, fontSize = (size * 1.2f).sp)
            Column {
                Box(Modifier.fillMaxWidth().height(1.dp).background(P.text.copy(alpha = 0.7f)))
                Box(Modifier.padding(horizontal = 2.dp)) { MathNodeView(node.body, size, display) }
            }
        }
        is MNode.Big -> {
            if (display) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    node.up?.let { MathNodeView(it, size * 0.62f, display) }
                    Text(node.sym, color = P.text, fontSize = (size * 1.45f).sp)
                    node.low?.let { MathNodeView(it, size * 0.62f, display) }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(node.sym, color = P.text, fontSize = size.sp)
                    node.low?.let { Box(Modifier.offset(y = (size * 0.2f).dp)) { MathNodeView(it, size * 0.6f, false) } }
                    node.up?.let { Box(Modifier.offset(y = (-size * 0.3f).dp)) { MathNodeView(it, size * 0.6f, false) } }
                }
            }
        }
        is MNode.Matrix -> {
            val left = when (node.style) { "pmatrix" -> "("; "bmatrix" -> "["; "vmatrix" -> "|"; "Bmatrix" -> "{"; else -> "" }
            val right = when (node.style) { "pmatrix" -> ")"; "bmatrix" -> "]"; "vmatrix" -> "|"; "Bmatrix" -> "}"; else -> "" }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (left.isNotEmpty()) Text(left, color = P.text, fontSize = (size * 2.1f).sp)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(horizontal = 4.dp)
                ) {
                    node.rows.forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(size.dp * 0.9f)) {
                            row.forEach { cell ->
                                Box(Modifier.padding(horizontal = 3.dp)) { MathNodeView(cell, size, display) }
                            }
                        }
                    }
                }
                if (right.isNotEmpty()) Text(right, color = P.text, fontSize = (size * 2.1f).sp)
            }
        }
        is MNode.Cases -> Row(verticalAlignment = Alignment.CenterVertically) {
            Text("{", color = P.text, fontSize = (size * 2.4f).sp)
            Spacer(Modifier.width(3.dp))
            Column {
                node.rows.forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(size.dp * 0.8f)) {
                        row.forEach { cell -> MathNodeView(cell, size, display) }
                    }
                }
            }
        }
    }
}

/** Inline math → unicode, so it truly flows inside a paragraph. */
fun latexInlineToUnicode(latex: String): String {
    var s = latex.trim()
    s = s.replace("\\left", "").replace("\\right", "")
    s = s.replace("\\displaystyle", "").replace("\\textstyle", "")
    val frac = Regex("\\\\[dt]?frac\\{([^{}]*)\\}\\{([^{}]*)\\}")
    while (frac.containsMatchIn(s)) s = frac.replace(s, "($1)/($2)")
    val sqrt = Regex("\\\\sqrt\\{([^{}]*)\\}")
    while (sqrt.containsMatchIn(s)) s = sqrt.replace(s, "√($1)")
    s = s.replace("\\,", " ").replace("\\;", " ").replace("\\!", "").replace("\\quad", "  ")
    s = s.replace("\\begin{cases}", "").replace("\\end{cases}", "")
    s = s.replace("\\\\", " ")
    s = s.replace("&", "  ")
    val cmd = Regex("\\\\([A-Za-z]+)")
    s = cmd.replace(s) { m ->
        val name = m.groupValues[1]
        val sym = SYMBOLS[name]
        if (sym != null) sym else name
    }
    s = s.replace("{", "").replace("}", "")
    val supMap = mapOf(
        '0' to '⁰', '1' to '¹', '2' to '²', '3' to '³', '4' to '⁴', '5' to '⁵',
        '6' to '⁶', '7' to '⁷', '8' to '⁸', '9' to '⁹', '-' to '⁻', 'n' to 'ⁿ',
        'i' to 'ⁱ', 'x' to 'ˣ',
    )
    val subMap = mapOf(
        '0' to '₀', '1' to '₁', '2' to '₂', '3' to '₃', '4' to '₄', '5' to '₅',
        '6' to '₆', '7' to '₇', '8' to '₈', '9' to '₉', 'i' to 'ᵢ', 'j' to 'ⱼ',
        'n' to 'ₙ', 'x' to 'ₓ', '+' to '₊', '-' to '₋',
    )
    val script = Regex("([\\^_])([A-Za-z0-9+\\-])")
    s = script.replace(s) { m ->
        val map = if (m.groupValues[1] == "^") supMap else subMap
        map[m.groupValues[2].first()]?.toString() ?: m.groupValues[2]
    }
    s = s.replace("^", "↑").replace("_", "")
    return s.trim()
}

/** True when the line opens/closes a display math block. */
fun isMathBlock(line: String): Boolean {
    val t = line.trim()
    return t == "$$" || (t.startsWith("$$") && t.endsWith("$$") && t.length > 2)
}

fun mathBlockContent(line: String): String {
    val t = line.trim()
    return when {
        t == "$$" -> ""
        t.startsWith("$$") && t.endsWith("$$") -> t.removePrefix("$$").removeSuffix("$$")
        else -> t
    }
}

/** Normalise the LaTeX delimiters models usually emit. */
fun normalizeMathDelimiters(text: String): String {
    val bs = 92.toChar().toString()
    return text.replace(bs + "[", "$$").replace(bs + "]", "$$")
        .replace(bs + "(", "$").replace(bs + ")", "$")
}
