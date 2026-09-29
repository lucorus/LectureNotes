package com.example.lecturenotes.network

import android.content.Context

object ApiKeyStore {
    private const val PREFS_NAME = "api_keys"

    private const val KEY_OPENROUTER_MODEL = "openrouter_model"

    // Модель по умолчанию
    private const val DEFAULT_MODEL = "google/gemma-4-31b-it:free"

    fun getGroqKey(context: Context): String {
        // Если пользователь сохранил ключ вручную — берём его, иначе используем ключ из Secrets.kt
        return prefs(context).getString("groq_key", null)
            ?: Secrets.GROQ_API_KEY
    }

    fun setGroqKey(context: Context, value: String) {
        prefs(context).edit().putString("groq_key", value).apply()
    }

    fun getOpenRouterKey(context: Context): String {
        return prefs(context).getString("openrouter_key", null)
            ?: Secrets.OPENROUTER_API_KEY
    }

    fun setOpenRouterKey(context: Context, value: String) {
        prefs(context).edit().putString("openrouter_key", value).apply()
    }

    fun getOpenRouterModel(context: Context): String {
        return prefs(context).getString(KEY_OPENROUTER_MODEL, DEFAULT_MODEL) ?: DEFAULT_MODEL
    }

    fun setOpenRouterModel(context: Context, value: String) {
        prefs(context).edit().putString(KEY_OPENROUTER_MODEL, value).apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}