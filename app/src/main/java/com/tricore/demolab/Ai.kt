package com.tricore.demolab

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/* ------------------------------------------------------------------ providers */

data class AiProvider(
    val id: String,
    val name: String,
    val base: String,
    val kind: String,        // openai | anthropic | gemini
    val modelsPath: String,  // "" when the provider lists models differently
    val docUrl: String,
)

val AI_PROVIDERS = listOf(
    AiProvider("deepseek", "DeepSeek", "https://api.deepseek.com", "openai", "/models", "https://platform.deepseek.com"),
    AiProvider("glm", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", "openai", "/models", "https://open.bigmodel.cn"),
    AiProvider("kimi", "Kimi / Moonshot", "https://api.moonshot.cn/v1", "openai", "/models", "https://platform.moonshot.cn"),
    AiProvider("qwen", "Qwen / 通义", "https://dashscope.aliyuncs.com/compatible-mode/v1", "openai", "/models", "https://dashscope.aliyun.com"),
    AiProvider("openai", "OpenAI", "https://api.openai.com/v1", "openai", "/models", "https://platform.openai.com"),
    AiProvider("claude", "Claude / Anthropic", "https://api.anthropic.com/v1", "anthropic", "/models", "https://console.anthropic.com"),
    AiProvider("grok", "Grok / xAI", "https://api.x.ai/v1", "openai", "/models", "https://console.x.ai"),
    AiProvider("gemini", "Gemini", "https://generativelanguage.googleapis.com/v1beta", "gemini", "", "https://aistudio.google.com"),
    AiProvider("minimax", "MiniMax", "https://api.minimax.chat/v1", "openai", "/models", "https://platform.minimaxi.com"),
    AiProvider("mimo", "MiMo / 小米", "https://api.mimo.xiaomi.com/v1", "openai", "/models", "https://platform.mimo.xiaomi.com"),
    AiProvider("custom", "自定义 / 第三方", "", "openai", "/models", ""),
)

fun providerOf(id: String): AiProvider = AI_PROVIDERS.firstOrNull { it.id == id } ?: AI_PROVIDERS[0]

/* ------------------------------------------------------------------ settings */

class AiStore(context: Context) {
    val prefs = context.getSharedPreferences("tricore_ai", Context.MODE_PRIVATE)

    fun apiKey(p: String): String = prefs.getString("key_$p", "") ?: ""
    fun model(p: String): String = prefs.getString("model_$p", "") ?: ""
    fun base(p: String): String = prefs.getString("base_$p", "") ?: ""
    fun lastProvider(): String = prefs.getString("last", "deepseek") ?: "deepseek"

    fun saveKey(p: String, v: String) = prefs.edit().putString("key_$p", v).apply()
    fun saveModel(p: String, v: String) = prefs.edit().putString("model_$p", v).apply()
    fun saveBase(p: String, v: String) = prefs.edit().putString("base_$p", v).apply()
    fun saveLast(p: String) = prefs.edit().putString("last", p).apply()

    fun deepThink(): Boolean = prefs.getBoolean("deep_think", false)
    fun webSearch(): Boolean = prefs.getBoolean("web_search", false)
    fun saveToggles(deep: Boolean, web: Boolean) {
        prefs.edit().putBoolean("deep_think", deep).putBoolean("web_search", web).apply()
    }

    // token usage, per provider
    fun promptTokens(p: String): Int = prefs.getInt("pt_$p", 0)
    fun completionTokens(p: String): Int = prefs.getInt("ct_$p", 0)
    fun addUsage(p: String, prompt: Int, completion: Int) {
        prefs.edit()
            .putInt("pt_$p", promptTokens(p) + prompt)
            .putInt("ct_$p", completionTokens(p) + completion)
            .apply()
    }

    fun resetUsage(p: String) = prefs.edit().remove("pt_$p").remove("ct_$p").apply()

    fun baseFor(p: AiProvider): String = base(p.id).ifBlank { p.base }
    fun kind(p: String): String = prefs.getString("kind_$p", "") ?: ""
    fun modelsPath(p: String): String = prefs.getString("path_$p", "") ?: ""
    fun saveKind(p: String, v: String) = prefs.edit().putString("kind_$p", v).apply()
    fun saveModelsPath(p: String, v: String) = prefs.edit().putString("path_$p", v).apply()

    fun config(p: AiProvider): AiConfig = AiConfig(
        provider = p,
        apiKey = apiKey(p.id),
        model = model(p.id),
        base = baseFor(p),
        kind = kind(p.id).ifBlank { p.kind },
        modelsPath = modelsPath(p.id).ifBlank { p.modelsPath },
    )
}

data class AiConfig(
    val provider: AiProvider,
    val apiKey: String,
    val model: String,
    val base: String,
    val kind: String = "openai",
    val modelsPath: String = "/models",
)

data class ChatMessage(
    val role: String,
    val text: String,
    val imageBase64: String? = null,
    val imageMime: String = "image/jpeg",
    val reasoning: String? = null,
)  // system | user | assistant

/** Scenario prompts: each one is a ready-to-use, deliberately constrained system prompt. */
data class AiScenario(val id: String, val title: String, val system: String)

val AI_SCENARIOS = listOf(
    AiScenario(
        "mentor", "学习导师",
        "你是电子信息专业的学习导师，熟悉神经网络、芯片设计（数字/模拟/验证）、机器人三个方向，"
            + "也熟悉从本科到硕士博士再到业界的路径。回答要求：先给结论，再给依据；"
            + "给到具体书目、章节与练习级别（A 概念验证 / B 工程实现 / C 跨方向）；"
            + "不编造论文、库或 API，不确定时明确说不确定。"
    ),
    AiScenario(
        "coach", "进度教练",
        "你是严格但务实的学习教练。用户会给你已完成的书/章/练习数据。"
            + "你要做三件事：1) 用数据指出 3 个最薄弱的环节（说明依据）；"
            + "2) 给出未来 4 周计划，每周列书目、章节、练习级别、验收标准；"
            + "3) 指出当前进度与三大方向就业要求的差距。不要泛泛鼓励，要可执行。"
    ),
    AiScenario(
        "tutor", "知识答疑",
        "你是耐心的一对一助教。先确认用户卡在哪一步，再用最小例子讲清概念，"
            + "然后给一个手算/手推的小练习并给出答案。避免堆砌术语，公式要说明每个符号含义。"
    ),
    AiScenario(
        "quiz", "出题批改",
        "你是出题人。根据用户指定的书与章节，生成由易到难的练习题：概念题、计算题、实现题各若干，"
            + "附评分点与参考答案。用户提交答案后，按评分点逐条批改并指出错误原因。"
    ),
    AiScenario(
        "vision", "看图讲题",
        "你能看图。用户会发教材截图、电路图、公式推导、代码或实验波形。"
            + "请先复述图里的关键信息（元件/公式/代码/曲线），确认理解无误，再分步讲解或纠错；"
            + "看不清的地方直接说看不清，不要猜。"
    ),
)

/* -------------------------------------------------------------- attachments */

fun readTextAttachment(ctx: android.content.Context, uri: android.net.Uri, limit: Int = 20000): String? =
    runCatching {
        ctx.contentResolver.openInputStream(uri)?.use { stream ->
            val bytes = stream.readBytes()
            val text = bytes.toString(Charsets.UTF_8)
            if (text.count { it == '\u0000' } > 8) null else text.take(limit)
        }
    }.getOrNull()

/** Downscale to <=1024px and return (mime, base64). */
fun imageAttachment(ctx: android.content.Context, uri: android.net.Uri): Pair<String, String>? =
    runCatching {
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, opts)
        }
        var sample = 1
        while (opts.outWidth / sample > 1024 || opts.outHeight / sample > 1024) sample *= 2
        val decodeOpts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = ctx.contentResolver.openInputStream(uri)?.use {
            android.graphics.BitmapFactory.decodeStream(it, null, decodeOpts)
        } ?: return null
        val out = java.io.ByteArrayOutputStream()
        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, out)
        val b64 = android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        "image/jpeg" to b64
    }.getOrNull()

