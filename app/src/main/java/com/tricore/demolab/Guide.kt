package com.tricore.demolab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Prompt-template workbench: pick a template, a book, a chapter and a level,
 * then copy the assembled prompt straight into any LLM.
 */
@Composable
fun TemplatesScreen(
    data: AppData,
    initialChapter: String? = null,
    onConsumed: () -> Unit = {},
    onSendToAi: (String) -> Unit = {},
    topInset: androidx.compose.ui.unit.Dp = 0.dp,
) {
    val clipboard = LocalClipboardManager.current
    var templateId by remember { mutableStateOf(data.templates.firstOrNull()?.id ?: "T1") }
    var bookId by remember { mutableStateOf(data.books.firstOrNull()?.id ?: "B01") }
    var chapterId by remember { mutableStateOf<String?>(null) }
    var level by remember { mutableStateOf("A") }
    var picker by remember { mutableStateOf<String?>(null) }   // "book" | "chapter"
    var copied by remember { mutableStateOf(false) }

    androidx.compose.runtime.LaunchedEffect(initialChapter) {
        val id = initialChapter ?: return@LaunchedEffect
        data.chaptersById[id]?.let { c ->
            bookId = c.book
            chapterId = c.id
            level = c.demos.firstOrNull()?.level ?: "A"
            onConsumed()
        }
    }

    val template = data.templates.firstOrNull { it.id == templateId }
    val book = data.booksById[bookId]
    val chapters = data.chaptersByBook[bookId].orEmpty()
    val chapter = chapterId?.let { data.chaptersById[it] } ?: chapters.firstOrNull()
    val demo = chapter?.demos?.firstOrNull { it.level == level } ?: chapter?.demos?.firstOrNull()

    val prompt = buildTemplatePrompt(data, template, book, chapter, demo, level)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
            .padding(top = topInset)
    ) {
        Text(
            "用法：选模板 → 选教材 → 选章节 → 选等级 → 复制提示词，粘到任意大模型即可生成练习。",
            color = P.muted, fontSize = 10.5.sp, lineHeight = 15.sp,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        // templates, stacked vertically so every option is visible
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            data.templates.forEach { item ->
                val sel = item.id == templateId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (sel) P.ai.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f))
                        .border(
                            1.dp,
                            if (sel) P.ai.copy(alpha = 0.52f) else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(14.dp)
                        )
                        .clickable { templateId = item.id }
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        Modifier
                            .width(3.dp)
                            .height(28.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (sel) P.ai else Color.White.copy(alpha = 0.14f))
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            item.name,
                            color = if (sel) P.text else P.muted,
                            fontSize = 12.sp,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal
                        )
                        Text(
                            item.desc,
                            color = P.dim,
                            fontSize = 9.5.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (sel) Text("●", color = P.ai, fontSize = 10.sp)
                }
            }
        }

        if (template != null) {
            Spacer(Modifier.height(8.dp))
            Text(template.desc, color = P.dim, fontSize = 10.5.sp, lineHeight = 15.sp)
        }

        Spacer(Modifier.height(12.dp))
        // selectors
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Selector(
                label = "教材",
                value = book?.title ?: "选择",
                modifier = Modifier.weight(1.25f),
            ) { picker = "book" }
            Selector(
                label = "章节",
                value = chapter?.let { "${it.n} ${it.title}" } ?: "选择",
                modifier = Modifier.weight(1.3f),
            ) { picker = "chapter" }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("A" to "概念验证", "B" to "工程实现", "C" to "跨方向").forEach { (lv, name) ->
                val sel = lv == demo?.level
                Box(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (sel) P.ai.copy(alpha = 0.05f) else Color.Transparent)
                        .border(1.dp, if (sel) P.ai.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(12.dp))
                        .clickable { level = lv }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text("$lv · $name", color = if (sel) P.text else P.dim, fontSize = 10.5.sp)
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        // preview
        GlassCard(Modifier.fillMaxWidth().heightIn(min = 220.dp), corner = 20) {
            Column(Modifier.fillMaxSize().padding(12.dp)) {
                Text("提示词预览", color = P.muted, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(prompt, color = P.text, fontSize = 11.5.sp, lineHeight = 17.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(18.dp))
                    .background(P.ai.copy(alpha = 0.9f))
                    .clickable {
                        clipboard.setText(AnnotatedString(prompt))
                        Sfx.click(pitch = 1.12f)
                        copied = true
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (copied) "已复制 ✓" else "复制提示词", color = Color(0xFF06101A),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
            Box(
                Modifier
                    .clip(RoundedCornerShape(18.dp))
                    .background(P.ai.copy(alpha = 0.18f))
                    .border(1.dp, P.ai.copy(alpha = 0.4f), RoundedCornerShape(18.dp))
                    .clickable { onSendToAi(prompt) }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("发送到 AI", color = P.text, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(96.dp))
    }

    if (picker != null) {
        Dialog(onDismissRequest = { picker = null }) {
            GlassCard(Modifier.fillMaxWidth().heightIn(max = 520.dp), corner = 22) {
                Column(Modifier.padding(14.dp)) {
                    Text(if (picker == "book") "选择教材" else "选择章节", color = P.text,
                        fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    LazyColumn(Modifier.fillMaxWidth()) {
                        if (picker == "book") {
                            items(data.books) { b ->
                                PickerRow(
                                    title = "${b.id}  ${b.title}",
                                    subtitle = "${b.dir} · ${b.chapterCount} 章",
                                    selected = b.id == bookId,
                                ) {
                                    bookId = b.id
                                    chapterId = data.chaptersByBook[b.id]?.firstOrNull()?.id
                                    picker = null
                                }
                            }
                        } else {
                            items(chapters) { c ->
                                PickerRow(
                                    title = "${c.n}　${c.title}",
                                    subtitle = c.points.take(3).joinToString(" · "),
                                    selected = c.id == chapter?.id,
                                ) {
                                    chapterId = c.id
                                    picker = null
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Selector(label: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(label, color = P.dim, fontSize = 9.5.sp)
        Spacer(Modifier.height(2.dp))
        Text(value, color = P.text, fontSize = 11.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun PickerRow(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) P.ai.copy(alpha = 0.16f) else Color.Transparent)
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Text(title, color = P.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(subtitle, color = P.dim, fontSize = 9.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Fills a template's placeholders with the chosen book / chapter / demo. */
fun buildTemplatePrompt(
    data: AppData,
    template: PromptTemplate?,
    book: Book?,
    chapter: Chapter?,
    demo: Demo?,
    level: String,
): String {
    if (template == null || book == null || chapter == null || demo == null) {
        return "先选择模板、教材与章节，这里会显示可直接复制的提示词。"
    }
    val prior = buildString {
        append("已完成本方向基础课程（《")
        append(data.layers.firstOrNull { it.id == book.layer }?.label ?: "基础")
        append("》相关章节）")
        val earlier = data.chaptersByBook[book.id].orEmpty().filter { it.n < chapter.n }.take(3)
        if (earlier.isNotEmpty()) {
            append("；本书已学：")
            append(earlier.joinToString("、") { "第 ${it.n} 章 ${it.title}" })
        }
    }
    return template.prompt
        .replace("{book}", book.title)
        .replace("{n}", chapter.n.toString())
        .replace("{chapter}", chapter.title)
        .replace("{prior}", prior)
        .replace("{task}", demo.task)
        .replace("{level}", level)
}
