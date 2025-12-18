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
    val cartesiaKey: String? = null,
    val googleCloudKey: String? = null,
    val featureFlags: List<String>? = null,
    val user: AuthUser? = null,
    val error: String? = null
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
}
