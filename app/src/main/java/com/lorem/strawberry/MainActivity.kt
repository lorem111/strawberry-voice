package com.lorem.strawberry

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lorem.strawberry.data.AppSettings
import com.lorem.strawberry.data.SettingsDataStore
import com.lorem.strawberry.ui.AssistantViewModel
import com.lorem.strawberry.ui.Message
import com.lorem.strawberry.ui.SettingsScreen
import com.lorem.strawberry.ui.theme.StrawberryTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val autoStartListening = intent?.getBooleanExtra("AUTO_START_LISTENING", false) ?: false

        setContent {
            StrawberryTheme {
                MainNavigation(autoStartListening = autoStartListening)
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Handle new intent when activity is already running (singleTask)
        // Could trigger listening here if needed
    }
}

@Composable
fun MainNavigation(autoStartListening: Boolean = false) {
    val navController = rememberNavController()
    val context = LocalContext.current
    val settingsDataStore = remember { SettingsDataStore(context) }
    val settings by settingsDataStore.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    NavHost(navController = navController, startDestination = "assistant") {
        composable("assistant") {
            AssistantScreen(
                settings = settings,
                onNavigateToSettings = { navController.navigate("settings") },
                autoStartListening = autoStartListening
            )
        }
        composable("settings") {
            SettingsScreen(
                settings = settings,
                onNavigateBack = { navController.popBackStack() },
                onUpdateOpenRouterKey = { scope.launch { settingsDataStore.updateOpenRouterApiKey(it) } },
                onUpdateCartesiaKey = { scope.launch { settingsDataStore.updateCartesiaApiKey(it) } },
                onUpdateLlmModel = { scope.launch { settingsDataStore.updateLlmModel(it) } },
                onUpdateTtsEngine = { scope.launch { settingsDataStore.updateTtsEngine(it) } },
                onUpdateTtsVoice = { scope.launch { settingsDataStore.updateTtsVoice(it) } },
                onUpdateCartesiaVoice = { scope.launch { settingsDataStore.updateCartesiaVoice(it) } }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    settings: AppSettings,
    onNavigateToSettings: () -> Unit,
    autoStartListening: Boolean = false,
    viewModel: AssistantViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Update ViewModel when settings change
    LaunchedEffect(settings) {
        viewModel.updateSettings(settings)
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted
        // Auto-start listening after permission granted if requested
        if (granted && autoStartListening) {
            viewModel.startListening()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Auto-start listening when app opens (if no conversation yet)
    LaunchedEffect(hasPermission) {
        if (hasPermission && !uiState.isListening && uiState.messages.isEmpty()) {
            viewModel.startListening()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Strawberry") },
                actions = {
                    if (uiState.messages.isNotEmpty()) {
                        IconButton(onClick = { viewModel.clearConversation() }) {
                            Icon(Icons.Default.Clear, contentDescription = "Clear")
                        }
                    }
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Messages list
                MessagesList(
                    messages = uiState.messages,
                    modifier = Modifier.weight(1f)
                )

                // Error message
                uiState.error?.let { error ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = error,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }

                // Partial speech indicator
                AnimatedVisibility(visible = uiState.partialSpeech.isNotEmpty()) {
                    Text(
                        text = uiState.partialSpeech,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }

                // Mic button
                MicButton(
                    isListening = uiState.isListening,
                    hasPermission = hasPermission,
                    onStartListening = { viewModel.startListening() },
                    onStopListening = { viewModel.stopListening() },
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(24.dp)
                )
            }

            // TTS Latency indicator (bottom right)
            uiState.ttsLatencyMs?.let { latency ->
                Text(
                    text = "${latency}ms",
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
fun MessagesList(
    messages: List<Message>,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()

    // With reverseLayout, index 0 is at the bottom, so we scroll to 0 to see latest
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    if (messages.isEmpty()) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Tap the mic and ask me anything",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            modifier = modifier,
            state = listState,
            reverseLayout = true,
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.Bottom)
        ) {
            // Reverse the list so newest messages are at index 0 (bottom with reverseLayout)
            items(messages.reversed()) { message ->
                MessageBubble(message = message)
            }
        }
    }
}

@Composable
fun MessageBubble(message: Message) {
    val alignment = if (message.isUser) Alignment.CenterEnd else Alignment.CenterStart
    val backgroundColor = if (message.isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }
    val textColor = if (message.isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSecondaryContainer
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = alignment
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = backgroundColor),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            if (message.isLoading) {
                LoadingDots(
                    modifier = Modifier.padding(16.dp)
                )
            } else {
                Text(
                    text = message.content,
                    modifier = Modifier.padding(12.dp),
                    color = textColor
                )
            }
        }
    }
}

@Composable
fun LoadingDots(modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "loading")

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        repeat(3) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(300),
                    repeatMode = RepeatMode.Reverse,
                    initialStartOffset = StartOffset(index * 100)
                ),
                label = "dot_$index"
            )

            Box(
                modifier = Modifier
                    .size(8.dp)
                    .scale(scale)
                    .background(
                        MaterialTheme.colorScheme.onSecondaryContainer,
                        CircleShape
                    )
            )
        }
    }
}

@Composable
fun MicButton(
    isListening: Boolean,
    hasPermission: Boolean,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onRequestPermission: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        FloatingActionButton(
            onClick = {
                when {
                    !hasPermission -> onRequestPermission()
                    isListening -> onStopListening()
                    else -> onStartListening()
                }
            },
            modifier = Modifier
                .size(72.dp)
                .then(if (isListening) Modifier.scale(scale) else Modifier),
            containerColor = if (isListening) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isListening) "Stop listening" else "Start listening",
                modifier = Modifier.size(32.dp)
            )
        }
    }
}
