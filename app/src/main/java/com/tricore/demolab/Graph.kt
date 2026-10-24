package com.tricore.demolab

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.sp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/* ------------------------------------------------------------------ virtual space */

private const val VW = 1240f
private const val VH = 1900f
private const val CX = VW / 2f
private const val CY = VH / 2f

private const val R_FOUNDATION = 200f
private const val R_HUB = 460f
private const val R_BOOK_A = 618f
private const val R_BOOK_B = 686f
private const val R_CHAPTER = 240f

private val CYAN = Color(0xFF54E4FF)
private val CYAN_DIM = Color(0xFF2C7E96)
private val INK = Color(0xFF05080F)

/* ------------------------------------------------------------------ model */

data class GNode(
    val id: String,
    val label: String,
    val kind: String,          // core | hub | foundation | book | chapter
    val x: Float,
    val y: Float,
    val radius: Float,
    val color: Color,
    val progress: Float,
    val dim: Boolean,
    val selected: Boolean,
    val dir: String? = null,
    val bookId: String? = null,
    val chapterId: String? = null,
)

data class GEdge(val a: String, val b: String, val kind: String) // spine | branch | cross

data class GLayout(val nodes: List<GNode>, val edges: List<GEdge>)

private fun dirOf(data: AppData, id: String?): String? = id?.let { data.dirOf(it) }

