# DevCompanion

An Android app that embeds a browser with AI agent integration for on-device debugging.

## What it does

- **Browser with DevTools** — WebView with console, network, and performance monitoring
- **AI Chat** — Talk to LLMs (Anthropic, OpenAI, Ollama, Gemini) directly on-device
- **Agent Loop** — LLM inspects and controls the browser via tool calls (DOM, styles, JS eval)
- **Bridge API** — REST server for external agents to drive the browser programmatically

## Getting Started

### Prerequisites

- Android Studio Hedgehog or later
- JDK 17
- Android SDK (compileSdk 36)

### Build

```bash
./gradlew assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`

## Project Structure

```
app/src/main/java/com/devcompanion/
├── browser/        Browser engine abstraction
├── debug/          WebViewDebugger, JS autocomplete
├── llm/            LLM adapters + agent loop
├── bridge/         REST API server (NanoHTTPD)
├── ui/             Compose UI
└── theme/          Material 3 theme
```

## License

MIT