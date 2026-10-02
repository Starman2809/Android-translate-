package com.kostysetinin.gametranslate.logic

data class TextBox(
    val text: String,
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val area: Float get() = width.coerceAtLeast(0f) * height.coerceAtLeast(0f)
}

/**
 * Turns raw OCR boxes into a short, stable list of lines worth translating.
 * Boxes are in screen pixels.
 */
object TextLayout {
    fun mapToScreen(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        cropLeft: Float,
        cropTop: Float,
        scale: Float,
    ): TextBox {
        val safeScale = if (scale <= 0f) 1f else scale
        return TextBox(
            text = "",
            left = cropLeft + left / safeScale,
            top = cropTop + top / safeScale,
            right = cropLeft + right / safeScale,
            bottom = cropTop + bottom / safeScale,
        )
    }

    fun prepare(lines: List<TextBox>, maxLines: Int = 20): List<TextBox> {
        val useful = lines.mapNotNull { line ->
            val text = line.text.trim()
            if (text.isEmpty() || !text.any { it.isLetterOrDigit() }) return@mapNotNull null
            if (line.height < 10f && text.length < 2) return@mapNotNull null
            line.copy(text = text)
        }
        val merged = mergeRows(useful)
        return capByArea(merged, maxLines)
    }

    fun mergeRows(lines: List<TextBox>): List<TextBox> {
        if (lines.isEmpty()) return emptyList()
        val sorted = lines.sortedWith(compareBy<TextBox> { it.top }.thenBy { it.left })
        val merged = mutableListOf(sorted.first())
        for (line in sorted.drop(1)) {
            val current = merged.last()
            if (sameRow(current, line)) {
                merged[merged.lastIndex] = join(current, line)
            } else {
                merged += line
            }
        }
        return merged
    }

    fun capByArea(lines: List<TextBox>, maxLines: Int): List<TextBox> {
        if (lines.size <= maxLines) return lines.sortedWith(readingOrder)
        return lines.sortedByDescending { it.area }.take(maxLines).sortedWith(readingOrder)
    }

    private fun sameRow(leftLine: TextBox, rightLine: TextBox): Boolean {
        val overlap = minOf(leftLine.bottom, rightLine.bottom) - maxOf(leftLine.top, rightLine.top)
        val minHeight = minOf(leftLine.height, rightLine.height).coerceAtLeast(1f)
        val gap = rightLine.left - leftLine.right
        return overlap > minHeight * 0.45f && gap < minHeight * 2.2f && gap > -minHeight
    }

    private fun join(leftLine: TextBox, rightLine: TextBox): TextBox {
        val separator = if (leftLine.text.endsWith("-") || leftLine.text.endsWith("－")) "" else " "
        val leftText = if (separator.isEmpty()) leftLine.text.dropLast(1) else leftLine.text
        return TextBox(
            text = leftText + separator + rightLine.text,
            left = minOf(leftLine.left, rightLine.left),
            top = minOf(leftLine.top, rightLine.top),
            right = maxOf(leftLine.right, rightLine.right),
            bottom = maxOf(leftLine.bottom, rightLine.bottom),
        )
    }

    private val readingOrder = Comparator<TextBox> { leftLine, rightLine ->
        val overlap = minOf(leftLine.bottom, rightLine.bottom) - maxOf(leftLine.top, rightLine.top)
        val minHeight = minOf(leftLine.height, rightLine.height).coerceAtLeast(1f)
        if (overlap > minHeight * 0.4f) {
            leftLine.left.compareTo(rightLine.left)
        } else {
            leftLine.top.compareTo(rightLine.top)
        }
    }
}

/**
 * Dialogue can be translated immediately. Short labels and changing numbers
 * wait until the same text is seen twice, so a moving percentage does not
 * block the rest of the screen.
 */
class LineReadiness {
    private val counts = LinkedHashMap<String, Int>()

    fun ready(lines: List<TextBox>, normalize: (String) -> String): List<TextBox> {
        val present = LinkedHashSet<String>()
        val selected = mutableListOf<TextBox>()
        for (line in lines) {
            val key = normalize(line.text)
            present += key
            val count = (counts[key] ?: 0) + 1
            counts[key] = count
            if (count >= framesNeeded(line.text)) selected += line
        }
        counts.keys.retainAll(present)
        return selected
    }

    private fun framesNeeded(text: String): Int {
        val letters = text.count { it.isLetter() }
        return if (letters >= 12) 1 else 2
    }
}

/** Waits until OCR text stops flickering before a network translation. */
class StabilityGate(private val framesRequired: Int = 2) {
    private var lastKey: String? = null
    private var count: Int = 0

    fun observe(key: String): Boolean {
        if (key == lastKey) {
            count++
        } else {
            lastKey = key
            count = 1
        }
        return count >= framesRequired
    }

    fun reset() {
        lastKey = null
        count = 0
    }
}

class TranslationMemory(private val maxSize: Int = 400) {
    private val map = object : LinkedHashMap<String, String>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            return size > maxSize
        }
    }

    fun get(language: String, text: String): String? = synchronized(map) {
        map[key(language, text)]
    }

    fun put(language: String, text: String, translation: String) {
        synchronized(map) {
            map[key(language, text)] = translation
        }
    }

    fun normalize(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    private fun key(language: String, text: String): String = language + "\u0000" + normalize(text)
}
