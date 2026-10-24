package com.tricore.demolab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.widget.FrameLayout

enum class Tab(val label: String) {
    GRAPH("图谱"), PATH("路线"), TOOLS("工具"), INDUSTRY("业界"), TEMPLATES("模板"), AI("AI"), PROGRESS("进度")
}

/** Shared UI state: the content layer and the chrome layer both read/write this. */
class UiState(context: Context) {
    val data: AppData = runCatching {
        parseAppData(context.assets.open("app_data.json").bufferedReader().use { it.readText() })
    }.getOrElse {
        AppData(
            "0", 0, 0, 0, 0, emptyList(), emptyList(), emptyList(),
            emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), emptyList()
        )
    }
    val store = ProgressStore(context)

    var tab by mutableStateOf(Tab.GRAPH)
    var searchOpen by mutableStateOf(false)
    var searchQuery by mutableStateOf("")
    var selectedDir by mutableStateOf<String?>(null)
    var toolsDir by mutableStateOf("ai")
    var industryDir by mutableStateOf("ai")
    var selectedBook by mutableStateOf<String?>(null)
    var selectedLayer by mutableStateOf<String?>(null)
    var openChapterId by mutableStateOf<String?>(null)
    var templateChapter by mutableStateOf<String?>(null)
    var bookDetail by mutableStateOf<String?>(null)
    var aiOpen by mutableStateOf(false)
    var aiDraft by mutableStateOf<String?>(null)

    /** snapshot of the content layer only — never contains the glass panels */
    var backdrop by mutableStateOf<ImageBitmap?>(null)
    var backdropScale by mutableStateOf(0.125f)
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val ui = UiState(this)
        Sfx.init(this)

        val content = ComposeView(this).apply {
            setContent { TriCoreTheme { ContentLayer(ui) } }
        }
        val chrome = ComposeView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setContent { TriCoreTheme { ChromeLayer(ui, content) } }
        }

        val root = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(chrome, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
    }
}

@Composable
private fun TriCoreTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = P.ai,
            background = P.bg,
            surface = P.surface,
            onSurface = P.text,
        )
    ) { content() }
}

// --------------------------------------------------------------------------- content layer

