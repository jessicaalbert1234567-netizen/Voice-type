package com.example.floating

import android.content.Context
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
     * Prefers ACTION_SET_TEXT. Preserves existing text and cursor location where available.
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
            val existingText = node.text?.toString() ?: ""
            val cursorPosition = node.textSelectionStart.takeIf { it >= 0 } ?: existingText.length
            val newFullText = computeMergedText(existingText, cursorPosition, cleanInsert)

            val arguments = Bundle().apply {
                putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, newFullText)
            }

            val success = node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            if (success) {
                // Update selection to end of inserted text if supported
                val newCursor = (cursorPosition.coerceAtLeast(0) + cleanInsert.length + if (existingText.isNotEmpty()) 1 else 0).coerceAtMost(newFullText.length)
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, newCursor)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, newCursor)
                }
                node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                return true
            }

            // Fallback for nodes that don't support ACTION_SET_TEXT directly
            return false
        } catch (e: Exception) {
            Log.e(TAG, "Exception during text insertion", e)
            return false
        }
    }
}