fun buildLayout(
    data: AppData,
    store: ProgressStore,
    selectedDir: String?,
    selectedBook: String?,
    selectedLayer: String? = null,
): GLayout {
    val nodes = mutableListOf<GNode>()
    val edges = mutableListOf<GEdge>()
    val pos = mutableMapOf<String, Offset>()

    fun add(node: GNode) {
        nodes.add(node)
        pos[node.id] = Offset(node.x, node.y)
    }

    // focused layer: spread its books on a full circle so every label is readable
    if (selectedLayer != null) {
        val layer = data.layers.firstOrNull { it.id == selectedLayer }
        if (layer != null) {
            val hubId = "hub-${layer.dir}"
            add(
                GNode(
                    id = "core", label = "", kind = "core",
                    x = CX, y = CY, radius = 30f, color = CYAN, progress = 0f,
                    dim = true, selected = false,
                )
            )
            add(
                GNode(
                    id = hubId,
                    label = data.directions.firstOrNull { it.id == layer.dir }?.name ?: layer.dir,
                    kind = "hub", x = CX, y = CY - 300f, radius = 46f,
                    color = dirColor(data, layer.dir), progress = 0f,
                    dim = true, selected = false, dir = layer.dir,
                )
            )
            add(
                GNode(
                    id = layer.id, label = layer.label, kind = "layer",
                    x = CX, y = CY, radius = 34f, color = dirColor(data, layer.dir),
                    progress = 0f, dim = false, selected = true, dir = layer.dir,
                )
            )
            edges.add(GEdge(hubId, layer.id, "spine"))
            val books = layer.books.mapNotNull { data.booksById[it] }
            books.forEachIndexed { index, book ->
                val angle = Math.toRadians((-90.0 + index * (360.0 / books.size.coerceAtLeast(1))))
                val r = 430f
                val x = CX + cos(angle).toFloat() * r * 0.86f
                val y = CY + sin(angle).toFloat() * r * 1.02f
                val chapters = data.chaptersByBook[book.id].orEmpty()
                val total = chapters.sumOf { it.demos.size }.coerceAtLeast(1)
                val done = store.doneInBook(book.id, chapters)
                add(
                    GNode(
                        id = book.id, label = book.title, kind = "book",
                        x = x, y = y, radius = 36f, color = dirColor(data, book.dir),
                        progress = done.toFloat() / total,
                        dim = false, selected = false, dir = book.dir, bookId = book.id,
                    )
                )
                edges.add(GEdge(layer.id, book.id, "spine"))
            }
            return GLayout(nodes, edges)
        }
    }

    // core
    add(
        GNode(
            id = "core", label = "TriCore", kind = "core",
            x = CX, y = CY, radius = 46f,
            color = CYAN, progress = 0f,
            dim = selectedDir != null || selectedBook != null,
            selected = false,
        )
    )

    val dirs = listOf("ai", "chip", "robot")
    val dirAngles = mapOf("ai" to -90f, "robot" to 150f, "chip" to 30f)

    // hubs
    dirs.forEach { dir ->
        val ang = Math.toRadians(dirAngles[dir]!!.toDouble())
        val x = CX + cos(ang).toFloat() * R_HUB * 0.88f
        val y = CY + sin(ang).toFloat() * R_HUB * 1.06f
        val (done, total) = store.doneByDir(data, dir)
        add(
            GNode(
                id = "hub-$dir",
                label = data.directions.firstOrNull { it.id == dir }?.name ?: dir,
                kind = "hub",
                x = x, y = y, radius = 54f,
                color = dirColor(data, dir),
                progress = if (total == 0) 0f else done.toFloat() / total,
                dim = (selectedDir != null && selectedDir != dir) ||
                    (selectedBook != null && dirOf(data, selectedBook) != dir),
                selected = selectedDir == dir,
                dir = dir,
            )
        )
        edges.add(GEdge("core", "hub-$dir", "spine"))
    }

    // foundation ring: the shallow layer every direction grows from
    val foundation = data.books.filter { it.dir == "base" }
    foundation.forEachIndexed { index, book ->
        val angle = -90f + index * (360f / foundation.size) + 18f
        val rad = Math.toRadians(angle.toDouble())
        val x = CX + cos(rad).toFloat() * R_FOUNDATION * 0.92f
        val y = CY + sin(rad).toFloat() * R_FOUNDATION * 1.04f
        val chapters = data.chaptersByBook[book.id].orEmpty()
        val total = chapters.sumOf { it.demos.size }.coerceAtLeast(1)
        val done = store.doneInBook(book.id, chapters)
        add(
            GNode(
                id = book.id,
                label = shortLabel(book.title),
                kind = "foundation",
                x = x, y = y,
                radius = if (selectedBook == book.id) 30f else 21f,
                color = Color(0xFF8FE9F5),
                progress = done.toFloat() / total,
                dim = selectedBook != null && selectedBook != book.id,
                selected = selectedBook == book.id,
                dir = "base",
                bookId = book.id,
            )
        )
        // ring wiring between foundation skills
        val next = foundation[(index + 1) % foundation.size]
        if (index < foundation.size - 1) edges.add(GEdge(book.id, next.id, "cross"))
        // and a spoke into each hub that depends on it
        dirs.forEach { dir ->
            edges.add(GEdge(book.id, "hub-$dir", "branch"))
        }
    }

    // books around their hub
    dirs.forEach { dir ->
        val list = data.books.filter { it.dir == dir }
        val baseAngle = dirAngles[dir]!!
        list.forEachIndexed { index, book ->
            val t = if (list.size == 1) 0.5f else index.toFloat() / (list.size - 1)
            val spread = if (selectedDir == dir) 150f else 88f
            val angle = baseAngle - spread / 2 + spread * t
            val rad = Math.toRadians(angle.toDouble())
            val r = (if (index % 2 == 0) R_BOOK_A else R_BOOK_B) *
                (if (selectedDir == dir) 1.12f else 1f)
            val x = CX + cos(rad).toFloat() * r * 0.80f
            val y = CY + sin(rad).toFloat() * r * 1.10f
            val chapters = data.chaptersByBook[book.id].orEmpty()
            val total = chapters.sumOf { it.demos.size }.coerceAtLeast(1)
            val done = store.doneInBook(book.id, chapters)
            add(
                GNode(
                    id = book.id,
                    label = shortLabel(book.title),
                    kind = "book",
                    x = x, y = y,
                    radius = if (selectedBook == book.id) 34f else 23f,
                    color = dirColor(data, dir),
                    progress = done.toFloat() / total,
                    dim = (selectedBook != null && selectedBook != book.id) ||
                        (selectedDir != null && selectedDir != dir),
                    selected = selectedBook == book.id,
                    dir = dir,
                    bookId = book.id,
                )
            )
            edges.add(GEdge("hub-$dir", book.id, "spine"))
            if (index > 0) {
                val prev = list[index - 1]
                edges.add(GEdge(prev.id, book.id, "branch"))
            }
        }
    }

    // cross-direction links inside the same study stage
    val pairs = mutableSetOf<Pair<String, String>>()
    data.stages.forEach { stage ->
        val ids = stage.books.map { it.book }.filter { data.booksById.containsKey(it) }
        for (i in ids.indices) for (j in i + 1 until ids.size) {
            val a = ids[i]; val b = ids[j]
            if (data.dirOf(a) == data.dirOf(b)) continue
            val key = if (a < b) a to b else b to a
            if (pairs.add(key)) edges.add(GEdge(a, b, "cross"))
        }
    }

    // knowledge layers: one node per layer, at the centroid of its books, pulled inward
    data.layers.forEach { layer ->
        val pts = layer.books.mapNotNull { pos[it] }
        if (pts.isNotEmpty()) {
            val cx = pts.map { it.x }.average().toFloat()
            val cy = pts.map { it.y }.average().toFloat()
            val lx = CX + (cx - CX) * 0.58f
            val ly = CY + (cy - CY) * 0.58f
            add(
                GNode(
                    id = layer.id,
                    label = layer.label,
                    kind = "layer",
                    x = lx,
                    y = ly,
                    radius = 18f,
                    color = dirColor(data, layer.dir),
                    progress = 0f,
                    dim = (selectedDir != null && selectedDir != layer.dir) ||
                        (selectedBook != null && dirOf(data, selectedBook) != layer.dir),
                    selected = false,
                    dir = layer.dir,
                )
            )
            edges.add(GEdge("hub-${layer.dir}", layer.id, "spine"))
            layer.books.forEach { bid ->
                if (pos.containsKey(bid)) edges.add(GEdge(layer.id, bid, "contains"))
            }
        }
    }

    // chapters of the selected book, pushed further out (deeper layer)
    selectedBook?.let { bookId ->
        val center = pos[bookId]
        val chapters = data.chaptersByBook[bookId].orEmpty()
        if (center != null && chapters.isNotEmpty()) {
            val outward = Math.toDegrees(
                atan2((center.y - CY).toDouble(), (center.x - CX).toDouble())
            ).toFloat()
            val spread = 230f
            chapters.forEachIndexed { index, chapter ->
                val t = if (chapters.size == 1) 0.5f else index.toFloat() / (chapters.size - 1)
                val angle = outward - spread / 2 + spread * t
                val rad = Math.toRadians(angle.toDouble())
                val rr = if (index % 2 == 0) R_CHAPTER else R_CHAPTER * 1.42f
                val x = center.x + cos(rad).toFloat() * rr * 0.92f
                val y = center.y + sin(rad).toFloat() * rr * 1.10f
                val done = store.doneInChapter(chapter)
                add(
                    GNode(
                        id = chapter.id,
                        label = chapter.title,
                        kind = "chapter",
                        x = x, y = y, radius = 21f,
                        color = dirColor(data, data.dirOf(bookId)),
                        progress = done / chapter.demos.size.toFloat().coerceAtLeast(1f),
                        dim = false,
                        selected = done == chapter.demos.size,
                        dir = data.dirOf(bookId),
                        bookId = bookId,
                        chapterId = chapter.id,
                    )
                )
                edges.add(GEdge(bookId, chapter.id, "branch"))
                if (index > 0) {
                    edges.add(GEdge(chapters[index - 1].id, chapter.id, "cross"))
                }
            }
        }
    }

    return GLayout(nodes, edges)
}

