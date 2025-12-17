# Strawberry

A voice assistant Android app with low-latency streaming text-to-speech.

## Features

- **Voice Input**: Tap the mic to speak, automatic speech recognition
- **AI Chat**: Powered by OpenRouter (supports multiple LLM models)
- **Streaming TTS**: Ultra-low latency voice responses with Cartesia Sonic
- **Auto-conversation**: Automatically resumes listening after AI responds
- **Multiple TTS Options**: Cartesia (streaming), Google Chirp, or local TTS

## Setup

### 1. Get API Keys

You'll need API keys from:

- **OpenRouter** (required): https://openrouter.ai/keys
  - Provides access to various LLM models (Gemini, Claude, GPT, Llama, etc.)

- **Cartesia** (recommended): https://cartesia.ai/
  - Low-latency streaming text-to-speech
  - Sign up and get API key from dashboard

### 2. Build & Install

1. Open the project in Android Studio
2. Build and install on your device
3. Open Settings in the app and enter your API keys

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
