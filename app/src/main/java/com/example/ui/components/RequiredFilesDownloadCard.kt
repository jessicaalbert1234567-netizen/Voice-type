package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.SttUiState
import com.example.ui.theme.ElegantDarkBackground
import com.example.ui.theme.ElegantDarkBorder
import com.example.ui.theme.ElegantDarkSurfaceCard
import com.example.ui.theme.ErrorContainer
import com.example.ui.theme.ErrorRed
import com.example.ui.theme.LavenderPrimary
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import java.text.DecimalFormat

@Composable
fun RequiredFilesDownloadCard(
    uiState: SttUiState,
    onRetryClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = ElegantDarkSurfaceCard),
        border = BorderStroke(1.dp, ElegantDarkBorder),
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .testTag("required_files_download_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val hasError = uiState.downloadError != null

            // Icon badge
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasError) ErrorContainer.copy(alpha = 0.3f)
                        else LavenderPrimary.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (hasError) Icons.Default.ErrorOutline else Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = if (hasError) ErrorRed else LavenderPrimary,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Main Title
            Text(
                text = if (hasError) "ডাউনলোড সম্পন্ন হয়নি" else "ডাউনলোডিং required files",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = if (hasError) ErrorRed else TextPrimary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Subtitle
            Text(
                text = if (hasError) {
                    uiState.downloadError ?: "ইন্টারনেট কানেকশন চেক করে আবার চেষ্টা করুন।"
                } else {
                    "প্রথমবার ব্যবহারের জন্য প্রয়োজনীয় ফাইল প্রস্তুত করা হচ্ছে..."
                },
                fontSize = 13.sp,
                color = if (hasError) TextMuted else TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(20.dp))

            if (!hasError) {
                // Percentage and Size Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Text(
                        text = "${uiState.downloadPercentage}%",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = LavenderPrimary
                    )

                    val downloadedFormatted = formatBytes(uiState.downloadBytesDownloaded)
                    val totalFormatted = formatBytes(if (uiState.downloadTotalBytes > 0) uiState.downloadTotalBytes else 144_918_311L)
                    Text(
                        text = "$downloadedFormatted / $totalFormatted",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = TextMuted
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Progress Bar
                if (uiState.downloadProgressFraction > 0f) {
                    LinearProgressIndicator(
                        progress = { uiState.downloadProgressFraction.coerceIn(0f, 1f) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = LavenderPrimary,
                        trackColor = ElegantDarkBorder
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        color = LavenderPrimary,
                        trackColor = ElegantDarkBorder
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                Text(
                    text = "ডাউনলোড একবারই হবে, পরবর্তী সময়ে সম্পূর্ণ অফলাইনে কাজ করবে।",
                    fontSize = 11.5.sp,
                    color = TextMuted,
                    textAlign = TextAlign.Center
                )
            } else {
                // Retry Button
                Button(
                    onClick = onRetryClick,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = LavenderPrimary,
                        contentColor = ElegantDarkBackground
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("retry_download_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "আবার চেষ্টা করুন (Retry)",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes <= 0) return "0 MB"
    val df = DecimalFormat("#.##")
    return when {
        bytes >= 1024 * 1024 -> "${df.format(bytes.toDouble() / (1024.0 * 1024.0))} MB"
        bytes >= 1024 -> "${df.format(bytes.toDouble() / 1024.0)} KB"
        else -> "$bytes B"
    }
}
