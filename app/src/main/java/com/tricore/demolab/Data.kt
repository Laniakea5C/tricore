package com.tricore.demolab

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

// --------------------------------------------------------------------------- models

data class Demo(
    val id: String,
    val level: String,
    val title: String,
    val task: String,
    val check: String,
)

data class Chapter(
    val id: String,
    val book: String,
    val n: Int,
    val title: String,
    val points: List<String>,
    val demos: List<Demo>,
    val tags: List<String> = emptyList(),
)

data class Book(
    val id: String,
    val title: String,
    val edition: String,
    val dir: String,
    val kind: String,
    val chapterCount: Int,
    val demoCount: Int,
    val layer: String = "",
    val stage: String = "",
    val read: String = "",
    val tags: List<String> = emptyList(),
)

data class Layer(
    val id: String,
    val label: String,
    val dir: String,
    val desc: String,
    val books: List<String>,
)

data class PromptTemplate(
    val id: String,
    val name: String,
    val desc: String,
    val fields: List<String>,
    val prompt: String,
)

data class RouteStep(
    val title: String,
    val goal: String,
    val books: String,
    val weekly: String,
    val output: String,
)

data class Route(
    val id: String,
    val title: String,
    val sub: String,
    val steps: List<RouteStep>,
)

data class StageBook(val book: String, val chapters: List<Int>)

data class Stage(
    val id: String,
    val title: String,
    val semester: String,
    val focus: String,
    val books: List<StageBook>,
)

data class Direction(val id: String, val name: String, val color: Long)

data class Tool(
    val dir: String,
    val category: String,
    val name: String,
    val url: String,
    val note: String,
    val level: String,
    val detail: String = "",
)

data class Resource(
    val name: String,
    val url: String,
    val kind: String,
    val dir: String,
    val note: String,
)

data class TimelinePhase(val phase: String, val items: List<String>)

data class IndustryBlock(
    val dir: String,
    val title: String,
    val must: List<String>,
    val portfolio: List<String>,
    val signals: List<String>,
    val timeline: List<TimelinePhase>,
    val detail: String = "",
    val mustDetail: List<String> = emptyList(),
    val portfolioDetail: List<String> = emptyList(),
    val signalsDetail: List<String> = emptyList(),
)

data class GraphNode(
    val id: String,
    val label: String,
    val level: Int,
    val dir: String,
    val kind: String,
    val book: String?,
)

data class GraphEdge(val a: String, val b: String, val kind: String)

class AppData(
    val version: String,
    val bookCount: Int,
    val chapterCount: Int,
    val demoCount: Int,
    val stageCount: Int,
    val directions: List<Direction>,
    val books: List<Book>,
    val chapters: List<Chapter>,
    val stages: List<Stage>,
    val tools: List<Tool>,
    val resources: List<Resource>,
    val industry: List<IndustryBlock>,
    val nodes: List<GraphNode>,
    val edges: List<GraphEdge>,
    val layers: List<Layer> = emptyList(),
    val templates: List<PromptTemplate> = emptyList(),
    val routes: List<Route> = emptyList(),
) {
    val booksById: Map<String, Book> = books.associateBy { it.id }
    val chaptersById: Map<String, Chapter> = chapters.associateBy { it.id }
    val chaptersByBook: Map<String, List<Chapter>> = chapters.groupBy { it.book }
    val demoTotal: Int = chapters.sumOf { it.demos.size }

    fun dirOf(bookId: String): String = booksById[bookId]?.dir ?: "base"
}

// --------------------------------------------------------------------------- parsing

private fun JSONArray.strings(): List<String> = (0 until length()).map { optString(it) }

