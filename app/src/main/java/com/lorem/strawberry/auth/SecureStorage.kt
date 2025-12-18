package com.lorem.strawberry.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Secure storage for sensitive API keys using EncryptedSharedPreferences.
 * Keys are encrypted using Android Keystore.
 */
class SecureStorage(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val securePrefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "strawberry_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    companion object {
        private const val KEY_OPENROUTER_API_KEY = "openrouter_api_key"
        private const val KEY_CARTESIA_API_KEY = "cartesia_api_key"
        private const val KEY_GOOGLE_CLOUD_API_KEY = "google_cloud_api_key"
        private const val KEY_FEATURE_FLAGS = "feature_flags"
        private const val KEY_USER_EMAIL = "user_email"
        private const val KEY_USER_NAME = "user_name"
        private const val KEY_USER_PICTURE = "user_picture"
        private const val KEY_IS_SIGNED_IN = "is_signed_in"

        // Feature flag constants
        const val FLAG_GEMINI_SEARCH = "gemini-search"
        const val FLAG_CAR_MODE = "car-mode"
    }

    var openRouterApiKey: String?
        get() = securePrefs.getString(KEY_OPENROUTER_API_KEY, null)
        set(value) = securePrefs.edit().putString(KEY_OPENROUTER_API_KEY, value).apply()

    var cartesiaApiKey: String?
        get() = securePrefs.getString(KEY_CARTESIA_API_KEY, null)
        set(value) = securePrefs.edit().putString(KEY_CARTESIA_API_KEY, value).apply()

    var googleCloudApiKey: String?
        get() = securePrefs.getString(KEY_GOOGLE_CLOUD_API_KEY, null)
        set(value) = securePrefs.edit().putString(KEY_GOOGLE_CLOUD_API_KEY, value).apply()

    var featureFlags: Set<String>
        get() = securePrefs.getStringSet(KEY_FEATURE_FLAGS, emptySet()) ?: emptySet()
        set(value) = securePrefs.edit().putStringSet(KEY_FEATURE_FLAGS, value).apply()

    fun hasFeatureFlag(flag: String): Boolean = featureFlags.contains(flag)

    var userEmail: String?
        get() = securePrefs.getString(KEY_USER_EMAIL, null)
        set(value) = securePrefs.edit().putString(KEY_USER_EMAIL, value).apply()

    var userName: String?
        get() = securePrefs.getString(KEY_USER_NAME, null)
        set(value) = securePrefs.edit().putString(KEY_USER_NAME, value).apply()

    var userPicture: String?
        get() = securePrefs.getString(KEY_USER_PICTURE, null)
        set(value) = securePrefs.edit().putString(KEY_USER_PICTURE, value).apply()

    var isSignedIn: Boolean
        get() = securePrefs.getBoolean(KEY_IS_SIGNED_IN, false)
        set(value) = securePrefs.edit().putBoolean(KEY_IS_SIGNED_IN, value).apply()

    fun saveAuthResponse(response: AuthResponse) {
        openRouterApiKey = response.openRouterKey
        cartesiaApiKey = response.cartesiaKey
        googleCloudApiKey = response.googleCloudKey
        featureFlags = response.featureFlags?.toSet() ?: emptySet()
        response.user?.let { user ->
            userEmail = user.email
            userName = user.name
            userPicture = user.picture
        }
        isSignedIn = true
    }

    fun clearAll() {
        securePrefs.edit().clear().apply()
    }

    fun hasValidCredentials(): Boolean {
        return isSignedIn && !openRouterApiKey.isNullOrEmpty()
    }
}
