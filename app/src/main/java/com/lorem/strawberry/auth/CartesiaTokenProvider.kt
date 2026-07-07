package com.lorem.strawberry.auth

import com.lorem.strawberry.core.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the short-lived Cartesia access token.
 *
 * Cartesia tokens expire (<=1h), so this refreshes them on demand via the auth
 * server's /api/cartesia-token endpoint using the opaque session token.
 *
 * Callers pass the token that just failed (if any) as [invalidToken]:
 *  - null  → normal pre-request fetch; returns a cached token if still fresh.
 *  - non-null → the token 401'd; force a refresh that must return a *different* token.
 *
 * Refreshes are mutex-serialized and coalesced: if several requests fail on the
 * same token, only the first mints a new one; the rest observe the fresh token
 * under the lock and reuse it. A refusal (over cap / blocked) clears the stored
 * token and returns null, which callers treat as "Cartesia unavailable".
 */
@Singleton
class CartesiaTokenProvider @Inject constructor(
    private val secureStorage: SecureStorage,
    private val logger: AppLogger
) {
    private val authService = AuthService()
    private val mutex = Mutex()

    companion object {
        private const val TAG = "CartesiaToken"
        // Refresh this far before actual expiry so a request never races the deadline.
        private const val EXPIRY_BUFFER_MS = 60_000L
    }

    /** Stored token if it is valid for at least [bufferMs] more; else null. */
    private fun tokenValidFor(bufferMs: Long): String? {
        val token = secureStorage.cartesiaToken ?: return null
        val expiresAt = secureStorage.cartesiaExpiresAtMs ?: return null
        return if (System.currentTimeMillis() < expiresAt - bufferMs) token else null
    }

    /**
     * Return a usable Cartesia token, refreshing when needed. Pass the token that
     * just returned 401 as [invalidToken] to force a refresh to a different token.
     * Returns null when none can be obtained (not signed in, over cap, refresh failed).
     */
    suspend fun getToken(invalidToken: String? = null): String? {
        // Fast path only for non-forced fetches.
        if (invalidToken == null) tokenValidFor(EXPIRY_BUFFER_MS)?.let { return it }

        return mutex.withLock {
            // Coalesce: another coroutine may have already refreshed while we waited.
            // Accept the stored token if it's fresh and isn't the one that just failed.
            val fresh = tokenValidFor(EXPIRY_BUFFER_MS)
            if (fresh != null && fresh != invalidToken) return@withLock fresh

            val session = secureStorage.sessionToken
            if (session.isNullOrBlank()) {
                logger.w(TAG, "No session token; cannot obtain Cartesia token")
                return@withLock null
            }

            authService.refreshCartesiaToken(session).fold(
                onSuccess = { resp ->
                    when {
                        resp.success && resp.cartesiaToken != null && isParseableExpiry(resp.cartesiaExpiresAt) -> {
                            secureStorage.setCartesiaCredentials(resp.cartesiaToken, resp.cartesiaExpiresAt)
                            logger.d(TAG, "Refreshed Cartesia token (usage ${resp.usage}/${resp.cap})")
                            resp.cartesiaToken
                        }
                        resp.error == "over_cap" || resp.error == "blocked" -> {
                            logger.w(TAG, "Cartesia refused: ${resp.error} (usage ${resp.usage}/${resp.cap})")
                            secureStorage.setCartesiaCredentials(null, null)
                            null
                        }
                        else -> {
                            // Malformed success (missing/unparseable expiry) or an
                            // unexpected error — don't poison storage, report unavailable.
                            logger.w(TAG, "Cartesia refresh not granted: ${resp.error}")
                            null
                        }
                    }
                },
                onFailure = { ex ->
                    logger.e(TAG, "Cartesia refresh failed", ex)
                    // Network blip: fall back to a still-valid token ONLY for a
                    // proactive fetch. After a 401 the stored token is known bad.
                    if (invalidToken == null) tokenValidFor(0) else null
                }
            )
        }
    }

    private fun isParseableExpiry(expiresAt: String?): Boolean {
        if (expiresAt == null) return false
        return try { java.time.Instant.parse(expiresAt); true } catch (_: Exception) { false }
    }
}
