package com.example.floating

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.MainActivity
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.example.service.FloatingVoiceTypingService
import kotlin.math.hypot

// Sleek modern dark aesthetic matching Kazalbrur STT
private val FloatingSurface = Color(0xFF1E1E2E)
private val FloatingBorder = Color(0xFF313244)
private val PurpleActive = Color(0xFFB4BEFE)
private val CrimsonRed = Color(0xFFF38BA8)
private val EmeraldGreen = Color(0xFFA6E3A1)
private val AmberWarn = Color(0xFFFAB387)
private val TextWhite = Color(0xFFCDD6F4)
private val TextMuted = Color(0xFFBAC2DE)

@Composable
fun FloatingVoiceOverlay(
    context: Context,
    onCloseClick: () -> Unit,
    onDragStart: () -> Unit = {},
    onDrag: (dx: Float, dy: Float) -> Unit = { _, _ -> },
    onDragEnd: () -> Unit = {}
) {
    val uiState by FloatingVoiceController.uiState.collectAsState()
    val rmsLevel by FloatingVoiceController.rmsLevel.collectAsState()

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.14f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    val isRecording = uiState == FloatingUiState.RECORDING

    val buttonBgColor by animateColorAsState(
        targetValue = when (uiState) {
            FloatingUiState.RECORDING -> CrimsonRed
            FloatingUiState.PROCESSING, FloatingUiState.INSERTING -> AmberWarn
            FloatingUiState.ERROR -> CrimsonRed.copy(alpha = 0.8f)
            FloatingUiState.IDLE -> PurpleActive
        },
        label = "btn_bg"
    )

    val handleOrbClick = {
        when (uiState) {
            FloatingUiState.RECORDING -> {
                FloatingVoiceTypingService.stopRecording(context)
            }
            FloatingUiState.IDLE, FloatingUiState.ERROR -> {
                val hasAudioPermission = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasAudioPermission) {
                    Toast.makeText(
                        context,
                        "মাইক্রোফোন পারমিশন প্রয়োজন। অ্যাপটি ওপেন করে অনুমতি দিন।",
                        Toast.LENGTH_LONG
                    ).show()
                    val intent = Intent(context, MainActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    context.startActivity(intent)
                } else {
                    FloatingVoiceTypingService.startRecording(context)
                }
            }
            FloatingUiState.PROCESSING, FloatingUiState.INSERTING -> {
                // Busy processing, no-op
            }
        }
    }

    Surface(
        color = Color.Transparent,
        modifier = Modifier.padding(4.dp)
    ) {
        Box(
            modifier = Modifier.size(62.dp),
            contentAlignment = Alignment.Center
        ) {
            // Draggable & Clickable Floating Circular Mic Orb
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(
                                FloatingSurface,
                                Color(0xFF11111B)
                            )
                        )
                    )
                    .border(1.5.dp, buttonBgColor.copy(alpha = 0.85f), CircleShape)
                    .floatingDraggableAndClickable(
                        onDragStart = onDragStart,
                        onDrag = onDrag,
                        onDragEnd = onDragEnd,
                        onClick = handleOrbClick
                    )
            ) {
                if (isRecording) {
                    // Outer audio reactive ring
                    val dynamicScale = 1f + (rmsLevel * 0.4f).coerceIn(0f, 0.5f)
                    Box(
                        modifier = Modifier
                            .size(50.dp)
                            .scale(dynamicScale)
                            .clip(CircleShape)
                            .background(CrimsonRed.copy(alpha = 0.25f))
                    )
                }

                when (uiState) {
                    FloatingUiState.RECORDING -> {
                        Icon(
                            imageVector = Icons.Default.Stop,
                            contentDescription = "Stop Voice Typing",
                            tint = CrimsonRed,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    FloatingUiState.PROCESSING, FloatingUiState.INSERTING -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = AmberWarn,
                            strokeWidth = 2.5.dp
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.Mic,
                            contentDescription = "Start Voice Typing",
                            tint = PurpleActive,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            // Small close button on top-right corner of the orb when idle
            if (uiState == FloatingUiState.IDLE) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF262638))
                        .border(1.dp, Color(0xFF4A4A62), CircleShape)
                        .clickable { onCloseClick() }
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close overlay",
                        tint = Color(0xFFA0A0BA),
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
        }
    }
}

/**
 * Modifier detecting drag gestures and passing relative offsets to update WindowManager overlay layout.
 */
private fun Modifier.floatingDragModifier(
    onDragStart: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onDragStart()
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break

            if (change.changedToUp() || !change.pressed) {
                onDragEnd()
                change.consume()
                break
            }

            val dragX = change.position.x - change.previousPosition.x
            val dragY = change.position.y - change.previousPosition.y
            change.consume()
            onDrag(dragX, dragY)
        }
    }
}

/**
 * Modifier distinguishing between tap gesture (to start/stop voice recording)
 * and drag gesture (to move the floating orb around the screen smoothly).
 */
private fun Modifier.floatingDraggableAndClickable(
    onDragStart: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onClick: () -> Unit
): Modifier = this.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        onDragStart()
        var totalDragX = 0f
        var totalDragY = 0f
        var isDragging = false
        val touchSlop = viewConfiguration.touchSlop

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break

            if (change.changedToUp()) {
                if (isDragging) {
                    onDragEnd()
                } else {
                    onClick()
                }
                change.consume()
                break
            }

            if (!change.pressed) {
                if (isDragging) onDragEnd()
                break
            }

            val dragX = change.position.x - change.previousPosition.x
            val dragY = change.position.y - change.previousPosition.y
            totalDragX += dragX
            totalDragY += dragY

            if (!isDragging && (hypot(totalDragX.toDouble(), totalDragY.toDouble()) > touchSlop)) {
                isDragging = true
            }

            if (isDragging) {
                change.consume()
                onDrag(dragX, dragY)
            }
        }
    }
}