@Composable
private fun ContentLayer(ui: UiState) {
    val context = LocalContext.current

    LaunchedEffect(ui.tab) {
        if (ui.tab == Tab.GRAPH && Sfx.musicEnabled) Sfx.resumeMusic(context) else Sfx.pauseMusic()
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START ->
                    if (ui.tab == Tab.GRAPH && Sfx.musicEnabled) Sfx.resumeMusic(context) else Unit
                Lifecycle.Event.ON_STOP -> Sfx.pauseMusic()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(ui.openChapterId) {
        val id = ui.openChapterId ?: return@LaunchedEffect
        ui.templateChapter = id
        ui.tab = Tab.TEMPLATES
        ui.openChapterId = null
    }

    Box(Modifier.fillMaxSize().background(P.bg)) {
        Background()
        Column(
            Modifier.fillMaxSize().then(
                if (ui.tab == Tab.AI) Modifier else Modifier.statusBarsPadding()
            )
        ) {
            if (ui.tab != Tab.AI) {
                TopBar(
                    ui = ui,
                    onSearch = { ui.searchOpen = true },
                    onAi = { ui.tab = Tab.AI },
                    onSound = {
                        Sfx.toggleMusic(context)
                        if (Sfx.musicEnabled && ui.tab == Tab.GRAPH) Sfx.resumeMusic(context) else Sfx.pauseMusic()
                    },
                )
            }
            Box(Modifier.weight(1f)) {
                when (ui.tab) {
                    Tab.GRAPH -> Box(
                        Modifier
                            .fillMaxSize()
                            .clipToBounds()
                    ) {
                        GraphScreen(
                            data = ui.data,
                            store = ui.store,
                            selectedDir = ui.selectedDir,
                            selectedBook = ui.selectedBook,
                            selectedLayer = ui.selectedLayer,
                            onSelectDir = { ui.selectedDir = it; ui.selectedLayer = null },
                            onSelectBook = { ui.selectedBook = it },
                            onSelectLayer = { ui.selectedLayer = if (ui.selectedLayer == it) null else it },
                            onOpenChapter = { ui.openChapterId = it },
                            onOpenBook = { ui.bookDetail = it },
                        )
                    }
                    Tab.PATH -> RouteScreen(ui.data, ui.store, onOpenChapter = { ui.openChapterId = it }, onOpenBook = { ui.bookDetail = it })
                    Tab.TOOLS -> ToolsScreen(ui.data, ui.toolsDir, topInset = 74.dp)
                    Tab.INDUSTRY -> IndustryScreen(ui.data, ui.industryDir, topInset = 74.dp)
                    Tab.TEMPLATES -> TemplatesScreen(
                        data = ui.data,
                        initialChapter = ui.templateChapter,
                        onConsumed = { ui.templateChapter = null },
                        onSendToAi = { prompt -> ui.aiDraft = prompt; ui.tab = Tab.AI },
                        topInset = 6.dp,
                    )
                    Tab.AI -> AiChatScreen(
                        data = ui.data,
                        store = ui.store,
                        draft = ui.aiDraft,
                        onDraftConsumed = { ui.aiDraft = null },
                    )
                    Tab.PROGRESS -> ProgressV2Screen(ui.data, ui.store, onOpenChapter = { ui.openChapterId = it }, onOpenBook = { ui.bookDetail = it })
                }
            }
        }

        ui.bookDetail?.let { bookId ->
            Box(Modifier.fillMaxSize().background(P.bg)) {
                BookDetailScreen(
                    data = ui.data,
                    store = ui.store,
                    bookId = bookId,
                    onBack = { ui.bookDetail = null },
                    onOpenChapter = { ui.templateChapter = it; ui.tab = Tab.TEMPLATES; ui.bookDetail = null },
                )
            }
        }

        if (ui.aiOpen) {
            androidx.activity.compose.BackHandler { ui.aiOpen = false }
            AiScreen(
                data = ui.data,
                store = ui.store,
                draft = ui.aiDraft,
                onDraftConsumed = { ui.aiDraft = null },
                onClose = { ui.aiOpen = false },
            )
        }

        if (ui.searchOpen) {
            androidx.activity.compose.BackHandler { ui.searchOpen = false; ui.searchQuery = "" }
            Box(Modifier.fillMaxSize().background(Color(0xFF06080F))) {
                Column(Modifier.fillMaxSize().statusBarsPadding()) {
                    SearchOverlay(
                        data = ui.data,
                        query = ui.searchQuery,
                        onQuery = { ui.searchQuery = it },
                        onOpenChapter = { ui.openChapterId = it; ui.searchOpen = false },
                        onClose = { ui.searchOpen = false; ui.searchQuery = "" },
                    )
                }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChapterSheetHost(ui: UiState, chapter: Chapter) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = { ui.openChapterId = null },
        sheetState = sheetState,
        containerColor = Color(0xFF0B0E15),
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.18f))
            )
        },
    ) {
        ChapterSheet(
            data = ui.data,
            chapter = chapter,
            store = ui.store,
        )
    }
}

// --------------------------------------------------------------------------- chrome layer

