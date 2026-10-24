# TriCore 技术文档

> 适用版本：v8.5（versionCode 38）
> 读者：想二次开发、接新厂商、扩充数据或优化渲染的开发者。

---

## 1. 总览

TriCore 是一个**单 Activity、双 ComposeView** 的 Android 应用：

- **内容层（contentView）**：背景、顶栏、各标签页、详情页、搜索页、AI 页
- **悬浮层（chromeView）**：方向胶囊栏、底部导航、液态玻璃面板

两层叠放在 `FrameLayout` 中，悬浮层从内容层的低分辨率快照取景做模糊，因此「面板背后的模糊」永远与真实内容一致。

### 技术栈

| 项 | 取值 |
|---|---|
| 语言 | Kotlin 2.0.21 |
| UI | Jetpack Compose（BOM 2024.10.01 / Compose 1.7.5） |
| 构建 | AGP 8.7.3 · Gradle 8.11.1 · JDK 21 |
| SDK | compileSdk 35 · minSdk 26 · targetSdk 35 |
| 依赖 | 仅 AndroidX + Compose + org.json，无第三方 UI/网络库 |

### 页面结构

```
MainActivity
├── contentView
│   ├── TopBar（AI 页隐藏）
│   └── Tab 内容
│       ├── GRAPH     知识图谱
│       ├── PATH      学习路线（按方向 / 按阶段）
│       ├── TOOLS     工具链（含详情弹窗）
│       ├── INDUSTRY  业界画像（逐条详情）
│       ├── TEMPLATES 提示词工坊
│       ├── AI        AI 学习导师（全屏聊天）
│       └── PROGRESS  进度（章节勾选、导入导出）
│   ├── BookDetail 覆盖层
│   ├── SearchOverlay 覆盖层
│   └── AI 页内：会话抽屉 / 设置页 / 模型列表弹窗
└── chromeView
    ├── DirSegments 方向胶囊
    └── BottomBar 底部导航
```

---

## 2. 模块结构

| 文件 | 职责 |
|---|---|
| `MainActivity.kt` | Activity、`UiState`、双 ComposeView 组装、Tab 路由、TopBar/BottomBar/DirSegments、搜索页 |
| `Data.kt` | 数据模型、`app_data.json` 解析、`ProgressStore`（进度、导入导出） |
| `Screens.kt` | 背景、玻璃卡片、工具页、业界页、进度页（旧）、搜索、通用组件 |
| `Graph.kt` | 图谱布局 `buildLayout`、Canvas 绘制、手势、标签、方向/知识层聚焦 |
| `Glass.kt` | `BackdropState`、`LocalBackdrop`、`buildBackdrop`、`GlassSurface`、`GlassChip` |
| `Roadmap2.kt` | 新版路线图与进度页（章节勾选、导入导出、重置） |
| `BookDetail.kt` | 教材详情、方向标签 `DirTag` / `TagRow` |
| `Guide.kt` | 模板工坊、提示词拼装、发送到 AI |
| `Ai.kt` | 厂商协议、模型查询、非流式/流式请求、DeepSeek 搜索、思考内容解析、用量 |
| `AiChat.kt` | 聊天页、会话持久化、设置页、模型列表、抽屉 |
| `Markdown.kt` | 轻量 Markdown 渲染（代码块、表格、标题、列表、引用、行内样式） |
| `Sound.kt` | 程序化环境音与点击音（AudioTrack / SoundPool） |

---

## 3. 液态玻璃渲染

### 3.1 为什么用双 View

单 View 方案中，面板自己也会被画进快照，导致「玻璃里出现自己」的光晕和反馈环。拆成两层后：

1. `contentView` 只包含真实内容
2. `chromeView` 每帧把 `contentView` 画进 `Bitmap(width/8, height/8)`
3. 悬浮面板用 `BitmapShader` 思路的等价实现：缩放绘制 + `RenderEffect` 高斯模糊

