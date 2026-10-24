# TriCore Technical Documentation

> Applies to v8.5 (versionCode 38).
> For developers extending the app, adding providers, expanding the dataset, or tuning rendering.

---

## 1. Overview

TriCore is a single-Activity Android app built on **two stacked `ComposeView`s**:

- **contentView** — background, top bar, tab screens, detail/search/AI overlays
- **chromeView** — direction chips, bottom navigation, liquid-glass panels

The chrome layer samples a downscaled snapshot of the content view, so whatever is blurred behind a glass panel is exactly the real content underneath (no self-feedback halos).

### Stack

| Item | Value |
|---|---|
| Language | Kotlin 2.0.21 |
| UI | Jetpack Compose (BOM 2024.10.01, Compose 1.7.5) |
| Build | AGP 8.7.3 · Gradle 8.11.1 · JDK 21 |
| SDK | compileSdk 35 · minSdk 26 · targetSdk 35 |
| Dependencies | AndroidX + Compose + `org.json` only |

### Screen map

```
MainActivity
├── contentView
│   ├── TopBar (hidden on the AI tab)
│   └── tabs: GRAPH · PATH · TOOLS · INDUSTRY · TEMPLATES · AI · PROGRESS
│   ├── BookDetail overlay
│   └── Search overlay; AI tab has its own drawer / settings / model dialogs
└── chromeView
    ├── DirSegments (direction chips)
    └── BottomBar
```

---

## 2. Module map

| File | Responsibility |
|---|---|
| `MainActivity.kt` | Activity, `UiState`, dual ComposeView assembly, tab routing, TopBar/BottomBar/DirSegments, search overlay |
| `Data.kt` | Data models, `app_data.json` parser, `ProgressStore` (progress, export/import/reset) |
| `Screens.kt` | Background, glass cards, tools, industry, legacy progress, search, shared widgets |
| `Graph.kt` | Graph layout `buildLayout`, Canvas painting, gestures, labels, focus modes |
| `Glass.kt` | `BackdropState`, `LocalBackdrop`, `buildBackdrop`, `GlassSurface`, `GlassChip` |
| `Roadmap2.kt` | Roadmap (by track / by semester) and progress screen with chapter toggles |
| `BookDetail.kt` | Textbook page, `DirTag` / `TagRow` |
| `Guide.kt` | Prompt workbench, prompt assembly, send-to-AI |
| `Ai.kt` | Provider protocols, model discovery, streaming & non-streaming chat, DeepSeek search, reasoning parsing, usage |
| `AiChat.kt` | Chat screen, session persistence, settings, model list, drawer |
| `Markdown.kt` | Lightweight Markdown renderer (code fences, tables, headings, lists, quotes, inline styles) |
| `Sound.kt` | Procedural ambient loop and click sounds |

---

### 2.1 Sound implementation (Sound.kt)

- Music: `buildMusic()` synthesises 240 s of mono 22.05 kHz PCM (triple-detuned E1/B1/E2 saws, noise tide, twelve bells, heartbeat sub, three comb-delay reverb), streamed through `AudioTrack` in `MODE_STREAM`, cached at `cacheDir/sfx/music_v5.pcm`
- Loop: every modulation frequency divides 240 s; the tail is crossfaded over 4 s
- Effects: `buildClick()` synthesises a 140 ms plastic switch (noise tick, 220/330 Hz body, 1.32 kHz edge, short tail), written as WAV and played by `SoundPool` with a `pitch` argument
- Toggle: persisted in `tricore_sound`; music starts only on the graph tab and pauses on leaving; clicks are independent of the music toggle
- Threads: synthesis on `sfx-build`, streaming on `sfx-stream`; write failures are swallowed with `runCatching`

## 3. Liquid-glass rendering

### 3.1 Why two views

With a single view, panels draw themselves into the snapshot, producing halos and feedback. Splitting them means the snapshot contains only content.

### 3.2 Downsampling

```kotlin
val w = contentView.width / 8
val h = contentView.height / 8
val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
val canvas = Canvas(bmp)
canvas.scale(1f / 8f, 1f / 8f)
contentView.draw(canvas)
```

1/8 per axis ≈ 1/64 of the pixels; after a Gaussian blur the difference is imperceptible but the cost is tiny.

### 3.3 Adaptive cadence

| Snapshot cost | Interval |
|---|---|
| ≤ 6 ms | 16 ms (60 fps) |
| ≤ 12 ms | 33 ms (30 fps) |
| otherwise | 66 ms (15 fps) |

Measured on a 120 Hz phone while dragging: p50 9–11 ms, p90 13–15 ms, jank 7–12%.

### 3.4 Panels

`GlassSurface` = blurred snapshot + 5% white wash + 8% direction tint + rounded clip. No refraction or rim highlights (an earlier AGSL refraction attempt was removed for halos and sampling offsets).

Direction chips animate x/y/w/h over 250 ms with `FastOutSlowInEasing`; press scale is 0.94.

---

## 4. Knowledge graph

### 4.1 Virtual coordinates

A fixed 1240×1900 canvas holds nodes on rings:

- `core`
- 4 direction hubs
- 15 knowledge layers
- 35 books (foundation books on their own ring)
- the selected book's chapters

Painting uses one transform:

```kotlin
translate(ox, oy) {
    scale(s, s, pivot = Offset.Zero) { ... }
}
```

with `s = min(width/bw, height/bh) * zoom * 0.97`.

### 4.2 Labels

