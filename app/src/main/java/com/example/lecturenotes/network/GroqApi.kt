package com.example.lecturenotes.network

import okhttp3.MultipartBody
import okhttp3.RequestBody
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

data class TranscriptionResponse(
    val text: String
)

interface GroqApi {

    // Документация: https://console.groq.com/docs/speech-text
    @Multipart
    @POST("openai/v1/audio/transcriptions")
    suspend fun transcribe(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody
    ): TranscriptionResponse
}
