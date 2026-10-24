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
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/* ------------------------------------------------------------------ sessions */

data class AiSession(
    val id: String,
    var title: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
)

private fun encodeSessions(list: List<AiSession>): String {
    val arr = JSONArray()
    list.forEach { se ->
        val msgs = JSONArray()
        se.messages.forEach { m ->
            msgs.put(
                JSONObject().put("r", m.role).put("t", m.text)
                    .put("i", m.imageBase64 ?: "").put("m", m.imageMime)
                    .put("rs", m.reasoning ?: "")
            )
        }
        arr.put(JSONObject().put("id", se.id).put("title", se.title).put("msgs", msgs))
    }
    return arr.toString()
}

private fun decodeSessions(raw: String): List<AiSession> = runCatching {
    val arr = JSONArray(raw)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        val msgs = o.optJSONArray("msgs") ?: JSONArray()
        val list = mutableListOf<ChatMessage>()
        for (j in 0 until msgs.length()) {
            val m = msgs.getJSONObject(j)
            list.add(
                ChatMessage(
                    m.optString("r"), m.optString("t"),
                    m.optString("i").ifBlank { null }, m.optString("m", "image/jpeg"),
                    m.optString("rs").ifBlank { null }
                )
            )
        }
        AiSession(o.optString("id"), o.optString("title", "新对话"), list)
    }
}.getOrDefault(emptyList())

/* ------------------------------------------------------------------ screen */

