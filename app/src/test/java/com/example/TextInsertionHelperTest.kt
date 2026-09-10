package com.example

import com.example.floating.TextInsertionHelper
import org.junit.Assert.assertEquals
import org.junit.Test

class TextInsertionHelperTest {

    @Test
    fun testSanitizeBengaliText() {
        val raw = "  আমার \u200B সোনার \uFEFF বাংলা   "
        val clean = TextInsertionHelper.sanitizeBengaliText(raw)
        assertEquals("আমার সোনার বাংলা", clean)
    }

    @Test
    fun testComputeMergedText_emptyExisting() {
        val input = "আমি বাংলায় গান গাই"
        val merged = TextInsertionHelper.computeMergedText(
            existingText = "",
            cursorPosition = 0,
            insertedText = input
        )
        assertEquals(TextInsertionHelper.sanitizeBengaliText(input), merged)
    }

    @Test
    fun testComputeMergedText_appendAtEnd() {
        val existing = "হ্যালো বন্ধু"
        val insert = "কেমন আছো?"
        val merged = TextInsertionHelper.computeMergedText(
            existingText = existing,
            cursorPosition = existing.length,
            insertedText = insert
        )
        assertEquals("হ্যালো বন্ধু কেমন আছো?", merged)
    }

    @Test
    fun testComputeMergedText_insertAtCursorMiddle() {
        val existing = "আমি খাই"
        val insert = "ভাত"
        // Insert between "আমি " and "খাই", cursor at index 4 ("আমি ".length)
        val merged = TextInsertionHelper.computeMergedText(
            existingText = existing,
            cursorPosition = 4,
            insertedText = insert
        )
        assertEquals("আমি ভাত খাই", merged)
    }

    @Test
    fun testComputeMergedText_insertAtBeginning() {
        val existing = "বাংলাদেশ"
        val insert = "সুন্দর"
        val merged = TextInsertionHelper.computeMergedText(
            existingText = existing,
            cursorPosition = 0,
            insertedText = insert
        )
        assertEquals("সুন্দর বাংলাদেশ", merged)
    }

    @Test
    fun testComputeMergedText_withPunctuation() {
        val existing = "ভালো।"
        val insert = "সব ঠিকঠাক"
        val merged = TextInsertionHelper.computeMergedText(
            existingText = existing,
            cursorPosition = existing.length,
            insertedText = insert
        )
        assertEquals("ভালো। সব ঠিকঠাক", merged)
    }
}