### 3.2 降采样

```kotlin
val w = contentView.width / 8
val h = contentView.height / 8
val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
val canvas = Canvas(bmp)
canvas.scale(1f / 8f, 1f / 8f)
contentView.draw(canvas)
```

1/8 分辨率意味着快照成本约为全分辨率的 1/64，再经过模糊后肉眼几乎看不出差异。

### 3.3 自适应频率

`ChromeLayer` 测量每次快照的耗时：

| 耗时 | 刷新间隔 |
|---|---|
| ≤ 6 ms | 16 ms（60 fps） |
| ≤ 12 ms | 33 ms（30 fps） |
| 其他 | 66 ms（15 fps） |

真机（120Hz）拖拽实测：50th 9–11 ms，90th 13–15 ms，jank 7–12%。

### 3.4 玻璃面板

`GlassSurface` = 模糊快照 + 白色 5% 薄涂 + 方向色 8% 着色 + 圆角裁切；不含折射/边缘高光（早期版本尝试过 AGSL 折射，因光晕与采样偏移被移除）。

方向胶囊：250ms `FastOutSlowInEasing` 的 x/y/w/h 动画；按下缩放 0.94。

---

## 4. 知识图谱

### 4.1 虚拟坐标系

固定画布 `1240 × 1900`，布局把节点放在若干环上：

- 中心 `core`
- 4 个方向 hub
- 15 个知识层（按方向分布）
- 35 本书（基础书单独成环）
- 选中书时展开 192 章中的当前书章节

绘制时统一变换：

```kotlin
translate(ox, oy) {
    scale(s, s, pivot = Offset.Zero) { ... }
}
```

`s = minOf(width/bw, height/bh) * zoom * 0.97`，`ox/oy` 由 pan 与 bounds 推导。

### 4.2 标签

- **与节点同变换**绘制，保证拖拽/缩放时完全同步
- 字号按 `1/s` 反算，屏幕上大小恒定（方向 11.5sp、知识层 9.5sp、书 9sp、章 8.5sp）
- 径向排布：左侧节点标在左侧、下方节点标在下方
- 节点完全离开可视矩形（留 0.6 倍半径余量）时不画标签，避免文字被夹回屏幕边缘
- 标签不再做逐帧碰撞重排（会造成"文字乱飘"），密集区域通过点击方向/知识层聚焦散开

### 4.3 交互

- 单指拖拽 / 双指缩放（`pointerInput(layout)`，避免每帧重启手势）
- 命中半径 220（虚拟单位），点标签附近也能选中
- 点方向 hub：筛选该方向，课程沿 150° 大弧展开
- 点知识层：进入「知识层专页」布局，该层书籍按 360° 均布
- 点书：进入教材详情覆盖层

### 4.4 性能约束

- 每帧不得新建全分辨率 Bitmap
- 图谱绘制不做 `toImageBitmap()`
- 背景网格、扫描线、vignette 均为轻量 Canvas 操作

---

## 5. 数据模型

### 5.1 资产

`app/src/main/assets/app_data.json`（约 830 KB）：

