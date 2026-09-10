package com.example.floating

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * UI states for the Wispr-style floating voice typing widget.
 */
enum class FloatingUiState {
    IDLE,        // Microphone icon, tap to record
    RECORDING,   // Listening / animated mic ("শুনছি...")
    PROCESSING,  // ASR finalizing ("প্রক্রিয়াকরণ...")
    INSERTING,   // Injecting text via Accessibility ("লিখছি...")
    ERROR        // Short error message toast/badge
}

/**
 * Singleton coordinator bridging the FloatingVoiceOverlayService, FloatingVoiceAccessibilityService,
 * and the FloatingVoiceTypingService (which hosts the existing ONNX STT pipeline).
 */
object FloatingVoiceController {

    private val _uiState = MutableStateFlow(FloatingUiState.IDLE)
    val uiState = _uiState.asStateFlow()

    private val _statusMessage = MutableStateFlow<String?>(null)
    val statusMessage = _statusMessage.asStateFlow()

    private val _rmsLevel = MutableStateFlow(0f)
    val rmsLevel = _rmsLevel.asStateFlow()

    // Live partial recognized Bengali text for floating display
    private val _livePreviewText = MutableStateFlow("")
    val livePreviewText = _livePreviewText.asStateFlow()

    // Events emitted when finalized Bengali text is ready for insertion
    private val _insertTextEvent = MutableSharedFlow<String>(extraBufferCapacity = 10)
    val insertTextEvent = _insertTextEvent.asSharedFlow()

    // Flag indicating whether the overlay window is currently attached
    private val _isOverlayActive = MutableStateFlow(false)
    val isOverlayActive = _isOverlayActive.asStateFlow()

    fun setUiState(state: FloatingUiState, message: String? = null) {
        _uiState.value = state
        _statusMessage.value = message
    }

    fun setRms(rms: Float) {
        _rmsLevel.value = rms
    }

    fun setLivePreview(text: String) {
        _livePreviewText.value = text
    }

    fun setOverlayActive(active: Boolean) {
        _isOverlayActive.value = active
    }

    fun emitFinalizedText(text: String) {
        if (text.isNotBlank()) {
            _insertTextEvent.tryEmit(text.trim())
        }
    }

    fun showError(message: String) {
        _uiState.value = FloatingUiState.ERROR
        _statusMessage.value = message
    }

    fun resetToIdle() {
        _uiState.value = FloatingUiState.IDLE
        _statusMessage.value = null
        _rmsLevel.value = 0f
        _livePreviewText.value = ""
    }
}
