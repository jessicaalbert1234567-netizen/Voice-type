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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.FloatingVoiceTypingService

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
    onCloseClick: () -> Unit
) {
    val uiState by FloatingVoiceController.uiState.collectAsState()
    val statusMessage by FloatingVoiceController.statusMessage.collectAsState()
    val livePreview by FloatingVoiceController.livePreviewText.collectAsState()
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
    val isProcessing = uiState == FloatingUiState.PROCESSING || uiState == FloatingUiState.INSERTING

    val buttonBgColor by animateColorAsState(
        targetValue = when (uiState) {
            FloatingUiState.RECORDING -> CrimsonRed
            FloatingUiState.PROCESSING, FloatingUiState.INSERTING -> AmberWarn
            FloatingUiState.ERROR -> CrimsonRed.copy(alpha = 0.8f)
            FloatingUiState.IDLE -> PurpleActive
        },
        label = "btn_bg"
    )

    Surface(
        color = Color.Transparent,
        modifier = Modifier.padding(6.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Live Preview or Status Pill if active
            if (isRecording || isProcessing || !statusMessage.isNullOrBlank() || livePreview.isNotBlank()) {
                Box(
                    modifier = Modifier
                        .padding(bottom = 6.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(FloatingSurface.copy(alpha = 0.95f))
                        .border(1.dp, FloatingBorder, RoundedCornerShape(12.dp))
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                        .widthIn(max = 200.dp)
                ) {
                    val displayText = when {
                        livePreview.isNotBlank() -> livePreview
                        !statusMessage.isNullOrBlank() -> statusMessage ?: ""
                        isRecording -> "শুনছি... কথা বলুন"
                        isProcessing -> "প্রক্রিয়াকরণ..."
                        else -> ""
                    }

                    Text(
                        text = displayText,
                        color = TextWhite,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // Floating Circular Mic Orb
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
                    .border(1.5.dp, buttonBgColor.copy(alpha = 0.8f), CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
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
                                // Busy, no-op
                            }
                        }
                    }
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
        }
    }
}
