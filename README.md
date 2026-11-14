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
- **Chat management** — multiple sessions per character, edit/delete/regenerate any message, everything stored locally in Room.

## Project layout

```
app/src/main/java/com/roleplay/ai/
├── MainActivity.kt, RoleplayApp.kt      # single-activity Compose host, manual DI
├── data/
│   ├── api/       OpenAiClient (streaming SSE + non-streaming), request/response models
│   ├── db/        Room: characters, sessions, messages, memories
│   ├── prefs/     DataStore settings (API config, theme, prompts, memory tuning)
│   └── repo/      ChatRepository — single facade over db + api
├── domain/        PromptBuilder — system prompt composition
└── ui/
    ├── theme/     Material 3 dynamic dark/light themes
    ├── RoleplayNav.kt                   # bottom tabs + detail routes
    ├── chatlist/  Chat list + character picker
    ├── chat/      Chat screen (streaming bubbles, edit/delete/pin/regenerate)
    ├── characters/ Character list + full character editor
    ├── memory/    Memory manager
    └── settings/  API, appearance, instructions, memory settings
```

## Building

Open the project in **Android Studio** (Ladybug or newer) and run the `app` configuration — Studio supplies the Gradle wrapper automatically. To build from the command line, install Gradle 8.9+ and run:

```bash
gradle wrapper   # once, to generate the full wrapper
./gradlew assembleDebug
```

Requirements: JDK 17, Android SDK 35, minSdk 26 (Android 8.0+).

## First run

1. Open **Settings** → choose a provider preset (or type a base URL), paste your API key, set the model.
2. Go to **Characters** → create one (persona + scenario + optional greeting).
3. Start a chat from the Characters tab or the ➕ on the Chats tab.
4. Optional: set global custom instructions and memory behavior in Settings; long-press messages to pin memories.

Local servers (Ollama / LM Studio): use their `http://localhost:…/v1` base URL and leave the key empty. To reach a server on your computer from a device, use your LAN IP instead of `localhost`.

## Notes

- The API key is stored locally in DataStore and excluded from Android backups (`app/src/main/res/xml/backup_rules.xml`).
- Requests go to `<base URL>/chat/completions` with `Authorization: Bearer <key>`, which is the OpenAI-compatible contract used by all the providers listed above.
