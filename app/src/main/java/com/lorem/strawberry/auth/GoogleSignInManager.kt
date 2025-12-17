package com.lorem.strawberry.auth

import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInClient
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.lorem.strawberry.BuildConfig

private const val TAG = "GoogleSignInManager"

class GoogleSignInManager(context: Context) {

    private val googleSignInClient: GoogleSignInClient

    init {
        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .requestEmail()
            .build()

        googleSignInClient = GoogleSignIn.getClient(context, gso)
        Log.d(TAG, "GoogleSignInManager initialized with client ID: ${BuildConfig.GOOGLE_WEB_CLIENT_ID.take(20)}...")
    }

    fun getSignInIntent(): Intent {
        Log.d(TAG, "Getting sign-in intent...")
        return googleSignInClient.signInIntent
    }

    fun handleSignInResult(data: Intent?): Result<String> {
        Log.d(TAG, "Handling sign-in result...")
        return try {
            val task = GoogleSignIn.getSignedInAccountFromIntent(data)
            val account = task.getResult(ApiException::class.java)
            val idToken = account?.idToken

            if (idToken != null) {
                Log.d(TAG, "Got ID token: ${idToken.take(20)}...")
                Result.success(idToken)
            } else {
                Log.e(TAG, "ID token is null")
                Result.failure(Exception("Failed to get ID token"))
            }
        } catch (e: ApiException) {
            Log.e(TAG, "Sign-in failed with code ${e.statusCode}: ${e.message}", e)
            Result.failure(Exception("Sign-in failed: ${e.statusCode} - ${e.message}"))
        }
    }

    fun signOut() {
        googleSignInClient.signOut()
        googleSignInClient.revokeAccess()
    }
}
