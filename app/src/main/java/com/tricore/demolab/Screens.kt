package com.tricore.demolab

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas

// --------------------------------------------------------------------------- palette

object P {
    val bg = Color(0xFF06070B)
    val surface = Color(0xFF0E111A)
    val text = Color(0xFFF2F4F8)
    val muted = Color(0xFF98A2B3)
    val dim = Color(0xFF5C6577)
    val ai = Color(0xFF7C6CF0)
    val chip = Color(0xFFF5A34A)
    val robot = Color(0xFF35D0B0)
    val base = Color(0xFF8A94A6)

    fun dir(id: String): Color = when (id) {
        "ai" -> ai
        "chip" -> chip
        "robot" -> robot
        else -> base
    }
}

@Composable
fun Background() {
    Box(
        Modifier
            .fillMaxSize()
            .background(P.bg)
            .background(
                Brush.radialGradient(
                    listOf(P.ai.copy(alpha = 0.30f), Color.Transparent),
                    center = Offset(180f, 120f), radius = 900f
                )
            )
            .background(
                Brush.radialGradient(
                    listOf(P.chip.copy(alpha = 0.20f), Color.Transparent),
                    center = Offset(1000f, 700f), radius = 900f
                )
            )
            .background(
                Brush.radialGradient(
                    listOf(P.robot.copy(alpha = 0.22f), Color.Transparent),
                    center = Offset(260f, 1900f), radius = 1000f
                )
            )
    )
}

// --------------------------------------------------------------------------- small parts

@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    corner: Int = 24,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val base = if (onClick != null) modifier.clickable { onClick() } else modifier
    FrostedSurface(modifier = base, corner = corner.dp) {
        Box(Modifier.padding(16.dp)) { content() }
    }
}

@Composable
fun ProgressRing(progress: Float, color: Color, size: Int = 52, stroke: Float = 4f, label: String? = null) {
    Box(Modifier.size(size.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val inset = stroke
            drawArc(
                color = Color.White.copy(alpha = 0.08f),
                startAngle = -90f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            drawArc(
                color = color,
                startAngle = -90f, sweepAngle = 360f * progress.coerceIn(0f, 1f), useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
        }
        if (label != null) {
            Text(label, color = P.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun MiniBar(progress: Float, color: Color, height: Int = 4) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(height.dp)
            .clip(RoundedCornerShape(height.dp))
            .background(Color.White.copy(alpha = 0.08f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(height.dp)
                .clip(RoundedCornerShape(height.dp))
                .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.9f), color.copy(alpha = 0.5f))))
        )
    }
}

@Composable
fun SectionTitle(text: String, right: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = P.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.2.sp)
        Spacer(Modifier.weight(1f))
        if (right != null) Text(right, color = P.dim, fontSize = 11.5.sp)
    }
}

fun openUrl(context: android.content.Context, url: String) {
    if (url.isBlank()) return
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)))
    }
}

// --------------------------------------------------------------------------- path screen

