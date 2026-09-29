package com.example.lecturenotes.network

import android.content.Context
import android.util.Log
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import kotlin.math.roundToInt

object LectureAiHelper {

    private const val SUMMARY_MODEL = "google/gemma-4-26b-a4b-it:free"

    private val SYSTEM_PROMPT = """
        Ты — профессиональный ассистент для создания учебных конспектов на русском языке. Твоя задача — обработать предоставленный текст лекции и превратить его в подробный, структурированный, готовый к экзамену документ.

        ## ЖЁСТКИЕ ПРАВИЛА ЯЗЫКА:
        - Твой единственный язык ответа — РУССКИЙ. Запрещено переходить на английский, даже если в тексте лекции встречаются иностранные термины.
        - Запрещено использовать транслит (не "primer", а "пример").
        - Пиши грамотно, соблюдай правила орфографии и пунктуации русского языка.
        - Иностранные термины оставляй на языке оригинала, но сразу давай перевод или объяснение на русском в скобках.

        ## ПРАВИЛА СОДЕРЖАНИЯ:
        - Твой конспект должен быть максимально подробным. Не сокращай информацию искусственно. Лучше написать больше, чем упустить деталь.
        - Объём конспекта должен быть пропорционален объёму исходного текста. Если лекция длинная, конспект тоже должен быть длинным.
        - Сохраняй все определения, даты, формулы, имена и примеры, которые приводил лектор.
        - Если лектор акцентирует внимание ("это важно", "спрошу на экзамене", "запомните"), обязательно отметь это.
        - Перерабатывай текст: группируй мысли, убирай словесный мусор и повторы, но сохраняй полноту смысла.

        ## ПРАВИЛА ОФОРМЛЕНИЯ (MARKDOWN):
        - Используй Markdown для структурирования текста.
        - Основные разделы выделяй заголовками второго уровня (##).
        - Подразделы — заголовками третьего уровня (###).
        - Важные термины и понятия выделяй **жирным шрифтом**.
        - Особо критичные моменты выделяй жирным шрифтом и добавляй эмодзи ⚠️ или пометку **[!ВАЖНО]**.
        - Для перечислений используй маркированные (-) или нумерованные (1.) списки.
        - Цитаты преподавателя или ключевые определения оформляй как цитаты (>).
        - Код, формулы или специальные обозначения оформляй как `встроенный код` или отдельными блоками.

        ## В КОНЦЕ КОНСПЕКТА ОБЯЗАТЕЛЬНО ДОБАВЬ БЛОК:
        ### 📝 Саммари для повторения
        (Краткий пересказ 3-5 ключевых мыслей лекции)

        ### ❓ Вопросы для самопроверки
        1. (Вопрос 1)
        2. (Вопрос 2)
        3. (Вопрос 3)

        Следуй этим инструкциям неукоснительно. Начинай обрабатывать следующий текст лекции.
    """.trimIndent()

    /**
     * Отправляет аудиофайл в Groq (Whisper) и возвращает распознанный текст.
     * language = "ru" — явно указываем русский язык для лучшего качества распознавания.
     */
    suspend fun transcribeAudio(context: Context, audioFile: File): String {
        val api = NetworkModule.groqApi(context)

        val requestFile = audioFile.asRequestBody("audio/*".toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)
        val modelPart = "whisper-large-v3-turbo".toRequestBody("text/plain".toMediaTypeOrNull())
        val languagePart = "ru".toRequestBody("text/plain".toMediaTypeOrNull())

        try {
            val response = api.transcribe(filePart, modelPart, languagePart)
            return response.text
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            val fullMessage = "Groq API [${e.code()}] (файл ${audioFile.name}, ${audioFile.length() / 1024} КБ): ${errorBody ?: e.message()}"

            // Сохраняем в файл
            val logFile = File(context.getExternalFilesDir(null), "transcription_errors.log")
            logFile.appendText("${System.currentTimeMillis()}: $fullMessage\n\n")

            // Логируем в стандартный лог
            Log.e("TranscriptionError", fullMessage)

            // Показываем путь к файлу в сообщении
            val shortMessage = "Ошибка Groq API [${e.code()}]. Детали в файле: ${logFile.absolutePath}"
            throw Exception(shortMessage)
        }
    }

    /**
     * Рассчитывает лимит токенов на ответ в зависимости от объёма исходного текста лекции,
     * чтобы длинные лекции получали более длинный (и не обрезанный) конспект.
     */
    fun calculateMaxTokens(text: String): Int {
        val wordCount = text.trim().split(Regex("\\s+")).count { it.isNotEmpty() }
        val targetSummaryWords = wordCount * 0.35
        // Коэффициент 2.5 токена на слово — эмпирически подходит для русского языка
        val targetTokens = (targetSummaryWords * 2.5).roundToInt()

        return targetTokens.coerceIn(1000, 8000)
    }

    /**
     * Отправляет текст лекции в OpenRouter (модель Gemma) и возвращает структурированный конспект в Markdown.
     */
    suspend fun summarizeText(context: Context, lectureText: String): String {
        val api = NetworkModule.openRouterApi(context)

        val request = OpenRouterRequest(
            model = SUMMARY_MODEL,
            messages = listOf(
                OpenRouterMessage(role = "system", content = SYSTEM_PROMPT),
                OpenRouterMessage(role = "user", content = "Вот текст лекции, сделай конспект:\n\n$lectureText")
            ),
            maxTokens = calculateMaxTokens(lectureText),
            temperature = 0.2,
            topP = 0.9
        )

        try {
            val response = api.chatCompletion(request)
            return response.choices?.firstOrNull()?.message?.content ?: ""
        } catch (e: retrofit2.HttpException) {
            val errorBody = e.response()?.errorBody()?.string()
            throw Exception("OpenRouter API [${e.code()}]: ${errorBody ?: e.message()}")
        }
    }
}
