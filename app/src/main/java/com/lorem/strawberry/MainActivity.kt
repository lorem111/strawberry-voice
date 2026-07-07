package com.lorem.strawberry

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lorem.strawberry.auth.AuthService
import com.lorem.strawberry.auth.GoogleSignInManager
import com.lorem.strawberry.auth.SecureStorage
import com.lorem.strawberry.auth.SignInScreen
import com.lorem.strawberry.conversation.AssistantScreen
import com.lorem.strawberry.settings.AppSettings
import com.lorem.strawberry.settings.DebugLogScreen
import com.lorem.strawberry.settings.SettingsDataStore
import com.lorem.strawberry.settings.SettingsScreen
import com.lorem.strawberry.telemetry.UsageLogger
import com.lorem.strawberry.ui.theme.StrawberryTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var settingsDataStore: SettingsDataStore

    @Inject
    lateinit var secureStorage: SecureStorage

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val autoStartListening = intent?.getBooleanExtra("AUTO_START_LISTENING", false) ?: false

        setContent {
            StrawberryTheme {
                MainNavigation(
                    settingsDataStore = settingsDataStore,
                    secureStorage = secureStorage,
                    autoStartListening = autoStartListening
                )
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
fun MainNavigation(
    settingsDataStore: SettingsDataStore,
    secureStorage: SecureStorage,
    autoStartListening: Boolean = false
) {
    val navController = rememberNavController()
    val settings by settingsDataStore.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // Determine start destination based on auth state
    val startDestination = if (secureStorage.hasValidCredentials()) "assistant" else "signin"

    // Wire usage logging and, for users who signed in before session tokens
    // existed, silently obtain one (plus a fresh Cartesia token) so TTS keeps working.
    LaunchedEffect(Unit) {
        UsageLogger.setUserEmail(secureStorage.userEmail)
        UsageLogger.setSessionToken(secureStorage.sessionToken)

        if (secureStorage.hasValidCredentials() && secureStorage.sessionToken.isNullOrBlank()) {
            GoogleSignInManager(context).silentSignIn().fold(
                onSuccess = { idToken ->
                    AuthService().authenticate(idToken).fold(
                        onSuccess = { resp ->
                            secureStorage.saveAuthResponse(resp)
                            UsageLogger.setUserEmail(resp.user?.email)
                            UsageLogger.setSessionToken(secureStorage.sessionToken)
                        },
                        onFailure = { /* Keep working for OpenRouter; Cartesia needs a manual re-sign-in. */ }
                    )
                },
                onFailure = { /* Silent re-auth unavailable; user can re-sign-in to restore Cartesia. */ }
            )
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("signin") {
            SignInScreen(
                onSignInComplete = {
                    navController.navigate("assistant") {
                        popUpTo("signin") { inclusive = true }
                    }
                }
            )
        }
        composable("assistant") {
            AssistantScreen(
                onNavigateToSettings = { navController.navigate("settings") },
                autoStartListening = autoStartListening
            )
        }
        composable("settings") {
            SettingsScreen(
                settings = settings,
                secureStorage = secureStorage,
                onNavigateBack = { navController.popBackStack() },
                onUpdateLlmModel = { scope.launch { settingsDataStore.updateLlmModel(it) } },
                onUpdateTtsEngine = { scope.launch { settingsDataStore.updateTtsEngine(it) } },
                onUpdateTtsVoice = { scope.launch { settingsDataStore.updateTtsVoice(it) } },
                onUpdateCartesiaVoice = { scope.launch { settingsDataStore.updateCartesiaVoice(it) } },
                onUpdateContinuousListening = { scope.launch { settingsDataStore.updateContinuousListening(it) } },
                onUpdateCarMode = { scope.launch { settingsDataStore.updateCarMode(it) } },
                onUpdateGeminiSearch = { scope.launch { settingsDataStore.updateGeminiSearch(it) } },
                onUpdateBargeIn = { scope.launch { settingsDataStore.updateBargeIn(it) } },
                onNavigateToDebugLog = { navController.navigate("debuglog") },
                onSignOut = {
                    secureStorage.clearAll()
                    UsageLogger.setUserEmail(null)
                    UsageLogger.setSessionToken(null)
                    navController.navigate("signin") {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
        composable("debuglog") {
            DebugLogScreen(
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
