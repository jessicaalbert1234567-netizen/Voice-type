package com.example.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.accessibility.FloatingVoiceAccessibilityService
import com.example.floating.FloatingPreferences
import com.example.floating.FloatingVoiceController
import com.example.floating.FloatingVoiceOverlayService
import com.example.ui.theme.ElegantDarkBackground
import com.example.ui.theme.ElegantDarkBorder
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LavenderPrimary
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

@Composable
fun FloatingVoiceTypingConfigCard(
    isModelReady: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val floatingPrefs = remember { FloatingPreferences(context) }

    var hasOverlayPermission by remember {
        mutableStateOf(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(context) else true
        )
    }

    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasAudioPermission = granted
    }

    var isAccessibilityActive by remember {
        mutableStateOf(FloatingVoiceAccessibilityService.isServiceRunning())
    }

    var isEnabled by remember {
        mutableStateOf(floatingPrefs.isFloatingEnabled && hasOverlayPermission && hasAudioPermission)
    }

    // Refresh permission statuses on resume
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                hasOverlayPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    Settings.canDrawOverlays(context)
                } else {
                    true
                }
                hasAudioPermission = ContextCompat.checkSelfPermission(
                    context, Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                isAccessibilityActive = FloatingVoiceAccessibilityService.isServiceRunning()
                if (isEnabled && (!hasOverlayPermission || !hasAudioPermission)) {
                    isEnabled = false
                    floatingPrefs.isFloatingEnabled = false
                    FloatingVoiceOverlayService.stop(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(ElegantDarkBackground)
            .border(1.dp, ElegantDarkBorder, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Default.Layers,
                    contentDescription = null,
                    tint = LavenderPrimary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Column {
                    Text(
                        text = "ফ্লোটিং ভয়েস টাইপিং",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                    Text(
                        text = "অন্যান্য অ্যাপে যেকোনো টেক্সটবক্সে কথা বলে সরাসরি লিখুন",
                        fontSize = 10.5.sp,
                        color = TextSecondary
                    )
                }
            }

            Switch(
                checked = isEnabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        if (!hasOverlayPermission) {
                            requestOverlayPermission(context)
                            return@Switch
                        }
                        if (!hasAudioPermission) {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            return@Switch
                        }
                        isEnabled = true
                        floatingPrefs.isFloatingEnabled = true
                        FloatingVoiceOverlayService.start(context)
                    } else {
                        isEnabled = false
                        floatingPrefs.isFloatingEnabled = false
                        FloatingVoiceOverlayService.stop(context)
                    }
                },
                modifier = Modifier.testTag("floating_voice_typing_switch"),
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LavenderPrimary,
                    checkedTrackColor = LavenderPrimary.copy(alpha = 0.3f),
                    uncheckedThumbColor = TextMuted,
                    uncheckedTrackColor = ElegantDarkBorder
                )
            )
        }

        // Requirements and permission warnings
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // Overlay Permission Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasOverlayPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (hasOverlayPermission) SuccessGreen else ErrorRed,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (hasOverlayPermission) "ডিসপ্লে ওভারলে অনুমোদিত" else "ডিসপ্লে ওভারলে পারমিশন প্রয়োজন",
                        fontSize = 11.sp,
                        color = if (hasOverlayPermission) SuccessGreen else TextSecondary
                    )
                }

                if (!hasOverlayPermission) {
                    Button(
                        onClick = { requestOverlayPermission(context) },
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LavenderPrimary.copy(alpha = 0.18f),
                            contentColor = LavenderPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("অনুমতি দিন", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(10.dp))
                    }
                }
            }

            // Microphone Permission Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (hasAudioPermission) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (hasAudioPermission) SuccessGreen else ErrorRed,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (hasAudioPermission) "মাইক্রোফোন পারমিশন অনুমোদিত" else "মাইক্রোফোন পারমিশন প্রয়োজন",
                        fontSize = 11.sp,
                        color = if (hasAudioPermission) SuccessGreen else TextSecondary
                    )
                }

                if (!hasAudioPermission) {
                    Button(
                        onClick = { audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LavenderPrimary.copy(alpha = 0.18f),
                            contentColor = LavenderPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("অনুমতি দিন", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Accessibility Service Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (isAccessibilityActive) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isAccessibilityActive) SuccessGreen else ErrorRed,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isAccessibilityActive) "অ্যাক্সেসিবিলিটি সার্ভিস সক্রিয়" else "অ্যাক্সেসিবিলিটি সক্রিয় করা প্রয়োজন",
                        fontSize = 11.sp,
                        color = if (isAccessibilityActive) SuccessGreen else TextSecondary
                    )
                }

                if (!isAccessibilityActive) {
                    Button(
                        onClick = { requestAccessibilitySettings(context) },
                        modifier = Modifier.height(28.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = LavenderPrimary.copy(alpha = 0.18f),
                            contentColor = LavenderPrimary
                        ),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("সক্রিয় করুন", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.width(3.dp))
                        Icon(Icons.Default.OpenInNew, contentDescription = null, modifier = Modifier.size(10.dp))
                    }
                }
            }

            // Model ready check
            if (!isModelReady) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = LavenderPrimary,
                        modifier = Modifier.size(13.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "ব্যবহারের পূর্বে মডেল ও টোকেনাইজার যুক্ত করুন",
                        fontSize = 10.5.sp,
                        color = TextMuted
                    )
                }
            }
        }
    }
}

private fun requestOverlayPermission(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
}

private fun requestAccessibilitySettings(context: Context) {
    try {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        // Fallback
    }
}
