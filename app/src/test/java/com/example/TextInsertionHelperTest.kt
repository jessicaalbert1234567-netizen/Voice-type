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

    @Test
    fun testIsPlaceholderOrHint_chromeAndYouTubeHints() {
        // Chrome omnibox hint
        org.junit.Assert.assertTrue(
            TextInsertionHelper.isPlaceholderOrHint("Search Google or type URL", null, false)
        )
        // YouTube search hint
        org.junit.Assert.assertTrue(
            TextInsertionHelper.isPlaceholderOrHint("Search YouTube", null, false)
        )
        // WhatsApp / messaging hint
        org.junit.Assert.assertTrue(
            TextInsertionHelper.isPlaceholderOrHint("Type a message", null, false)
        )
        // Generic search hint
        org.junit.Assert.assertTrue(
            TextInsertionHelper.isPlaceholderOrHint("Search...", null, false)
        )
        // Matching explicit hint
        org.junit.Assert.assertTrue(
            TextInsertionHelper.isPlaceholderOrHint("Custom search hint", "Custom search hint", false)
        )
        // When system reports isShowingHint = true
        org.junit.Assert.assertTrue(
            TextInsertionHelper.isPlaceholderOrHint("Any text", null, true)
        )
        // Real user text should NOT be identified as placeholder
        org.junit.Assert.assertFalse(
            TextInsertionHelper.isPlaceholderOrHint("প্রথম আলো পত্রিকা", null, false)
        )
        org.junit.Assert.assertFalse(
            TextInsertionHelper.isPlaceholderOrHint("আমার সোনার বাংলা", null, false)
        )
        // Chrome url_bar view id test
        org.junit.Assert.assertTrue(
            TextInsertionHelper.isPlaceholderOrHint("Search Google or type URL", null, false, "com.android.chrome:id/url_bar")
        )
        // YouTube search_edit_text view id test
        org.junit.Assert.assertTrue(
            TextInsertionHelper.isPlaceholderOrHint("Search YouTube", null, false, "com.google.android.youtube:id/search_edit_text")
        )
        // Bengali search hint test
        org.junit.Assert.assertTrue(
            TextInsertionHelper.isPlaceholderOrHint("YouTube-এ খুঁজুন", null, false)
        )
        org.junit.Assert.assertTrue(
            TextInsertionHelper.isPlaceholderOrHint("Google-এ খুঁজুন", null, false)
        )
    }
}
