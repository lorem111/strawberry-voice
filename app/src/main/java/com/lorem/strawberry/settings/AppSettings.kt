package com.lorem.strawberry.settings

// TTS Engine ids (stored in DataStore — do not change existing values)
object TtsEngineId {
    const val CARTESIA = "cartesia"  // Cartesia Sonic (streaming, low latency)
    const val CHIRP = "chirp"  // Google Cloud Chirp 3 HD (non-streaming)
    const val LOCAL = "local"  // Android's built-in TTS
}

data class AppSettings(
    val openRouterApiKey: String = "",
    val googleCloudApiKey: String = "",
    // Presence gates whether Cartesia TTS is available; the actual short-lived
    // token is owned by CartesiaTokenProvider, not carried here.
    val sessionToken: String = "",
    // DO NOT MODIFY: Default model must match first entry in SettingsScreen.availableLlmModels
    val llmModel: String = "google/gemini-3-flash-preview",
    val ttsEngine: String = TtsEngineId.CARTESIA,
    val ttsVoice: String = "Kore",
    val cartesiaVoice: String = "6f84f4b8-58a2-430c-8c79-688dad597532", // Brooke - Big Sister
    val continuousListening: Boolean = false,  // Auto-restart listening after silence
    val carMode: Boolean = false,              // Use Bluetooth SCO for hands-free audio
    val geminiSearch: Boolean = false,         // Use Gemini with Google Search grounding
    val bargeIn: Boolean = false               // Talk over the assistant to interrupt it (experimental)
)