@Composable
private fun ChromeLayer(ui: UiState, contentView: ComposeView) {
    val backdrop = remember {
        BackdropState().apply {
            scale = 0.125f
            image = ui.backdrop
        }
    }

    // capture ONLY the content view: panels are not part of it, so sampling
    // directly behind a panel is exact and free of self-feedback.
    LaunchedEffect(contentView) {
        var intervalMs = 33L
        while (true) {
            kotlinx.coroutines.delay(intervalMs)
            val started = SystemClock.elapsedRealtime()
            runCatching {
                val w = contentView.width / 8
                val h = contentView.height / 8
                if (w > 0 && h > 0) {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val canvas = Canvas(bmp)
                    canvas.scale(1f / 8f, 1f / 8f)
                    contentView.draw(canvas)
                    val image = bmp.asImageBitmap()
                    backdrop.image = image
                    backdrop.scale = 1f / 8f
                    ui.backdrop = image
                }
            }
            val cost = SystemClock.elapsedRealtime() - started
            intervalMs = when {
                cost <= 6L -> 16L
                cost <= 12L -> 33L
                else -> 66L
            }
        }
    }

    CompositionLocalProvider(LocalBackdrop provides backdrop) {
        Box(Modifier.fillMaxSize()) {
            if (ui.bookDetail == null && !ui.searchOpen && !ui.aiOpen &&
                (ui.tab == Tab.GRAPH || ui.tab == Tab.TOOLS || ui.tab == Tab.INDUSTRY)
            ) {
                DirSegments(
                    tab = ui.tab,
                    data = ui.data,
                    selectedDir = ui.selectedDir,
                    toolsDir = ui.toolsDir,
                    industryDir = ui.industryDir,
                    onPick = { id ->
                        when (ui.tab) {
                            Tab.GRAPH -> { ui.selectedDir = if (ui.selectedDir == id) null else id; ui.selectedBook = null }
                            Tab.TOOLS -> ui.toolsDir = id
                            else -> ui.industryDir = id
                        }
                    },
                    onAll = { ui.selectedDir = null; ui.selectedBook = null },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 78.dp),
                )
            }

            if (ui.bookDetail == null && !ui.searchOpen && !ui.aiOpen) BottomBar(
                current = ui.tab,
                onSelect = { ui.tab = it; ui.searchOpen = false },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 10.dp),
            )
        }
    }
}

// --------------------------------------------------------------------------- bars

@Composable
private fun TopBar(ui: UiState, onSearch: () -> Unit, onSound: () -> Unit, onAi: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                "电子信息战士技能菜单",
                color = P.text,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.2).sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                "${ui.data.bookCount} 本教材 · ${ui.data.chapterCount} 章 · ${ui.data.demoTotal} 个 Demo",
                color = P.dim, fontSize = 10.5.sp,
            )
        }
        if (ui.tab == Tab.GRAPH) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color(0xFF54E4FF).copy(alpha = if (Sfx.musicEnabled) 0.42f else 0.12f), CircleShape)
                    .clickable { onSound() },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (Sfx.musicEnabled) "♬" else "♪̸",
                    color = if (Sfx.musicEnabled) Color(0xFF9FF4FF) else P.dim,
                    fontSize = 16.sp,
                )
            }
            Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.06f))
                .border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape)
                .clickable { onSearch() },
            contentAlignment = Alignment.Center,
        ) {
            Text("⌕", color = P.muted, fontSize = 17.sp)
        }
    }
}

