# Contributing

Thanks for helping improve TriCore.

## Before you start

- Run `./gradlew :app:assembleDebug` and make sure the app builds.
- For UI changes, attach a screenshot (and a before/after when possible).
- Never commit API keys, keystores or `local.properties`.

## What we need most

1. **Data**: new textbooks, chapters, exercises, toolchain entries, industry guidance
2. **Providers**: new AI protocols or fixes for model discovery / streaming
3. **Rendering**: graph label layout, glass performance
4. **Docs**: corrections to the bilingual technical docs

## Data contributions

Edit `app/src/main/assets/app_data.json`:

- Books: `id` (`B36`…), `title`, `edition`, `dir`, `chapterCount`, `demoCount`, `layer`, `tags`
- Chapters: `id` (`B36-C01`), `book`, `n`, `title`, `points`, `tags`, `demos` (A/B/C)
- Graph: add nodes and `layer → book → chapter` edges, update `meta` counts

Exercise prompts should stay self-contained and avoid fabricated references; mark uncertain facts as “待核实”.

## Code style

- Kotlin, 4-space indent, no wildcard imports
- Keep AI keys out of source; read them from `SharedPreferences` only
- UI strings in Chinese to match the app; docs in both Chinese and English

## Pull requests

- One topic per PR, with a short description of the change and how it was verified
- Link related issues
- Note whether it was tested on a physical device (and which Android version)

## License

By contributing you agree that your work is licensed under the [MIT License](LICENSE).
