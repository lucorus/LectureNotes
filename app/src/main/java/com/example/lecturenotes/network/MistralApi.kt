package com.example.lecturenotes.network

import com.google.gson.annotations.SerializedName
import retrofit2.http.Body
import retrofit2.http.Headers
import retrofit2.http.POST

// --- Модели запроса ---
data class MistralRequest(
    val model: String,
    val messages: List<MistralMessage>,
    @SerializedName("max_tokens") val maxTokens: Int,
    val temperature: Double,
    @SerializedName("top_p") val topP: Double
)

data class MistralMessage(
    val role: String,
    val content: String
)

// --- Модели ответа ---
data class MistralResponse(
    val choices: List<MistralChoice>?
)

data class MistralChoice(
    val message: MistralMessage?
)

interface MistralApi {

    // Документация: https://docs.mistral.ai/api/
    @Headers("Content-Type: application/json")
    @POST("v1/chat/completions")
    suspend fun chatCompletion(@Body request: MistralRequest): MistralResponse
}
