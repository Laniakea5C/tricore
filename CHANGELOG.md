# Changelog

All notable changes to TriCore are documented here. Versions follow `versionCode` / `versionName` in `app/build.gradle.kts`.

## [9.8] — 2026-10-24

### Fixed
- Long answers could disappear at a formula on screen while the copied text was complete: the Markdown parser swallowed the rest of the message when a display-math block had an irregular closing delimiter
- Chat bubbles are now rendered line by line (`SafeMarkdownText`): no parse branch can drop content, formulas are only recognised when a single line is fully closed

### Changed
- Inline formulas (`$...$`, `\(...\)`, `[...]`) render as Unicode inline text; matrices, cases, fractions, roots and stacked `\sum` limits keep rendering in display blocks
- Stop button aborts the socket immediately (`disconnect()`) instead of waiting for the next streamed chunk
- Auto-follow now reacts to reasoning growth and to expanding the thinking section

### Added
- Automatic continuation (up to 2 rounds) when the provider reports `finish_reason=length`, ends the stream without a finish reason, or stops mid-sentence; continued text is appended to the same bubble
- Status line diagnostics: `token 输入 / 输出 · finish=stop|length|无`

## [8.5] — 2026-10-24

### Fixed
- Streaming replies no longer render `nullnullnull…` (org.json returns `"null"` for JSON null; all text reads now use a null-safe helper)
- AI tab no longer resets to a new conversation when switching tabs (current session ID and messages are persisted)
- Long answers are no longer truncated (DeepSeek 8K/64K, others 4K, Anthropic 8K with thinking)
- Graph labels no longer drift or desync while dragging (labels painted inside the same canvas transform, fixed radial placement)
- Labels of nodes outside the viewport are no longer clamped back onto the screen edge

### Added
- Streaming responses (SSE) for OpenAI-compatible and Anthropic providers; reasoning deltas stream into the collapsible thinking block
- DeepSeek: official `thinking` / `reasoning_effort` parameters, `deepseek-v4-pro` web search via `/v1/tools/search`, official search results injected as citations
- Anthropic: extended-thinking parameters and reasoning parsing; Grok/OpenAI reasoning models get `reasoning_effort`
- Model discovery returns context/output limits where the provider exposes them (Anthropic capability fields, Gemini `inputTokenLimit`/`outputTokenLimit`)
- Markdown rendering for assistant messages: fenced code, tables, headings, lists, quotes, inline code/bold/links
- Progress export/import (JSON via clipboard) and reset with confirmation
- Full-screen AI tab: app title and search button hidden, auto-growing composer (max 6 lines)
- Bilingual technical documentation (`docs/TECHNICAL.zh-CN.md`, `docs/TECHNICAL.en.md`)

## [8.0] — 2026-10-24

### Added
- AI tutor with 10 native providers plus custom endpoints (OpenAI-compatible / Anthropic / Gemini)
- Model discovery, per-provider token accounting, session history with export
- Image (camera / gallery) and text-file attachments
- Deep-thinking and web-search toggles (remembered)

## [7.9] — 2026-10-23

### Added
- Chapter-level progress with per-book, per-track and global progress rings
- Progress export/import/reset

## [7.5] — 2026-10-22

### Added
- Direction-first roadmap: master's, PhD and industry stages merged into each track, with books, toolchain and portfolio items
- Per-item industry guidance (must / portfolio / signals)

## [7.0] — 2026-10-20

### Added
- Knowledge layers (15) between directions and books; cross-layer edges
- Prompt workbench with six templates; send-to-AI
- Learning roadmap with by-track and by-semester views, plus table of contents

## [6.0] — 2026-10-18

### Added
- 35 textbooks / 192 chapters / 576 exercises derived from the study handbook
- Book detail pages with chapter checkmarks
