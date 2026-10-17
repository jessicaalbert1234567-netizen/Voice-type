package com.example.floating

import android.content.Context
import android.util.Log
import com.example.dsp.MelSpectrogramPreprocessor
import com.example.engine.AudioFileProcessor
import com.example.engine.CtcDecoder
import com.example.engine.OnnxAsrEngine
import com.example.model.ModelManager
import com.example.tokenizer.SentencePieceTokenizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Singleton holder keeping the ONNX ASR engine and SentencePiece tokenizer pre-warmed in memory
 * while the floating voice typing overlay is active.
 *
 * This completely eliminates the 2-3 second cold-start delay when tapping the floating mic button,
 * allowing instant microphone activation and sub-second transcription.
 */
object FloatingVoiceEngineHolder {
    private const val TAG = "FloatingVoiceEngine"

    val preprocessor = MelSpectrogramPreprocessor()
    val onnxEngine = OnnxAsrEngine()
    val ctcDecoder = CtcDecoder(blankIndex = 128)
    var tokenizer: SentencePieceTokenizer? = null
        private set

    val audioFileProcessor by lazy { AudioFileProcessor(preprocessor, onnxEngine, ctcDecoder) }

    private val warmupMutex = Mutex()

    @Volatile
    var isWarmedUp: Boolean = false
        private set

    /**
     * Preloads the ONNX model and SentencePiece tokenizer into memory in the background.
     * Safe to call multiple times; returns immediately if already loaded.
     */
    suspend fun warmUp(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isWarmedUp && onnxEngine.isLoaded && tokenizer != null) {
            return@withContext true
        }

        warmupMutex.withLock {
            if (isWarmedUp && onnxEngine.isLoaded && tokenizer != null) {
                return@withContext true
            }

            try {
                val modelManager = ModelManager(context.applicationContext)
                val modelFile = modelManager.modelFile
                val tokenizerFile = modelManager.tokenizerFile

                if (!modelFile.exists() || !tokenizerFile.exists()) {
                    Log.w(TAG, "Model or tokenizer file missing on disk; cannot pre-warm.")
                    return@withContext false
                }

                if (!onnxEngine.isLoaded) {
                    val startTime = System.currentTimeMillis()
                    Log.i(TAG, "Pre-warming ONNX ASR engine...")
                    val result = onnxEngine.loadModel(modelFile, numThreads = 2)
                    if (result.isFailure) {
                        Log.e(TAG, "Failed to load ONNX model during warm-up", result.exceptionOrNull())
                        return@withContext false
                    }
                    Log.i(TAG, "ONNX model loaded in ${System.currentTimeMillis() - startTime}ms")
                }

                if (tokenizer == null) {
                    val startTime = System.currentTimeMillis()
                    Log.i(TAG, "Pre-warming SentencePiece tokenizer...")
                    val tok = SentencePieceTokenizer.fromFile(tokenizerFile)
                    tokenizer = tok
                    ctcDecoder.updateBlankIndexFromVocab(tok.vocabSize, 129)
                    Log.i(TAG, "Tokenizer loaded in ${System.currentTimeMillis() - startTime}ms")
                }

                isWarmedUp = true
                Log.i(TAG, "Floating Voice ASR Engine is fully pre-warmed and ready for instant capture!")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during ASR engine warm-up", e)
                false
            }
        }
    }

    /**
     * Releases ONNX runtime session and tokenizer memory when the floating overlay is dismissed.
     */
    fun release() {
        try {
            onnxEngine.release()
            tokenizer = null
            isWarmedUp = false
            Log.i(TAG, "Floating Voice ASR Engine released")
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing ASR engine", e)
        }
    }
}
