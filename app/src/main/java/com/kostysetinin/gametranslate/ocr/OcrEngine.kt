package com.kostysetinin.gametranslate.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.kostysetinin.gametranslate.logic.TextBox
import com.kostysetinin.gametranslate.logic.TextLayout
import com.kostysetinin.gametranslate.prefs.OcrScript
import kotlinx.coroutines.tasks.await

class OcrEngine {
    private var script: OcrScript? = null
    private var recognizer: TextRecognizer? = null

    suspend fun read(
        bitmap: Bitmap,
        script: OcrScript,
        cropLeft: Float,
        cropTop: Float,
        scale: Float,
    ): List<TextBox> {
        val client = clientFor(script)
        val result = client.process(InputImage.fromBitmap(bitmap, 0)).await()
        val lines = mutableListOf<TextBox>()
        for (block in result.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                val mapped = TextLayout.mapToScreen(
                    left = box.left.toFloat(),
                    top = box.top.toFloat(),
                    right = box.right.toFloat(),
                    bottom = box.bottom.toFloat(),
                    cropLeft = cropLeft,
                    cropTop = cropTop,
                    scale = scale,
                )
                lines += mapped.copy(text = line.text)
            }
        }
        return TextLayout.prepare(lines)
    }

    fun close() {
        runCatching { recognizer?.close() }
        recognizer = null
        script = null
    }

    private fun clientFor(script: OcrScript): TextRecognizer {
        val current = recognizer
        if (current != null && this.script == script) return current
        current?.close()
        val created = when (script) {
            OcrScript.LATIN -> TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            OcrScript.JAPANESE -> TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())
            OcrScript.CHINESE -> TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            OcrScript.KOREAN -> TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        }
        this.script = script
        recognizer = created
        return created
    }
}