fun parseAppData(raw: String): AppData {
    val root = JSONObject(raw)
    val meta = root.optJSONObject("meta") ?: JSONObject()

    val directions = root.optJSONArray("directions").orEmpty().let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Direction(o.getString("id"), o.getString("name"), parseColor(o.optString("color", "#8A94A6")))
        }
    }

    val books = root.optJSONArray("books").orEmpty().let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Book(
                id = o.getString("id"),
                title = o.getString("title"),
                edition = o.optString("edition"),
                dir = o.optString("dir", "base"),
                kind = o.optString("kind", "textbook"),
                chapterCount = o.optInt("chapterCount"),
                demoCount = o.optInt("demoCount"),
                layer = o.optString("layer"),
                stage = o.optString("stage"),
                read = o.optString("read"),
                tags = o.optJSONArray("tags").orEmpty().strings(),
            )
        }
    }

    val chapters = root.optJSONArray("chapters").orEmpty().let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val demos = o.optJSONArray("demos").orEmpty().let { ds ->
                (0 until ds.length()).map { j ->
                    val d = ds.getJSONObject(j)
                    Demo(
                        id = d.getString("id"),
                        level = d.optString("level", "A"),
                        title = d.optString("title"),
                        task = d.optString("task"),
                        check = d.optString("check"),
                    )
                }
            }
            Chapter(
                id = o.getString("id"),
                book = o.getString("book"),
                n = o.optInt("n"),
                title = o.optString("title"),
                points = o.optJSONArray("points").orEmpty().strings(),
                demos = demos,
                tags = o.optJSONArray("tags").orEmpty().strings(),
            )
        }
    }

    val stages = root.optJSONArray("stages").orEmpty().let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val bs = o.optJSONArray("books").orEmpty().let { bsArr ->
                (0 until bsArr.length()).map { j ->
                    val b = bsArr.getJSONObject(j)
                    StageBook(
                        book = b.getString("book"),
                        chapters = b.optJSONArray("chapters").orEmpty().let { c ->
                            (0 until c.length()).map { k -> c.getInt(k) }
                        },
                    )
                }
            }
            Stage(
                id = o.getString("id"),
                title = o.optString("title"),
                semester = o.optString("semester"),
                focus = o.optString("focus"),
                books = bs,
            )
        }
    }

    val toolchain = root.optJSONObject("toolchain")
    val tools = mutableListOf<Tool>()
    if (toolchain != null) {
        for (dir in listOf("ai", "chip", "robot", "base")) {
            val arr = toolchain.optJSONArray(dir) ?: continue
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                tools.add(
                    Tool(
                        dir = dir,
                        category = o.optString("category"),
                        name = o.optString("name"),
                        url = o.optString("url"),
                        note = o.optString("note"),
                        level = o.optString("level", "必学"),
                        detail = o.optString("detail"),
                    )
                )
            }
        }
    }

    val resources = root.optJSONArray("resources").orEmpty().let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Resource(
                name = o.optString("name"),
                url = o.optString("url"),
                kind = o.optString("kind"),
                dir = o.optString("dir", "base"),
                note = o.optString("note"),
            )
        }
    }

    val industryRoot = root.optJSONObject("industry")
    val industry = mutableListOf<IndustryBlock>()
    if (industryRoot != null) {
        for (dir in listOf("ai", "chip", "robot", "base")) {
            val o = industryRoot.optJSONObject(dir) ?: continue
            val timeline = o.optJSONArray("timeline").orEmpty().let { arr ->
                (0 until arr.length()).map { i ->
                    val t = arr.getJSONObject(i)
                    TimelinePhase(t.optString("phase"), t.optJSONArray("items").orEmpty().strings())
                }
            }
            industry.add(
                IndustryBlock(
                    dir = dir,
                    title = o.optString("title", dir),
                    must = o.optJSONArray("must").orEmpty().strings(),
                    portfolio = o.optJSONArray("portfolio").orEmpty().strings(),
                    signals = o.optJSONArray("signals").orEmpty().strings(),
                    timeline = timeline,
                    detail = o.optString("detail"),
                    mustDetail = o.optJSONArray("mustDetail").orEmpty().strings(),
                    portfolioDetail = o.optJSONArray("portfolioDetail").orEmpty().strings(),
                    signalsDetail = o.optJSONArray("signalsDetail").orEmpty().strings(),
                )
            )
        }
    }

    val graph = root.optJSONObject("graph")
    val nodes = graph?.optJSONArray("nodes").orEmpty().let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GraphNode(
                id = o.getString("id"),
                label = o.optString("label"),
                level = o.optInt("level"),
                dir = o.optString("dir", "base"),
                kind = o.optString("kind", "chapter"),
                book = if (o.has("book")) o.optString("book") else null,
            )
        }
    }
    val edges = graph?.optJSONArray("edges").orEmpty().let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            GraphEdge(o.getString("a"), o.getString("b"), o.optString("kind", "contains"))
        }
    }

    val layers = root.optJSONArray("layers").orEmpty().let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Layer(
                id = o.getString("id"),
                label = o.optString("label"),
                dir = o.optString("dir", "base"),
                desc = o.optString("desc"),
                books = o.optJSONArray("books").orEmpty().strings(),
            )
        }
    }

    val templates = root.optJSONArray("templates").orEmpty().let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            PromptTemplate(
                id = o.optString("id"),
                name = o.optString("name"),
                desc = o.optString("desc"),
                fields = o.optJSONArray("fields").orEmpty().strings(),
                prompt = o.optString("prompt"),
            )
        }
    }

    val routes = root.optJSONArray("routes").orEmpty().let { arr ->
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val steps = o.optJSONArray("steps").orEmpty().let { sa ->
                (0 until sa.length()).map { j ->
                    val st = sa.getJSONObject(j)
                    RouteStep(
                        title = st.optString("title"),
                        goal = st.optString("goal"),
                        books = st.optString("books"),
                        weekly = st.optString("weekly"),
                        output = st.optString("output"),
                    )
                }
            }
            Route(
                id = o.optString("id"),
                title = o.optString("title"),
                sub = o.optString("sub"),
                steps = steps,
            )
        }
    }

    return AppData(
        version = meta.optString("version", "3.0"),
        bookCount = meta.optInt("bookCount", books.size),
        chapterCount = meta.optInt("chapterCount", chapters.size),
        demoCount = meta.optInt("demoCount", chapters.sumOf { it.demos.size }),
        stageCount = meta.optInt("stageCount", stages.size),
        directions = directions,
        books = books,
        chapters = chapters,
        stages = stages,
        tools = tools,
        resources = resources,
        industry = industry,
        nodes = nodes,
        edges = edges,
        layers = layers,
        templates = templates,
        routes = routes,
    )
}