data class AiModelInfo(
    val id: String,
    val context: Int? = null,
    val output: Int? = null,
)

data class AiResult(
    val text: String,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val reasoning: String = "",
    val truncated: Boolean = false,
    val finishReason: String = "",
)

/* ------------------------------------------------------------------ client */

object AiClient {
    @Volatile private var active: java.net.HttpURLConnection? = null

    /** Abort the in-flight request immediately (used by the stop button). */
    fun cancelActive() {
        runCatching { active?.disconnect() }
        active = null
    }

    private const val TAG = "AiClient"

    suspend fun listModels(cfg: AiConfig): Result<List<String>> =
        listModelInfos(cfg).map { list -> list.map { it.id } }

    suspend fun listModelInfos(cfg: AiConfig): Result<List<AiModelInfo>> = withContext(Dispatchers.IO) {
        runCatching {
            when (cfg.kind) {
                "anthropic" -> {
                    val res = request(
                        "${cfg.base}/models?limit=100", "GET",
                        mapOf("x-api-key" to cfg.apiKey, "anthropic-version" to "2023-06-01")
                    )
                    val arr = JSONObject(res).optJSONArray("data") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        AiModelInfo(
                            id = o.optString("id"),
                            context = if (o.has("max_input_tokens")) o.optInt("max_input_tokens") else null,
                            output = if (o.has("max_tokens")) o.optInt("max_tokens") else null,
                        )
                    }.sortedBy { it.id }
                }
                "gemini" -> {
                    val res = request("${cfg.base}/models?key=${cfg.apiKey}", "GET", emptyMap())
                    val root = JSONObject(res)
                    val arr = root.optJSONArray("models") ?: JSONArray()
                    (0 until arr.length()).mapNotNull { i ->
                        val o = arr.optJSONObject(i) ?: return@mapNotNull null
                        AiModelInfo(
                            id = o.optString("name").removePrefix("models/"),
                            context = if (o.has("inputTokenLimit")) o.optInt("inputTokenLimit") else null,
                            output = if (o.has("outputTokenLimit")) o.optInt("outputTokenLimit") else null,
                        )
                    }.filter { it.id.isNotBlank() }.sortedBy { it.id }
                }
                else -> {
                    val path = cfg.modelsPath.ifBlank { "/models" }
                    val res = request("${cfg.base}${if (path.startsWith("/")) path else "/$path"}", "GET",
                        mapOf("Authorization" to "Bearer ${cfg.apiKey}"))
                    parseIds(JSONObject(res)).map { AiModelInfo(it) }
                }
            }
        }
    }

    private fun parseIds(root: JSONObject): List<String> {
        val arr = root.optJSONArray("data") ?: root.optJSONArray("models") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").ifBlank { o.optString("name") }
            id.ifBlank { null }
        }.sorted()
    }

    /** Streaming chat: emits text deltas as they arrive (OpenAI-compatible + Anthropic). */
    suspend fun chatStream(
        cfg: AiConfig,
        messages: List<ChatMessage>,
        deepThink: Boolean = false,
        webSearch: Boolean = false,
        onText: (String) -> Unit,
        onReason: (String) -> Unit = {},
    ): Result<AiResult> = withContext(Dispatchers.IO) {
        runCatching {
            if (cfg.apiKey.isBlank()) error("请先填写 API Key")
            if (cfg.model.isBlank()) error("请先选择或填写模型")
            when (cfg.kind) {
                "anthropic" -> streamAnthropic(cfg, messages, deepThink, webSearch, onText, onReason)
                "gemini" -> chat(cfg, messages, deepThink, webSearch).getOrThrow()   // Gemini：非流式兜底
                else -> streamOpenAi(cfg, messages, deepThink, webSearch, onText, onReason)
            }
        }
    }

    private fun streamOpenAi(
        cfg: AiConfig,
        messages: List<ChatMessage>,
        deepThink: Boolean,
        webSearch: Boolean,
        onText: (String) -> Unit,
        onReason: (String) -> Unit,
    ): AiResult {
        val arr = JSONArray()
        messages.forEach { m ->
            val content: Any = if (m.imageBase64 != null) {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", m.text))
                    .put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", "data:${m.imageMime};base64,${m.imageBase64}")
                        )
                    )
            } else m.text
            arr.put(JSONObject().put("role", m.role).put("content", content))
        }
        val providerId = cfg.provider.id
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", arr)
            .put("stream", true)
        if (providerId == "deepseek") {
            body.put("thinking", JSONObject().put("type", if (deepThink) "enabled" else "disabled"))
            if (deepThink) body.put("reasoning_effort", "high")
            body.put("max_tokens", if (deepThink) 131072 else 65536)
        } else {
            body.put("max_tokens", 8192)
            if (deepThink && REASONING_HINT.any { cfg.model.contains(it, true) }) {
                body.put("reasoning_effort", "medium")
            }
        }
        body.put("temperature", 0.6)
        if (webSearch && (providerId == "openai" || providerId == "grok")) {
            body.put("tools", JSONArray().put(JSONObject().put("type", "web_search_preview")))
        }

        val conn = openPost("${cfg.base}/chat/completions", cfg.apiKey, body.toString(), stream = true)
        try {
            val reader = java.io.BufferedReader(
                java.io.InputStreamReader(conn.inputStream, Charsets.UTF_8)
            )
            val text = StringBuilder()
            val reason = StringBuilder()
            var prompt = 0
            var completion = 0
            var truncated = false
            var sawFinish = false
            var lastFinish = ""
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                val data = line.trim().removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val usage = obj.optJSONObject("usage")
                if (usage != null) {
                    prompt = usage.optInt("prompt_tokens", prompt)
                    completion = usage.optInt("completion_tokens", completion)
                }
                val choice = obj.optJSONArray("choices")?.optJSONObject(0)
                val fr = choice?.optString("finish_reason").orEmpty()
                if (fr.isNotEmpty()) { sawFinish = true; lastFinish = fr }
                if (fr == "length") truncated = true
                val delta = choice?.optJSONObject("delta") ?: continue
                val r = delta.str("reasoning_content")
                if (r.isNotEmpty()) { reason.append(r); onReason(r) }
                val c = delta.str("content")
                if (c.isNotEmpty()) { text.append(c); onText(c) }
            }
            val nl = 10.toChar()
            val body = text.toString()
            // 流结束却没有 finish_reason，说明连接被服务端提前掐断
            if (!sawFinish && body.isNotBlank()) truncated = true
            // 结尾不像一句话结束（没有句号/代码块闭合），也视为不完整
            val tail = body.trimEnd()
            if (tail.isNotEmpty() && tail.last() !in "...。！？.!?）)】」」`" ) truncated = true
            val finalText = body.ifBlank { "(空响应)" } +
                (if (truncated) "$nl$nl> ⚠️ 输出达到长度上限被截断，回复「继续」可接着写。" else "")
            return AiResult(
                text = finalText,
                promptTokens = prompt,
                completionTokens = completion,
                reasoning = reason.toString(),
                truncated = truncated,
                finishReason = lastFinish,
            )
        } finally {
            runCatching { conn.disconnect() }
            active = null
        }
    }

    private fun streamAnthropic(
        cfg: AiConfig,
        messages: List<ChatMessage>,
        deepThink: Boolean,
        webSearch: Boolean,
        onText: (String) -> Unit,
        onReason: (String) -> Unit,
    ): AiResult {
        val system = messages.filter { it.role == "system" }.joinToString(NL) { it.text }
        val arr = JSONArray()
        messages.filter { it.role != "system" }.forEach { m ->
            val content: Any = if (m.imageBase64 != null) {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", m.text))
                    .put(
                        JSONObject().put("type", "image").put(
                            "source",
                            JSONObject().put("type", "base64")
                                .put("media_type", m.imageMime).put("data", m.imageBase64)
                        )
                    )
            } else m.text
            arr.put(JSONObject().put("role", m.role).put("content", content))
        }
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", if (deepThink) 8192 else 4096)
            .put("messages", arr)
            .put("stream", true)
        if (system.isNotBlank()) body.put("system", system)
        if (deepThink) {
            body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 4096))
        }
        if (webSearch) {
            body.put("tools", JSONArray().put(
                JSONObject().put("type", "web_search_20250305").put("name", "web_search")
            ))
        }
        val conn = openPost("${cfg.base}/messages", cfg.apiKey, body.toString(), stream = true)
        try {
            val reader = java.io.BufferedReader(
                java.io.InputStreamReader(conn.inputStream, Charsets.UTF_8)
            )
            val text = StringBuilder()
            val reason = StringBuilder()
            var input = 0
            var output = 0
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                val data = line.trim().removePrefix("data:").trim()
                if (data.isEmpty() || data == "[DONE]") continue
                val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                when (obj.optString("type")) {
                    "content_block_delta" -> {
                        val delta = obj.optJSONObject("delta") ?: continue
                        when (delta.optString("type")) {
                            "text_delta" -> {
                                val t = delta.str("text")
                                if (t.isNotEmpty()) { text.append(t); onText(t) }
                            }
                            "thinking_delta" -> {
                                val t = delta.str("thinking")
                                if (t.isNotEmpty()) { reason.append(t); onReason(t) }
                            }
                        }
                    }
                    "message_start" -> {
                        input = obj.optJSONObject("message")?.optJSONObject("usage")
                            ?.optInt("input_tokens") ?: input
                    }
                    "message_delta" -> {
                        output = obj.optJSONObject("usage")?.optInt("output_tokens") ?: output
                    }
                }
            }
            return AiResult(
                text = text.toString().ifBlank { "(空响应)" },
                promptTokens = input,
                completionTokens = output,
                reasoning = reason.toString(),
            )
        } finally {
            runCatching { conn.disconnect() }
            active = null
        }
    }

    private fun openPost(url: String, key: String, body: String, stream: Boolean): HttpURLConnection {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 300_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
            setRequestProperty("Authorization", "Bearer $key")
            // Anthropic accepts Authorization bearer as well; x-api-key is added by caller when needed
            setRequestProperty("x-api-key", key)
            setRequestProperty("anthropic-version", "2023-06-01")
        }
        active = conn
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = conn.responseCode
        if (code !in 200..299) {
            val err = conn.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
            error("HTTP $code：${err.take(220)}")
        }
        return conn
    }

    /** DeepSeek 官方搜索 API：POST /v1/tools/search。 */
    suspend fun deepseekSearch(cfg: AiConfig, query: String): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val nl = 10.toChar()
            val body = JSONObject().put("query", query.take(120)).put("count", 6).put("need_content", false)
            val res = request("${cfg.base}/v1/tools/search", "POST",
                mapOf("Authorization" to "Bearer ${cfg.apiKey}"), body.toString())
            val results = JSONObject(res).optJSONArray("results") ?: JSONArray()
            if (results.length() == 0) return@runCatching ""
            val sb = StringBuilder()
            sb.append("以下是刚刚检索到的实时网页结果，请优先使用并在回答末尾列出引用来源：").append(nl)
            for (i in 0 until results.length()) {
                val o = results.getJSONObject(i)
                sb.append("[").append(i + 1).append("] ").append(o.str("title")).append(nl)
                sb.append(o.optString("url")).append(nl)
                sb.append(o.str("summary").ifBlank { o.str("snippet") }.take(600)).append(nl).append(nl)
            }
            sb.toString()
        }
    }

    suspend fun chat(
        cfg: AiConfig,
        messages: List<ChatMessage>,
        deepThink: Boolean = false,
        webSearch: Boolean = false,
    ): Result<AiResult> =
        withContext(Dispatchers.IO) {
            runCatching {
                if (cfg.apiKey.isBlank()) error("请先填写 API Key")
                if (cfg.model.isBlank()) error("请先选择或填写模型")
                when (cfg.kind) {
                    "anthropic" -> anthropic(cfg, messages, webSearch, deepThink)
                    "gemini" -> gemini(cfg, messages, webSearch)
                    else -> openai(cfg, messages, cfg.provider.id, webSearch, deepThink)
                }
            }
        }

    private fun openai(cfg: AiConfig, messages: List<ChatMessage>, providerId: String, webSearch: Boolean, deepThink: Boolean = false): AiResult {
        val arr = JSONArray()
        messages.forEach { m ->
            val content: Any = if (m.imageBase64 != null) {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", m.text))
                    .put(
                        JSONObject().put("type", "image_url").put(
                            "image_url",
                            JSONObject().put("url", "data:${m.imageMime};base64,${m.imageBase64}")
                        )
                    )
            } else m.text
            arr.put(JSONObject().put("role", m.role).put("content", content))
        }
        val body = JSONObject()
            .put("model", cfg.model)
            .put("messages", arr)
        if (providerId == "deepseek") {
            body.put("thinking", JSONObject().put("type", if (deepThink) "enabled" else "disabled"))
            if (deepThink) body.put("reasoning_effort", "high")
            body.put("max_tokens", if (deepThink) 65536 else 8192)
        } else {
            body.put("max_tokens", 4096)
            if (deepThink && REASONING_HINT.any { cfg.model.contains(it, true) }) {
                body.put("reasoning_effort", "medium")
            }
        }
        body.put("temperature", 0.6)
        if (webSearch && (providerId == "openai" || providerId == "grok")) {
            body.put("tools", JSONArray().put(JSONObject().put("type", "web_search_preview")))
        }
        val res = request("${cfg.base}/chat/completions", "POST",
            mapOf("Authorization" to "Bearer ${cfg.apiKey}"), body.toString())
        val root = JSONObject(res)
        val msg = root.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
        val reasoning = msg.strOrEmpty("reasoning_content")
        val text = msg.strOrEmpty("content")
        val merged = text
        val usage = root.optJSONObject("usage")
        return AiResult(
            text = merged.ifBlank { "(空响应)" },
            promptTokens = usage?.optInt("prompt_tokens") ?: 0,
            completionTokens = usage?.optInt("completion_tokens") ?: 0,
            reasoning = reasoning,
        )
    }

    private fun anthropic(cfg: AiConfig, messages: List<ChatMessage>, webSearch: Boolean, deepThink: Boolean = false): AiResult {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.text }
        val arr = JSONArray()
        messages.filter { it.role != "system" }.forEach { m ->
            val content: Any = if (m.imageBase64 != null) {
                JSONArray()
                    .put(JSONObject().put("type", "text").put("text", m.text))
                    .put(
                        JSONObject().put("type", "image").put(
                            "source",
                            JSONObject()
                                .put("type", "base64")
                                .put("media_type", m.imageMime)
                                .put("data", m.imageBase64)
                        )
                    )
            } else m.text
            arr.put(JSONObject().put("role", m.role).put("content", content))
        }
        val body = JSONObject()
            .put("model", cfg.model)
            .put("max_tokens", 4096)
            .put("messages", arr)
        if (system.isNotBlank()) body.put("system", system)
        if (deepThink) {
            body.put("thinking", JSONObject().put("type", "enabled").put("budget_tokens", 4096))
            body.put("max_tokens", 8192)
        }
        if (webSearch) {
            body.put("tools", JSONArray().put(
                JSONObject().put("type", "web_search_20250305").put("name", "web_search")
            ))
        }
        val res = request("${cfg.base}/messages", "POST", mapOf(
            "x-api-key" to cfg.apiKey,
            "anthropic-version" to "2023-06-01",
            "content-type" to "application/json",
        ), body.toString())
        val root = JSONObject(res)
        val content = root.optJSONArray("content")
        val text = buildString {
            if (content != null) for (i in 0 until content.length()) {
                val b = content.optJSONObject(i)
                if (b.strOrEmpty("type") == "text") append(b.strOrEmpty("text"))
            }
        }
        val thinking = buildString {
            if (content != null) for (i in 0 until content.length()) {
                val b = content.optJSONObject(i)
                if (b.strOrEmpty("type") == "thinking") append(b.strOrEmpty("thinking"))
            }
        }
        val usage = root.optJSONObject("usage")
        return AiResult(
            text = text.ifBlank { "(空响应)" },
            promptTokens = usage?.optInt("input_tokens") ?: 0,
            completionTokens = usage?.optInt("output_tokens") ?: 0,
            reasoning = thinking,
        )
    }

    private fun gemini(cfg: AiConfig, messages: List<ChatMessage>, webSearch: Boolean): AiResult {
        val system = messages.filter { it.role == "system" }.joinToString("\n") { it.text }
        val contents = JSONArray()
        messages.filter { it.role != "system" }.forEach { m ->
            val parts = JSONArray().put(JSONObject().put("text", m.text))
            if (m.imageBase64 != null) {
                parts.put(
                    JSONObject().put(
                        "inline_data",
                        JSONObject().put("mime_type", m.imageMime).put("data", m.imageBase64)
                    )
                )
            }
            contents.put(
                JSONObject()
                    .put("role", if (m.role == "assistant") "model" else "user")
                    .put("parts", parts)
            )
        }
        val body = JSONObject().put("contents", contents)
        if (system.isNotBlank()) {
            body.put("systemInstruction", JSONObject().put("parts",
                JSONArray().put(JSONObject().put("text", system))))
        }
        if (webSearch) {
            body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        }
        val model = cfg.model.removePrefix("models/")
        val res = request("${cfg.base}/models/$model:generateContent?key=${cfg.apiKey}",
            "POST", emptyMap(), body.toString())
        val root = JSONObject(res)
        val parts = root.optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts")
        val text = buildString {
            if (parts != null) for (i in 0 until parts.length()) {
                val pt = parts.optJSONObject(i)
                if (pt?.optBoolean("thought") != true) append(pt.strOrEmpty("text"))
            }
        }
        val thinking = buildString {
            if (parts != null) for (i in 0 until parts.length()) {
                val pt = parts.optJSONObject(i)
                if (pt?.optBoolean("thought") == true) append(pt.strOrEmpty("text"))
            }
        }
        val usage = root.optJSONObject("usageMetadata")
        return AiResult(
            text = text.ifBlank { "(空响应)" },
            promptTokens = usage?.optInt("promptTokenCount") ?: 0,
            completionTokens = usage?.optInt("candidatesTokenCount") ?: 0,
            reasoning = thinking,
        )
    }

    private fun request(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String? = null,
    ): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 20_000
            readTimeout = 120_000
            setRequestProperty("Accept", "application/json")
            headers.forEach { (k, v) -> setRequestProperty(k, v) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        try {
            if (body != null) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = BufferedReader(stream?.reader(Charsets.UTF_8) ?: return "{}").use { it.readText() }
            if (code !in 200..299) {
                val msg = runCatching {
                    val o = JSONObject(text)
                    o.optJSONObject("error")?.optString("message")
                        ?: o.optString("message")
                        ?: text
                }.getOrNull() ?: text
                Log.w(TAG, "HTTP $code: $msg")
                error("HTTP $code：${msg.take(220)}")
            }
            return text
        } finally {
            runCatching { conn.disconnect() }
            active = null
        }
    }
}


/** Single general-purpose persona: tutor + vision + planner in one. */
const val UNIVERSAL_SYSTEM_PROMPT: String =
    "你是电子信息专业的学习导师兼助教，熟悉神经网络、芯片设计（数字/模拟/验证）、机器人三个方向，" +
        "也熟悉从本科到硕博再到业界的路径。你能看图：教材截图、电路图、公式推导、代码、实验波形。" +
        "回答要求：先给结论（看图时先复述图中的关键信息并确认理解），再分步骤讲解；" +
        "给到具体书目、章节与练习级别（A 概念验证 / B 工程实现 / C 跨方向）；" +
        "不编造论文、库或 API，不确定时明确说不确定；涉及最新信息时说明可能过时。"

private val NL = 10.toChar().toString()

private val REASONING_HINT = listOf("o1", "o3", "o4", "gpt-5", "grok-3-mini", "grok-4", "reason")

/** org.json 的 optString 会把 JSON null 变成 "null"，这里统一安全取值。 */
private fun JSONObject.str(key: String): String {
    val v = opt(key)
    return if (v == null || v == JSONObject.NULL) "" else v.toString()
}
private fun JSONObject?.strOrEmpty(key: String): String = this?.str(key).orEmpty()
