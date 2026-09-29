package com.example.lecturenotes.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

// --- Модели запроса ---
data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>,
    @SerializedName("max_tokens") val maxTokens: Int,
    val temperature: Double,
    @SerializedName("top_p") val topP: Double
)

data class OpenRouterMessage(
    val role: String,
    val content: String
)

// --- Модели ответа ---
data class OpenRouterResponse(
    val choices: List<OpenRouterChoice>?,
    val usage: OpenRouterUsage? = null  // Статистика использования токенов
)

data class OpenRouterChoice(
    val message: OpenRouterMessage?,
    @SerializedName("finish_reason") val finishReason: String? = null  // Причина завершения генерации
)

data class OpenRouterUsage(
    @SerializedName("prompt_tokens") val promptTokens: Int? = null,
    @SerializedName("completion_tokens") val completionTokens: Int? = null,
    @SerializedName("total_tokens") val totalTokens: Int? = null
)

interface OpenRouterApi {
    @Headers("Content-Type: application/json")
    @POST("api/v1/chat/completions")
    suspend fun chatCompletion(@Body request: OpenRouterRequest): OpenRouterResponse
}