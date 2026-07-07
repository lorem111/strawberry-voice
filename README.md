# Strawberry

A voice assistant Android app with low-latency streaming text-to-speech.

Download at: [https://github.com/lorem111/strawberry-voice/releases](https://github.com/lorem111/strawberry-voice/releases)

## Features

- **Voice Input**: Tap the mic to speak, automatic speech recognition
- **Text & Images**: Type messages and attach photos (multimodal models)
- **Chat Threads**: ChatGPT-style drawer with persistent, switchable conversations
- **AI Chat**: Powered by OpenRouter (supports multiple LLM models)
- **Streaming TTS**: Ultra-low latency voice responses with Cartesia Sonic
- **Auto-conversation**: Automatically resumes listening after AI responds
- **Multiple TTS Options**: Cartesia (streaming), Google Chirp, or local TTS
- **Google Sign-In**: Secure authentication with per-user API keys

## Setup

### For Users

1. Download the APK from releases
2. Install and open the app
3. Sign in with your Google account
4. Start chatting!

### For Developers

#### 1. Google Cloud Console Setup

1. Go to [Google Cloud Console](https://console.cloud.google.com/)
2. Create a new project or select existing
3. Enable the Google Identity Services API
4. Go to **APIs & Services** > **Credentials**
5. Create an **OAuth 2.0 Client ID** (Web application type)
6. Copy the Client ID - this is your `GOOGLE_WEB_CLIENT_ID`

#### 2. Configure Build

Add to `local.properties`:

```properties
GOOGLE_WEB_CLIENT_ID=your-client-id.apps.googleusercontent.com
AUTH_SERVER_URL=https://your-auth-server.vercel.app
```

#### 3. Auth Server Setup

See the `authserver/` directory for the Vercel backend that handles:
- Google token verification
- OpenRouter key provisioning
- User management

#### 4. Build & Install

1. Open the project in Android Studio
2. Build and install on your device
3. Sign in with Google to get your API keys automatically

## Architecture

The app is organized by feature, with the voice loop driven by a small state machine:

- **`core/`** — interfaces everything else implements: `TtsEngine`, `LlmClient`, `SpeechInput`, `ScoController`, plus `AppLogger`/`DebugLog` (in-app log ring buffer for admins)
- **`conversation/`** — `ConversationOrchestrator` owns the listen → think → speak → listen loop as a sealed `ConversationState` machine; `AssistantViewModel` is a thin UI mapper; `ConversationService` keeps audio alive in the background
- **`tts/`** — Cartesia (streaming), Google Chirp 3 HD, local Android TTS
- **`llm/`** — OpenRouter and Gemini (with Google Search grounding)
- **`audio/`** — SpeechRecognizer wrapper and Bluetooth SCO (car mode)
- **`chat/`** — Room-backed thread persistence (`ChatStore`) and `ImageStore` (image import + base64 for LLM payloads)
- **`di/`** — Hilt modules and `EngineRegistry`, which watches settings/API keys and swaps engines at runtime

Adding a new TTS or LLM = implement the `core/` interface, wire it in `EngineRegistry`.

Unit tests cover the conversation state machine (`app/src/test/`); CI runs lint + tests on every push.

## Tech Stack

- Kotlin
- Jetpack Compose
- Ktor Client (OkHttp engine)
- Kotlin Coroutines & Flow
- DataStore for preferences

## License

MIT License


