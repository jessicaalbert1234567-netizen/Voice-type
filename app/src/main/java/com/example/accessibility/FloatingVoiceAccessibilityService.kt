package com.example.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.floating.FloatingUiState
import com.example.floating.FloatingVoiceController
import com.example.floating.TextInsertionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * FloatingVoiceAccessibilityService listens to window & focus changes to keep track of the currently
 * active editable input field, and inserts recognized Bengali text directly into the focused field
 * when commanded by FloatingVoiceController.
 */
class FloatingVoiceAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FloatingVoiceA11y"
        private var instance: FloatingVoiceAccessibilityService? = null

        fun isServiceRunning(): Boolean = instance != null
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastFocusedNode: AccessibilityNodeInfo? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "FloatingVoiceAccessibilityService started")

        // Listen for transcription insertion events
        serviceScope.launch {
            FloatingVoiceController.insertTextEvent.collect { recognizedBengaliText ->
                performInsertion(recognizedBengaliText)
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED -> {
                val source = event.source
                if (source != null && source.isEditable) {
                    safeUpdateFocusedNode(source)
                }
            }
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // Update focus if an active editable field is present
                val root = rootInActiveWindow
                val editable = TextInsertionHelper.findFocusedEditableNode(root)
                if (editable != null) {
                    safeUpdateFocusedNode(editable)
                }
            }
        }
    }

    @Synchronized
    private fun safeUpdateFocusedNode(node: AccessibilityNodeInfo) {
        if (lastFocusedNode != node) {
            // Keep reference to latest active editable node
            lastFocusedNode = node
        }
    }

    private fun performInsertion(text: String) {
        if (text.isBlank()) {
            FloatingVoiceController.resetToIdle()
            return
        }

        FloatingVoiceController.setUiState(FloatingUiState.INSERTING, "লিখছি...")

        serviceScope.launch {
            // Always copy to system clipboard first as guaranteed fallback and paste buffer
            try {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = ClipData.newPlainText("Bengali Speech", text)
                clipboard?.setPrimaryClip(clip)
            } catch (e: Exception) {
                Log.w(TAG, "Error copying to clipboard", e)
            }

            // Give window manager a brief moment to stabilize focus if coming from overlay click
            delay(50)

            var targetNode: AccessibilityNodeInfo? = null

            // 1. Try finding currently focused editable node in active window
            val root = rootInActiveWindow
            targetNode = TextInsertionHelper.findFocusedEditableNode(root)

            // 2. If null, fall back to last recorded focused node if still valid
            if (targetNode == null) {
                targetNode = lastFocusedNode
            }

            if (targetNode == null) {
                Log.w(TAG, "No editable text field currently focused - text copied to clipboard")
                Toast.makeText(applicationContext, "টেক্সট কপি করা হয়েছে, পেস্ট করুন", Toast.LENGTH_SHORT).show()
                delay(800)
                FloatingVoiceController.resetToIdle()
                return@launch
            }

            val inserted = TextInsertionHelper.insertTextIntoNode(targetNode, text)
            if (inserted) {
                Log.i(TAG, "Successfully inserted text: $text")
                delay(400)
                FloatingVoiceController.resetToIdle()
            } else {
                Log.w(TAG, "Direct insertion failed, but text is available in clipboard")
                Toast.makeText(applicationContext, "টেক্সট কপি করা হয়েছে, পেস্ট করুন", Toast.LENGTH_SHORT).show()
                delay(800)
                FloatingVoiceController.resetToIdle()
            }
        }
    }

    override fun onInterrupt() {
        Log.w(TAG, "Accessibility Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        lastFocusedNode = null
        serviceScope.cancel()
        Log.i(TAG, "FloatingVoiceAccessibilityService destroyed")
    }
}
