package com.lorem.strawberry.ui

import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lorem.strawberry.auth.AuthService
import com.lorem.strawberry.auth.GoogleSignInManager
import com.lorem.strawberry.auth.SecureStorage
import kotlinx.coroutines.launch

private const val TAG = "SignInScreen"

@Composable
fun SignInScreen(
    onSignInComplete: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val googleSignInManager = remember { GoogleSignInManager(context) }
    val authService = remember { AuthService() }
    val secureStorage = remember { SecureStorage(context) }

    var isLoading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        Log.d(TAG, "Sign-in result received: ${result.resultCode}")

        val googleResult = googleSignInManager.handleSignInResult(result.data)

        googleResult.fold(
            onSuccess = { idToken ->
                Log.d(TAG, "Got ID token, calling auth server...")
                scope.launch {
                    try {
                        val authResult = authService.authenticate(idToken)
                        authResult.fold(
                            onSuccess = { response ->
                                Log.d(TAG, "Auth successful, saving credentials...")
                                secureStorage.saveAuthResponse(response)
                                isLoading = false
                                onSignInComplete()
                            },
                            onFailure = { e ->
                                Log.e(TAG, "Auth server error: ${e.message}", e)
                                error = "Auth server error: ${e.message}"
                                isLoading = false
                            }
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Auth request failed: ${e.message}", e)
                        error = "Auth request failed: ${e.message}"
                        isLoading = false
                    }
                }
            },
            onFailure = { e ->
                Log.e(TAG, "Google sign-in failed: ${e.message}", e)
                error = "Google sign-in failed: ${e.message}"
                isLoading = false
            }
        )
    }

    fun performSignIn() {
        Log.d(TAG, "Starting sign-in...")
        isLoading = true
        error = null

        val signInIntent = googleSignInManager.getSignInIntent()
        signInLauncher.launch(signInIntent)
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = "Strawberry",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Your AI Voice Assistant",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(48.dp))

            if (isLoading) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Signing in...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Button(
                    onClick = { performSignIn() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text("Sign in with Google")
                }

                error?.let { errorMessage ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = errorMessage,
                            modifier = Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = "Sign in to get your personalized API keys",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
