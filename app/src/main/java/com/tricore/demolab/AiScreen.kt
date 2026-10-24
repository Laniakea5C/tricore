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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch

internal fun progressSummary(data: AppData, store: ProgressStore): String {
    val sb = StringBuilder()
    sb.append("我已学的进度（全书 35 本 / 192 章 / 576 个练习）：\n")
    listOf("ai" to "神经网络", "chip" to "芯片", "robot" to "机器人", "base" to "基础").forEach { (id, name) ->
        val (d, t) = store.doneByDir(data, id)
        sb.append("- $name：完成 $d / $t 个练习\n")
        val books = data.books.filter { it.dir == id }
        books.forEach { b ->
            val chs = data.chaptersByBook[b.id].orEmpty()
            val done = chs.count { store.isChapterDone(it) }
            if (done > 0 || b.id in listOf("B01", "B04", "B10", "B13", "B22", "B09")) {
                sb.append("  · ${b.id} ${b.title}：$done/${chs.size} 章\n")
            }
        }
    }
    sb.append("请基于以上进度：1) 指出我当前最该补的 3 个薄弱点；2) 给出未来 4 周的具体学习计划（每周书目+章节+练习级别）；3) 说明这些内容与三大方向就业要求的关系。")
    return sb.toString()
}

@Composable
fun AiScreen(
    data: AppData,
    store: ProgressStore,
    draft: String?,
    onDraftConsumed: () -> Unit,
    onClose: () -> Unit,
) {
    val ctx = LocalContext.current
    val ai = remember { AiStore(ctx) }
    var providerId by remember { mutableStateOf(ai.lastProvider()) }
    val provider = providerOf(providerId)
    var apiKey by remember(providerId) { mutableStateOf(ai.apiKey(providerId)) }
    var model by remember(providerId) { mutableStateOf(ai.model(providerId)) }
    var base by remember(providerId) { mutableStateOf(ai.base(providerId)) }
    var kind by remember(providerId) {
        mutableStateOf(ai.kind(providerId).ifBlank { provider.kind })
    }
    var modelsPath by remember(providerId) {
        mutableStateOf(ai.modelsPath(providerId).ifBlank { provider.modelsPath })
    }
    var showAdvanced by remember { mutableStateOf(false) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModels by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var usageTick by remember { mutableStateOf(0) }

    var scenarioId by remember { mutableStateOf("mentor") }
    val scenario = AI_SCENARIOS.first { it.id == scenarioId }
    var systemPrompt by remember(scenarioId) { mutableStateOf(scenario.system) }
    var showPrompt by remember { mutableStateOf(false) }

    var imageB64 by remember { mutableStateOf<String?>(null) }
    var imageMime by remember { mutableStateOf("image/jpeg") }
    var fileName by remember { mutableStateOf<String?>(null) }
    var fileText by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }

    val messages = remember { mutableStateListOf<ChatMessage>() }
    var input by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current

    val gallery = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let {
            val p = imageAttachment(ctx, it)
            if (p != null) { imageMime = p.first; imageB64 = p.second; fileName = null; fileText = null }
        }
    }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok ->
        if (ok) cameraUri?.let {
            val p = imageAttachment(ctx, it)
            if (p != null) { imageMime = p.first; imageB64 = p.second }
        }
    }
    val docs = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            val t = readTextAttachment(ctx, it)
            if (t != null) {
                fileText = t
                fileName = it.lastPathSegment?.substringAfterLast('/') ?: "附件"
                imageB64 = null
            } else status = "这个文件读不出文本（暂支持 txt/md/code/csv）"
        }
    }

    fun launchCamera() {
        runCatching {
            val file = java.io.File(ctx.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "${ctx.packageName}.fileprovider", file
            )
            cameraUri = uri
            camera.launch(uri)
        }.onFailure { status = "相机不可用：${it.message?.take(120)}" }
    }

    LaunchedEffect(draft) {
        if (!draft.isNullOrBlank()) { input = draft; onDraftConsumed() }
    }

    fun save() {
        ai.saveKey(providerId, apiKey)
        ai.saveModel(providerId, model)
        ai.saveBase(providerId, base)
        ai.saveKind(providerId, kind)
        ai.saveModelsPath(providerId, modelsPath)
        ai.saveLast(providerId)
    }

    fun send(text: String) {
        if (busy) return
        val attachedText = fileText
        val body = buildString {
            append(text)
            if (attachedText != null) {
                append("\n\n【附件 ")
                append(fileName ?: "")
                append("】\n")
                append(attachedText)
            }
        }
        if (body.isBlank() && imageB64 == null) return
        save()
        messages.add(ChatMessage("user", body, imageB64, imageMime))
        input = ""
        imageB64 = null
        fileText = null
        fileName = null
        busy = true
        status = ""
        scope.launch {
            val cfg = AiConfig(provider, apiKey, model, base.ifBlank { provider.base }, kind, modelsPath)
            val res = AiClient.chat(cfg, listOf(ChatMessage("system", systemPrompt)) + messages.toList())
            busy = false
            res.onSuccess {
                messages.add(ChatMessage("assistant", it.text))
                ai.addUsage(providerId, it.promptTokens, it.completionTokens)
                usageTick++
                status = "本次 token：输入 ${it.promptTokens} / 输出 ${it.completionTokens}"
                scope.launch { listState.animateScrollToItem((messages.size - 1).coerceAtLeast(0)) }
            }.onFailure {
                status = "请求失败：${it.message?.take(180)}"
            }
        }
    }

    Column(Modifier.fillMaxSize().background(P.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("AI 学习导师", color = P.text, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("清除对话", color = P.muted, fontSize = 10.sp,
                modifier = Modifier.clickable { messages.clear() }.padding(horizontal = 8.dp))
            Text("关闭", color = P.muted, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp))
                    .background(Color.White.copy(alpha = 0.07f))
                    .clickable { onClose() }
                    .padding(horizontal = 10.dp, vertical = 5.dp))
        }

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AI_PROVIDERS.forEach { p ->
                val sel = p.id == providerId
                Box(
                    Modifier.clip(RoundedCornerShape(12.dp))
                        .background(if (sel) P.ai.copy(alpha = 0.22f) else Color.White.copy(alpha = 0.06f))
                        .border(1.dp, if (sel) P.ai.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f),
                            RoundedCornerShape(12.dp))
                        .clickable { save(); providerId = p.id; messages.clear(); status = "" }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) { Text(p.name, color = if (sel) P.text else P.muted, fontSize = 10.5.sp, maxLines = 1) }
            }
        }

        // protocol selector: lets any third-party endpoint be wired in
        if (providerId == "custom") {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(
                    "openai" to "OpenAI 兼容",
                    "anthropic" to "Anthropic",
                    "gemini" to "Gemini",
                ).forEach { (id, label) ->
                    val sel = kind == id
                    Box(
                        Modifier.clip(RoundedCornerShape(10.dp))
                            .background(if (sel) P.ai.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f))
                            .border(1.dp, if (sel) P.ai.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.10f),
                                RoundedCornerShape(10.dp))
                            .clickable { kind = id }
                            .padding(horizontal = 9.dp, vertical = 5.dp)
                    ) { Text(label, color = if (sel) P.text else P.dim, fontSize = 10.sp) }
                }
            }
        }

        // scenario chips
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            AI_SCENARIOS.forEach { sc ->
                val sel = sc.id == scenarioId
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(if (sel) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                        .border(1.dp, if (sel) P.ai.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.10f),
                            RoundedCornerShape(10.dp))
                        .clickable { scenarioId = sc.id; showPrompt = false }
                        .padding(horizontal = 9.dp, vertical = 5.dp)
                ) { Text(sc.title, color = if (sel) P.text else P.dim, fontSize = 10.sp) }
            }
        }

        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    Field("API Key", apiKey, "粘贴 ${provider.name} 官网生成的 Key", password = true) { apiKey = it }
                }
                Spacer(Modifier.width(6.dp))
                Box(Modifier.weight(0.8f)) {
                    Field("模型", model, defaultModelHint(providerId), password = false) { model = it }
                }
                Spacer(Modifier.width(6.dp))
                Action("查询", enabled = !busy) {
                    save(); busy = true; status = "正在查询模型…"
                    scope.launch {
                        AiClient.listModels(
                            AiConfig(provider, apiKey, model, base.ifBlank { provider.base }, kind, modelsPath)
                        )
                            .onSuccess { models = it; showModels = true; status = "可用模型 ${it.size} 个" }
                            .onFailure { status = "查询失败：${it.message?.take(160)}" }
                        busy = false
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("token 累计：输入 ${ai.promptTokens(providerId)} · 输出 ${ai.completionTokens(providerId)}",
                    color = P.dim, fontSize = 9.sp, modifier = Modifier.weight(1f))
                Text("清零", color = P.muted, fontSize = 9.sp,
                    modifier = Modifier.clickable { ai.resetUsage(providerId); usageTick++ }.padding(horizontal = 5.dp))
                Text(if (showPrompt) "收起提示词" else "提示词", color = P.muted, fontSize = 9.sp,
                    modifier = Modifier.clickable { showPrompt = !showPrompt }.padding(horizontal = 5.dp))
                Text(if (showAdvanced) "收起" else "高级", color = P.muted, fontSize = 9.sp,
                    modifier = Modifier.clickable { showAdvanced = !showAdvanced }.padding(horizontal = 5.dp))
            }
            if (showAdvanced) {
                Spacer(Modifier.height(4.dp))
                Field(
                    "Base URL",
                    base,
                    if (providerId == "custom") "例如 https://api.example.com/v1（含版本路径）" else provider.base,
                    password = false
                ) { base = it }
                if (providerId == "custom") {
                    Spacer(Modifier.height(4.dp))
                    Field("模型列表路径", modelsPath, "/models", password = false) { modelsPath = it }
                }
            }
            if (showPrompt) {
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(10.dp)
                ) {
                    BasicTextField(
                        value = systemPrompt, onValueChange = { systemPrompt = it },
                        textStyle = TextStyle(color = P.text, fontSize = 10.5.sp, lineHeight = 15.sp),
                        cursorBrush = SolidColor(P.ai),
                        modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp, max = 150.dp),
                    )
                }
                Text("系统提示词，可自己改；切换场景会重置", color = P.dim, fontSize = 8.5.sp,
                    modifier = Modifier.padding(top = 3.dp))
            }
            if (status.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(status, color = if (status.contains("失败") || status.contains("不可用")) Color(0xFFFF8A80) else P.muted,
                    fontSize = 9.5.sp, lineHeight = 13.sp)
            }
        }

        // attachments + quick actions
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Action("拍照") { launchCamera() }
            Action("相册") { gallery.launch("image/*") }
            Action("文件") { docs.launch(arrayOf("text/*", "application/json", "application/xml")) }
            Action("评估进度") { send(progressSummary(data, store)) }
            Action("三方向答疑") { input = "请分别用 5 句话说明：神经网络、芯片设计、机器人三个方向的核心能力、典型岗位、最常见的入门误区。" }
            Action("一周练习") { input = "请给我一周的练习计划：每天 2 小时，覆盖数学、编程和一个方向课，每天给出具体章节与 A/B 级练习。" }
        }

        if (imageB64 != null || fileText != null) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (imageB64 != null) "📷 已附带图片" else "📄 ${fileName ?: "附件"}",
                    color = P.ai, fontSize = 10.sp,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp))
                        .background(P.ai.copy(alpha = 0.14f))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text("移除", color = P.dim, fontSize = 9.sp,
                    modifier = Modifier.clickable { imageB64 = null; fileText = null; fileName = null }
                        .padding(4.dp))
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (messages.isEmpty()) {
                item {
                    Text(
                        "可以发文字、图片（教材截图/电路图/波形）和文本文件；系统提示词按场景预设，可自行修改。",
                        color = P.dim, fontSize = 10.5.sp, lineHeight = 15.sp
                    )
                }
            }
            items(messages.size) { i ->
                val m = messages[i]
                val mine = m.role == "user"
                Column(
                    Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (mine) P.ai.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.06f))
                        .padding(10.dp)
                ) {
                    Text(if (mine) "我" else provider.name, color = if (mine) P.ai else P.muted, fontSize = 9.sp)
                    if (m.imageBase64 != null) {
                        Text("[图片已发送]", color = P.dim, fontSize = 9.sp)
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(m.text, color = P.text, fontSize = 11.5.sp, lineHeight = 17.sp)
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(12.dp).imePadding(),
            verticalAlignment = Alignment.Bottom
        ) {
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .border(1.dp, Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp))
                    .padding(horizontal = 12.dp, vertical = 9.dp)
            ) {
                BasicTextField(
                    value = input, onValueChange = { input = it },
                    textStyle = TextStyle(color = P.text, fontSize = 12.sp),
                    cursorBrush = SolidColor(P.ai),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 18.dp, max = 96.dp),
                    decorationBox = { inner ->
                        if (input.isEmpty()) Text("问学习问题，或粘贴模板提示词…", color = P.dim, fontSize = 11.sp)
                        inner()
                    },
                )
            }
            Spacer(Modifier.width(8.dp))
            Box(
                Modifier.clip(RoundedCornerShape(16.dp))
                    .background(if (busy) Color.White.copy(alpha = 0.10f) else P.ai)
                    .clickable { send(input) }
                    .padding(horizontal = 14.dp, vertical = 11.dp)
            ) { Text(if (busy) "…" else "发送", color = if (busy) P.muted else Color(0xFF06101A),
                fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        }
    }

    if (showModels) {
        Dialog(onDismissRequest = { showModels = false }) {
            GlassCard(Modifier.fillMaxWidth().heightIn(max = 520.dp), corner = 20) {
                Column(Modifier.padding(14.dp)) {
                    Text("${provider.name} 可用模型", color = P.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn {
                        items(models) { id ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 5.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { model = id; save(); showModels = false }
                                    .padding(horizontal = 6.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(id, color = P.text, fontSize = 11.sp,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f))
                                Text("复制", color = P.ai, fontSize = 9.sp,
                                    modifier = Modifier.clickable { clipboard.setText(AnnotatedString(id)) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun Field(label: String, value: String, hint: String, password: Boolean, onChange: (String) -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.05f))
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(label, color = P.dim, fontSize = 8.5.sp)
        BasicTextField(
            value = value, onValueChange = onChange, singleLine = true,
            textStyle = TextStyle(color = P.text, fontSize = 11.5.sp),
            cursorBrush = SolidColor(P.ai),
            visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
            modifier = Modifier.fillMaxWidth(),
            decorationBox = { inner ->
                if (value.isEmpty()) Text(hint, color = P.dim, fontSize = 10.sp, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
                inner()
            },
        )
    }
}

@Composable
private fun Action(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.08f))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 10.dp, vertical = 7.dp)
    ) { Text(text, color = if (enabled) P.text else P.dim, fontSize = 10.5.sp, maxLines = 1) }
}

internal fun defaultModelHint(id: String): String = when (id) {
    "custom" -> "填写第三方模型 ID"
    "deepseek" -> "deepseek-flash（上下文 128K）"
    "glm" -> "glm-4-flash"
    "kimi" -> "moonshot-v1-8k"
    "qwen" -> "qwen-plus"
    "openai" -> "gpt-4o-mini"
    "claude" -> "claude-3-5-sonnet-latest"
    "grok" -> "grok-2-latest"
    "gemini" -> "gemini-1.5-flash"
    "minimax" -> "abab6.5s-chat"
    "mimo" -> "mimo-7b-rl"
    else -> "model-id"
}
