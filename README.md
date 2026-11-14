# Roleplay AI (Android)

An Android app for AI roleplay chats. Works with **any OpenAI-compatible API** (OpenAI, OpenRouter, Groq, Mistral, DeepSeek, local Ollama / LM Studio, …), with **dark / light / system theming**, a **memory extension**, and **custom prompt instructions** — in a familiar chat-app layout.

## Features

- **Any AI provider** — one client for all OpenAI-compatible endpoints. Pick a quick preset (OpenAI, OpenRouter, Groq, Mistral, DeepSeek, Ollama, LM Studio) or enter any base URL + key + model.
- **Streaming responses** (SSE) with a stop button that keeps the partial reply.
- **Roleplay character cards** — name, tagline, persona, description & scenario, example dialogue, first message, avatar color.
- **Memory extension**
  - *Pinned memories*: long-press any message → "Save as memory"; manage (enable/disable/delete) in the Memories screen. Injected into every request.
  - *Rolling auto-summary*: when a chat grows past a configurable threshold, older turns are folded into a summary via the API, so long stories keep context without an ever-growing prompt.
- **Custom prompt instructions** — global instructions applied to every chat (writing style, formatting, content boundaries), composed together with the character persona, memories, and summary into the system prompt.
- **Dark / light / system theme** — Material 3 with dynamic color on Android 12+, persisted in DataStore.
- **Chat management** — multiple sessions per character, edit/delete any message, everything stored locally in Room.
- **Message swiping** — regenerating keeps the earlier replies as variants. Swipe left/right on the last reply (or use the arrows) to cycle through them; the `2/3` indicator shows where you are.
- **Continue** — extend the last reply in place.
- **Group chats** — pick two or more characters when starting a chat; the model plays all of them, each part prefixed with `**Name:**`.
- **Writing mode** — a freeform, autosaved document. *Continue* sends the entire document (no truncation) and appends what the model writes next. Lorebook, memories and global instructions still apply.
- **Lorebook / world info** — entries with trigger keywords. An entry is added to the system prompt only while one of its keywords appears (case-insensitively) in the recent messages or the document.
- **Auto-memory** — after each reply, one background request extracts up to 3 new durable facts into that chat's Memories, skipping ones already saved. Toggle in Settings (on by default).
- **Images** — attach photos to a message (sent to vision models as `image_url` parts, copied into app storage, shown in the bubble), and set a custom, dimmed chat background in Settings.
- **Character pictures** — add a profile picture to any character in the editor; it replaces the letter avatar everywhere.
- **Per-chat notes** — an author's note for one chat or document (note icon in the top bar). It is sent with every request, just before the latest message, and shown as a strip under the top bar.
- **Token counter** — under the message box, how many tokens the last request sent and received. Uses the provider's real counts when it reports them, otherwise an estimate (≈).
- **Writing-mode limit** — Settings → Writing mode: send only the latest N words instead of the whole document, to keep long documents cheap.
- **Generate with AI** — in the character editor, turn a short description into a full character card.
- **Backup / restore** — export or import characters, chats, documents, messages, memories and the lorebook as one JSON file (Settings). Settings, the API key and attached photos are not included.

## Project layout

```
app/src/main/java/com/roleplay/ai/
├── MainActivity.kt, RoleplayApp.kt      # single-activity Compose host, manual DI
├── data/
│   ├── api/       OpenAiClient (streaming SSE + non-streaming), request/response models
│   ├── backup/    BackupManager — JSON export/import
│   ├── db/        Room: characters, sessions, messages, memories, lorebook (+ MIGRATION_1_2)
│   ├── files/     ImageStore — photos copied into app storage
│   ├── prefs/     DataStore settings (API config, theme, prompts, memory tuning)
│   └── repo/      ChatRepository — single facade over db + api
├── domain/        PromptBuilder (chat, group, writing prompts), lorebook matching, JSON extraction
└── ui/
    ├── theme/     Material 3 dynamic dark/light themes
    ├── RoleplayNav.kt                   # bottom tabs + detail routes
    ├── chatlist/  Chat list + character picker
    ├── chat/      Chat screen (streaming bubbles, swipes, continue, photos, edit/delete/pin)
    ├── writing/   Writing-mode document editor
    ├── lorebook/  Lorebook manager
    ├── characters/ Character list + full character editor
    ├── memory/    Memory manager
    └── settings/  API, appearance, instructions, memory settings
```

## Building

Open the project in **Android Studio** (Ladybug or newer) and run the `app` configuration, or build from the command line:

```bash
./gradlew assembleDebug        # debug APK
./gradlew testDebugUnitTest    # unit, migration and UI tests (run on the JVM, no device needed)
```

Requirements: JDK 17+, Android SDK 35, minSdk 26 (Android 8.0+).

## Database changes

The database is at version 3. Existing installs are upgraded by `AppDatabase.MIGRATION_1_2` and `MIGRATION_2_3` (`ALL_MIGRATIONS`); `MigrationTest` builds version-1 and version-2 databases from the exported schemas in `app/schemas/` and checks that every chat survives. When the schema changes again, bump the version, add a new `Migration`, commit the newly exported schema file, and never use destructive migration.

## First run

1. Open **Settings** → choose a provider preset (or type a base URL), paste your API key, set the model.
2. Go to **Characters** → create one (persona + scenario + optional greeting).
3. Start a chat from the Characters tab or the ➕ on the Chats tab. The ➕ also starts group chats (tick several characters) and writing documents.
4. Optional: set global custom instructions and memory behavior in Settings; long-press messages to pin memories.

Local servers (Ollama / LM Studio): use their `http://localhost:…/v1` base URL and leave the key empty. To reach a server on your computer from a device, use your LAN IP instead of `localhost`.

## Notes

- The API key is stored locally in DataStore and excluded from Android backups (`app/src/main/res/xml/backup_rules.xml`).
- Requests go to `<base URL>/chat/completions` with `Authorization: Bearer <key>`, which is the OpenAI-compatible contract used by all the providers listed above.
