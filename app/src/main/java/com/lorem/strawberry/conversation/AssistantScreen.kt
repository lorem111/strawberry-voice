package com.lorem.strawberry.conversation

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.rememberAsyncImagePainter
import com.lorem.strawberry.core.ChatSummary
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AssistantScreen(
    onNavigateToSettings: () -> Unit,
    autoStartListening: Boolean = false,
    viewModel: AssistantViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val chats by viewModel.chats.collectAsState()
    val activeChatId by viewModel.activeChatId.collectAsState()
    val pendingImagePath by viewModel.pendingImagePath.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val context = LocalContext.current
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()

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

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ChatDrawer(
                chats = chats,
                activeChatId = activeChatId,
                onNewChat = {
                    viewModel.newChat()
                    scope.launch { drawerState.close() }
                },
                onSelectChat = {
                    viewModel.selectChat(it)
                    scope.launch { drawerState.close() }
                },
                onDeleteChat = { viewModel.deleteChat(it) }
            )
        }
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Strawberry") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Chats")
                        }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.newChat() }) {
                            Icon(Icons.Default.Edit, contentDescription = "New chat")
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
                    // Offline banner
                    AnimatedVisibility(visible = !isOnline) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "You're offline — responses won't work until you reconnect",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                            )
                        }
                    }

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

                    InputBar(
                        isListening = uiState.isListening,
                        hasPermission = hasPermission,
                        pendingImagePath = pendingImagePath,
                        onSendMessage = { viewModel.sendMessage(it) },
                        onAttachImage = { viewModel.attachImage(it) },
                        onRemoveImage = { viewModel.removePendingImage() },
                        onStartListening = { viewModel.startListening() },
                        onStopListening = { viewModel.stopListening() },
                        onRequestPermission = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
                    )
                }

                // TTS Latency indicator (above input bar, right)
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
}

@Composable
private fun ChatDrawer(
    chats: List<ChatSummary>,
    activeChatId: Long?,
    onNewChat: () -> Unit,
    onSelectChat: (Long) -> Unit,
    onDeleteChat: (Long) -> Unit
) {
    var chatPendingDelete by remember { mutableStateOf<ChatSummary?>(null) }

    ModalDrawerSheet {
        Text(
            text = "Strawberry",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
        )

        NavigationDrawerItem(
            label = { Text("New chat") },
            icon = { Icon(Icons.Default.Add, contentDescription = null) },
            selected = false,
            onClick = onNewChat,
            modifier = Modifier.padding(horizontal = 12.dp)
        )

        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

        if (chats.isEmpty()) {
            Text(
                text = "No chats yet",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(chats, key = { it.id }) { chat ->
                    NavigationDrawerItem(
                        label = {
                            Text(
                                text = chat.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        selected = chat.id == activeChatId,
                        onClick = { onSelectChat(chat.id) },
                        badge = {
                            IconButton(onClick = { chatPendingDelete = chat }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "Delete chat",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }
    }

    chatPendingDelete?.let { chat ->
        AlertDialog(
            onDismissRequest = { chatPendingDelete = null },
            title = { Text("Delete chat") },
            text = { Text("Delete \"${chat.title}\"? This can't be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteChat(chat.id)
                        chatPendingDelete = null
                    }
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { chatPendingDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun InputBar(
    isListening: Boolean,
    hasPermission: Boolean,
    pendingImagePath: String?,
    onSendMessage: (String) -> Unit,
    onAttachImage: (android.net.Uri) -> Unit,
    onRemoveImage: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onRequestPermission: () -> Unit
) {
    var text by remember { mutableStateOf("") }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        uri?.let(onAttachImage)
    }

    Surface(tonalElevation = 2.dp) {
        Column {
            // Attached image preview
            pendingImagePath?.let { path ->
                Box(modifier = Modifier.padding(start = 16.dp, top = 8.dp)) {
                    Image(
                        painter = rememberAsyncImagePainter(File(path)),
                        contentDescription = "Attached image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                    IconButton(
                        onClick = onRemoveImage,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .size(24.dp)
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.6f), CircleShape)
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = "Remove image",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                IconButton(
                    onClick = {
                        imagePicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                        )
                    },
                    modifier = Modifier.padding(bottom = 4.dp)
                ) {
                    Icon(
                        Icons.Default.Image,
                        contentDescription = "Attach image",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message") },
                    maxLines = 4,
                    shape = RoundedCornerShape(24.dp)
                )

                val canSend = text.isNotBlank() || pendingImagePath != null
                if (canSend) {
                    IconButton(
                        onClick = {
                            onSendMessage(text)
                            text = ""
                        },
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                } else {
                    MicIconButton(
                        isListening = isListening,
                        hasPermission = hasPermission,
                        onStartListening = onStartListening,
                        onStopListening = onStopListening,
                        onRequestPermission = onRequestPermission,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun MicIconButton(
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
        targetValue = 1.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    FilledIconButton(
        onClick = {
            when {
                !hasPermission -> onRequestPermission()
                isListening -> onStopListening()
                else -> onStartListening()
            }
        },
        modifier = modifier.then(if (isListening) Modifier.scale(scale) else Modifier),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = if (isListening) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            }
        )
    ) {
        Icon(
            imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
            contentDescription = if (isListening) "Stop listening" else "Start listening"
        )
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
                text = "Speak or type to start a chat",
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
                Column {
                    message.imagePath?.let { path ->
                        Image(
                            painter = rememberAsyncImagePainter(File(path)),
                            contentDescription = "Attached image",
                            contentScale = ContentScale.FillWidth,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 240.dp)
                                .padding(4.dp)
                                .clip(RoundedCornerShape(12.dp))
                        )
                    }
                    if (message.content.isNotBlank()) {
                        Text(
                            text = message.content,
                            modifier = Modifier.padding(12.dp),
                            color = textColor
                        )
                    }
                }
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
