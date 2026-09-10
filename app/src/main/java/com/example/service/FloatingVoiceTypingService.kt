package com.example.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import com.example.audio.AudioRecorderManager
import com.example.dsp.MelSpectrogramPreprocessor
import com.example.engine.CtcDecoder
import com.example.engine.OnnxAsrEngine
import com.example.engine.TranscriptAccumulator
import com.example.floating.FloatingUiState
import com.example.floating.FloatingVoiceController
import com.example.model.ModelManager
import com.example.tokenizer.SentencePieceTokenizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

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

    // Reusing the existing DSP, AudioRecorderManager, OnnxAsrEngine, and CtcDecoder
    private lateinit var audioRecorder: AudioRecorderManager
    private val preprocessor = MelSpectrogramPreprocessor()
    private val onnxEngine = OnnxAsrEngine()
    private val ctcDecoder = CtcDecoder(blankIndex = 128)
    private var tokenizer: SentencePieceTokenizer? = null
    private val transcriptAccumulator = TranscriptAccumulator()

    private val isInferring = AtomicBoolean(false)
    private var recordingJob: Job? = null

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
                processAudioWindow(audioWindow, isEndOfUtterance)
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
            // Check if model and tokenizer exist
            val modelManager = ModelManager(applicationContext)
            val modelFile = modelManager.modelFile
            val tokenizerFile = modelManager.tokenizerFile

            if (!modelFile.exists() || !tokenizerFile.exists()) {
                withContext(Dispatchers.Main) {
                    FloatingVoiceController.showError("মডেল ও টোকেনাইজার নেই")
                }
                stopSelf()
                return@launch
            }

            // Ensure model & tokenizer are loaded
            if (!onnxEngine.isLoaded) {
                val modelResult = onnxEngine.loadModel(modelFile, numThreads = 2)
                if (modelResult.isFailure) {
                    withContext(Dispatchers.Main) {
                        FloatingVoiceController.showError("মডেল লোড ব্যর্থ হয়েছে")
                    }
                    stopSelf()
                    return@launch
                }
            }

            if (tokenizer == null) {
                try {
                    val tok = SentencePieceTokenizer.fromFile(tokenizerFile)
                    tokenizer = tok
                    ctcDecoder.updateBlankIndexFromVocab(tok.vocabSize, 129)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load tokenizer", e)
                    withContext(Dispatchers.Main) {
                        FloatingVoiceController.showError("টোকেনাইজার লোড ব্যর্থ")
                    }
                    stopSelf()
                    return@launch
                }
            }

            transcriptAccumulator.clear()

            withContext(Dispatchers.Main) {
                FloatingVoiceController.setUiState(FloatingUiState.RECORDING, "শুনছি...")
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
                FloatingVoiceController.setUiState(FloatingUiState.PROCESSING, "প্রক্রিয়াকরণ...")
            }

            audioRecorder.stopRecording()
            val flushedSegment = transcriptAccumulator.flushOnStop()

            val textToInsert = if (flushedSegment != null && flushedSegment.text.isNotBlank()) {
                flushedSegment.text.trim()
            } else {
                transcriptAccumulator.finalTranscript.trim()
            }

            if (textToInsert.isNotBlank()) {
                withContext(Dispatchers.Main) {
                    FloatingVoiceController.emitFinalizedText(textToInsert)
                }
            } else {
                withContext(Dispatchers.Main) {
                    FloatingVoiceController.showError("কোনো কথা শোনা যায়নি")
                    kotlinx.coroutines.delay(1200)
                    FloatingVoiceController.resetToIdle()
                }
            }

            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun processAudioWindow(audioWindow: ShortArray, isEndOfUtterance: Boolean) {
        if (!isInferring.compareAndSet(false, true)) return

        serviceScope.launch(Dispatchers.Default) {
            try {
                // 1. Preprocessing
                val prepResult = preprocessor.process(audioWindow)
                if (prepResult.numFrames <= 0 || prepResult.isSilence) {
                    return@launch
                }

                // 2. ONNX Inference
                val inferResult = onnxEngine.runInference(prepResult)
                if (inferResult.isFailure) return@launch
                val inference = inferResult.getOrThrow()

                // 3. CTC Decoding
                val decodeResult = ctcDecoder.decode(
                    logprobs = inference.logprobs,
                    numFrames = inference.numFrames,
                    numClasses = inference.numClasses,
                    tokenizer = tokenizer
                )
                val decodedText = decodeResult.text

                if (isEndOfUtterance) {
                    // Only committed final text segment is eligible for insertion
                    val committedSegment = transcriptAccumulator.commitFinal(
                        rawText = decodedText,
                        startMs = 0L,
                        endMs = 0L
                    )
                    val committedText = committedSegment?.text ?: ""
                    if (committedText.isNotBlank()) {
                        withContext(Dispatchers.Main) {
                            FloatingVoiceController.setLivePreview(committedText)
                        }
                    }
                } else {
                    // Update live preview only, NEVER insert intermediate hypotheses!
                    val live = transcriptAccumulator.updateLive(decodedText)
                    withContext(Dispatchers.Main) {
                        FloatingVoiceController.setLivePreview(live)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio window processing error", e)
            } finally {
                isInferring.set(false)
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
        onnxEngine.release()
        serviceScope.cancel()
        Log.i(TAG, "FloatingVoiceTypingService destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