@Composable
fun AiChatScreen(
    data: AppData,
    store: ProgressStore,
    draft: String?,
    onDraftConsumed: () -> Unit,
) {
    val ctx = LocalContext.current
    val ai = remember { AiStore(ctx) }
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()

    var showSettings by remember { mutableStateOf(false) }
    var showDrawer by remember { mutableStateOf(false) }
    var providerId by remember { mutableStateOf(ai.lastProvider()) }
    val provider = providerOf(providerId)
    var apiKey by remember(providerId) { mutableStateOf(ai.apiKey(providerId)) }
    var model by remember(providerId) { mutableStateOf(ai.model(providerId)) }
    var base by remember(providerId) { mutableStateOf(ai.base(providerId)) }
    var kind by remember(providerId) { mutableStateOf(ai.kind(providerId).ifBlank { provider.kind }) }
    var modelsPath by remember(providerId) { mutableStateOf(ai.modelsPath(providerId).ifBlank { provider.modelsPath }) }
    var models by remember { mutableStateOf<List<String>>(emptyList()) }
    var showModels by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var sendJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    var cancelled by remember { mutableStateOf(false) }

    var deepThink by remember { mutableStateOf(ai.deepThink()) }
    var webSearch by remember { mutableStateOf(ai.webSearch()) }

    var sessionId by remember {
        mutableStateOf(ai.prefs.getString("current_session", "") ?: ("s" + System.currentTimeMillis()))
    }
    var sessions by remember { mutableStateOf(decodeSessions(ai.prefs.getString("sessions", "") ?: "")) }
    var input by remember { mutableStateOf("") }
    var imageB64 by remember { mutableStateOf<String?>(null) }
    var imageMime by remember { mutableStateOf("image/jpeg") }
    var fileName by remember { mutableStateOf<String?>(null) }
    var fileText by remember { mutableStateOf<String?>(null) }
    var cameraUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var showAttach by remember { mutableStateOf(false) }
    var showMore by remember { mutableStateOf(false) }

    val messages = remember { mutableStateListOf<ChatMessage>() }
    val listState = rememberLazyListState()
    var autoFollow by remember { mutableStateOf(true) }
    var showJump by remember { mutableStateOf(false) }
    var followPulse by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.snapshotFlow { !listState.canScrollForward }
            .collect { bottom ->
                if (bottom) {
                    autoFollow = true
                    showJump = false
                }
            }
    }
    LaunchedEffect(
        messages.size,
        messages.lastOrNull()?.text?.length,
        messages.lastOrNull()?.reasoning?.length,
        followPulse,
    ) {
        if (autoFollow && messages.isNotEmpty()) {
            withFrameNanos { }
            listState.scrollToItem(messages.size - 1, Int.MAX_VALUE / 4)
            withFrameNanos { }
            listState.scrollToItem(messages.size - 1, Int.MAX_VALUE / 4)
        }
    }
    var restored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (!restored) {
            restored = true
            val se = sessions.firstOrNull { it.id == sessionId }
            if (se != null && messages.isEmpty()) messages.addAll(se.messages)
        }
    }

    fun persist() {
        val idx = sessions.indexOfFirst { it.id == sessionId }
        val title = messages.firstOrNull { it.role == "user" }?.text?.take(18)?.replace("\n", " ") ?: "新对话"
        if (idx >= 0) {
            sessions[idx].messages.clear(); sessions[idx].messages.addAll(messages)
            sessions[idx].title = title
        } else {
            sessions = sessions + AiSession(sessionId, title, messages.toMutableList())
        }
        ai.prefs.edit().putString("sessions", encodeSessions(sessions))
            .putString("current_session", sessionId).apply()
    }

    fun newChat() {
        persist()
        messages.clear()
        sessionId = "s" + System.currentTimeMillis()
        ai.prefs.edit().putString("current_session", sessionId).apply()
        showDrawer = false
        status = ""
    }

    fun save() {
        ai.saveKey(providerId, apiKey)
        ai.saveModel(providerId, model)
        ai.saveBase(providerId, base)
        ai.saveKind(providerId, kind)
        ai.saveModelsPath(providerId, modelsPath)
        ai.saveLast(providerId)
    }

    val gallery = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { val p = imageAttachment(ctx, it); if (p != null) { imageMime = p.first; imageB64 = p.second } } }
    val camera = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.TakePicture()
    ) { ok -> if (ok) cameraUri?.let { val p = imageAttachment(ctx, it); if (p != null) { imageMime = p.first; imageB64 = p.second } } }
    val docs = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let {
        val t = readTextAttachment(ctx, it)
        if (t != null) { fileText = t; fileName = it.lastPathSegment?.substringAfterLast('/') ?: "附件" }
        else status = "只支持文本类文件"
    } }

    fun launchCamera() {
        runCatching {
            val f = java.io.File(ctx.cacheDir, "shot_${System.currentTimeMillis()}.jpg")
            val uri = androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", f)
            cameraUri = uri
            camera.launch(uri)
        }.onFailure { status = "相机不可用：${it.message?.take(100)}" }
    }

    fun send(text: String) {
        if (busy) return
        val body = buildString {
            append(text)
            fileText?.let { append("\n\n【附件 "); append(fileName ?: ""); append("】\n"); append(it) }
        }
        if (body.isBlank() && imageB64 == null) return
        save()
        if (webSearch) status = "正在检索网页…"
        autoFollow = true
        showJump = false
        messages.add(ChatMessage("user", body, imageB64, imageMime))
        val replyIndex = messages.size
        messages.add(ChatMessage("assistant", ""))
        input = ""
        imageB64 = null; fileText = null; fileName = null
        busy = true; status = ""; cancelled = false
        val sysBase = buildString {
            append(UNIVERSAL_SYSTEM_PROMPT)
            if (deepThink) append("\n回答前先写出推理要点，再给结论；复杂问题分步骤。")
            if (webSearch) append("\n如需最新信息，请尝试检索；若接口不支持检索，请明确说明信息可能过时。")
        }
        sendJob = scope.launch {
            val cfg = AiConfig(provider, apiKey, model, base.ifBlank { provider.base }, kind, modelsPath)
            var sys = sysBase
            if (webSearch && providerId == "deepseek") {
                val hits = AiClient.deepseekSearch(cfg, body).getOrNull().orEmpty()
                if (hits.isNotBlank()) {
                    sys = sysBase + 10.toChar() + 10.toChar() + hits
                } else {
                    status = "检索无结果，按已有知识回答"
                }
            }
            val textBuf = StringBuilder()
            val reasonBuf = StringBuilder()
            val res = AiClient.chatStream(
                cfg, listOf(ChatMessage("system", sys)) + messages.dropLast(1).toList(),
                deepThink = deepThink, webSearch = webSearch,
                onText = { delta ->
                    textBuf.append(delta)
                    messages[replyIndex] = ChatMessage(
                        "assistant", textBuf.toString(),
                        reasoning = reasonBuf.toString().ifBlank { null }
                    )
                },
                onReason = { delta ->
                    reasonBuf.append(delta)
                    messages[replyIndex] = ChatMessage(
                        "assistant", textBuf.toString(),
                        reasoning = reasonBuf.toString().ifBlank { null }
                    )
                },
            )
            var result = res.getOrNull()
            var autoRounds = 0
            var promptTokens = result?.promptTokens ?: 0
            var completionTokens = result?.completionTokens ?: 0
            // 服务端因长度截断时自动续写，拼回同一条气泡
            while (result?.truncated == true && !cancelled && autoRounds < 2) {
                autoRounds++
                if (textBuf.isNotEmpty()) textBuf.append(10.toChar()).append(10.toChar())
                val cont = buildList {
                    add(ChatMessage("system", sys))
                    addAll(messages.dropLast(1))
                    add(ChatMessage("assistant", textBuf.toString()))
                    add(ChatMessage("user", "继续，从断开处接着写，不要重复已有内容，不要重新开头。"))
                }
                val r2 = AiClient.chatStream(
                    cfg, cont, deepThink = false, webSearch = false,
                    onText = { delta ->
                        textBuf.append(delta)
                        messages[replyIndex] = ChatMessage(
                            "assistant", textBuf.toString(),
                            reasoning = reasonBuf.toString().ifBlank { null }
                        )
                    },
                    onReason = { reasonBuf.append(it) },
                )
                val r = r2.getOrNull() ?: break
                promptTokens += r.promptTokens
                completionTokens += r.completionTokens
                result = r
            }
            busy = false
            if (result != null) {
                messages[replyIndex] = ChatMessage(
                    "assistant",
                    textBuf.toString().ifBlank { result.text },
                    reasoning = reasonBuf.toString().ifBlank { null }
                )
                ai.addUsage(providerId, promptTokens, completionTokens)
                persist()
                status = "token 输入 $promptTokens / 输出 $completionTokens" +
                    (if (autoRounds > 0) "（自动续写 $autoRounds 次）" else "") +
                    (if (result.finishReason.isNotBlank()) " · finish=${result.finishReason}" else " · finish=无")
            } else {
                status = if (cancelled || res.isFailure && res.exceptionOrNull() is kotlinx.coroutines.CancellationException) {
                    "已停止"
                } else {
                    "失败：${res.exceptionOrNull()?.message?.take(180)}"
                }
            }
            if (autoFollow) scope.launch { listState.scrollToItem((messages.size - 1).coerceAtLeast(0), Int.MAX_VALUE / 4) }
        }
    }

    LaunchedEffect(draft) {
        if (!draft.isNullOrBlank()) { input = draft; onDraftConsumed() }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPaddingIfNeeded()
                .padding(bottom = 96.dp)
        ) {
            // header
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("☰", color = P.muted, fontSize = 16.sp,
                    modifier = Modifier.clip(CircleShape).clickable { showDrawer = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp))
                Spacer(Modifier.width(2.dp))
                Column(Modifier.weight(1f)) {
                    Text("AI 学习导师", color = P.text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("${provider.name} · ${model.ifBlank { "未选模型" }}", color = P.dim, fontSize = 9.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.07f))
                        .clickable { showSettings = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("模型设置", color = P.muted, fontSize = 10.sp) }
            }

            // messages
            Box(Modifier.weight(1f).fillMaxWidth()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
                    .padding(horizontal = 14.dp, vertical = 8.dp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            var drag = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                if (event.type == PointerEventType.Move) {
                                    val ch = event.changes.firstOrNull()
                                    if (ch != null) {
                                        drag += ch.position.y - ch.previousPosition.y
                                        if (drag > 24f) {
                                            autoFollow = false
                                            showJump = true
                                            drag = 0f
                                        } else if (drag < -24f) {
                                            drag = 0f
                                        }
                                    }
                                }
                            }
                        }
                    },
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (messages.isEmpty()) {
                    item {
                        Column {
                            Text("今天想学什么？", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "可以直接提问、发教材截图（拍照/相册）或传文本文件；也可以把模板生成的提示词发过来。",
                                color = P.dim, fontSize = 10.5.sp, lineHeight = 15.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Text("公式会原生渲染：", color = P.muted, fontSize = 10.sp)
                            Spacer(Modifier.height(4.dp))
                            MathText("E = mc^2,\\quad \\frac{a}{b} = \\frac{c}{d},\\quad \\int_0^1 x^2\\,dx = \\frac{1}{3}", size = 14f)
                        }
                    }
                }
                items(messages.size) { i ->
                    val m = messages[i]
                    val mine = m.role == "user"
                    Column(
                        Modifier.fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(if (mine) P.ai.copy(alpha = 0.16f) else Color.White.copy(alpha = 0.05f))
                            .padding(10.dp)
                    ) {
                        Text(if (mine) "我" else provider.name, color = if (mine) P.ai else P.muted, fontSize = 9.sp)
                        if (m.imageBase64 != null) Text("[图片]", color = P.dim, fontSize = 9.sp)
                        Spacer(Modifier.height(3.dp))
                        androidx.compose.foundation.text.selection.SelectionContainer {
                            Column {
                                if (mine) {
                                    Text(m.text, color = P.text, fontSize = 11.5.sp, lineHeight = 17.sp)
                                } else {
                                    var openThink by androidx.compose.runtime.remember(i) {
                                        androidx.compose.runtime.mutableStateOf(false)
                                    }
                                    if (!m.reasoning.isNullOrBlank()) {
                                        Text(
                                            if (openThink) "▾ 收起思考过程" else "▸ 思考过程 · 点击展开",
                                            color = P.ai.copy(alpha = 0.75f), fontSize = 9.5.sp,
                                            modifier = Modifier.fillMaxWidth()
                                                .clickable {
                                                    openThink = !openThink
                                                    if (openThink) {
                                                        autoFollow = true
                                                        showJump = false
                                                        followPulse++
                                                    }
                                                }
                                                .padding(vertical = 3.dp)
                                        )
                                        if (openThink) {
                                            Column(
                                                Modifier.fillMaxWidth()
                                                    .clip(RoundedCornerShape(10.dp))
                                                    .background(Color.White.copy(alpha = 0.04f))
                                                    .padding(9.dp)
                                            ) {
                                                SafeMarkdownText(m.reasoning, Modifier.fillMaxWidth())
                                            }
                                            Spacer(Modifier.height(4.dp))
                                        }
                                    }
                                    SafeMarkdownText(m.text, Modifier.fillMaxWidth())
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            Text(
                                "复制",
                                color = P.dim, fontSize = 8.5.sp,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .clickable {
                                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(m.text))
                                        status = "已复制"
                                    }
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
                if (status.isNotBlank()) {
                    item { Text(status, color = P.dim, fontSize = 9.sp) }
                }
            }

            if (showJump) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 4.dp, bottom = 6.dp)
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(P.ai.copy(alpha = 0.92f))
                        .clickable {
                            autoFollow = true
                            showJump = false
                            scope.launch {
                                repeat(6) {
                                    listState.scrollToItem(
                                        (messages.size - 1).coerceAtLeast(0),
                                        Int.MAX_VALUE / 4
                                    )
                                    kotlinx.coroutines.delay(16)
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("↓", color = Color(0xFF06101A), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
            }

            // composer
            Column(Modifier.fillMaxWidth().imePadding()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ToggleChip("深度思考", deepThink) {
                        deepThink = !deepThink; ai.saveToggles(deepThink, webSearch)
                    }
                    ToggleChip("联网搜索", webSearch) {
                        webSearch = !webSearch; ai.saveToggles(deepThink, webSearch)
                    }
                }
                Spacer(Modifier.height(4.dp))
                if (imageB64 != null || fileText != null) {
                    Row(Modifier.padding(horizontal = 14.dp)) {
                        Text(
                            if (imageB64 != null) "📷 图片" else "📄 ${fileName ?: "附件"}",
                            color = P.ai, fontSize = 9.5.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp))
                                .background(P.ai.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("移除", color = P.dim, fontSize = 9.sp,
                            modifier = Modifier.clickable { imageB64 = null; fileText = null; fileName = null }
                                .padding(4.dp))
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text("＋", color = P.text, fontSize = 20.sp,
                        modifier = Modifier.clip(CircleShape).background(Color.White.copy(alpha = 0.08f))
                            .clickable { showAttach = true }
                            .padding(horizontal = 9.dp, vertical = 3.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp))
                            .background(Color.White.copy(alpha = 0.05f))
                            .border(1.dp, Color.White.copy(alpha = 0.10f), RoundedCornerShape(16.dp))
                            .padding(horizontal = 12.dp, vertical = 9.dp)
                            .heightIn(min = 40.dp, max = 150.dp)
                    ) {
                        BasicTextField(
                            value = input, onValueChange = { input = it },
                            textStyle = TextStyle(color = P.text, fontSize = 12.sp),
                            cursorBrush = SolidColor(P.ai),
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 6,
                            minLines = 1,
                            decorationBox = { inner ->
                                if (input.isEmpty()) Text("发消息…", color = P.dim, fontSize = 11.sp)
                                inner()
                            },
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.clip(CircleShape)
                            .background(if (busy) Color.White.copy(alpha = 0.10f) else P.ai)
                            .clickable {
                                if (busy) {
                                    cancelled = true
                                    AiClient.cancelActive()
                                    sendJob?.cancel()
                                    busy = false
                                    status = "已停止"
                                } else {
                                    send(input)
                                }
                            }
                            .padding(horizontal = 16.dp, vertical = 11.dp)
                    ) { Text(if (busy) "■" else "↑", color = if (busy) Color(0xFFFF7A7A) else Color(0xFF06101A),
                        fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        // attach menu
        if (showAttach) {
            Dialog(onDismissRequest = { showAttach = false }) {
                GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MenuRow("拍照 / 相册", "拍教材、电路图、波形") {
                            showAttach = false
                            launchCamera()
                        }
                        MenuRow("文件", "txt / md / 代码，读入文本") {
                            showAttach = false
                            docs.launch(arrayOf("text/*", "application/json", "application/xml"))
                        }
                        MenuRow("更多功能", "评估进度 / 三方向答疑 / 一周练习") {
                            showAttach = false; showMore = true
                        }
                        MenuRow("从相册选择", "已有截图") { showAttach = false; gallery.launch("image/*") }
                    }
                }
            }
        }

        if (showMore) {
            Dialog(onDismissRequest = { showMore = false }) {
                GlassCard(Modifier.fillMaxWidth(), corner = 20) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MenuRow("评估学习进度", "按已完成章节生成诊断") {
                            showMore = false; send(progressSummary(data, store))
                        }
                        MenuRow("三方向知识答疑", "核心能力 / 岗位 / 误区") {
                            showMore = false
                            input = "请分别用 5 句话说明：神经网络、芯片设计、机器人三个方向的核心能力、典型岗位、最常见的入门误区。"
                        }
                        MenuRow("一周练习计划", "每天 2 小时") {
                            showMore = false
                            input = "请给我一周的练习计划：每天 2 小时，覆盖数学、编程和一个方向课，每天给出具体章节与 A/B 级练习。"
                        }
                    }
                }
            }
        }

        // sessions drawer (slides in from the left)
        androidx.compose.animation.AnimatedVisibility(
            visible = showDrawer,
            enter = androidx.compose.animation.slideInHorizontally(initialOffsetX = { -it }) +
                androidx.compose.animation.fadeIn(),
            exit = androidx.compose.animation.slideOutHorizontally(targetOffsetX = { -it }) +
                androidx.compose.animation.fadeOut(),
        ) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.fillMaxHeight().fillMaxWidth(0.78f)
                        .padding(bottom = 88.dp)
                        .background(Color(0xFF0B0F18))
                        .border(1.dp, Color.White.copy(alpha = 0.08f))
                        .padding(14.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("对话记录", color = P.text, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.weight(1f))
                        Text("关闭", color = P.dim, fontSize = 10.sp,
                            modifier = Modifier.clickable { showDrawer = false }.padding(4.dp))
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(P.ai.copy(alpha = 0.9f))
                                .clickable { newChat() }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) { Text("＋ 新建对话", color = Color(0xFF06101A), fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                        Box(
                            Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))
                                .clickable {
                                    val text = messages.joinToString("\n\n") {
                                        (if (it.role == "user") "我：" else "AI：") + it.text
                                    }
                                    clipboard.setText(AnnotatedString(text.ifBlank { "（空对话）" }))
                                    status = "已导出到剪贴板"
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        ) { Text("导出", color = P.text, fontSize = 10.sp) }
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(sessions.size) { i ->
                            val se = sessions[i]
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(
                                        if (se.id == sessionId) P.ai.copy(alpha = 0.14f)
                                        else Color.White.copy(alpha = 0.05f)
                                    )
                                    .clickable {
                                        persist()
                                        messages.clear(); messages.addAll(se.messages)
                                        sessionId = se.id
                                        showDrawer = false
                                    }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(se.title, color = P.text, fontSize = 11.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    Text("${se.messages.size} 条", color = P.dim, fontSize = 8.5.sp)
                                }
                                Text("✕", color = P.dim, fontSize = 10.sp,
                                    modifier = Modifier.clickable {
                                        sessions = sessions.filterIndexed { idx, _ -> idx != i }
                                        ai.prefs.edit().putString("sessions", encodeSessions(sessions))
            .putString("current_session", sessionId).apply()
                                    }.padding(6.dp))
                            }
                        }
                    }
                }
                Box(Modifier.weight(1f).fillMaxHeight().clickable { showDrawer = false })
            }
        }

        // settings overlay
        if (showSettings) {
            AiSettingsPanel(
                ai = ai, providerId = providerId, onProvider = { providerId = it; apiKey = ai.apiKey(it); model = ai.model(it); base = ai.base(it) },
                apiKey = apiKey, onKey = { apiKey = it },
                model = model, onModel = { model = it },
                base = base, onBase = { base = it },
                kind = kind, onKind = { kind = it },
                modelsPath = modelsPath, onModelsPath = { modelsPath = it },
                models = models, onModels = { showModels = true },
                onSave = { save() },
                onClose = { save(); showSettings = false },
                status = status, onStatus = { status = it },
            )
        }

        if (showModels) {
            Dialog(onDismissRequest = { showModels = false }) {
                GlassCard(Modifier.fillMaxWidth().heightIn(max = 520.dp), corner = 20) {
                    Column(Modifier.padding(14.dp)) {
                        Text("可用模型", color = P.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
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
                                    Text(id, color = P.text, fontSize = 11.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
}

@Composable
private fun ToggleChip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.clip(RoundedCornerShape(10.dp))
            .background(if (on) P.ai.copy(alpha = 0.20f) else Color.White.copy(alpha = 0.05f))
            .border(1.dp, if (on) P.ai.copy(alpha = 0.45f) else Color.White.copy(alpha = 0.10f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) { Text(text, color = if (on) P.text else P.dim, fontSize = 10.sp) }
}

@Composable
private fun MenuRow(title: String, subtitle: String, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(if (selected) P.ai.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.04f))
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(title, color = P.text, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
        if (subtitle.isNotBlank()) Text(subtitle, color = P.dim, fontSize = 9.sp)
    }
}

/* ------------------------------------------------------------------ settings */

@Composable
private fun AiSettingsPanel(
    ai: AiStore,
    providerId: String,
    onProvider: (String) -> Unit,
    apiKey: String, onKey: (String) -> Unit,
    model: String, onModel: (String) -> Unit,
    base: String, onBase: (String) -> Unit,
    kind: String, onKind: (String) -> Unit,
    modelsPath: String, onModelsPath: (String) -> Unit,
    models: List<String>, onModels: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    status: String,
    onStatus: (String) -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val provider = providerOf(providerId)
    var showProviders by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var localModels by remember { mutableStateOf<List<AiModelInfo>>(emptyList()) }
    var showModelList by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier.fillMaxSize().background(Color(0xFF0A0E16)).statusBarsPaddingIfNeeded()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("模型设置", color = P.text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text("完成", color = P.ai, fontSize = 11.sp,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(P.ai.copy(alpha = 0.16f))
                    .clickable { onClose() }.padding(horizontal = 12.dp, vertical = 6.dp))
        }
        Column(Modifier.padding(horizontal = 14.dp)) {
            Text("服务商", color = P.dim, fontSize = 9.sp)
            Box(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.06f))
                    .clickable { showProviders = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${provider.name}", color = P.text, fontSize = 12.sp, modifier = Modifier.weight(1f))
                    Text("▾", color = P.muted, fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Field("API Key", apiKey, "粘贴 Key", true, onKey)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field("模型", model, defaultModelHint(providerId), false, onModel) }
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.08f))
                        .clickable {
                            busy = true; onStatus("查询中…")
                            scope.launch {
                                AiClient.listModelInfos(AiConfig(provider, apiKey, model, base.ifBlank { provider.base }, kind, modelsPath))
                                    .onSuccess {
                                        localModels = it
                                        showModelList = it.isNotEmpty()
                                        onStatus("可用模型 ${it.size} 个，点选填入")
                                    }
                                    .onFailure { onStatus("失败：${it.message?.take(140)}") }
                                busy = false
                            }
                        }
                        .padding(horizontal = 10.dp, vertical = 8.dp)
                ) { Text(if (busy) "…" else "查询", color = P.text, fontSize = 10.sp) }
            }
            Spacer(Modifier.height(6.dp))
            if (providerId == "custom") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("openai" to "OpenAI 兼容", "anthropic" to "Anthropic", "gemini" to "Gemini").forEach { (id, label) ->
                        Box(
                            Modifier.clip(RoundedCornerShape(9.dp))
                                .background(if (kind == id) P.ai.copy(alpha = 0.2f) else Color.White.copy(alpha = 0.05f))
                                .border(1.dp, if (kind == id) P.ai.copy(alpha = 0.5f) else Color.White.copy(alpha = 0.1f),
                                    RoundedCornerShape(9.dp))
                                .clickable { onKind(id) }
                                .padding(horizontal = 8.dp, vertical = 5.dp)
                        ) { Text(label, color = if (kind == id) P.text else P.dim, fontSize = 9.5.sp) }
                    }
                }
                Spacer(Modifier.height(6.dp))
            }
            Field("Base URL", base, provider.base.ifBlank { "https://api.example.com/v1" }, false, onBase)
            if (providerId == "custom") {
                Spacer(Modifier.height(6.dp))
                Field("模型列表路径", modelsPath, "/models", false, onModelsPath)
            }
            Spacer(Modifier.height(8.dp))
            Text("token 累计：输入 ${ai.promptTokens(providerId)} · 输出 ${ai.completionTokens(providerId)}",
                color = P.dim, fontSize = 9.sp)
            if (status.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(status, color = if (status.contains("失败")) Color(0xFFFF8A80) else P.muted, fontSize = 9.5.sp)
            }
        }
    }

    if (showModelList) {
        Dialog(onDismissRequest = { showModelList = false }) {
            GlassCard(Modifier.fillMaxWidth().heightIn(max = 520.dp), corner = 20) {
                Column(Modifier.padding(14.dp)) {
                    Text("${provider.name} 可用模型（点一下填入）", color = P.text, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn {
                        items(localModels) { info ->
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 4.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onModel(info.id); onSave(); showModelList = false }
                                    .padding(horizontal = 6.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(info.id, color = P.text, fontSize = 11.sp, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                    val meta = buildString {
                                        info.context?.let { append("上下文 ").append(it / 1000).append("K") }
                                        info.output?.let {
                                            if (isNotEmpty()) append(" · ")
                                            append("输出 ").append(it / 1000).append("K")
                                        }
                                    }
                                    if (meta.isNotBlank()) {
                                        Text(meta, color = P.dim, fontSize = 8.5.sp)
                                    }
                                }
                                Text("复制", color = P.ai, fontSize = 9.sp,
                                    modifier = Modifier.clickable { clipboard.setText(AnnotatedString(info.id)) }
                                        .padding(horizontal = 6.dp, vertical = 3.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showProviders) {
        Dialog(onDismissRequest = { showProviders = false }) {
            GlassCard(Modifier.fillMaxWidth().heightIn(max = 520.dp), corner = 20) {
                Column(Modifier.padding(12.dp)) {
                    Text("选择服务商", color = P.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    LazyColumn {
                        items(AI_PROVIDERS.size) { i ->
                            val p = AI_PROVIDERS[i]
                            MenuRow(p.name, if (p.id == "custom") "自定义协议与地址" else p.base,
                                selected = p.id == providerId) {
                                onProvider(p.id); showProviders = false
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun Modifier.statusBarsPaddingIfNeeded(): Modifier = this.statusBarsPadding()
