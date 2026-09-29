package com.example.lecturenotes.network

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object SummaryService {

    private const val TAG = "SummaryService"

    // Модель поддерживает до 32,768 токенов в ответе — используем максимум
    private const val MAX_COMPLETION_TOKENS = 31_000

    // С запасом для контекста модели (обычно 262K токенов ≈ 200K+ символов)
    private const val MAX_CHUNK_SIZE = 180_000

    // Температура 0.2 для максимальной точности
    private const val TEMPERATURE = 0.2

    // Задержка между запросами
    private const val DELAY_BETWEEN_REQUESTS_MS = 15_000L

    // Максимум повторных попыток при rate limit
    private const val MAX_RETRIES = 3
    private const val BASE_RETRY_DELAY_MS = 10_000L

    /**
     * Получает модель из настроек пользователя
     */
    private fun getModel(context: Context): String {
        return ApiKeyStore.getOpenRouterModel(context)
    }

    suspend fun createSummary(context: Context, text: String): String {
        return withContext(Dispatchers.IO) {
            if (text.length <= MAX_CHUNK_SIZE) {
                Log.d(TAG, "Текст ${text.length} символов — обработка одним запросом")
                summarizeSingle(context, text)
            } else {
                Log.d(TAG, "Текст ${text.length} символов — разбивка на части")
                val chunks = splitIntoChunks(text)
                Log.d(TAG, "Разбито на ${chunks.size} частей")
                summarizeLongText(context, chunks)
            }
        }
    }

    private suspend fun summarizeSingle(context: Context, text: String): String {
        val api = NetworkModule.openRouterApi(context)
        val model = getModel(context)

        Log.d(TAG, "Используется модель: $model")

        val prompt = """
            Создай структурированный конспект лекции на русском языке.
            
            ВАЖНЫЕ ПРАВИЛА:
            1. НИЧЕГО НЕ ДОДУМЫВАЙ — только то, что сказано в тексте
            2. Сохраняй ключевые моменты, даже если конспект будет длинным
            3. Используй маркдаун разметку:
               - # для названия темы лекции
               - ## для основных разделов
               - ### для подразделов
               - **жирный** для терминов и определений
               - *курсив* для примеров и пояснений
               - - для списков
               - > для цитат лектора (дословных фраз)
            4. Сохраняй логику повествования лектора
            5. Включай все даты, имена, формулы, цифры
            6. Если лектор приводит примеры — ОБЯЗАТЕЛЬНО запиши их
            7. Делай конспект подробным, НЕ сокращай содержание
            
            Текст лекции:
            
            $text
        """.trimIndent()

        return callOpenRouter(api, prompt, context)
    }

    private suspend fun summarizeLongText(context: Context, chunks: List<String>): String {
        val api = NetworkModule.openRouterApi(context)
        val partSummaries = mutableListOf<String>()

        for ((index, chunk) in chunks.withIndex()) {
            Log.d(TAG, "Обработка части ${index + 1}/${chunks.size} (${chunk.length} символов)")

            val prompt = """
                Создай ПОДРОБНЫЙ конспект этой части лекции.
                
                ПРАВИЛА:
                - Не додумывай, только то что в тексте
                - Сохраняй ВСЕ ключевые моменты, термины, примеры
                - Используй маркдаун: ## для разделов, **жирный** для терминов
                - > для дословных цитат
                - Включай все цифры, даты, имена
                
                Это часть ${index + 1} из ${chunks.size}.
                
                $chunk
            """.trimIndent()

            try {
                val summary = callOpenRouter(api, prompt, context)
                partSummaries.add("## Часть ${index + 1}\n\n$summary")
                Log.d(TAG, "Часть ${index + 1} обработана успешно")
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка части ${index + 1}: ${e.message}")
                partSummaries.add("## Часть ${index + 1}\n\n*Ошибка обработки*\n\n${chunk.take(500)}...")
            }

            if (index < chunks.size - 1) delay(DELAY_BETWEEN_REQUESTS_MS)
        }

        val combined = partSummaries.joinToString("\n\n---\n\n")

        return if (combined.length > MAX_CHUNK_SIZE / 3) {
            Log.d(TAG, "Создание итогового конспекта")

            val finalPrompt = """
                Ниже — конспекты частей ОДНОЙ лекции. Объедини их в ЕДИНЫЙ, связный конспект.
                
                ПРАВИЛА:
                - Сохрани ВСЁ важное из каждой части
                - Убери повторения, но не теряй содержание
                - Выстрой единую логическую структуру
                - Используй: # для темы, ## для разделов, ### для подразделов
                - **жирный** для терминов, > для цитат
                - Конспект должен быть ПОДРОБНЫМ
                
                $combined
            """.trimIndent()

            callOpenRouter(api, finalPrompt, context)
        } else {
            combined
        }
    }

    private suspend fun callOpenRouter(
        api: OpenRouterApi,
        userMessage: String,
        context: Context,
        retryCount: Int = 0
    ): String {
        val model = getModel(context)

        val request = OpenRouterRequest(
            model = model,
            messages = listOf(
                OpenRouterMessage(
                    role = "system",
                    content = "Ты — профессиональный составитель учебных конспектов. Твоя задача — создать подробный, точный конспект лекции. НЕ додумывай, НЕ сокращай важное. Используй маркдаун для структурирования."
                ),
                OpenRouterMessage(
                    role = "user",
                    content = userMessage
                )
            ),
            maxTokens = MAX_COMPLETION_TOKENS,
            temperature = TEMPERATURE,
            topP = 1.0
        )

        Log.d(TAG, "Запрос к модели $model (попытка ${retryCount + 1})")

        return try {
            val response = api.chatCompletion(request)

            val content = response.choices?.firstOrNull()?.message?.content

            if (content.isNullOrBlank()) {
                throw Exception("Пустой ответ от API")
            }

            val finishReason = response.choices?.firstOrNull()?.finishReason
            val usageTokens = response.usage?.completionTokens
            Log.d(TAG, "Ответ: ${content.length} символов, finish=$finishReason, tokens=$usageTokens")

            if (finishReason == "length") {
                Log.w(TAG, "⚠️ Ответ обрезан! Модель использовала все доступные токены")
            }

            content.trim()

        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            val statusCode = e.code()

            Log.e(TAG, "HTTP $statusCode: $errorBody")

            when (statusCode) {
                429 -> {
                    if (retryCount < MAX_RETRIES) {
                        val waitTime = BASE_RETRY_DELAY_MS * (1 shl retryCount)
                        Log.w(TAG, "Rate limit. Ожидание ${waitTime/1000}с")
                        delay(waitTime)
                        callOpenRouter(api, userMessage, context, retryCount + 1)
                    } else {
                        throw Exception("Превышен лимит запросов. Подождите несколько минут.")
                    }
                }
                402 -> throw Exception("Недостаточно кредитов на OpenRouter")
                401 -> throw Exception("Неверный API ключ")
                else -> throw Exception("Ошибка API ($statusCode): ${errorBody ?: "неизвестно"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ошибка", e)
            throw e
        }
    }

    private fun splitIntoChunks(text: String): List<String> {
        if (text.length <= MAX_CHUNK_SIZE) return listOf(text)

        val chunks = mutableListOf<String>()
        var start = 0

        while (start < text.length) {
            var end = minOf(start + MAX_CHUNK_SIZE, text.length)

            if (end < text.length) {
                val breakPoints = listOf(
                    text.lastIndexOf("\n\n", end),
                    text.lastIndexOf(". ", end),
                    text.lastIndexOf("? ", end),
                    text.lastIndexOf("! ", end)
                )
                val bestBreak = breakPoints.maxOrNull() ?: -1

                if (bestBreak > start + MAX_CHUNK_SIZE / 2) {
                    end = bestBreak + 1
                }
            }

            chunks.add(text.substring(start, end).trim())
            start = end
        }

        return chunks
    }
}