- Painted **inside the same transform**, so they move and scale exactly with the nodes
- Font size is divided by `s`, keeping a constant on-screen size (11.5 / 9.5 / 9 / 8.5 sp)
- Placed radially away from the graph centre
- A node fully outside the visible rect (with a 0.6× radius margin) gets no label, so text is never clamped back onto the screen edge
- No per-frame collision shuffling (that caused jitter); dense areas are resolved by tapping a hub/layer to fan out

### 4.3 Interaction

- Drag / pinch with `pointerInput(layout)` (avoids restarting the gesture every frame)
- Hit radius 220 virtual units, so tapping the label area also selects
- Tap a hub: filter that track, courses fan out on a 150° arc
- Tap a layer: dedicated layout with its books evenly distributed on a circle
- Tap a book: open the textbook detail overlay

### 4.4 Performance rules

- Never allocate a full-resolution Bitmap per frame
- Never call `toImageBitmap()` while drawing the graph
- Background grid, scan line and vignette are cheap Canvas operations

---

## 5. Data model

Everything ships in `app/src/main/assets/app_data.json` (~830 KB, fully offline): `meta`, `directions`, `layers`, `books`, `chapters` (with `demos`), `templates`, `routes`, `toolchain`, `industry`, `graph`.

`parseAppData(raw)` produces immutable Kotlin structures with `booksById`, `chaptersById`, `chaptersByBook`, `dirOf`, `demoTotal`.

`ProgressStore` (SharedPreferences `tricore_progress`) stores a `Set<String>` of completed demo IDs and offers chapter toggles, per-book/per-track aggregation, JSON export/import and reset.

A chapter counts as complete when all of its demos are complete.

---

## 6. AI integration

### 6.1 Providers

Ten presets plus a custom entry. Each provider declares `base`, `kind` (`openai` / `anthropic` / `gemini`) and `modelsPath`. The custom entry lets users pick a protocol, base URL and models path for arbitrary gateways.

### 6.2 Configuration

`AiStore` (SharedPreferences `tricore_ai`) stores per-provider key/model/base/kind/path, remembered deep-thinking and web-search toggles, current session ID, serialized sessions, and cumulative prompt/completion token counters.

Keys never leave the device and there is no relay server.

### 6.3 Requests

Non-streaming `chat()` and streaming `chatStream()` share message construction:

- OpenAI-compatible: `messages[]`, images as `image_url` data URLs
- Anthropic: `system` + content blocks, images as `image.source {type: base64}`
- Gemini: `contents[].parts`, images as `inline_data`

| Provider | Thinking | Output limits |
|---|---|---|
| DeepSeek | `thinking {type, reasoning_effort}` | 64K thinking / 8K normal (`max_tokens` up to 384K) |
| Anthropic | `thinking {type: enabled, budget_tokens}` | 8K / 4K |
| OpenAI / Grok | `reasoning_effort` for matching models | 4K |
| Others | prompt-level | 4K |

### 6.4 Streaming

`chatStream()` consumes SSE:

- OpenAI-compatible: `data: {choices:[{delta:{content, reasoning_content}}]}`
- Anthropic: `content_block_delta` with `text_delta` / `thinking_delta`
- Gemini: non-streaming fallback for now

The UI inserts an empty assistant message, then replaces it on every delta (triggering recomposition) and finally overwrites it with the completed result before persisting.

**Gotcha:** `org.json`'s `optString` returns the literal string `"null"` for JSON `null`; use the `JSONObject.str()` helper or streaming renders `nullnullnull`.

### 6.5 DeepSeek web search

Two-step: call `POST {base}/v1/tools/search` (`query`, `count: 6`, `need_content: false`), then inject the returned title/URL/summary block into the system prompt and ask the model to prefer it and cite sources. Falls back to a normal answer when the search fails or returns nothing.

### 6.6 Reasoning display

DeepSeek `reasoning_content`, Anthropic `thinking` blocks and Gemini `thought` parts are captured separately from the answer and rendered in a collapsible "thinking" section that grows live during streaming. Sessions persist it in the `rs` field.

### 6.7 Model discovery

`listModelInfos()` returns `AiModelInfo(id, context, output)`: OpenAI-style APIs give IDs only; Anthropic exposes `max_input_tokens` / `max_tokens` capability fields; Gemini exposes `inputTokenLimit` / `outputTokenLimit`. The settings screen lists them, fills the chosen ID, and can copy it.

---

## 7. Build & release

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Configure signing locally; never commit keystores or API keys. Bump `versionCode` / `versionName` in `app/build.gradle.kts`.

---

## 8. Extending

### Add a textbook

1. Append to `books` (e.g. `B36`, with `dir`, `layer`, `tags`)
2. Append chapters `B36-C01…` with three demos each (A/B/C)
3. Add graph nodes and `layer → book → chapter` edges
4. Update `meta` counts, rebuild, inspect the graph

### Add a provider

- OpenAI-compatible: add an `AI_PROVIDERS` entry with `kind = "openai"` and `modelsPath = "/models"`
- New protocol: add branches in `listModelInfos`, `chat` and `chatStream`, and return `AiResult.reasoning` when applicable

### Add a prompt template

Append `{id, name, system, prompt}`; placeholders `{book}`, `{n}`, `{chapter}`, `{prior}`, `{task}`, `{level}` are substituted.

### Theming

The palette lives in `Screens.kt` / `Glass.kt` (`P.ai`, `P.dir`, `P.bg`).

---

## 9. Known limitations

- No context trimming for very long chats; the provider will error instead
- Gemini streaming not implemented
- Attachments are text-only (txt/md/code/json/xml/csv); PDF/Word are not parsed
- Vision and web search depend on the chosen model/provider
- Dense graph regions can still overlap labels
- Portrait phones only
- AI features require the user's own API key; the repository ships none
