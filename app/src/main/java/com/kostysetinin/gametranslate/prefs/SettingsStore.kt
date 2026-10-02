package com.kostysetinin.gametranslate.prefs

import android.content.Context

enum class OcrScript(val id: String, val label: String) {
    LATIN("latin", "Латиница (английский и европейские)"),
    JAPANESE("japanese", "Японский"),
    CHINESE("chinese", "Китайский"),
    KOREAN("korean", "Корейский"),
    ;

    companion object {
        fun fromId(id: String?): OcrScript = entries.firstOrNull { it.id == id } ?: LATIN
    }
}

enum class TargetLanguage(val id: String, val promptName: String, val label: String) {
    RUSSIAN("ru", "Russian", "Русский"),
    ENGLISH("en", "English", "English"),
    UKRAINIAN("uk", "Ukrainian", "Українська"),
    GERMAN("de", "German", "Deutsch"),
    SPANISH("es", "Spanish", "Español"),
    FRENCH("fr", "French", "Français"),
    CHINESE("zh", "Simplified Chinese", "中文"),
    JAPANESE("ja", "Japanese", "日本語"),
    KOREAN("ko", "Korean", "한국어"),
    ;

    companion object {
        fun fromId(id: String?): TargetLanguage = entries.firstOrNull { it.id == id } ?: RUSSIAN
    }
}

data class CaptureRegion(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    fun isFullScreen(): Boolean {
        return left <= 0.01f && top <= 0.01f && right >= 0.99f && bottom >= 0.99f
    }

    companion object {
        val FULL = CaptureRegion(0f, 0f, 1f, 1f)
    }
}

data class TranslateSettings(
    val apiKey: String,
    val model: String,
    val script: OcrScript,
    val target: TargetLanguage,
    val intervalMs: Long,
    val region: CaptureRegion,
    val showOriginal: Boolean,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): TranslateSettings {
        return TranslateSettings(
            apiKey = prefs.getString(KEY_API, "")?.trim().orEmpty(),
            model = prefs.getString(KEY_MODEL, DEFAULT_MODEL)?.takeIf { it in ALLOWED_MODELS } ?: DEFAULT_MODEL,
            script = OcrScript.fromId(prefs.getString(KEY_SCRIPT, OcrScript.LATIN.id)),
            target = TargetLanguage.fromId(prefs.getString(KEY_TARGET, TargetLanguage.RUSSIAN.id)),
            intervalMs = prefs.getLong(KEY_INTERVAL, 700L).coerceIn(400L, 2000L),
            region = CaptureRegion(
                left = prefs.getFloat(KEY_LEFT, 0f),
                top = prefs.getFloat(KEY_TOP, 0f),
                right = prefs.getFloat(KEY_RIGHT, 1f),
                bottom = prefs.getFloat(KEY_BOTTOM, 1f),
            ),
            showOriginal = prefs.getBoolean(KEY_ORIGINAL, false),
        )
    }

    fun saveApiKey(value: String) = prefs.edit().putString(KEY_API, value.trim()).apply()

    fun saveModel(value: String) = prefs.edit().putString(KEY_MODEL, value).apply()

    fun saveScript(value: OcrScript) = prefs.edit().putString(KEY_SCRIPT, value.id).apply()

    fun saveTarget(value: TargetLanguage) = prefs.edit().putString(KEY_TARGET, value.id).apply()

    fun saveInterval(ms: Long) = prefs.edit().putLong(KEY_INTERVAL, ms).apply()

    fun saveRegion(region: CaptureRegion) {
        prefs.edit()
            .putFloat(KEY_LEFT, region.left)
            .putFloat(KEY_TOP, region.top)
            .putFloat(KEY_RIGHT, region.right)
            .putFloat(KEY_BOTTOM, region.bottom)
            .apply()
    }

    fun saveShowOriginal(value: Boolean) = prefs.edit().putBoolean(KEY_ORIGINAL, value).apply()

    companion object {
        const val DEFAULT_MODEL = "gemini-3.5-flash-lite"
        const val QUALITY_MODEL = "gemini-3.5-flash"
        val ALLOWED_MODELS = setOf(DEFAULT_MODEL, QUALITY_MODEL)

        private const val PREFS = "gametranslate"
        private const val KEY_API = "api_key"
        private const val KEY_MODEL = "model"
        private const val KEY_SCRIPT = "script"
        private const val KEY_TARGET = "target"
        private const val KEY_INTERVAL = "interval_ms"
        private const val KEY_LEFT = "region_left"
        private const val KEY_TOP = "region_top"
        private const val KEY_RIGHT = "region_right"
        private const val KEY_BOTTOM = "region_bottom"
        private const val KEY_ORIGINAL = "show_original"
    }
}