private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

fun parseColor(hex: String): Long {
    val cleaned = hex.removePrefix("#")
    return when (cleaned.length) {
        6 -> 0xFF000000L or cleaned.toLong(16)
        8 -> cleaned.toLong(16)
        else -> 0xFF8A94A6
    }
}

// --------------------------------------------------------------------------- progress store

class ProgressStore(context: Context) {
    private val prefs = context.getSharedPreferences("tricore_progress", Context.MODE_PRIVATE)

    var done by mutableStateOf(load())
        private set
    private val bookmarks = mutableStateMapOf<String, Boolean>()

    private fun load(): Set<String> = prefs.getStringSet("done", emptySet())?.toSet() ?: emptySet()

    fun isDone(demoId: String): Boolean = done.contains(demoId)

    fun toggle(demoId: String) {
        val next = done.toMutableSet()
        if (!next.add(demoId)) next.remove(demoId)
        done = next
        prefs.edit().putStringSet("done", next).apply()
    }

    fun doneInChapter(chapter: Chapter): Int = chapter.demos.count { done.contains(it.id) }

    fun isChapterDone(chapter: Chapter): Boolean =
        chapter.demos.isNotEmpty() && chapter.demos.all { done.contains(it.id) }

    /** toggles the whole chapter in one write */
    fun toggleChapter(chapter: Chapter) {
        val next = done.toMutableSet()
        val allDone = chapter.demos.all { next.contains(it.id) }
        chapter.demos.forEach { if (allDone) next.remove(it.id) else next.add(it.id) }
        done = next
        prefs.edit().putStringSet("done", next).apply()
    }

    fun doneInBook(bookId: String, chapters: List<Chapter>): Int =
        chapters.filter { it.book == bookId }.sumOf { doneInChapter(it) }

    fun doneByDir(data: AppData, dir: String): Pair<Int, Int> {
        var doneCount = 0
        var total = 0
        for (chapter in data.chapters) {
            if (data.dirOf(chapter.book) != dir) continue
            total += chapter.demos.size
            doneCount += doneInChapter(chapter)
        }
        return doneCount to total
    }

    fun exportText(): String = org.json.JSONArray(done.sorted()).toString()

    fun importText(text: String): Int = runCatching {
        val arr = org.json.JSONArray(text.trim())
        val next = mutableSetOf<String>()
        for (i in 0 until arr.length()) next.add(arr.getString(i))
        done = next
        prefs.edit().putStringSet("done", next).apply()
        next.size
    }.getOrDefault(-1)

    fun reset() {
        done = emptySet()
        prefs.edit().clear().apply()
    }

    fun toggleBookmark(id: String) {
        bookmarks[id] = !(bookmarks[id] ?: false)
    }
}
