package com.example.lecturenotes.network

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object NetworkModule {

    // Загрузка и обработка аудио может занимать время, поэтому таймауты увеличены
    private fun okHttpClient(
        context: Context,
        authKeyProvider: ((Context) -> String)?,
        extraHeaders: Map<String, String> = emptyMap()
    ): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val builder = OkHttpClient.Builder()
            .connectTimeout(60, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(180, TimeUnit.SECONDS)
            .addInterceptor(logging)

        builder.addInterceptor { chain ->
            var request = chain.request().newBuilder()
            if (authKeyProvider != null) {
                request.addHeader("Authorization", "Bearer ${authKeyProvider(context)}")
            }
            extraHeaders.forEach { (key, value) -> request.addHeader(key, value) }
            chain.proceed(request.build())
        }
        return builder.build()
    }

    fun groqApi(context: Context): GroqApi {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://api.groq.com/")
            .client(okHttpClient(
                context,
                { ctx -> ApiKeyStore.getGroqKey(ctx) }
            ))
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(GroqApi::class.java)
    }

    fun openRouterApi(context: Context): OpenRouterApi {
        val retrofit = Retrofit.Builder()
            .baseUrl("https://openrouter.ai/")
            .client(
                okHttpClient(
                    context,
                    authKeyProvider = { ctx -> ApiKeyStore.getOpenRouterKey(ctx) },
                    extraHeaders = mapOf(
                        // Необязательные заголовки, которые рекомендует OpenRouter (не обязательны для работы)
                        "HTTP-Referer" to "https://github.com/lecture-notes-app",
                        "X-Title" to "LectureNotes"
                    )
                )
            )
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        return retrofit.create(OpenRouterApi::class.java)
    }
}