```json
{
  "meta": { "version": "2.0", "bookCount": 35, "chapterCount": 192, "demoCount": 576 },
  "directions": [{ "id": "ai", "name": "神经网络" }, ...],
  "layers":    [{ "id": "L01", "label": "数学基础", "dir": "base", "books": ["B01"] }],
  "books":     [{ "id": "B01", "title": "高等数学", "edition": "...", "dir": "base",
                  "chapterCount": 12, "demoCount": 36, "layer": "L01",
                  "stage": "大一上", "read": "...", "tags": ["ai","chip","robot"] }],
  "chapters":  [{ "id": "B01-C01", "book": "B01", "n": 1, "title": "函数与极限",
                  "points": ["映射与函数"], "tags": ["ai","chip","robot"],
                  "demos": [{ "id": "B01-C01-A", "level": "A", "title": "概念验证",
                              "task": "...", "check": "...", "minutes": "30–60 分钟",
                              "prompt": "..." }] }],
  "templates": [{ "id": "T1", "name": "概念验证（A 级）", "system": "...", "prompt": "..." }],
  "routes":    [{ "id": "R1", "title": "本科前两年", "steps": [{ "title": "大一上",
                  "goal": "...", "books": "B01、B04", "weekly": "...", "output": "..." }] }],
  "toolchain": { "ai": [{ "name": "PyTorch", "category": "...", "level": "必学",
                          "note": "...", "detail": "怎么学…", "url": "..." }] },
  "industry":  { "ai": { "must": ["..."], "mustDetail": ["..."],
                          "portfolio": ["..."], "portfolioDetail": ["..."],
                          "signals": ["..."], "signalsDetail": ["..."],
                          "timeline": [{ "phase": "...", "items": ["..."] }] } },
  "graph":     { "nodes": [{ "id": "B01", "label": "高等数学", "level": 2, "kind": "book" }],
                 "edges": [{ "a": "L01", "b": "B01", "kind": "contains" }] }
}
```

### 5.2 解析

`parseAppData(raw: String): AppData` 一次性解析为不可变 Kotlin 数据结构，提供：

- `booksById` / `chaptersById` / `chaptersByBook` / `dirOf` / `demoTotal`
- 每本书的 `chapterCount`、`demoCount` 与实际数据一致（构建时校验）

### 5.3 进度

`ProgressStore`（SharedPreferences `tricore_progress`）：

- `done: Set<String>`：已完成 Demo ID
- `toggle(demoId)` / `toggleChapter(chapter)` / `isChapterDone(chapter)`
- `doneInChapter` / `doneInBook` / `doneByDir`
- `exportText(): String`（JSON 数组）/ `importText(text): Int` / `reset()`

章节完成 = 该章全部 Demo 完成。

---

## 6. AI 集成

### 6.1 厂商

`AI_PROVIDERS` 描述 10 家预置厂商 + 自定义：

| 字段 | 含义 |
|---|---|
| `base` | 默认 Base URL（可被用户覆盖） |
| `kind` | `openai` / `anthropic` / `gemini` |
| `modelsPath` | 模型列表路径（OpenAI 系 `/models`） |

自定义厂商可选三种协议并填写 Base URL 与模型列表路径。

### 6.2 配置与密钥

`AiStore`（SharedPreferences `tricore_ai`）：

- `key_<provider>` / `model_<provider>` / `base_<provider>` / `kind_<provider>` / `path_<provider>`
- `deep_think` / `web_search`（记忆开关）
- `current_session` + `sessions`（会话 JSON）
- `pt_<provider>` / `ct_<provider>`（累计 token）

密钥只存在本机，不经过任何中转服务。

### 6.3 请求

非流式 `chat()` 与流式 `chatStream()` 共用同一套消息构造：

- OpenAI 兼容：`messages[]`，图片用 `image_url: data:image/jpeg;base64,...`
- Anthropic：`system` + content blocks，图片用 `image.source {type:base64}`
- Gemini：`contents[].parts`，图片用 `inline_data`

参数差异：

| 厂商 | 思考 | 输出上限 |
|---|---|---|
| DeepSeek | `thinking: {type, reasoning_effort}` | 思考 64K / 非思考 8K（`max_tokens` 上限 384K） |
| Anthropic | `thinking: {type: enabled, budget_tokens}` | 思考 8K / 普通 4K |
| OpenAI / Grok | `reasoning_effort`（按模型名匹配） | 4K |
| 其他 | 提示词层 | 4K |

### 6.4 流式

`chatStream()` 使用 SSE：

- OpenAI 兼容：`data: {choices:[{delta:{content, reasoning_content}}]}`
- Anthropic：`event: content_block_delta` + `data: {delta:{text|thinking}}`
- Gemini：暂用非流式兜底

