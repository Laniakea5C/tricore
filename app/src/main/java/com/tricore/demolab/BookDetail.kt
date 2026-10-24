package com.tricore.demolab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Small direction tag shown on books, chapters and courses. */
@Composable
fun DirTag(dir: String, compact: Boolean = false) {
    val name = when (dir) {
        "ai" -> "AI"
        "chip" -> "芯片"
        "robot" -> "机器人"
        else -> "基础"
    }
    Box(
        Modifier
            .clip(RoundedCornerShape(7.dp))
            .background(P.dir(dir).copy(alpha = 0.16f))
            .border(1.dp, P.dir(dir).copy(alpha = 0.35f), RoundedCornerShape(7.dp))
            .padding(horizontal = if (compact) 4.dp else 6.dp, vertical = 1.dp)
    ) {
        Text(name, color = P.dir(dir), fontSize = if (compact) 8.sp else 9.sp)
    }
}

@Composable
fun TagRow(tags: List<String>, modifier: Modifier = Modifier) {
    if (tags.isEmpty()) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        val shared = tags.size >= 3
        if (shared) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color.White.copy(alpha = 0.10f))
                    .padding(horizontal = 6.dp, vertical = 1.dp)
            ) { Text("共同基础", color = P.text, fontSize = 9.sp) }
        }
        tags.forEach { DirTag(it, compact = shared) }
    }
}

/** A book's own page: chapters can be marked studied, and the prompt workbench is one tap away. */
@Composable
fun BookDetailScreen(
    data: AppData,
    store: ProgressStore,
    bookId: String,
    onBack: () -> Unit,
    onOpenChapter: (String) -> Unit,
) {
    val book = data.booksById[bookId] ?: return
    val chapters = data.chaptersByBook[bookId].orEmpty()
    val done = chapters.count { store.isChapterDone(it) }
    val accent = P.dir(book.dir)

    androidx.activity.compose.BackHandler { onBack() }

    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 10.dp, bottom = 140.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            GlassCard(Modifier.fillMaxWidth(), corner = 24) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("‹ 返回", color = P.ai, fontSize = 11.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { onBack() }
                                .padding(horizontal = 6.dp, vertical = 4.dp))
                        Spacer(Modifier.weight(1f))
                        Text("$done/${chapters.size} 章", color = P.dim, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(book.title, color = P.text, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${book.id}　${book.edition}", color = P.dim, fontSize = 10.sp)
                    }
                    Spacer(Modifier.height(6.dp))
                    TagRow(book.tags)
                    if (book.read.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text("读法：${book.read}", color = P.muted, fontSize = 9.5.sp, lineHeight = 14.sp)
                    }
                    Spacer(Modifier.height(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(accent.copy(alpha = 0.16f))
                            .border(1.dp, accent.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                            .clickable { chapters.firstOrNull()?.let { onOpenChapter(it.id) } }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("打开 Demo 模板生成器", color = P.text, fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        item {
            Text("章节 · 点圆圈标记学习，点章节进入模板", color = P.dim, fontSize = 10.sp,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp))
        }

        items(chapters.size) { i ->
            val ch = chapters[i]
            val isDone = store.isChapterDone(ch)
            GlassCard(Modifier.fillMaxWidth(), corner = 16) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(22.dp)
                            .clip(CircleShape)
                            .background(if (isDone) accent.copy(alpha = 0.92f) else Color.White.copy(alpha = 0.08f))
                            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                            .clickable { Sfx.click(pitch = if (isDone) 0.9f else 1.12f); store.toggleChapter(ch) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(if (isDone) "✓" else "", color = Color(0xFF06101A), fontSize = 12.sp,
                            fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(
                        Modifier
                            .weight(1f)
                            .clickable { onOpenChapter(ch.id) }
                    ) {
                        Text("${ch.n}　${ch.title}", color = if (isDone) P.text else P.muted,
                            fontSize = 12.sp, fontWeight = FontWeight.Medium,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(ch.points.take(3).joinToString(" · "), color = P.dim, fontSize = 9.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Spacer(Modifier.width(6.dp))
                    Text("${store.doneInChapter(ch)}/3", color = P.dim, fontSize = 9.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("›", color = accent, fontSize = 15.sp)
                }
            }
        }
    }
}
