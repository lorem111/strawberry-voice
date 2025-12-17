package com.lorem.strawberry.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import com.lorem.strawberry.data.AppSettings
import com.lorem.strawberry.data.TtsEngine
import com.lorem.strawberry.speech.availableCartesiaVoices
import com.lorem.strawberry.speech.availableTtsVoices

// ============================================================================
// DO NOT MODIFY THESE MODEL IDS - They must match OpenRouter's exact model IDs
// DO NOT rename, reorder, or "fix" these model names in future edits
// ============================================================================
val availableLlmModels = listOf(
    "google/gemini-2.5-flash-preview-05-20" to "Gemini 3.0 Flash",
    "google/gemini-2.5-flash" to "Gemini 2.5 Flash",
    "openai/gpt-5-mini" to "GPT-5 Mini",
    "anthropic/claude-haiku-4.5" to "Claude Haiku 4.5",
)

val availableTtsEngines = listOf(
    TtsEngine.CARTESIA to "Cartesia Sonic (Streaming, Fast)",
    TtsEngine.CHIRP to "Chirp 3 HD (Cloud, High Quality)",
    TtsEngine.LOCAL to "Local TTS (Device, Offline)",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    onNavigateBack: () -> Unit,
    onUpdateOpenRouterKey: (String) -> Unit,
    onUpdateCartesiaKey: (String) -> Unit,
    onUpdateLlmModel: (String) -> Unit,
    onUpdateTtsEngine: (String) -> Unit,
    onUpdateTtsVoice: (String) -> Unit,
    onUpdateCartesiaVoice: (String) -> Unit
) {
    var openRouterKey by remember(settings.openRouterApiKey) { mutableStateOf(settings.openRouterApiKey) }
    var cartesiaKey by remember(settings.cartesiaApiKey) { mutableStateOf(settings.cartesiaApiKey) }
    var showOpenRouterKey by remember { mutableStateOf(false) }
    var showCartesiaKey by remember { mutableStateOf(false) }
    var showVoiceDialog by remember { mutableStateOf(false) }
    var showCartesiaVoiceDialog by remember { mutableStateOf(false) }
    var showLlmModelDialog by remember { mutableStateOf(false) }
    var showTtsEngineDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // API Keys Section
            SectionHeader("API Keys")

            ApiKeyField(
                label = "OpenRouter API Key",
                value = openRouterKey,
                onValueChange = { openRouterKey = it },
                onSave = { onUpdateOpenRouterKey(openRouterKey) },
                isVisible = showOpenRouterKey,
                onToggleVisibility = { showOpenRouterKey = !showOpenRouterKey }
            )

            ApiKeyField(
                label = "Cartesia API Key",
                value = cartesiaKey,
                onValueChange = { cartesiaKey = it },
                onSave = { onUpdateCartesiaKey(cartesiaKey) },
                isVisible = showCartesiaKey,
                onToggleVisibility = { showCartesiaKey = !showCartesiaKey }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // LLM Section
            SectionHeader("Language Model")

            val currentLlmModelName = availableLlmModels.find { it.first == settings.llmModel }?.second
                ?: settings.llmModel

            ListItem(
                headlineContent = { Text("Model") },
                supportingContent = { Text(currentLlmModelName) },
                modifier = Modifier.clickable { showLlmModelDialog = true }
            )

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))

            // TTS Section
            SectionHeader("Text-to-Speech")

            val currentTtsEngineName = availableTtsEngines.find { it.first == settings.ttsEngine }?.second
                ?: settings.ttsEngine

            ListItem(
                headlineContent = { Text("TTS Engine") },
                supportingContent = { Text(currentTtsEngineName) },
                modifier = Modifier.clickable { showTtsEngineDialog = true }
            )

            // Show voice selection based on engine
            if (settings.ttsEngine == TtsEngine.CHIRP) {
                val currentVoiceName = availableTtsVoices.find { it.first == settings.ttsVoice }?.second
                    ?: settings.ttsVoice

                ListItem(
                    headlineContent = { Text("Voice") },
                    supportingContent = { Text(currentVoiceName) },
                    modifier = Modifier.clickable { showVoiceDialog = true }
                )
            }

            if (settings.ttsEngine == TtsEngine.CARTESIA) {
                val currentCartesiaVoiceName = availableCartesiaVoices.find { it.first == settings.cartesiaVoice }?.second
                    ?: settings.cartesiaVoice

                ListItem(
                    headlineContent = { Text("Voice") },
                    supportingContent = { Text(currentCartesiaVoiceName) },
                    modifier = Modifier.clickable { showCartesiaVoiceDialog = true }
                )
            }
        }
    }

    // Voice Selection Dialog (Chirp voices)
    if (showVoiceDialog) {
        AlertDialog(
            onDismissRequest = { showVoiceDialog = false },
            title = { Text("Select Voice") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    availableTtsVoices.forEach { (voiceId, voiceName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateTtsVoice(voiceId)
                                    showVoiceDialog = false
                                }
                                .padding(vertical = 12.dp)
                        ) {
                            RadioButton(
                                selected = voiceId == settings.ttsVoice,
                                onClick = {
                                    onUpdateTtsVoice(voiceId)
                                    showVoiceDialog = false
                                }
                            )
                            Text(
                                voiceName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showVoiceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // LLM Model Selection Dialog
    if (showLlmModelDialog) {
        AlertDialog(
            onDismissRequest = { showLlmModelDialog = false },
            title = { Text("Select LLM Model") },
            text = {
                Column {
                    availableLlmModels.forEach { (modelId, modelName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateLlmModel(modelId)
                                    showLlmModelDialog = false
                                }
                                .padding(vertical = 12.dp)
                        ) {
                            RadioButton(
                                selected = modelId == settings.llmModel,
                                onClick = {
                                    onUpdateLlmModel(modelId)
                                    showLlmModelDialog = false
                                }
                            )
                            Text(
                                modelName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLlmModelDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // TTS Engine Selection Dialog
    if (showTtsEngineDialog) {
        AlertDialog(
            onDismissRequest = { showTtsEngineDialog = false },
            title = { Text("Select TTS Engine") },
            text = {
                Column {
                    availableTtsEngines.forEach { (engineId, engineName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateTtsEngine(engineId)
                                    showTtsEngineDialog = false
                                }
                                .padding(vertical = 12.dp)
                        ) {
                            RadioButton(
                                selected = engineId == settings.ttsEngine,
                                onClick = {
                                    onUpdateTtsEngine(engineId)
                                    showTtsEngineDialog = false
                                }
                            )
                            Text(
                                engineName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTtsEngineDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Cartesia Voice Selection Dialog
    if (showCartesiaVoiceDialog) {
        AlertDialog(
            onDismissRequest = { showCartesiaVoiceDialog = false },
            title = { Text("Select Cartesia Voice") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    availableCartesiaVoices.forEach { (voiceId, voiceName) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onUpdateCartesiaVoice(voiceId)
                                    showCartesiaVoiceDialog = false
                                }
                                .padding(vertical = 12.dp)
                        ) {
                            RadioButton(
                                selected = voiceId == settings.cartesiaVoice,
                                onClick = {
                                    onUpdateCartesiaVoice(voiceId)
                                    showCartesiaVoiceDialog = false
                                }
                            )
                            Text(
                                voiceName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showCartesiaVoiceDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
    )
}

@Composable
fun ApiKeyField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    onSave: () -> Unit,
    isVisible: Boolean,
    onToggleVisibility: () -> Unit
) {
    var hasChanges by remember { mutableStateOf(false) }

    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = {
                onValueChange(it)
                hasChanges = true
            },
            label = { Text(label) },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            visualTransformation = if (isVisible) {
                VisualTransformation.None
            } else {
                PasswordVisualTransformation()
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            trailingIcon = {
                IconButton(onClick = onToggleVisibility) {
                    Icon(
                        imageVector = if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isVisible) "Hide" else "Show"
                    )
                }
            }
        )

        if (hasChanges) {
            TextButton(
                onClick = {
                    onSave()
                    hasChanges = false
                },
                modifier = Modifier.padding(top = 4.dp)
            ) {
                Text("Save")
            }
        }
    }
}
