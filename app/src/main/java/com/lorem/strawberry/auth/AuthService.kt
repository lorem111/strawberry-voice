package com.lorem.strawberry.auth

import com.lorem.strawberry.BuildConfig
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class AuthRequest(
    val idToken: String
)

@Serializable
data class AuthUser(
    val email: String,
    val name: String,
    val picture: String? = null
)

@Serializable
data class AuthResponse(
    val success: Boolean,
    val openRouterKey: String? = null,
    val cartesiaToken: String? = null,      // short-lived (<=1h) Cartesia access token
    val cartesiaExpiresAt: String? = null,  // ISO-8601 expiry of the token above
    val sessionToken: String? = null,       // opaque token for refresh + usage logging
    val googleCloudKey: String? = null,
    val featureFlags: List<String>? = null,
    val user: AuthUser? = null,
    val error: String? = null
)

@Serializable
data class CartesiaTokenResponse(
    val success: Boolean,
    val cartesiaToken: String? = null,
    val cartesiaExpiresAt: String? = null,
    val usage: Int? = null,
    val cap: Int? = null,
    val error: String? = null               // "over_cap" | "blocked" | "no_user" | ...
)

class AuthService {
    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
    }

    suspend fun authenticate(googleIdToken: String): Result<AuthResponse> {
        return try {
            val response = client.post("${BuildConfig.AUTH_SERVER_URL}/api/auth") {
                contentType(ContentType.Application.Json)
                setBody(AuthRequest(idToken = googleIdToken))
            }

            val authResponse: AuthResponse = response.body()

            if (authResponse.success) {
                Result.success(authResponse)
            } else {
                Result.failure(Exception(authResponse.error ?: "Authentication failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Mint a fresh Cartesia access token using the opaque session token.
     * A non-2xx with a parseable body (e.g. over_cap / blocked) is returned as a
     * success-wrapped response with `success=false` so the caller can branch on
     * `error`; only transport failures become Result.failure.
     */
    suspend fun refreshCartesiaToken(sessionToken: String): Result<CartesiaTokenResponse> {
        return try {
            val response = client.post("${BuildConfig.AUTH_SERVER_URL}/api/cartesia-token") {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer $sessionToken")
                // Valid (if empty) JSON body — an empty body labeled as JSON
                // trips Vercel's body parser if the handler ever reads req.body.
                setBody("{}")
            }
            Result.success(response.body())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
