package com.lorem.strawberry.core

/**
 * One turn of conversation as sent to an LLM. [imagePath] points to a local file
 * (already imported/compressed by ImageStore); each LlmClient encodes it for its
 * own wire format via [ImageEncoder].
 */
data class ChatTurn(
    val role: String,          // "user" or "assistant"
    val text: String,
    val imagePath: String? = null
)

data class EncodedImage(
    val mimeType: String,
    val base64: String
)

fun interface ImageEncoder {
    /** Returns null if the file can't be read. */
    fun encode(path: String): EncodedImage?
}
