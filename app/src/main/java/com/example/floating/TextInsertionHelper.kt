package com.example.floating

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer

/**
 * Pure, testable helper functions for robust, cursor-aware Bengali text insertion into
 * AccessibilityNodeInfo instances.
 */
object TextInsertionHelper {

    private const val TAG = "TextInsertionHelper"

    /**
     * Finds the currently focused, editable AccessibilityNodeInfo in the active window hierarchy.
     */
    fun findFocusedEditableNode(rootNode: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (rootNode == null) return null

        // Try direct accessibility focus or input focus
        val focusedInput = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focusedInput != null && focusedInput.isEditable) {
            return focusedInput
        }

        val accessibilityFocused = rootNode.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY)
        if (accessibilityFocused != null && accessibilityFocused.isEditable) {
            return accessibilityFocused
        }

        // Fallback: breadth-first search for focused editable node
        return searchNodeForEditableFocus(rootNode)
    }

    private fun searchNodeForEditableFocus(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isFocused && node.isEditable) {
            return node
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val result = searchNodeForEditableFocus(child)
            if (result != null) {
                // If this is not the result node, recycle child if we created it
                if (result != child) {
                    child.recycle()
                }
                return result
            }
            child.recycle()
        }
        return null
    }

    /**
     * Formats recognized Bengali text with proper spacing and punctuation preservation,
     * trimming unwanted leading/trailing whitespace without introducing double spaces.
     */
    fun sanitizeBengaliText(rawText: String): String {
        if (rawText.isBlank()) return ""
        val normalized = Normalizer.normalize(rawText, Normalizer.Form.NFC)
        return normalized
            .replace("\u200B", "") // Zero width space
            .replace("\uFEFF", "") // BOM
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    /**
     * Checks if the given text represents a placeholder or hint rather than actual user input.
     * Prevents prepending browser/app search hints (e.g. "Search Google or type URL", "Search YouTube")
     * to voice typing outputs.
     */
    fun isPlaceholderOrHint(
        rawText: String?,
        hintText: String? = null,
        isShowingHint: Boolean = false,
        viewId: String? = null
    ): Boolean {
        if (rawText.isNullOrBlank()) return true
        if (isShowingHint) return true

        val cleanRaw = rawText.replace("\u00A0", " ").trim()
        val lower = cleanRaw.lowercase()

        // Placeholders and search prompts are typically short phrases (< 60 chars)
        if (cleanRaw.length > 70) return false

        // Exact match with node's hintText
        if (!hintText.isNullOrBlank()) {
            val cleanHint = hintText.replace("\u00A0", " ").trim()
            if (cleanRaw.equals(cleanHint, ignoreCase = true) || lower.contains(cleanHint.lowercase())) {
                return true
            }
        }

        // Search bar or Omnibox view ID detection (Chrome, YouTube, etc.)
        val isSearchBox = viewId?.let { id ->
            val idLower = id.lowercase()
            idLower.contains("search") ||
            idLower.contains("url_bar") ||
            idLower.contains("omnibox") ||
            idLower.contains("query")
        } == true

        // Keyword phrases that represent placeholders/search hints
        val placeholderKeywords = listOf(
            "search google",
            "search youtube",
            "type url",
            "search or type",
            "web address",
            "type a message",
            "type something",
            "write a message",
            "write something",
            "send a message",
            "ask a question",
            "ask anything",
            "search apps",
            "search here",
            "search videos",
            "search music",
            "search web",
            "what's on your mind",
            "write a comment",
            "google-এ খুঁজুন",
            "youtube-এ খুঁজুন",
            "খুঁজুন",
            "অনুসন্ধান",
            "বার্তা লিখুন"
        )

        for (kw in placeholderKeywords) {
            if (lower.contains(kw)) {
                return true
            }
        }

        // Generic single-word search or hint indicators
        if (lower == "search" || lower == "search..." || lower == "search…" ||
            lower == "find" || lower == "find..." ||
            lower == "message" || lower == "message..." ||
            lower == "aa"
        ) {
            return true
        }

        // If it's a known search box and the text doesn't contain Bengali characters,
        // and doesn't look like a real website URL, it's the initial search hint
        val hasBengaliChar = cleanRaw.any { it in '\u0980'..'\u09FF' }
        if (isSearchBox && !hasBengaliChar) {
            if (!lower.startsWith("http://") && !lower.startsWith("https://") && !lower.contains(".")) {
                return true
            }
        }

        // Regex check for generic search/type placeholder phrases
        val regex = Regex("^(search|find|type|ask|write|খুঁজুন|অনুসন্ধান|বার্তা)\\b.*", RegexOption.IGNORE_CASE)
        if (regex.matches(lower)) {
            return true
        }

        return false
    }

    /**
     * Extracts existing text from an AccessibilityNodeInfo, stripping any placeholder/hint text.
     */
    fun getEffectiveExistingText(node: AccessibilityNodeInfo): String {
        val isShowingHint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.isShowingHintText
        } else {
            false
        }

        val hint = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            node.hintText?.toString()
        } else {
            null
        }

        val raw = node.text?.toString() ?: ""
        val viewId = node.viewIdResourceName
        if (isPlaceholderOrHint(raw, hint, isShowingHint, viewId)) {
            return ""
        }
        return raw
    }

    /**
     * Calculates the merged text when inserting [insertedText] into [existingText] at [cursorPosition].
     *
     * Rules:
     * 1. If [insertedText] is blank, returns [existingText].
     * 2. If [existingText] is blank or empty, returns [insertedText].
     * 3. Prevents duplicate spaces around insertion boundary.
     * 4. Accurately slices around [cursorPosition]. If cursor is -1 or out of bounds, appends at the end.
     */
    fun computeMergedText(
        existingText: String,
        cursorPosition: Int,
        insertedText: String
    ): String {
        val cleanInsert = sanitizeBengaliText(insertedText)
        if (cleanInsert.isEmpty()) return existingText
        if (existingText.isEmpty()) return cleanInsert

        val length = existingText.length
        val validCursor = if (cursorPosition in 0..length) cursorPosition else length

        val beforeCursor = existingText.substring(0, validCursor)
        val afterCursor = existingText.substring(validCursor)

        val needsSpaceBefore = beforeCursor.isNotEmpty() && !beforeCursor.endsWith(" ") && !beforeCursor.endsWith("\n")
        val needsSpaceAfter = afterCursor.isNotEmpty() && !afterCursor.startsWith(" ") && !afterCursor.startsWith("\n") && !afterCursor.startsWith("।") && !afterCursor.startsWith(",")

        val sb = StringBuilder()
        sb.append(beforeCursor)
        if (needsSpaceBefore) {
            sb.append(" ")
        }
        sb.append(cleanInsert)
        if (needsSpaceAfter) {
            sb.append(" ")
        }
        sb.append(afterCursor)

        return sb.toString()
    }

    /**
     * Inserts [transcribedText] into the provided [node].
     *
     * Primary approach: ACTION_SET_TEXT
     *   Directly sets the text without any search prompts or placeholders (such as "Search Google or type URL",
     *   "Search YouTube"). If the field had genuine prior user text, it cleanly merges at cursor.
     *
     * Fallback approach: ACTION_PASTE
     *   Used only if ACTION_SET_TEXT fails. If the field originally contained placeholder text, selects all
     *   first so paste replaces the placeholder rather than appending to it.
     *
     * Returns true if successfully inserted, false otherwise.
     */
    fun insertTextIntoNode(node: AccessibilityNodeInfo?, transcribedText: String): Boolean {
        if (node == null || !node.isEditable) {
            Log.w(TAG, "Cannot insert text: node is null or not editable")
            return false
        }

        val cleanInsert = sanitizeBengaliText(transcribedText)
        if (cleanInsert.isEmpty()) {
            return false
        }

        try {
            // Ensure the node has input focus
            if (!node.isFocused) {
                node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            }

            // Extract genuine existing user text (ignoring search bar placeholders like "Search Google...", "Search YouTube")
            val existingText = getEffectiveExistingText(node)
            val selStart = node.textSelectionStart
            val selEnd = node.textSelectionEnd

            val newFullText = if (existingText.isEmpty()) {
                cleanInsert
            } else if (selStart >= 0 && selEnd > selStart && selStart <= existingText.length) {
                val before = existingText.substring(0, selStart)
                val after = existingText.substring(selEnd.coerceAtMost(existingText.length))
                computeMergedText(before + after, selStart, cleanInsert)
            } else {
                val cursorPosition = selStart.takeIf { it >= 0 } ?: existingText.length
                computeMergedText(existingText, cursorPosition, cleanInsert)
            }

            // 1. Primary method: ACTION_SET_TEXT
            // Replaces the content with newFullText. This guarantees "Search Google...", "Search YouTube", etc.
            // are completely replaced by cleanInsert.
            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newFullText)
            }

            val setTextSuccess = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (setTextSuccess) {
                val newCursor = if (existingText.isEmpty()) {
                    cleanInsert.length
                } else {
                    (selStart.coerceAtLeast(0) + cleanInsert.length + 1).coerceAtMost(newFullText.length)
                }
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                Log.i(TAG, "Successfully inserted text via ACTION_SET_TEXT: $newFullText")
                return true
            }

            // 2. Fallback method: ACTION_PASTE (for custom non-standard editors)
            Log.w(TAG, "ACTION_SET_TEXT failed, attempting fallback ACTION_PASTE")
            if (existingText.isEmpty() && !node.text.isNullOrEmpty()) {
                // If original text was a placeholder, select all before pasting so it gets overwritten
                val selectAllArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, node.text?.length ?: 0)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selectAllArgs)
            }

            val pasteSuccess = node.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            if (pasteSuccess) {
                Log.i(TAG, "Successfully inserted text via fallback ACTION_PASTE")
                return true
            }

            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception during text insertion", e)
            return false
        }
    }
}