@Composable
private fun BottomBar(current: Tab, onSelect: (Tab) -> Unit, modifier: Modifier = Modifier) {
    val entries = Tab.entries
    val index = entries.indexOf(current).coerceAtLeast(0)
    var barWidth by remember { mutableStateOf(0f) }
    val density = androidx.compose.ui.platform.LocalDensity.current

    val slotPx = if (barWidth > 0f) (barWidth - with(density) { 12.dp.toPx() }) / entries.size else 0f
    val pillX by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (slotPx > 0f) with(density) { 6.dp.toPx() } + index * slotPx else 0f,
        animationSpec = androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "pillX",
    )
    val pillW by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (slotPx > 0f) slotPx - with(density) { 8.dp.toPx() } else 0f,
        animationSpec = androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "pillW",
    )

    GlassSurface(
        modifier = modifier.width(348.dp).height(64.dp),
        corner = 32.dp,
        tint = Color(0xFF0F1322),
    ) {
        Box(Modifier.matchParentSize()) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        if (pillW > 1f) {
                            val h = 48.dp.toPx()
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        Color.White.copy(alpha = 0.20f),
                                        Color.White.copy(alpha = 0.06f),
                                    )
                                ),
                                topLeft = Offset(pillX + 4.dp.toPx(), (size.height - h) / 2f),
                                size = androidx.compose.ui.geometry.Size(pillW, h),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(h / 2f, h / 2f),
                            )
                        }
                    }
            )
            Row(
                Modifier
                    .fillMaxSize()
                    .onSizeChanged { barWidth = it.width.toFloat() }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                entries.forEach { entry ->
                    val selected = entry == current
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .clickable { onSelect(entry) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(glyph(entry), color = if (selected) P.text else P.dim, fontSize = 15.sp)
                            Spacer(Modifier.height(1.dp))
                            Text(
                                entry.label,
                                color = if (selected) P.text else P.dim,
                                fontSize = 9.5.sp,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun glyph(tab: Tab): String = when (tab) {
    Tab.GRAPH -> "◎"
    Tab.PATH -> "≡"
    Tab.TOOLS -> "⌘"
    Tab.INDUSTRY -> "◆"
    Tab.TEMPLATES -> "✦"
    Tab.AI -> "✧"
    Tab.PROGRESS -> "◔"
}

@Composable
private fun DirSegments(
    tab: Tab,
    data: AppData,
    selectedDir: String?,
    toolsDir: String,
    industryDir: String,
    onPick: (String) -> Unit,
    onAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ids = if (tab == Tab.GRAPH) listOf("ai", "chip", "robot") else listOf("ai", "chip", "robot", "base")
    val current = when (tab) {
        Tab.GRAPH -> selectedDir
        Tab.TOOLS -> toolsDir
        else -> industryDir
    }
    val keys: List<String> = buildList {
        if (tab == Tab.GRAPH) add("all")
        ids.forEach { add(it) }
    }
    val activeKey = current ?: "all"
    val index = keys.indexOf(activeKey).coerceAtLeast(0)

    var containerOrigin by remember { mutableStateOf(Offset.Zero) }
    val chipRects = remember { androidx.compose.runtime.mutableStateListOf<androidx.compose.ui.geometry.Rect>() }
    val rect = chipRects.getOrNull(index)
    val relLeft = (rect?.left ?: 0f) - containerOrigin.x
    val relTop = (rect?.top ?: 0f) - containerOrigin.y
    val relWidth = rect?.width ?: 0f
    val relHeight = rect?.height ?: 0f

    val pillX by androidx.compose.animation.core.animateFloatAsState(
        targetValue = relLeft,
        animationSpec = androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "segX",
    )
    val pillY by androidx.compose.animation.core.animateFloatAsState(
        targetValue = relTop,
        animationSpec = androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "segY",
    )
    val pillW by androidx.compose.animation.core.animateFloatAsState(
        targetValue = relWidth,
        animationSpec = androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "segW",
    )
    val pillH by androidx.compose.animation.core.animateFloatAsState(
        targetValue = relHeight,
        animationSpec = androidx.compose.animation.core.tween(250, easing = androidx.compose.animation.core.FastOutSlowInEasing),
        label = "segH",
    )
    val accent = if (activeKey == "all") Color.White else P.dir(activeKey)

    GlassSurface(
        modifier = modifier.padding(horizontal = 16.dp),
        corner = 26.dp,
        tint = Color(0xFF0E1320),
    ) {
        Box(
            Modifier
                .onGloballyPositioned { containerOrigin = it.positionInRoot() }
        ) {
            Box(
                Modifier
                    .matchParentSize()
                    .drawBehind {
                        if (pillW > 1f && pillH > 1f) {
                            drawRoundRect(
                                brush = Brush.verticalGradient(
                                    listOf(
                                        accent.copy(alpha = 0.36f),
                                        accent.copy(alpha = 0.16f),
                                    )
                                ),
                                topLeft = Offset(pillX, pillY),
                                size = androidx.compose.ui.geometry.Size(pillW, pillH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                    pillH / 2f, pillH / 2f
                                ),
                            )
                        }
                    }
            )
            Row(
                Modifier.padding(horizontal = 8.dp, vertical = 7.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                keys.forEachIndexed { i, key ->
                    val label = if (key == "all") "全部"
                        else data.directions.firstOrNull { it.id == key }?.name ?: key
                    val chipAccent = if (key == "all") P.text else P.dir(key)
                    GlassChip(
                        selected = activeKey == key,
                        onClick = { if (key == "all") onAll() else onPick(key) },
                        label = label,
                        accent = chipAccent,
                        onBounds = { r ->
                            val old = chipRects.getOrNull(i)
                            if (old == null) {
                                chipRects.add(r)
                            } else if (kotlin.math.abs(old.left - r.left) > 0.5f ||
                                kotlin.math.abs(old.width - r.width) > 0.5f
                            ) {
                                chipRects[i] = r
                            }
                        },
                    )
                }
            }
        }
    }
}



// --------------------------------------------------------------------------- sheet

@Composable
private fun ChapterSheet(
    data: AppData,
    chapter: Chapter,
    store: ProgressStore,
) {
    val clipboard = LocalClipboardManager.current
    val book = data.booksById[chapter.book]
    val color = P.dir(data.dirOf(chapter.book))

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 40.dp),
    ) {
        item {
            Text(book?.title ?: "", color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "\\u7b2c ${chapter.n} \\u7ae0\\u3000${chapter.title}",
                color = P.text, fontSize = 19.sp, fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(8.dp))
            Text(chapter.points.joinToString("\\u3000\\u00b7\\u3000"), color = P.dim, fontSize = 10.5.sp, lineHeight = 16.sp)
            Spacer(Modifier.height(16.dp))
        }

        items(chapter.demos) { demo ->
            val done = store.isDone(demo.id)
            GlassCard(Modifier.fillMaxWidth().padding(vertical = 5.dp), corner = 22) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(levelColor(demo.level).copy(alpha = 0.16f))
                                .padding(horizontal = 7.dp, vertical = 2.dp)
                        ) {
                            Text(
                                "${demo.level} \\u00b7 ${levelName(demo.level)}",
                                color = levelColor(demo.level), fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(
                            demo.title, color = P.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (done) color.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.08f))
                                .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                                .clickable {
                                    Sfx.click(pitch = if (done) 0.88f else 1.16f)
                                    store.toggle(demo.id)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (done) "\\u2713" else "", color = Color(0xFF05070C), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(demo.task, color = P.muted, fontSize = 12.sp, lineHeight = 18.sp)
                    Spacer(Modifier.height(10.dp))
                    Text("\\u9a8c\\u6536\\u3000" + demo.check, color = P.dim, fontSize = 10.5.sp, lineHeight = 15.sp)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SmallAction("\\u590d\\u5236\\u63d0\\u793a\\u8bcd", accent = color) {
                            clipboard.setText(AnnotatedString(buildPrompt(data, chapter, demo)))
                        }
                        SmallAction(if (done) "\\u6807\\u8bb0\\u672a\\u5b8c\\u6210" else "\\u6807\\u8bb0\\u5b8c\\u6210", accent = P.muted) {
                            Sfx.click(pitch = if (done) 0.88f else 1.16f)
                            store.toggle(demo.id)
                        }
                    }
                }
            }
        }
    }
}

private fun levelColor(level: String): Color = when (level) {
    "A" -> Color(0xFF8A94A6)
    "B" -> Color(0xFFF5A34A)
    else -> Color(0xFF35D0B0)
}

private fun levelName(level: String): String = when (level) {
    "A" -> "\\u6982\\u5ff5\\u9a8c\\u8bc1"
    "B" -> "\\u5de5\\u7a0b\\u5b9e\\u73b0"
    else -> "\\u8de8\\u65b9\\u5411\\u4e32\\u8054"
}

@Composable
private fun SmallAction(label: String, accent: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(accent.copy(alpha = 0.14f))
            .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(14.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 7.dp)
    ) {
        Text(label, color = accent, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
    }
}

/** Rebuilds the copy-paste prompt: book + chapter + prior + task + output order + constraints. */
fun buildPrompt(data: AppData, chapter: Chapter, demo: Demo): String {
    val book = data.booksById[chapter.book]
    val bookTitle = book?.title ?: chapter.book
    val edition = book?.edition ?: ""
    val earlier = data.stages
        .flatMap { it.books }
        .mapNotNull { data.booksById[it.book] }
        .map { it.title }
        .distinct()
        .take(6)
    val prior = chapter.points.take(2).joinToString("\\u3001")
    return buildString {
        append("\\u6211\\u5728\\u5b66\\u300a").append(bookTitle).append("\\u300b\\uff08").append(edition).append("\\uff09\\u7b2c ")
        append(chapter.n).append(" \\u7ae0\\u300c").append(chapter.title).append("\\u300d\\u3002")
        append("\\u6211\\u7684\\u5df2\\u5b66\\u8303\\u56f4\\uff1a\\u5df2\\u5b8c\\u6210 ").append(earlier.joinToString("\\u3001")).append(" \\u7b49\\u524d\\u5e8f\\u8bfe\\u7a0b\\uff1b")
        append("\\u672c\\u4e66\\u672c\\u7ae0\\u8981\\u70b9\\uff1a").append(prior).append("\\u3002\\n")
        append("\\u8bf7\\u6309\\u300c\\u7ec3\\u4e60\\u5305\\u300d\\u89c4\\u8303\\u7ed9\\u6211\\u4e00\\u4e2a ").append(demo.level).append(" \\u7ea7\\uff08")
        append(levelName(demo.level)).append("\\uff09\\u7ec3\\u4e60\\uff1a").append(demo.task).append("\\n")
        append("\\u4ea7\\u51fa\\u987a\\u5e8f\\uff1a\\u2460 3 \\u4e2a\\u524d\\u7f6e\\u81ea\\u6d4b\\u95ee\\u9898 \\u2192 \\u2461 \\u5206\\u6b65\\u63d0\\u793a\\uff08\\u5148\\u601d\\u8def\\u540e\\u7ec6\\u8282\\uff0c\\u4e0d\\u76f4\\u63a5\\u7ed9\\u7b54\\u6848\\uff09\\u2192 ")
        append("\\u2462 \\u5b8c\\u6574\\u53c2\\u8003\\u7b54\\u6848\\uff08\\u63a8\\u5bfc\\u6216\\u53ef\\u8fd0\\u884c\\u4ee3\\u7801\\uff0c\\u542b\\u6ce8\\u91ca\\u4e0e\\u8fd0\\u884c\\u65b9\\u5f0f\\uff09\\u2192 \\u2463 3 \\u4e2a\\u8fb9\\u754c\\u60c5\\u51b5\\u6216\\u53cd\\u4f8b \\u2192 ")
        append("\\u2464 \\u9a8c\\u6536\\u6e05\\u5355\\uff08\\u53ef\\u52fe\\u9009\\uff09\\u2192 \\u2465 2 \\u4e2a\\u5e38\\u89c1\\u9519\\u8bef\\u4e0e\\u6392\\u67e5\\u3002\\n")
        append("\\u7ea6\\u675f\\uff1a\\u4e0d\\u5f97\\u7f16\\u9020\\u5b9a\\u7406\\u6761\\u4ef6\\u3001API \\u6216\\u6570\\u636e\\uff1b\\u4e0d\\u786e\\u5b9a\\u5904\\u6807\\u6ce8\\u300c\\u5f85\\u6838\\u5b9e\\u300d\\uff1b\\u4ee3\\u7801\\u7ed9\\u51fa\\u8fd0\\u884c\\u73af\\u5883\\u4e0e\\u7248\\u672c\\u3002")
    }
}