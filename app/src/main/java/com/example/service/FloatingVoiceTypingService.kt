package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.AudioFileDecoder
import com.example.audio.AudioRecorderManager
import com.example.dsp.MelSpectrogramPreprocessor
import com.example.engine.AudioFileProcessor
import com.example.engine.CtcDecoder
import com.example.engine.OnnxAsrEngine
import com.example.engine.TranscriptAccumulator
import com.example.floating.FloatingUiState
import com.example.floating.FloatingVoiceController
import com.example.floating.FloatingVoiceEngineHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Collections

/**
 * Foreground service hosting the microphone capture and offline ONNX ASR streaming pipeline
 * for floating voice typing. Keeps background audio capture alive safely and terminates as soon as
 * transcription finishes.
 */
class FloatingVoiceTypingService : Service() {

    companion object {
        private const val TAG = "FloatingVoiceService"
        const val CHANNEL_ID = "bangla_floating_voice_channel"
        const val NOTIFICATION_ID = 2001

        const val ACTION_START_RECORDING = "com.example.action.START_RECORDING"
        const val ACTION_STOP_RECORDING = "com.example.action.STOP_RECORDING"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SERVICE"

        private var isRunning = false
        fun isServiceActive(): Boolean = isRunning

        fun startRecording(context: Context) {
            val intent = Intent(context, FloatingVoiceTypingService::class.java).apply {
                action = ACTION_START_RECORDING
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopRecording(context: Context) {
            val intent = Intent(context, FloatingVoiceTypingService::class.java).apply {
                action = ACTION_STOP_RECORDING
            }
            context.startService(intent)
        }

        fun stopService(context: Context) {
            val intent = Intent(context, FloatingVoiceTypingService::class.java).apply {
                action = ACTION_STOP_SERVICE
            }
            context.startService(intent)
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var audioRecorder: AudioRecorderManager
    private val transcriptAccumulator = TranscriptAccumulator()

    // Buffer to capture full raw PCM samples during the recording session
    private val recordedChunks = Collections.synchronizedList(mutableListOf<ShortArray>())

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        createNotificationChannel()

        audioRecorder = AudioRecorderManager()
        setupAudioListener()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: return START_NOT_STICKY
        Log.i(TAG, "onStartCommand action: $action")

        when (action) {
            ACTION_START_RECORDING -> {
                startForegroundWithNotification()
                startListening()
            }
            ACTION_STOP_RECORDING -> {
                stopListening()
            }
            ACTION_STOP_SERVICE -> {
                stopListening()
                stopSelf()
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundWithNotification() {
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        val notification = buildForegroundNotification("শুনছি... কথা বলুন")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasAudioPermission) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Bangla Voice Typing")
            .setContentText(contentText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun setupAudioListener() {
        audioRecorder.setListener(object : AudioRecorderManager.AudioListener {
            override fun onAudioChunkAvailable(
                audioWindow: ShortArray,
                totalDurationMs: Long,
                audioFrameStart: Long,
                audioFrameEnd: Long,
                vadState: String,
                isEndOfUtterance: Boolean
            ) {
                // When user finishes speaking and pause boundary is detected, automatically stop & transcribe
                if (isEndOfUtterance && audioRecorder.isRecordingActive()) {
                    Log.i(TAG, "Speech end detected via VAD. Auto-stopping and processing audio file.")
                    stopListening()
                }
            }

            override fun onRawPcmChunk(chunk: ShortArray, count: Int) {
                if (audioRecorder.isRecordingActive() && count > 0) {
                    val copy = chunk.copyOf(count)
                    recordedChunks.add(copy)
                }
            }

            override fun onAmplitudeChanged(rmsNormalized: Float) {
                FloatingVoiceController.setRms(rmsNormalized)
            }

            override fun onError(message: String) {
                FloatingVoiceController.showError(message)
                stopListening()
            }
        })
    }

    private fun startListening() {
        serviceScope.launch {
            // Fast warm-up check (returns instantly if already warmed up by FloatingVoiceOverlayService)
            val isReady = FloatingVoiceEngineHolder.warmUp(applicationContext)
            val tokenizer = FloatingVoiceEngineHolder.tokenizer
            if (!isReady || !FloatingVoiceEngineHolder.onnxEngine.isLoaded || tokenizer == null) {
                withContext(Dispatchers.Main) {
                    FloatingVoiceController.showError("মডেল ও টোকেনাইজার প্রস্তুত নয়")
                }
                stopSelf()
                return@launch
            }

            recordedChunks.clear()
            transcriptAccumulator.clear()

            withContext(Dispatchers.Main) {
                FloatingVoiceController.setLivePreview("")
                FloatingVoiceController.setUiState(FloatingUiState.RECORDING, null)
            }

            val started = audioRecorder.startRecording(serviceScope)
            if (!started) {
                withContext(Dispatchers.Main) {
                    FloatingVoiceController.showError("মাইক্রোফোন চালু করা যায়নি")
                }
                stopSelf()
            }
        }
    }

    private fun stopListening() {
        serviceScope.launch {
            withContext(Dispatchers.Main) {
                FloatingVoiceController.setUiState(FloatingUiState.PROCESSING, null)
                FloatingVoiceController.setLivePreview("")
            }

            audioRecorder.stopRecording()

            // 1. Gather all captured PCM audio samples
            val totalSamples = synchronized(recordedChunks) {
                recordedChunks.sumOf { it.size }
            }

            if (totalSamples < 3200) { // Less than 200ms of audio
                withContext(Dispatchers.Main) {
                    FloatingVoiceController.showError("কোনো কথা শোনা যায়নি")
                    delay(1200)
                    FloatingVoiceController.resetToIdle()
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                return@launch
            }

            val fullPcm = ShortArray(totalSamples)
            var offset = 0
            synchronized(recordedChunks) {
                for (chunk in recordedChunks) {
                    System.arraycopy(chunk, 0, fullPcm, offset, chunk.size)
                    offset += chunk.size
                }
                recordedChunks.clear()
            }

            try {
                // 2. Direct high-speed in-memory transcription: ZERO disk writing, ZERO MediaCodec decoding!
                transcriptAccumulator.clear()
                FloatingVoiceEngineHolder.audioFileProcessor.transcribeAudio(
                    samples = fullPcm,
                    tokenizer = FloatingVoiceEngineHolder.tokenizer,
                    accumulator = transcriptAccumulator
                )

                val textToInsert = transcriptAccumulator.finalTranscript.trim()
                if (textToInsert.isNotBlank()) {
                    try {
                        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                        val clip = ClipData.newPlainText("Bengali Speech", textToInsert)
                        clipboard?.setPrimaryClip(clip)
                    } catch (e: Exception) {
                        Log.w(TAG, "Error copying to clipboard", e)
                    }

                    withContext(Dispatchers.Main) {
                        FloatingVoiceController.emitFinalizedText(textToInsert)
                        delay(600)
                        FloatingVoiceController.resetToIdle()
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        FloatingVoiceController.showError("কোনো কথা বোঝা যায়নি")
                        delay(1200)
                        FloatingVoiceController.resetToIdle()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "In-memory transcription error", e)
                withContext(Dispatchers.Main) {
                    FloatingVoiceController.showError("ট্রান্সক্রিপশন ত্রুটি")
                    delay(1200)
                    FloatingVoiceController.resetToIdle()
                }
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Bangla Voice Typing",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows recording indicator while voice typing is active"
                enableLights(false)
                enableVibration(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        audioRecorder.stopRecording()
        // Note: FloatingVoiceEngineHolder is intentionally kept warm so subsequent voice typing taps remain instantaneous!
        serviceScope.cancel()
        Log.i(TAG, "FloatingVoiceTypingService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
