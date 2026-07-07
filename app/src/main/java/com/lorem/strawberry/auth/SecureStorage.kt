package com.lorem.strawberry.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Secure storage for sensitive API keys using EncryptedSharedPreferences.
 * Keys are encrypted using Android Keystore.
 */
@Singleton
class SecureStorage @Inject constructor(@ApplicationContext context: Context) {

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
        private const val KEY_CARTESIA_TOKEN = "cartesia_token"
        private const val KEY_CARTESIA_EXPIRES_AT = "cartesia_expires_at"
        private const val KEY_SESSION_TOKEN = "session_token"
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

    /**
     * Emits whenever any stored value changes (and once on collection start), so
     * dependents (EngineRegistry) can re-read keys after sign-in/sign-out.
     */
    val changes: Flow<Unit> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        securePrefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(Unit)
        awaitClose { securePrefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }

    var openRouterApiKey: String?
        get() = securePrefs.getString(KEY_OPENROUTER_API_KEY, null)
        set(value) = securePrefs.edit().putString(KEY_OPENROUTER_API_KEY, value).apply()

    /** Short-lived Cartesia access token (minted server-side; refreshed via session token). */
    var cartesiaToken: String?
        get() = securePrefs.getString(KEY_CARTESIA_TOKEN, null)
        set(value) = securePrefs.edit().putString(KEY_CARTESIA_TOKEN, value).apply()

    /** ISO-8601 expiry of [cartesiaToken], as returned by the server. */
    var cartesiaExpiresAt: String?
        get() = securePrefs.getString(KEY_CARTESIA_EXPIRES_AT, null)
        set(value) = securePrefs.edit().putString(KEY_CARTESIA_EXPIRES_AT, value).apply()

    /** [cartesiaExpiresAt] parsed to epoch millis, or null if unset/unparseable. */
    val cartesiaExpiresAtMs: Long?
        get() = cartesiaExpiresAt?.let {
            try { java.time.Instant.parse(it).toEpochMilli() } catch (_: Exception) { null }
        }

    /** Write the Cartesia token and its expiry in a single commit so a reader never
     * observes a new token paired with an old expiry (or vice versa). */
    fun setCartesiaCredentials(token: String?, expiresAt: String?) {
        securePrefs.edit()
            .putString(KEY_CARTESIA_TOKEN, token)
            .putString(KEY_CARTESIA_EXPIRES_AT, expiresAt)
            .apply()
    }

    /** Opaque session token used to refresh Cartesia tokens and authenticate usage logs. */
    var sessionToken: String?
        get() = securePrefs.getString(KEY_SESSION_TOKEN, null)
        set(value) = securePrefs.edit().putString(KEY_SESSION_TOKEN, value).apply()

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
        // sessionToken may be absent on a failed Cartesia mint; only overwrite when present
        // so a partial re-auth never wipes an existing valid session.
        response.sessionToken?.let { sessionToken = it }
        setCartesiaCredentials(response.cartesiaToken, response.cartesiaExpiresAt)
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
