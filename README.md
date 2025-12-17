# Strawberry

A voice assistant Android app with low-latency streaming text-to-speech.

Download at: https://github.com/lorem111/strawberry-voice/releases/tag/v0.10

## Features

- **Voice Input**: Tap the mic to speak, automatic speech recognition
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

- **Speech Recognition**: Android's built-in SpeechRecognizer
- **LLM**: OpenRouter API with streaming support
- **TTS**: Cartesia Sonic with HTTP streaming for low latency
- **UI**: Jetpack Compose with Material 3

## Tech Stack

- Kotlin
- Jetpack Compose
- Ktor Client (OkHttp engine)
- Kotlin Coroutines & Flow
- DataStore for preferences

## License

MIT License
