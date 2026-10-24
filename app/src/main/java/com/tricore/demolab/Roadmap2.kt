package com.tricore.demolab

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

private val DIRS = listOf("ai", "chip", "robot")
private val STAGE_ORDER = listOf(
    "大一上", "大一下", "大二上", "大二下", "大三上", "大三下", "大四上", "大四下",
    "研一", "研二", "博士", "业界 Q1–Q2", "业界 Q3–Q4"
)

private fun dirName(data: AppData, dir: String) =
    data.directions.firstOrNull { it.id == dir }?.name ?: dir

private fun booksOf(data: AppData, step: RouteStep): List<Book> =
    Regex("B\\d{2}").findAll(step.books).mapNotNull { data.booksById[it.value] }.toList()

@Composable
fun RouteScreen(
    data: AppData,
    store: ProgressStore,
    onOpenChapter: (String) -> Unit,
    onOpenBook: (String) -> Unit,
) {
    var mode by remember { mutableStateOf("dir") }
    var dir by remember { mutableStateOf("ai") }
    var openTool by remember { mutableStateOf<String?>(null) }
    var projectDetail by remember { mutableStateOf<Pair<String, String>?>(null) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    val baseRoute = data.routes.firstOrNull { it.title.contains("本科前两年") }
    val laterRoute = data.routes.firstOrNull { it.title.contains("硕士") }
    val dirRoute = data.routes.firstOrNull {
        (dir == "ai" && it.title.contains("AI")) ||
            (dir == "chip" && it.title.contains("芯片")) ||
            (dir == "robot" && it.title.contains("机器人"))
    }

    val stageMap = linkedMapOf<String, MutableList<Book>>()
    data.routes.forEach { route ->
        route.steps.forEach { step ->
            val bucket = stageMap.getOrPut(step.title) { mutableListOf() }
            booksOf(data, step).forEach { b -> if (bucket.none { it.id == b.id }) bucket.add(b) }
        }
    }
    val commonBooks = data.books.filter { it.tags.size >= 3 }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            GlassCard(Modifier.fillMaxWidth(), corner = 22) {
                Column {
                    Text("学习路线图", color = P.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "按方向：先选方向，只看这个方向要学的课；按阶段：看同一学期三个方向一起要学什么。",
                        color = P.dim, fontSize = 9.5.sp, lineHeight = 14.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        modeChip("按方向", mode == "dir") { mode = "dir" }
                        modeChip("按阶段", mode == "stage") { mode = "stage" }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (mode == "dir") {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            DIRS.forEach { d ->
                                modeChip(dirName(data, d), dir == d) {
                                    dir = d
                                    scope.launch { listState.animateScrollToItem(1) }
                                }
                            }

                        }
                    } else {
                        Row(
                            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            STAGE_ORDER.filter { stageMap.containsKey(it) }.forEachIndexed { i, st ->
                                tocChip(st) { scope.launch { listState.animateScrollToItem(i + 1) } }
                            }
                        }
                    }
                }
            }
        }

        if (mode == "dir") {
            run {
                item {
                    GlassCard(Modifier.fillMaxWidth(), corner = 22) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(dirName(data, dir), color = P.dir(dir), fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold)
                                Spacer(Modifier.width(8.dp))
                                TagRow(listOf(dir))
                                Spacer(Modifier.weight(1f))
                                Text("${dirRoute?.steps?.size ?: 0} 个阶段", color = P.dim, fontSize = 9.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(dirRoute?.sub ?: "方向课程按学期推进", color = P.dim, fontSize = 10.sp)
                        }
                    }
                }
                item {
                    GlassCard(Modifier.fillMaxWidth(), corner = 22) {
                        Column {
                            Text("共同基础（三个方向都要学）", color = P.text, fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text("数学、编程、工程素养，先修完再进入方向课。", color = P.dim, fontSize = 9.sp)
                            Spacer(Modifier.height(6.dp))
                            commonBooks.forEach { courseRow(it, data, store, onOpenBook, stage = baseStage(it, data)) }
                        }
                    }
                }
                dirRoute?.steps?.forEach { step ->
                    item {
                        GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                            Column {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(8.dp))
                                            .background(P.dir(dir).copy(alpha = 0.16f))
                                            .padding(horizontal = 8.dp, vertical = 3.dp)
                                    ) { Text(step.title, color = P.dir(dir), fontSize = 9.5.sp) }
                                    Spacer(Modifier.width(8.dp))
                                    Text(step.goal, color = P.text, fontSize = 11.5.sp)
                                }
                                step.books.split("、").mapNotNull { data.booksById[it.trim()] }
                                    .forEach { courseRow(it, data, store, onOpenBook, stage = null) }
                                if (step.books.split("、").mapNotNull { data.booksById[it.trim()] }.isEmpty()) {
                                    Text(step.books, color = P.dim, fontSize = 10.sp,
                                        modifier = Modifier.padding(top = 6.dp))
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("产出：${step.output}", color = P.dim, fontSize = 9.sp, lineHeight = 13.sp)
                            }
                        }
                    }
                }
            }
            laterRoute?.steps?.forEach { step ->
                item {
                    GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                        .background(Color(0xFF8FA3B8).copy(alpha = 0.16f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) { Text(step.title, color = Color(0xFFB8C6D6), fontSize = 9.5.sp) }
                                Spacer(Modifier.width(8.dp))
                                Text(step.goal, color = P.text, fontSize = 11.5.sp)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text("周投入：${step.weekly}", color = P.muted, fontSize = 9.sp)
                            Text("产出：${step.output}", color = P.dim, fontSize = 9.sp, lineHeight = 13.sp)
                            step.books.split("、").mapNotNull { data.booksById[it.trim()] }
                                .forEach { courseRow(it, data, store, onOpenBook, stage = step.title) }
                        }
                    }
                }
            }
            // toolchain for this direction
            val tools = data.tools.filter { it.dir == dir }
            if (tools.isNotEmpty()) {
                item {
                    GlassCard(Modifier.fillMaxWidth(), corner = 22) {
                        Column {
                            Text("方向工具链", color = P.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text("点开看学习建议；工具对应书在「工具」页", color = P.dim, fontSize = 9.sp)
                            Spacer(Modifier.height(6.dp))
                            tools.forEach { tool ->
                                val open = openTool == tool.name
                                Column(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .clickable { openTool = if (open) null else tool.name }
                                        .padding(horizontal = 10.dp, vertical = 7.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(tool.name, color = P.text, fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                                        Text(tool.level, color = P.dir(dir), fontSize = 8.5.sp)
                                    }
                                    Text(tool.note, color = P.dim, fontSize = 9.sp, maxLines = if (open) 6 else 1,
                                        overflow = TextOverflow.Ellipsis)
                                    if (open && tool.detail.isNotBlank()) {
                                        Spacer(Modifier.height(3.dp))
                                        Text(tool.detail, color = P.muted, fontSize = 9.5.sp, lineHeight = 14.sp)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // industry accumulation projects
            val industry = data.industry.firstOrNull { it.dir == dir }
            if (industry != null) {
                item {
                    GlassCard(Modifier.fillMaxWidth(), corner = 22) {
                        Column {
                            Text("业界积累项目", color = P.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text("简历与面试里真正被看的东西，点开看具体解释", color = P.dim, fontSize = 9.sp)
                            Spacer(Modifier.height(6.dp))
                            (industry.portfolio.ifEmpty { industry.must }).forEachIndexed { i, text ->
                                val det = industry.portfolioDetail.getOrNull(i)
                                    ?: industry.mustDetail.getOrNull(i)
                                    ?: industry.detail
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(Color.White.copy(alpha = 0.04f))
                                        .clickable { projectDetail = text to det }
                                        .padding(horizontal = 10.dp, vertical = 7.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(text, color = P.muted, fontSize = 10.5.sp,
                                        lineHeight = 14.sp, modifier = Modifier.weight(1f))
                                    Text("›", color = P.muted, fontSize = 14.sp)
                                }
                            }
                        }
                    }
                }
            }
        } else {
            STAGE_ORDER.filter { stageMap.containsKey(it) }.forEach { stage ->
                item {
                    GlassCard(Modifier.fillMaxWidth(), corner = 22) {
                        Column {
                            Text(stage, color = P.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(2.dp))
                            Text("这一阶段三个方向一起要学的课程", color = P.dim, fontSize = 9.sp)
                            Spacer(Modifier.height(6.dp))
                            val stageSteps = data.routes.flatMap { it.steps }.filter { it.title == stage }
                            stageSteps.firstOrNull()?.let { st ->
                                Text("目标：${st.goal}", color = P.muted, fontSize = 9.5.sp)
                                Text("周投入：${st.weekly}", color = P.dim, fontSize = 9.sp)
                                Text("产出：${st.output}", color = P.dim, fontSize = 9.sp, lineHeight = 13.sp)
                                Spacer(Modifier.height(6.dp))
                            }
                            stageMap[stage].orEmpty().forEach { courseRow(it, data, store, onOpenBook, stage = null) }
                            val stageBookIds = stageMap[stage].orEmpty().map { it.id }
                            stageSteps.drop(1).forEach { st ->
                                val extra = Regex("B\\d{2}").findAll(st.books).map { it.value }
                                    .filter { it !in stageBookIds }.toList()
                                if (extra.isNotEmpty()) {
                                    Text("补充：${extra.joinToString("、")}（${st.goal}）",
                                        color = P.dim, fontSize = 9.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun baseStage(book: Book, data: AppData): String {
    val step = data.routes.filter { it.title.contains("本科前两年") }
        .flatMap { it.steps }
        .firstOrNull { step -> book.id in Regex("B\\d{2}").findAll(step.books).map { it.value } }
    return step?.title.orEmpty()
}

@Composable
private fun modeChip(text: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) P.ai.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
            .border(1.dp, if (selected) P.ai.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.10f),
                RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(text, color = if (selected) P.text else P.muted, fontSize = 11.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable
private fun tocChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) { Text(text, color = P.muted, fontSize = 10.sp, maxLines = 1) }
}

@Composable
private fun routeCard(
    route: Route,
    data: AppData,
    store: ProgressStore,
    accent: Color,
    onOpenBook: (String) -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth(), corner = 24) {
        Column {
            Text(route.title, color = P.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(route.sub, color = P.dim, fontSize = 10.5.sp)
            Spacer(Modifier.height(6.dp))
            route.steps.forEach { step ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp))
                                .background(accent.copy(alpha = 0.6f))
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${step.title}　${step.goal}", color = P.text, fontSize = 12.sp,
                                fontWeight = FontWeight.Medium)
                            Text("周投入：${step.weekly}", color = P.muted, fontSize = 9.5.sp)
                            Text("产出：${step.output}", color = P.dim, fontSize = 9.5.sp, lineHeight = 13.sp)
                        }
                    }
                    booksOf(data, step).forEach { courseRow(it, data, store, onOpenBook) }
                }
            }
        }
    }
}

@Composable
private fun courseRow(
    book: Book,
    data: AppData,
    store: ProgressStore,
    onOpenBook: (String) -> Unit,
    stage: String? = null,
) {
    val chapters = data.chaptersByBook[book.id].orEmpty()
    val done = chapters.count { store.isChapterDone(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .clickable { onOpenBook(book.id) }
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${book.id}　${book.title}", color = P.text, fontSize = 11.5.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!stage.isNullOrBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        Modifier.clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.08f))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) { Text(stage, color = P.muted, fontSize = 8.sp) }
                }
            }
            Spacer(Modifier.height(3.dp))
            TagRow(book.tags)
            Spacer(Modifier.height(2.dp))
            Text("$done/${chapters.size} 章已学　·　${book.edition}", color = P.dim,
                fontSize = 8.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text("›", color = P.muted, fontSize = 16.sp)
    }
}

@Composable
fun ProgressV2Screen(
    data: AppData,
    store: ProgressStore,
    onOpenChapter: (String) -> Unit,
    onOpenBook: (String) -> Unit,
) {
    var openBook by remember { mutableStateOf<String?>(null) }
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    var backupText by remember { mutableStateOf("") }
    var showImport by remember { mutableStateOf(false) }
    var showReset by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            GlassCard(Modifier.fillMaxWidth(), corner = 22) {
                Column {
                    Text("学习进度", color = P.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text("点「标记 / 已学」切换整章状态；点章节文字进入提示词模板；点书名看教材详情。",
                        color = P.dim, fontSize = 9.5.sp)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("导出", "导入", "重置").forEach { label ->
                            Box(
                                Modifier.clip(RoundedCornerShape(9.dp))
                                    .background(Color.White.copy(alpha = 0.07f))
                                    .clickable {
                                        when (label) {
                                            "导出" -> {
                                                backupText = store.exportText()
                                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(backupText))
                                                status = "已导出到剪贴板（${store.done.size} 条）"
                                            }
                                            "导入" -> showImport = true
                                            else -> showReset = true
                                        }
                                    }
                                    .padding(horizontal = 10.dp, vertical = 5.dp)
                            ) { Text(label, color = P.muted, fontSize = 9.5.sp) }
                        }
                    }
                    if (status.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(status, color = P.dim, fontSize = 8.5.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        (DIRS + "base").forEach { dir ->
                            val (d, t) = store.doneByDir(data, dir)
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                ProgressRing(
                                    progress = if (t == 0) 0f else d.toFloat() / t,
                                    color = P.dir(dir), size = 52, stroke = 4f,
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(dirName(data, dir), color = P.muted, fontSize = 9.sp)
                                Text("$d/$t", color = P.dim, fontSize = 8.sp)
                            }
                        }
                    }
                }
            }
        }

        (DIRS + "base").forEach { dir ->
            val books = data.books.filter { it.dir == dir }
            if (books.isEmpty()) return@forEach
            item {
                Text(dirName(data, dir), color = P.dir(dir), fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp, top = 4.dp))
            }
            items(books.size) { bi ->
                val book = books[bi]
                val chapters = data.chaptersByBook[book.id].orEmpty()
                val isOpen = openBook == book.id
                val doneChapters = chapters.count { store.isChapterDone(it) }
                GlassCard(Modifier.fillMaxWidth(), corner = 18) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).clickable { onOpenBook(book.id) }) {
                                Text("${book.id}　${book.title}", color = P.text, fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(3.dp))
                                TagRow(book.tags)
                                Spacer(Modifier.height(3.dp))
                                Text("$doneChapters/${chapters.size} 章已完成", color = P.dim, fontSize = 9.sp)
                            }
                            Text(
                                if (isOpen) "收起" else "展开",
                                color = P.ai, fontSize = 10.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(P.ai.copy(alpha = 0.14f))
                                    .clickable { openBook = if (isOpen) null else book.id }
                                    .padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                        if (isOpen) {
                            Spacer(Modifier.height(8.dp))
                            chapters.forEach { ch ->
                                val done = store.isChapterDone(ch)
                                Row(
                                    Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .padding(horizontal = 4.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        "${ch.n}　${ch.title}",
                                        color = if (done) P.text else P.muted,
                                        fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f).clickable { onOpenChapter(ch.id) }
                                    )
                                    TagRow(ch.tags.take(3), Modifier.padding(horizontal = 4.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(
                                        if (done) "已学 ✓" else "标记",
                                        color = if (done) P.ai else P.muted, fontSize = 9.sp,
                                        modifier = Modifier
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(
                                                if (done) P.ai.copy(alpha = 0.16f)
                                                else Color.White.copy(alpha = 0.07f)
                                            )
                                            .clickable {
                                                Sfx.click(pitch = if (done) 0.9f else 1.12f)
                                                store.toggleChapter(ch)
                                            }
                                            .padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (showReset) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showReset = false }) {
            GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                Column(Modifier.padding(16.dp)) {
                    Text("重置全部进度？", color = P.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("会清空所有已学的章节标记，且不可恢复。建议先导出备份。", color = P.dim, fontSize = 10.5.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color(0xFFFF6B6B).copy(alpha = 0.9f))
                            .clickable { store.reset(); showReset = false; status = "已重置" }
                            .padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text("确认重置", color = Color(0xFF1A0606), fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))
                            .clickable { showReset = false }.padding(horizontal = 14.dp, vertical = 8.dp)) {
                            Text("取消", color = P.muted, fontSize = 11.sp)
                        }
                    }
                }
            }
        }
    }

    if (showImport) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { showImport = false }) {
            GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                Column(Modifier.padding(16.dp)) {
                    Text("导入进度", color = P.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(4.dp))
                    Text("从剪贴板读取备份文本；或把备份粘贴到输入框再导入。", color = P.dim, fontSize = 10.sp)
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth().heightIn(max = 140.dp)
                        .clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.05f))
                        .padding(8.dp)) {
                        androidx.compose.foundation.text.BasicTextField(
                            value = backupText, onValueChange = { backupText = it },
                            textStyle = androidx.compose.ui.text.TextStyle(color = P.text, fontSize = 9.5.sp),
                            modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))
                            .clickable {
                                val t = clipboard.getText()?.text ?: ""
                                if (t.isNotBlank()) backupText = t
                            }.padding(horizontal = 10.dp, vertical = 6.dp)) {
                            Text("读取剪贴板", color = P.muted, fontSize = 9.5.sp)
                        }
                        Box(Modifier.clip(RoundedCornerShape(10.dp)).background(P.ai.copy(alpha = 0.9f))
                            .clickable {
                                val n = store.importText(backupText)
                                status = if (n >= 0) "已导入 $n 条" else "格式不对（应为 JSON 数组）"
                                showImport = false
                            }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                            Text("导入", color = Color(0xFF06101A), fontSize = 10.5.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}
