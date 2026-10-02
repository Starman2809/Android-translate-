package com.kostysetinin.gametranslate.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationLogicTest {
    @Test
    fun parsesJsonArrayWrappedInProse() {
        val parsed = JsonStrings.parseArray(
            """
            Here is the JSON requested:
            ["Привет, путник.", "Ворота открыты."]
            """.trimIndent(),
        )
        assertEquals(listOf("Привет, путник.", "Ворота открыты."), parsed)
    }

    @Test
    fun parsesEscapes() {
        val parsed = JsonStrings.parseArray("[\"say \\\"hi\\\"\", \"line\\n2\", \"\\u041f\"]")
        assertEquals(listOf("say \"hi\"", "line\n2", "П"), parsed)
    }

    @Test
    fun rejectsNonArray() {
        assertNull(JsonStrings.parseArray("just text"))
        assertNull(JsonStrings.parseArray("[1, 2]"))
    }

    @Test
    fun mergesFragmentsOnTheSameRow() {
        val lines = listOf(
            TextBox("Hello,", 10f, 100f, 80f, 130f),
            TextBox("traveler", 90f, 102f, 180f, 128f),
            TextBox("HP 10", 10f, 200f, 70f, 230f),
        )
        val merged = TextLayout.mergeRows(lines)
        assertEquals(2, merged.size)
        assertEquals("Hello, traveler", merged[0].text)
        assertEquals(10f, merged[0].left)
        assertEquals(180f, merged[0].right)
        assertEquals("HP 10", merged[1].text)
    }

    @Test
    fun dropsNoiseAndCapsByArea() {
        val lines = listOf(
            TextBox(".", 0f, 0f, 40f, 30f),
            TextBox("Quest", 0f, 0f, 200f, 80f),
            TextBox("OK", 0f, 100f, 40f, 140f),
            TextBox("Map", 0f, 200f, 30f, 220f),
        )
        val prepared = TextLayout.prepare(lines, maxLines = 2)
        assertEquals(listOf("Quest", "OK"), prepared.map { it.text })
    }

    @Test
    fun mapsCroppedBitmapBackToScreen() {
        val mapped = TextLayout.mapToScreen(
            left = 10f,
            top = 20f,
            right = 40f,
            bottom = 50f,
            cropLeft = 100f,
            cropTop = 200f,
            scale = 0.5f,
        )
        assertEquals(120f, mapped.left)
        assertEquals(240f, mapped.top)
        assertEquals(180f, mapped.right)
        assertEquals(300f, mapped.bottom)
    }

    @Test
    fun stabilityRequiresTwoIdenticalFrames() {
        val gate = StabilityGate(framesRequired = 2)
        assertFalse(gate.observe("hello"))
        assertTrue(gate.observe("hello"))
        assertFalse(gate.observe("other"))
        assertTrue(gate.observe("other"))
    }

    @Test
    fun memoryNormalizesWhitespaceAndEvictsOldEntries() {
        val memory = TranslationMemory(maxSize = 2)
        memory.put("ru", "Hello   traveler", "Привет")
        assertEquals("Привет", memory.get("ru", "Hello traveler"))
        memory.put("ru", "one", "1")
        memory.put("ru", "two", "2")
        memory.put("ru", "three", "3")
        assertNull(memory.get("ru", "Hello traveler"))
        assertEquals("3", memory.get("ru", "three"))
    }
}