UI 先把空 assistant 消息放入列表，`onText` / `onReason` 每收到增量就替换该消息（触发重组），完成后用最终结果覆盖并持久化。

**注意**：`org.json` 的 `optString` 对 JSON `null` 返回字符串 `"null"`，必须用 `JSONObject.str()` 安全取值，否则流式会出现 `nullnullnull`。

### 6.5 DeepSeek 联网搜索

两步式：

1. `POST {base}/v1/tools/search`，body `{query, count: 6, need_content: false}`
2. 把 `[标题/URL/summary]` 拼进 system prompt，要求模型优先使用并列出引用

失败或无结果时降级为普通回答并提示。

### 6.6 思考内容

- DeepSeek：`message.reasoning_content`（流式在 `delta.reasoning_content`）
- Anthropic：`content[]` 中 `type=thinking` 的 `thinking`
- Gemini：`parts[]` 中 `thought=true`

UI 用可折叠「思考过程」区展示，随流式实时增长；会话持久化时保存 `rs` 字段。

### 6.7 模型查询

统一返回 `AiModelInfo(id, context, output)`：

- OpenAI 系：只有 `data[].id`
- Anthropic：`data[].id` + `max_input_tokens` / `max_tokens`（能力字段）
- Gemini：`models[].name` + `inputTokenLimit` / `outputTokenLimit`

设置页查询后弹窗展示，点选填入，右侧可复制 ID。

---

## 7. 构建与发布

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

签名：在 `app/build.gradle.kts` 配置 keystore，或用环境变量/`gradle.properties`；**不要提交密钥**。

版本号：`app/build.gradle.kts` 的 `versionCode` / `versionName`。

---

## 8. 扩展指南

### 8.1 增加一本教材

1. 在 `app_data.json` 的 `books` 增加对象（`id` 如 `B36`，指定 `dir`、`layer`、`tags`）
2. `chapters` 增加章节（`id = B36-C01`），每章建议 3 个 `demos`（A/B/C）
3. `graph.nodes` 增加书节点与章节节点，`graph.edges` 连接 `layer → book → chapter`
4. 更新 `meta.bookCount/chapterCount/demoCount`
5. 重新构建并检查图谱布局

### 8.2 新增厂商

- 若兼容 OpenAI：在 `AI_PROVIDERS` 增加一项，`kind="openai"`，`modelsPath="/models"`
- 若是新协议：在 `AiClient` 增加 `listModelInfos` 分支与 `chat`/`chatStream` 分支
- 思考内容：在对应解析处返回 `AiResult.reasoning`

### 8.3 新增提示词模板

在 `templates` 增加 `{id, name, system, prompt}`，`prompt` 支持占位符 `{book}` `{n}` `{chapter}` `{prior}` `{task}` `{level}`。

### 8.4 替换主题色

`P`（调色板）集中在 `Screens.kt`/`Glass.kt`，改 `P.ai / P.dir / P.bg` 即可；方向色与 `directions` 一一对应。

---

## 9. 已知限制

- 对话无多模态历史裁剪策略：超长会由厂商报错
- Gemini 未实现流式
- 附件只读文本类文件（txt/md/code/json/xml/csv），不解析 PDF/Word
- 图片理解取决于所选模型
- 图谱密集区标签仍可能部分重叠
- 仅手机竖屏布局
- AI 相关功能需用户自备 API Key，仓库不含任何密钥

---

## 附录：目录

```
app/src/main/java/com/tricore/demolab/
├── MainActivity.kt   Data.kt   Screens.kt   Graph.kt
├── Glass.kt          Roadmap2.kt  BookDetail.kt  Guide.kt
├── Ai.kt             AiChat.kt    Markdown.kt    Sound.kt
app/src/main/assets/app_data.json
app/src/main/res/...（图标、主题、FileProvider）
docs/（技术文档、截图）
```
