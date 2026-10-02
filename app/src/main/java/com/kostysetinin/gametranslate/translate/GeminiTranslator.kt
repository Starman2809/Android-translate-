package com.kostysetinin.gametranslate.translate

import com.kostysetinin.gametranslate.logic.JsonStrings
import com.kostysetinin.gametranslate.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class TranslationException(val httpCode: Int, message: String) : Exception(message)

class GeminiTranslator(
    private val client: OkHttpClient = defaultClient(),
) {
    suspend fun translate(
        lines: List<String>,
        targetLanguageName: String,
        apiKey: String,
        model: String,
    ): List<String> {
        if (lines.isEmpty()) return emptyList()
        return try {
            request(lines, targetLanguageName, apiKey, model)
        } catch (error: TranslationException) {
            if (error.httpCode == 503 || error.httpCode == 429) {
                delay(450)
                request(lines, targetLanguageName, apiKey, model)
            } else {
                throw error
            }
        }
    }

    private suspend fun request(
        lines: List<String>,
        targetLanguageName: String,
        apiKey: String,
        model: String,
    ): List<String> = withContext(Dispatchers.IO) {
        val prompt = buildPrompt(lines, targetLanguageName)
        val generation = JSONObject()
            .put("temperature", 0)
            .put("maxOutputTokens", (lines.size * 96).coerceIn(160, 1024))
            .put("responseMimeType", "application/json")
        if (!model.contains("lite")) {
            generation.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        val body = JSONObject()
            .put(
                "contents",
                JSONArray().put(
                    JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", prompt))),
                ),
            )
            .put("generationConfig", generation)

        val request = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .header("x-goog-api-key", apiKey)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val response = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            throw TranslationException(0, "Нет сети: ${error.message ?: "таймаут"}")
        }
        val payload = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw TranslationException(response.code, messageFor(response.code, payload))
        }
        val text = extractText(payload)
            ?: throw TranslationException(200, "Пустой ответ модели")
        val parsed = JsonStrings.parseArray(text)
            ?: throw TranslationException(200, "Модель вернула не список строк")
        if (parsed.size != lines.size) {
            throw TranslationException(200, "Модель вернула ${parsed.size} строк вместо ${lines.size}")
        }
        parsed
    }

    private fun messageFor(code: Int, payload: String): String {
        val apiMessage = runCatching {
            JSONObject(payload).getJSONObject("error").getString("message")
        }.getOrNull()
        return when (code) {
            400 -> "Запрос отклонён. ${apiMessage ?: "Проверьте модель."}"
            401, 403 -> "Ключ отклонён. Вставьте ключ Gemini из Google AI Studio."
            404 -> "Модель не найдена. Выберите другую в настройках."
            429 -> "Слишком много запросов. Увеличьте интервал."
            503 -> "Модель перегружена. Повторю на следующем кадре."
            else -> apiMessage ?: "Ошибка API ($code)"
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()

        val MODELS = listOf(
            SettingsStore.DEFAULT_MODEL to "Быстрая (Flash Lite)",
            SettingsStore.QUALITY_MODEL to "Точнее (Flash)",
        )

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()

        fun buildPrompt(lines: List<String>, targetLanguageName: String): String {
            val input = JSONArray()
            lines.forEach { input.put(it.take(500)) }
            return """
                You translate on-screen video game text into $targetLanguageName.
                The input is a JSON array of separate UI labels, subtitles, or dialogue lines.
                Return ONLY a JSON array of strings with the same length and the same order.
                Translate each item by itself. Keep numbers, player names, and control keys (E, F, WASD, Esc).
                If an item is already in $targetLanguageName, return it unchanged.
                No markdown and no comments.
                $input
            """.trimIndent()
        }

        fun extractText(payload: String): String? = runCatching {
            val parts = JSONObject(payload)
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
            buildString {
                for (index in 0 until parts.length()) {
                    val part = parts.getJSONObject(index)
                    if (part.has("text")) append(part.getString("text"))
                }
            }.takeIf { it.isNotBlank() }
        }.getOrNull()
    }
}