@Composable
fun PathScreen(
    data: AppData,
    store: ProgressStore,
    onOpenChapter: (String) -> Unit,
) {
    var openStage by remember { mutableStateOf<String?>(data.stages.firstOrNull()?.id) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            start = 16.dp, end = 16.dp, top = 8.dp, bottom = 140.dp
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 0: TOC
        item {
            GlassCard(Modifier.fillMaxWidth(), corner = 24) {
                Column {
                    Text("目录 · 点击跳转", color = P.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        data.routes.forEach { route ->
                            TocChip(route.title) {
                                scope.launch {
                                    listState.animateScrollToItem(1 + data.routes.indexOf(route))
                                }
                            }
                        }
                        data.stages.forEach { stage ->
                            TocChip(stage.title.substringBefore("：")) {
                                scope.launch {
                                    listState.animateScrollToItem(1 + data.routes.size + data.stages.indexOf(stage))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("共 ${data.routes.size} 条阶段路线 + ${data.stages.size} 个学期阶段",
                        color = P.dim, fontSize = 9.5.sp)
                }
            }
        }

        items(data.routes) { route ->
            GlassCard(Modifier.fillMaxWidth(), corner = 24) {
                Column {
                    Text(route.title, color = P.text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(2.dp))
                    Text(route.sub, color = P.dim, fontSize = 10.5.sp)
                    Spacer(Modifier.height(8.dp))
                    route.steps.forEach { step ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                            Box(
                                Modifier
                                    .width(3.dp)
                                    .height(34.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(P.ai.copy(alpha = 0.55f))
                            )
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("${step.title}　${step.goal}", color = P.text, fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Medium)
                                Text("书：${step.books}　｜　周投入：${step.weekly}",
                                    color = P.muted, fontSize = 9.5.sp, lineHeight = 14.sp)
                                Text("产出：${step.output}", color = P.dim, fontSize = 9.5.sp,
                                    lineHeight = 14.sp)
                            }
                        }
                    }
                }
            }
        }

        items(data.stages) { stage ->
            val expanded = openStage == stage.id
            val books = stage.books.mapNotNull { sb -> data.booksById[sb.book]?.let { it to sb } }
            val totalDemos = books.sumOf { (b, _) -> b.demoCount }
            val doneDemos = books.sumOf { (b, _) -> store.doneInBook(b.id, data.chaptersByBook[b.id].orEmpty()) }

            GlassCard(
                modifier = Modifier.fillMaxWidth(),
                corner = 26,
                onClick = { openStage = if (expanded) null else stage.id },
            ) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                stage.title,
                                color = P.text, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(stage.semester, color = P.dim, fontSize = 11.sp)
                        }
                        ProgressRing(
                            progress = if (totalDemos == 0) 0f else doneDemos.toFloat() / totalDemos,
                            color = P.ai, size = 40, stroke = 3f,
                            label = "$doneDemos",
                        )
                    }
                    if (expanded) {
                        Spacer(Modifier.height(10.dp))
                        Text(stage.focus, color = P.muted, fontSize = 12.sp, lineHeight = 18.sp)
                        Spacer(Modifier.height(10.dp))
                        books.forEach { (book, sb) ->
                            BookBlock(data, store, book, sb.chapters, onOpenChapter)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TocChip(text: String, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(text, color = P.muted, fontSize = 10.sp, maxLines = 1)
    }
}

@Composable
private fun BookBlock(
    data: AppData,
    store: ProgressStore,
    book: Book,
    chapterNumbers: List<Int>,
    onOpenChapter: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val color = P.dir(book.dir)
    val chapters = data.chaptersByBook[book.id].orEmpty().filter { it.n in chapterNumbers }
    val total = chapters.sumOf { it.demos.size }
    val done = store.doneInBook(book.id, chapters)

    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { expanded = !expanded }.padding(vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(book.title, color = P.text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text("${book.edition}", color = P.dim, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text("$done/$total", color = P.muted, fontSize = 10.5.sp)
        }
        MiniBar(if (total == 0) 0f else done.toFloat() / total, color)
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            chapters.forEach { chapter ->
                val dc = store.doneInChapter(chapter)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onOpenChapter(chapter.id) }
                        .padding(vertical = 8.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "%02d".format(chapter.n),
                        color = color.copy(alpha = 0.9f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        chapter.title, color = P.muted, fontSize = 12.sp,
                        modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text("$dc/3", color = if (dc == 3) color else P.dim, fontSize = 10.5.sp)
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- tools screen

@Composable
fun ToolsScreen(data: AppData, dir: String, topInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val context = LocalContext.current
    val tools = data.tools.filter { it.dir == dir }
    val grouped = tools.groupBy { it.category }
    val resources = data.resources.filter { it.dir == dir }
    var detailTool by remember { mutableStateOf<Tool?>(null) }
    val clipboard = LocalClipboardManager.current

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp + topInset, bottom = 140.dp),
    ) {
        grouped.forEach { (category, list) ->
            item {
                SectionTitle(category, "${list.size} 项")
            }
            items(list) { tool ->
                GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), corner = 20, onClick = { detailTool = tool }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(34.dp).clip(RoundedCornerShape(11.dp))
                                .background(P.dir(dir).copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(tool.name.take(1), color = P.dir(dir), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(tool.name, color = P.text, fontSize = 13.5.sp, fontWeight = FontWeight.Medium)
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    tool.level,
                                    color = P.dir(dir).copy(alpha = 0.9f),
                                    fontSize = 9.sp,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(P.dir(dir).copy(alpha = 0.14f))
                                        .padding(horizontal = 5.dp, vertical = 1.dp),
                                )
                            }
                            Spacer(Modifier.height(3.dp))
                            Text(tool.note, color = P.dim, fontSize = 11.sp, lineHeight = 15.sp)
                        }
                        Text("›", color = P.dim, fontSize = 15.sp)
                    }
                }
            }
        }

        if (resources.isNotEmpty()) {
            item { SectionTitle("开源学习资源", "${resources.size} 条") }
            items(resources) { res ->
                GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), corner = 18, onClick = { openUrl(context, res.url) }) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                res.kind,
                                color = P.muted, fontSize = 9.5.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(alpha = 0.06f))
                                    .padding(horizontal = 6.dp, vertical = 1.5.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(res.name, color = P.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(res.note, color = P.dim, fontSize = 11.sp, lineHeight = 15.sp)
                    }
                }
            }
        }
    }

    detailTool?.let { tool ->
        Dialog(onDismissRequest = { detailTool = null }) {
            GlassCard(Modifier.fillMaxWidth(), corner = 22) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(tool.name, color = P.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(8.dp))
                        Text(tool.level, color = P.dir(dir), fontSize = 9.sp,
                            modifier = Modifier.clip(RoundedCornerShape(6.dp))
                                .background(P.dir(dir).copy(alpha = 0.14f))
                                .padding(horizontal = 5.dp, vertical = 1.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                    Text("${tool.category}　·　${tool.dir}", color = P.dim, fontSize = 9.5.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(tool.note, color = P.muted, fontSize = 11.sp, lineHeight = 16.sp)
                    if (tool.detail.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text("怎么学", color = P.text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(3.dp))
                        Text(tool.detail, color = P.muted, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .background(P.ai.copy(alpha = 0.9f))
                                .clickable { openUrl(context, tool.url) }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) { Text("打开官网", color = Color(0xFF06101A), fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                        Box(
                            Modifier.clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .clickable { clipboard.setText(AnnotatedString(tool.url)); detailTool = null }
                                .padding(horizontal = 14.dp, vertical = 8.dp)
                        ) { Text("复制链接", color = P.muted, fontSize = 11.sp) }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------------- industry screen

@Composable
fun IndustryScreen(data: AppData, dir: String, topInset: androidx.compose.ui.unit.Dp = 0.dp) {
    val block = data.industry.firstOrNull { it.dir == dir }
    var showDetail by remember { mutableStateOf(false) }
    var itemTitle by remember { mutableStateOf("") }
    var itemDetail by remember { mutableStateOf("") }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp + topInset, bottom = 140.dp),
    ) {
        if (block == null) {
            item { SectionTitle("数据准备中") }
        } else {
            item {
                GlassCard(Modifier.fillMaxWidth().padding(vertical = 6.dp), corner = 20,
                    onClick = { showDetail = true }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("方向说明 · 点开看完整解读", color = P.text, fontSize = 12.sp,
                                fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(3.dp))
                            Text(block.detail.take(46) + "…", color = P.dim, fontSize = 10.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                        Text("›", color = P.muted, fontSize = 16.sp)
                    }
                }
            }
            item { SectionTitle("必须具备的经历") }
            items(block.must.size) { i ->
                BulletCard(block.must[i], P.dir(dir)) {
                    itemTitle = block.must[i]
                    itemDetail = block.mustDetail.getOrNull(i) ?: block.detail
                    showDetail = true
                }
            }

            item { SectionTitle("作品集要素") }
            items(block.portfolio.size) { i ->
                BulletCard(block.portfolio[i], P.dir(dir)) {
                    itemTitle = block.portfolio[i]
                    itemDetail = block.portfolioDetail.getOrNull(i) ?: block.detail
                    showDetail = true
                }
            }

            item { SectionTitle("招聘方看的信号") }
            items(block.signals.size) { i ->
                BulletCard(block.signals[i], P.muted) {
                    itemTitle = block.signals[i]
                    itemDetail = block.signalsDetail.getOrNull(i) ?: block.detail
                    showDetail = true
                }
            }

            if (block.timeline.isNotEmpty()) {
                item { SectionTitle("时间线") }
                items(block.timeline) { phase ->
                    GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), corner = 20) {
                        Column {
                            Text(phase.phase, color = P.dir(dir), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            phase.items.forEach { line ->
                                Row(Modifier.padding(vertical = 2.dp)) {
                                    Text("·", color = P.dim, fontSize = 12.sp)
                                    Spacer(Modifier.width(8.dp))
                                    Text(line, color = P.muted, fontSize = 11.5.sp, lineHeight = 16.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDetail && block != null) {
        Dialog(onDismissRequest = { showDetail = false }) {
            GlassCard(Modifier.fillMaxWidth(), corner = 22) {
                Column(Modifier.padding(16.dp)) {
                    Text(if (itemTitle.isBlank()) block.title else itemTitle,
                        color = P.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(8.dp))
                    Text(if (itemDetail.isBlank()) block.detail else itemDetail,
                        color = P.muted, fontSize = 11.sp, lineHeight = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun BulletCard(text: String, accent: Color, onClick: (() -> Unit)? = null) {
    GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), corner = 18, onClick = onClick) {
        Row {
            Box(Modifier.padding(top = 6.dp).size(5.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(10.dp))
            Text(text, color = P.muted, fontSize = 12.sp, lineHeight = 17.sp)
        }
    }
}

// --------------------------------------------------------------------------- progress screen

@Composable
fun ProgressScreen(data: AppData, store: ProgressStore, onOpenChapter: (String) -> Unit = {}) {
    val dirs = listOf("ai", "chip", "robot")
    var confirmReset by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "3.0"
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 140.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            GlassCard(Modifier.fillMaxWidth(), corner = 28) {
                Column {
                    Text("总进度", color = P.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        dirs.forEach { dir ->
                            val (done, total) = store.doneByDir(data, dir)
                            val id = data.directions.firstOrNull { it.id == dir }?.name ?: dir
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                                ProgressRing(
                                    progress = if (total == 0) 0f else done.toFloat() / total,
                                    color = P.dir(dir), size = 68, stroke = 5f,
                                    label = "${(if (total == 0) 0 else done * 100 / total)}%",
                                )
                                Spacer(Modifier.height(6.dp))
                                Text(id, color = P.muted, fontSize = 10.5.sp)
                                Text("$done/$total", color = P.dim, fontSize = 9.5.sp)
                            }
                        }
                    }
                }
            }
        }

        item {
            val next = data.chapters.firstOrNull { store.doneInChapter(it) < it.demos.size }
            if (next != null) {
                val book = data.booksById[next.book]
                GlassCard(Modifier.fillMaxWidth(), corner = 24, onClick = { onOpenChapter(next.id) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("下一步", color = P.dir(data.dirOf(next.book)), fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "第 ${next.n} 章　${next.title}",
                                color = P.text, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(3.dp))
                            Text(
                                "${book?.title ?: ""}　·　已完成 ${store.doneInChapter(next)}/${next.demos.size}",
                                color = P.dim, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text("→", color = P.muted, fontSize = 18.sp)
                    }
                }
            }
        }

        item { SectionTitle("分书目进度", "${data.books.size} 本") }
        items(data.books) { book ->
            val chapters = data.chaptersByBook[book.id].orEmpty()
            val total = chapters.sumOf { it.demos.size }
            val done = store.doneInBook(book.id, chapters)
            GlassCard(Modifier.fillMaxWidth(), corner = 18) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(P.dir(book.dir)))
                        Spacer(Modifier.width(9.dp))
                        Text(book.title, color = P.text, fontSize = 12.5.sp, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("$done/$total", color = P.dim, fontSize = 10.5.sp)
                    }
                    Spacer(Modifier.height(7.dp))
                    MiniBar(if (total == 0) 0f else done.toFloat() / total, P.dir(book.dir))
                }
            }
        }

        item {
            Spacer(Modifier.height(10.dp))
            GlassCard(Modifier.fillMaxWidth(), corner = 20, onClick = {
                val total = store.done.size
                val body = buildString {
                    append("TriCore Demo Lab v").append(version).append(" 学习进度"); appendLine()
                    append("已完成 ").append(total).append(" / ").append(data.demoTotal).append(" 个 Demo")
                    appendLine(); appendLine()
                    listOf("ai", "chip", "robot").forEach { dir ->
                        val (done, all) = store.doneByDir(data, dir)
                        val name = data.directions.firstOrNull { it.id == dir }?.name ?: dir
                        append(name).append("：").append(done).append("/").append(all); appendLine()
                    }
                    appendLine(); appendLine("分书目：")
                    data.books.forEach { book ->
                        val chapters = data.chaptersByBook[book.id].orEmpty()
                        val done = store.doneInBook(book.id, chapters)
                        val all = chapters.sumOf { it.demos.size }
                        if (done > 0) {
                            append("· ").append(book.title).append(" ").append(done).append("/").append(all)
                            appendLine()
                        }
                    }
                }
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "TriCore Demo Lab 进度")
                    putExtra(Intent.EXTRA_TEXT, body)
                }
                runCatching { context.startActivity(Intent.createChooser(send, "导出进度")) }
            }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("导出进度", color = P.text, fontSize = 12.5.sp, modifier = Modifier.weight(1f))
                    Text("分享文本", color = P.dim, fontSize = 10.5.sp)
                }
            }

            GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp), corner = 20, onClick = { confirmReset = true }) {
                Text(
                    if (confirmReset) "再点一次确认清空所有进度" else "清空进度记录",
                    color = if (confirmReset) Color(0xFFFF6B6B) else P.muted,
                    fontSize = 12.5.sp,
                )
            }
            if (confirmReset) {
                GlassCard(Modifier.fillMaxWidth().padding(top = 8.dp), corner = 20, onClick = {
                    store.reset(); confirmReset = false
                }) {
                    Text("确认清空", color = Color(0xFFFF6B6B), fontSize = 12.5.sp)
                }
            }

            Spacer(Modifier.height(14.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                Text("TriCore Demo Lab　v$version", color = P.dim, fontSize = 10.sp)
                Spacer(Modifier.height(3.dp))
                Text(
                    "${data.bookCount} 本教材 · ${data.chapterCount} 章 · ${data.demoTotal} 个 Demo · ${data.stageCount} 个阶段",
                    color = P.dim, fontSize = 10.sp,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    "数据来源：TriCore Demo Lab 课程库；工具与资源链接指向官方仓库",
                    color = P.dim, fontSize = 10.sp,
                )
            }
        }
    }
}

// --------------------------------------------------------------------------- search

@Composable
fun SearchOverlay(
    data: AppData,
    query: String,
    onQuery: (String) -> Unit,
    onOpenChapter: (String) -> Unit,
    onClose: () -> Unit,
) {
    val q = query.trim()
    val chapters = if (q.isEmpty()) emptyList() else data.chapters.filter {
        it.title.contains(q, true) || it.points.any { p -> p.contains(q, true) } ||
            it.demos.any { d -> d.title.contains(q, true) || d.task.contains(q, true) }
    }.take(20)
    val tools = if (q.isEmpty()) emptyList() else data.tools.filter {
        it.name.contains(q, true) || it.category.contains(q, true) || it.note.contains(q, true)
    }.take(8)
    val resources = if (q.isEmpty()) emptyList() else data.resources.filter {
        it.name.contains(q, true) || it.note.contains(q, true)
    }.take(8)
    val context = LocalContext.current

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF141827).copy(alpha = 0.92f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⌕", color = P.muted, fontSize = 16.sp)
            Spacer(Modifier.width(10.dp))
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = P.text, fontSize = 14.sp),
                cursorBrush = SolidColor(P.ai),
                modifier = Modifier.weight(1f),
                decorationBox = { inner ->
                    if (query.isEmpty()) {
                        Text("搜索章节 / 知识点 / Demo / 工具", color = P.dim, fontSize = 13.sp)
                    }
                    inner()
                },
            )
            Text("取消", color = P.muted, fontSize = 12.sp, modifier = Modifier.clickable { onClose() })
        }

        LazyColumn(Modifier.fillMaxSize()) {
            if (q.isEmpty()) {
                item {
                    Text(
                        "试试搜「Cache」「SLAM」「Triton」「卡诺图」",
                        color = P.dim, fontSize = 12.sp,
                        modifier = Modifier.padding(top = 16.dp),
                    )
                }
            }
            if (chapters.isNotEmpty()) {
                item { SectionTitle("章节与 Demo", "${chapters.size} 条") }
                items(chapters) { chapter ->
                    GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), corner = 18, onClick = { onOpenChapter(chapter.id) }) {
                        Column {
                            Text(chapter.title, color = P.text, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Spacer(Modifier.height(2.dp))
                            Text(
                                data.booksById[chapter.book]?.title ?: "",
                                color = P.dir(data.dirOf(chapter.book)), fontSize = 10.5.sp,
                            )
                        }
                    }
                }
            }
            if (tools.isNotEmpty()) {
                item { SectionTitle("工具链", "${tools.size} 项") }
                items(tools) { tool ->
                    GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), corner = 18, onClick = { openUrl(context, tool.url) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(tool.name, color = P.text, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                                Text(tool.note, color = P.dim, fontSize = 10.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("↗", color = P.dim, fontSize = 13.sp)
                        }
                    }
                }
            }
            if (resources.isNotEmpty()) {
                item { SectionTitle("资源", "${resources.size} 条") }
                items(resources) { res ->
                    GlassCard(Modifier.fillMaxWidth().padding(vertical = 4.dp), corner = 18, onClick = { openUrl(context, res.url) }) {
                        Text(res.name, color = P.text, fontSize = 12.5.sp)
                    }
                }
            }
            item { Spacer(Modifier.height(160.dp)) }
        }
    }
}