private fun dirColor(data: AppData, dir: String): Color {
    val hex = data.directions.firstOrNull { it.id == dir }?.color ?: 0xFF8A94A6
    return Color(hex)
}

private class GraphCache {
    val labels = HashMap<String, TextLayoutResult>()
    val hexes = HashMap<String, Path>()
    val glows = HashMap<String, Brush>()
}

private fun hexPath(cx: Float, cy: Float, radius: Float): Path {
    val p = Path()
    for (i in 0 until 6) {
        val a = Math.toRadians((-90f + i * 60f).toDouble())
        val x = cx + cos(a).toFloat() * radius
        val y = cy + sin(a).toFloat() * radius
        if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
    }
    p.close()
    return p
}

/* ------------------------------------------------------------------ screen */

@Composable
fun GraphScreen(
    data: AppData,
    store: ProgressStore,
    selectedDir: String?,
    selectedBook: String?,
    onSelectDir: (String?) -> Unit,
    onSelectBook: (String?) -> Unit,
    onOpenChapter: (String) -> Unit,
    onOpenBook: (String) -> Unit = {},
    selectedLayer: String? = null,
    onSelectLayer: (String) -> Unit = {},
) {
    var zoom by remember { mutableStateOf(1f) }
    var pan by remember { mutableStateOf(Offset(0f, -18f)) }
    androidx.compose.runtime.LaunchedEffect(selectedLayer) {
        if (selectedLayer != null) { zoom = 1.15f; pan = Offset.Zero }
    }
    val layout = remember(selectedBook, selectedDir, store.done) {
        buildLayout(data, store, selectedDir, selectedBook)
    }
    val bounds = remember(layout) {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val focus = layout.nodes.filter { !it.dim }
        val use = if (selectedBook != null && focus.size > 2) focus else layout.nodes
        use.forEach { n ->
            if (n.x - n.radius < minX) minX = n.x - n.radius
            if (n.x + n.radius > maxX) maxX = n.x + n.radius
            if (n.y - n.radius < minY) minY = n.y - n.radius
            if (n.y + n.radius > maxY) maxY = n.y + n.radius
        }
        androidx.compose.ui.geometry.Rect(minX, minY, maxX, maxY)
    }
    val measurer = rememberTextMeasurer()
    val cache = remember(layout) { GraphCache() }

    Box(Modifier.fillMaxSize()) {
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(layout) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        var travelled = 0f
                        var maxPointers = 1
                        var lastCentroid = down.position
                        var lastSpan = 0f
                        var moved = false

                        while (true) {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.isEmpty()) break
                            maxPointers = maxOf(maxPointers, pressed.size)

                            val centroid = pressed.fold(Offset.Zero) { acc, c -> acc + c.position } /
                                pressed.size.toFloat()
                            if (pressed.size >= 2) {
                                val span = (pressed[0].position - pressed[1].position).getDistance()
                                if (lastSpan > 0f && span > 0f) {
                                    zoom = (zoom * (span / lastSpan)).coerceIn(0.55f, 3.4f)
                                }
                                lastSpan = span
                                moved = true
                            }
                            if (pressed.size == 1) {
                                travelled += (pressed[0].position - lastCentroid).getDistance()
                            }
                            if (travelled > 16f) moved = true
                            if (moved) {
                                pan += centroid - lastCentroid
                                event.changes.forEach { it.consume() }
                            }
                            lastCentroid = centroid
                        }

                        if (!moved && maxPointers == 1) {
                            val s = minOf(size.width.toFloat() / (bounds.width + 410f), size.height.toFloat() / (bounds.height + 148f)) *
                                zoom * 0.97f
                            val ox = (size.width.toFloat() - (bounds.width + 410f) * s) / 2f -
                                (bounds.left - 205f) * s + pan.x
                            val oy = (size.height.toFloat() - (bounds.height + 148f) * s) / 2f -
                                (bounds.top - 74f) * s + pan.y
                            val vx = (down.position.x - ox) / s
                            val vy = (down.position.y - oy) / s
                            val node = layout.nodes.minByOrNull { hypot(it.x - vx, it.y - vy) }
                            val hit = node != null &&
                                hypot(node.x - vx, node.y - vy) < 220f
                            if (hit && node != null) {
                                when (node.kind) {
                                    "hub" -> {
                                        Sfx.click(pitch = 1.0f)
                                        onSelectBook(null)
                                        onSelectDir(if (selectedDir == node.dir) null else node.dir)
                                    }
                                    "book", "foundation" -> {
                                        Sfx.click(pitch = 1.06f)
                                        onOpenBook(node.id)
                                    }
                                    "layer" -> {
                                        Sfx.click(pitch = 1.02f)
                                        onSelectBook(null)
                                        onSelectLayer(node.id)
                                    }
                                    "foundation" -> {
                                        Sfx.click(pitch = 1.12f)
                                        onSelectBook(if (selectedBook == node.id) null else node.id)
                                    }
                                    "chapter" -> {
                                        Sfx.on()
                                        node.chapterId?.let(onOpenChapter)
                                    }
                                    "core" -> {
                                        Sfx.click(pitch = 0.82f)
                                        onSelectBook(null)
                                        onSelectDir(null)
                                    }
                                }
                            } else if (selectedBook != null) {
                                Sfx.click(pitch = 0.9f)
                                onSelectBook(null)
                            }
                        }
                    }
                }
        ) {
            val padX = 205f
            val padY = 74f
            val bw = (bounds.width + padX * 2f).coerceAtLeast(1f)
            val bh = (bounds.height + padY * 2f).coerceAtLeast(1f)
            val s = minOf(size.width / bw, size.height / bh) * zoom * 0.97f
            val ox = (size.width - bw * s) / 2f - (bounds.left - padX) * s + pan.x
            val oy = (size.height - bh * s) / 2f - (bounds.top - padY) * s + pan.y
            translate(ox, oy) {
                scale(s, s, pivot = Offset.Zero) {
                    val byId = layout.nodes.associateBy { it.id }

                    // edges
                    layout.edges.forEach { edge ->
                        val a = byId[edge.a] ?: return@forEach
                        val b = byId[edge.b] ?: return@forEach
                        if (a.dim && b.dim) return@forEach
                        val alpha = when (edge.kind) {
                            "spine" -> 0.34f
                            "branch" -> 0.20f
                            else -> 0.11f
                        }
                        val color = when (edge.kind) {
                            "cross" -> CYAN.copy(alpha = alpha * 0.72f)
                            else -> CYAN.copy(alpha = alpha)
                        }
                        val effect = if (edge.kind == "cross") {
                            PathEffect.dashPathEffect(floatArrayOf(7f, 9f), 0f)
                        } else null
                        drawLine(
                            color = color.copy(alpha = color.alpha * 0.28f),
                            start = Offset(a.x, a.y),
                            end = Offset(b.x, b.y),
                            strokeWidth = 7f,
                            pathEffect = effect,
                        )
                        drawLine(
                            color = color,
                            start = Offset(a.x, a.y),
                            end = Offset(b.x, b.y),
                            strokeWidth = if (edge.kind == "spine") 2.0f else 1.2f,
                            pathEffect = effect,
                        )
                    }

                    // nodes
                    layout.nodes.forEach { node ->
                        val alpha = if (node.dim) 0.14f else 1f
                        val c = node.color.copy(alpha = alpha)

                        val glow = cache.glows.getOrPut(node.id) {
                            Brush.radialGradient(
                                colors = listOf(node.color.copy(alpha = 0.30f), Color.Transparent),
                                center = Offset(node.x, node.y),
                                radius = node.radius * 2.6f,
                            )
                        }
                        drawCircle(
                            brush = glow,
                            radius = node.radius * 2.6f,
                            center = Offset(node.x, node.y),
                            alpha = alpha,
                        )

                        if (node.kind == "chapter" || node.kind == "foundation") {
                            drawCircle(
                                color = INK.copy(alpha = 0.9f * alpha),
                                radius = node.radius,
                                center = Offset(node.x, node.y),
                            )
                            drawCircle(
                                color = c.copy(alpha = 0.85f * alpha),
                                radius = node.radius,
                                center = Offset(node.x, node.y),
                                style = Stroke(width = 1.6f),
                            )
                        } else {
                            val hex = cache.hexes.getOrPut(node.id) { hexPath(node.x, node.y, node.radius) }
                            drawPath(hex, color = INK.copy(alpha = 0.92f * alpha))
                            drawPath(hex, color = c.copy(alpha = 0.95f * alpha), style = Stroke(width = 2.2f))
                            drawPath(
                                cache.hexes.getOrPut("${node.id}-inner") {
                                    hexPath(node.x, node.y, node.radius * 0.72f)
                                },
                                color = c.copy(alpha = 0.22f * alpha),
                                style = Stroke(width = 1f),
                            )
                        }

                        if (node.progress > 0.001f) {
                            drawArc(
                                color = CYAN.copy(alpha = 0.95f * alpha),
                                startAngle = -90f,
                                sweepAngle = 360f * node.progress.coerceIn(0f, 1f),
                                useCenter = false,
                                topLeft = Offset(node.x - node.radius - 7f, node.y - node.radius - 7f),
                                size = androidx.compose.ui.geometry.Size(
                                    (node.radius + 7f) * 2f,
                                    (node.radius + 7f) * 2f,
                                ),
                                style = Stroke(width = 3.2f),
                            )
                        }

                        if (node.selected) {
                            val pulse = 1.06f
                            drawCircle(
                                color = c.copy(alpha = 0.55f),
                                radius = node.radius * 1.45f * pulse,
                                center = Offset(node.x, node.y),
                                style = Stroke(width = 1.6f),
                            )
                        }
                    }

                    // labels are drawn inside the same transform, so they stay glued to nodes
                    val visL = -ox / s
                    val visT = -oy / s
                    val visR = visL + size.width / s
                    val visB = visT + size.height / s
                    layout.nodes.forEach { node ->
                        if (node.kind == "core") return@forEach
                        if (node.kind == "chapter" && s < 0.24f && !node.selected) return@forEach
                        // a node outside the screen must not clamp its label back into view
                        val pad = node.radius * 0.6f
                        if (node.x < visL - pad || node.x > visR + pad) return@forEach
                        if (node.y < visT - pad || node.y > visB + pad) return@forEach
                        drawNodeLabel(
                            measurer, cache, node, visL, visT, visR, visB, s
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ background */

private fun DrawScope.drawCyberBackground(time: Float, s: Float, ox: Float, oy: Float) {
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color(0xFF0B1420), Color(0xFF05080F)),
            center = Offset(size.width * 0.5f, size.height * 0.42f),
            radius = size.height * 0.75f,
        )
    )

    val grid = 74f * s
    val gridAlpha = 0.05f
    var x = (ox % grid) - grid
    while (x < size.width) {
        drawLine(Color(0xFF2BB6D6).copy(alpha = gridAlpha), Offset(x, 0f), Offset(x, size.height), 1f)
        x += grid
    }
    var y = (oy % grid) - grid
    while (y < size.height) {
        drawLine(Color(0xFF2BB6D6).copy(alpha = gridAlpha), Offset(0f, y), Offset(size.width, y), 1f)
        y += grid
    }

    // slow rotating sweep from the graph centre
    val cx = size.width / 2f + (0f) * s
    val cy = size.height / 2f
    val sweepAngle = (time * 16f) % 360f
    val r = size.height * 0.62f
    drawArc(
        color = Color(0xFF54E4FF).copy(alpha = 0.13f),
        startAngle = sweepAngle,
        sweepAngle = 46f,
        useCenter = false,
        topLeft = Offset(cx - r, cy - r),
        size = androidx.compose.ui.geometry.Size(r * 2f, r * 2f),
        style = Stroke(width = 2f),
    )

    // vignette
    drawRect(
        Brush.radialGradient(
            colors = listOf(Color.Transparent, Color(0xFF02040A).copy(alpha = 0.85f)),
            center = Offset(cx, cy),
            radius = size.height * 0.62f,
        )
    )
}

/* ------------------------------------------------------------------ labels */

private fun DrawScope.drawNodeLabel(
    measurer: TextMeasurer,
    cache: GraphCache,
    node: GNode,
    visL: Float,
    visT: Float,
    visR: Float,
    visB: Float,
    s: Float,
) {
    // keep a constant on-screen size by dividing out the canvas scale
    val base = when (node.kind) {
        "hub" -> 11.5f
        "layer" -> 9.5f
        "book", "foundation" -> 9f
        "chapter" -> 8.5f
        else -> 9f
    }
    val fontSp = (base / s.coerceAtLeast(0.25f)).toFloat()
    val maxW = when (node.kind) {
        "hub" -> 250
        "layer" -> 160
        "book" -> 200
        "chapter" -> 160
        else -> 180
    }
    val drift = when (node.kind) {
        "hub" -> 46f
        "layer" -> 40f
        "book", "foundation" -> 36f
        else -> 30f
    }
    val key = node.id + (if (node.selected) "*" else "") + "#" + (s * 10).toInt()
    val layout: TextLayoutResult = cache.labels.getOrPut(key) {
        measurer.measure(
            text = node.label,
            style = TextStyle(
                fontSize = fontSp.sp,
                color = when (node.kind) {
                    "hub" -> Color(0xFFEAFDFF)
                    "layer" -> Color(0xFF9FE6F2)
                    "book", "foundation" -> Color(0xFFCDE9F4)
                    else -> Color(0xFFA8CBD8)
                },
                fontWeight = if (node.kind == "hub" || node.selected) FontWeight.SemiBold else FontWeight.Normal,
            ),
            maxLines = 2,
            softWrap = true,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            constraints = Constraints(maxWidth = (maxW / s.coerceAtLeast(0.25f)).toInt().coerceAtLeast(120)),
        )
    }
    val w = layout.size.width
    val h = layout.size.height
    // place the text radially away from the graph centre so neighbours fan out
    val ux = ((node.x - CX) / 620f).coerceIn(-1f, 1f)
    val uy = ((node.y - CY) / 950f).coerceIn(-1f, 1f)
    var x: Float
    var y: Float
    if (kotlin.math.abs(ux) > 0.55f) {
        x = if (ux > 0) node.x + drift else node.x - drift - w
        y = node.y - h / 2f
    } else {
        x = node.x - w / 2f
        y = if (uy > 0) node.y + drift else node.y - drift - h
    }
    x = x.coerceIn(visL + 12f, (visR - w - 12f).coerceAtLeast(visL + 12f))
    y = y.coerceIn(visT + 12f, (visB - h - 12f).coerceAtLeast(visT + 12f))
    drawText(textLayoutResult = layout, topLeft = Offset(x, y))
}


/** Compact label used inside the dense graph: drop the subtitle and clamp length. */
private fun shortLabel(title: String): String {
    val cut = title.split("：", "—", "（", "·").firstOrNull()?.trim().orEmpty()
    val base = if (cut.length >= 4) cut else title
    return if (base.length <= 14) base else base.take(13) + "…"
}
            val placedLabels = mutableListOf<androidx.compose.ui.geometry.Rect>()
