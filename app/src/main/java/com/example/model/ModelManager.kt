package com.example.model

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.text.DecimalFormat
import java.util.concurrent.TimeUnit

/**
 * Handles persistent storage, validation, stream-copying, auto-downloading, and status of
 * the Android-compatible ONNX model and SentencePiece tokenizer files.
 */
class ModelManager(private val context: Context) {

    companion object {
        const val MODEL_DIR_NAME = "models"
        const val MODEL_FILE_NAME = "kazalbrur_android_compatible.onnx"
        const val TOKENIZER_FILE_NAME = "tokenizer.model"

        const val MODEL_DOWNLOAD_URL =
            "https://github.com/jessicaalbert1234567-netizen/Voice-type/releases/download/bangla-conformer-v1.0.0/kazalbrur_android_compatible.onnx"
        const val TOKENIZER_DOWNLOAD_URL =
            "https://github.com/jessicaalbert1234567-netizen/Voice-type/releases/download/bangla-conformer-v1.0.0/tokenizer.model"

        const val EXPECTED_MODEL_BYTES = 144_678_833L
        const val EXPECTED_TOKENIZER_BYTES = 239_478L
        const val EXPECTED_TOTAL_BYTES = 144_918_311L

        private const val BUFFER_SIZE = 64 * 1024 // 64 KB streaming buffer
    }

    data class DownloadProgress(
        val isDownloading: Boolean = false,
        val progressFraction: Float = 0f,
        val percentage: Int = 0,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = 0L,
        val statusMessage: String = "",
        val error: String? = null
    )

    data class ImportProgress(
        val isImporting: Boolean = false,
        val fileName: String = "",
        val bytesCopied: Long = 0L,
        val totalBytes: Long = -1L,
        val progressFraction: Float = 0f,
        val statusMessage: String = ""
    )

    private val _importProgress = MutableStateFlow(ImportProgress())
    val importProgress = _importProgress.asStateFlow()

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    val downloadProgress = _downloadProgress.asStateFlow()

    private val okHttpClient by lazy {
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(45, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .build()
    }

    val modelsDir: File
        get() = File(context.filesDir, MODEL_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }

    val modelFile: File
        get() = File(modelsDir, MODEL_FILE_NAME)

    val tokenizerFile: File
        get() = File(modelsDir, TOKENIZER_FILE_NAME)

    fun isModelReady(): Boolean {
        val file = modelFile
        return file.exists() && file.length() > 50_000_000 // Android compatible ONNX model is ~144MB
    }

    fun isTokenizerReady(): Boolean {
        val file = tokenizerFile
        return file.exists() && file.length() > 50_000 // Tokenizer is ~239KB
    }

    fun getModelSizeBytes(): Long = if (modelFile.exists()) modelFile.length() else 0L

    fun getTokenizerSizeBytes(): Long = if (tokenizerFile.exists()) tokenizerFile.length() else 0L

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "0 MB"
        val df = DecimalFormat("#.##")
        return when {
            bytes >= 1024 * 1024 -> "${df.format(bytes.toDouble() / (1024.0 * 1024.0))} MB"
            bytes >= 1024 -> "${df.format(bytes.toDouble() / 1024.0)} KB"
            else -> "$bytes B"
        }
    }

    /**
     * Stream-copies an imported model file from SAF URI into internal storage.
     * Uses buffered streaming to avoid loading large files into RAM.
     */
    suspend fun importModelFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        copyUriToFile(
            uri = uri,
            targetFile = modelFile,
            displayName = MODEL_FILE_NAME
        )
    }

    /**
     * Stream-copies an imported tokenizer file from SAF URI into internal storage.
     */
    suspend fun importTokenizerFromUri(uri: Uri): Result<File> = withContext(Dispatchers.IO) {
        copyUriToFile(
            uri = uri,
            targetFile = tokenizerFile,
            displayName = TOKENIZER_FILE_NAME
        )
    }

    private suspend fun copyUriToFile(
        uri: Uri,
        targetFile: File,
        displayName: String
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            _importProgress.value = ImportProgress(
                isImporting = true,
                fileName = displayName,
                statusMessage = "Opening file stream..."
            )

            val contentResolver = context.contentResolver
            var totalBytes = -1L

            // Attempt to query file size
            try {
                contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val sizeIndex = cursor.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (sizeIndex != -1 && cursor.moveToFirst()) {
                        totalBytes = cursor.getLong(sizeIndex)
                    }
                }
            } catch (_: Exception) {}

            val tempFile = File(modelsDir, "${targetFile.name}.tmp")
            if (tempFile.exists()) tempFile.delete()

            contentResolver.openInputStream(uri)?.use { inputStream ->
                FileOutputStream(tempFile).use { outputStream ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int
                    var totalCopied = 0L

                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalCopied += bytesRead

                        val fraction = if (totalBytes > 0) {
                            (totalCopied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
                        } else {
                            0f
                        }

                        _importProgress.value = ImportProgress(
                            isImporting = true,
                            fileName = displayName,
                            bytesCopied = totalCopied,
                            totalBytes = totalBytes,
                            progressFraction = fraction,
                            statusMessage = "Copying: ${formatFileSize(totalCopied)}" +
                                    if (totalBytes > 0) " / ${formatFileSize(totalBytes)}" else "..."
                        )
                    }
                    outputStream.flush()
                }
            } ?: return@withContext Result.failure(Exception("Could not open input stream for URI: $uri"))

            // Atomic rename from tmp to destination
            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                // Fallback copy if rename fails
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            _importProgress.value = ImportProgress(
                isImporting = false,
                fileName = displayName,
                statusMessage = "Successfully imported $displayName"
            )

            Result.success(targetFile)
        } catch (e: Exception) {
            _importProgress.value = ImportProgress(
                isImporting = false,
                fileName = displayName,
                statusMessage = "Failed: ${e.localizedMessage}"
            )
            Result.failure(e)
        }
    }

    fun deleteModel(): Boolean {
        return if (modelFile.exists()) modelFile.delete() else true
    }

    fun deleteTokenizer(): Boolean {
        return if (tokenizerFile.exists()) tokenizerFile.delete() else true
    }

    /**
     * Downloads required files (SentencePiece tokenizer and Android-compatible ONNX model)
     * with streaming progress reporting.
     *
     * In accordance with requirements:
     * - Does not expose internal file names or types to status messages.
     * - Only downloads once; skips already downloaded and validated files.
     * - Reports overall progress fraction (0f..1f), percentage (0..100), and byte counts.
     */
    suspend fun downloadRequiredFiles(
        onProgress: (DownloadProgress) -> Unit = {}
    ): Result<Unit> = withContext(Dispatchers.IO) {
        if (isModelReady() && isTokenizerReady()) {
            val completed = DownloadProgress(
                isDownloading = false,
                progressFraction = 1f,
                percentage = 100,
                bytesDownloaded = EXPECTED_TOTAL_BYTES,
                totalBytes = EXPECTED_TOTAL_BYTES,
                statusMessage = "Ready"
            )
            _downloadProgress.value = completed
            onProgress(completed)
            return@withContext Result.success(Unit)
        }

        val totalExpected = EXPECTED_TOTAL_BYTES
        var initialProgress = DownloadProgress(
            isDownloading = true,
            progressFraction = 0f,
            percentage = 0,
            bytesDownloaded = 0L,
            totalBytes = totalExpected,
            statusMessage = "Downloading required files..."
        )
        _downloadProgress.value = initialProgress
        onProgress(initialProgress)

        var accumulatedBytes = 0L

        // 1. Download Tokenizer if needed
        if (!isTokenizerReady()) {
            val tokResult = downloadFileWithProgress(
                url = TOKENIZER_DOWNLOAD_URL,
                targetFile = tokenizerFile,
                startOffsetBytes = accumulatedBytes,
                grandTotalBytes = totalExpected
            ) { bytes, total, frac, pct ->
                val p = DownloadProgress(
                    isDownloading = true,
                    progressFraction = frac,
                    percentage = pct,
                    bytesDownloaded = bytes,
                    totalBytes = total,
                    statusMessage = "Downloading required files..."
                )
                _downloadProgress.value = p
                onProgress(p)
            }

            if (tokResult.isFailure) {
                val err = tokResult.exceptionOrNull()?.localizedMessage ?: "Download error"
                val failed = DownloadProgress(
                    isDownloading = false,
                    error = err,
                    statusMessage = "Download failed: $err"
                )
                _downloadProgress.value = failed
                onProgress(failed)
                return@withContext Result.failure(tokResult.exceptionOrNull() ?: Exception(err))
            }
            accumulatedBytes += tokenizerFile.length()
        } else {
            accumulatedBytes += tokenizerFile.length()
        }

        // 2. Download ONNX Model if needed
        if (!isModelReady()) {
            val modelResult = downloadFileWithProgress(
                url = MODEL_DOWNLOAD_URL,
                targetFile = modelFile,
                startOffsetBytes = accumulatedBytes,
                grandTotalBytes = totalExpected
            ) { bytes, total, frac, pct ->
                val p = DownloadProgress(
                    isDownloading = true,
                    progressFraction = frac,
                    percentage = pct,
                    bytesDownloaded = bytes,
                    totalBytes = total,
                    statusMessage = "Downloading required files..."
                )
                _downloadProgress.value = p
                onProgress(p)
            }

            if (modelResult.isFailure) {
                val err = modelResult.exceptionOrNull()?.localizedMessage ?: "Download error"
                val failed = DownloadProgress(
                    isDownloading = false,
                    error = err,
                    statusMessage = "Download failed: $err"
                )
                _downloadProgress.value = failed
                onProgress(failed)
                return@withContext Result.failure(modelResult.exceptionOrNull() ?: Exception(err))
            }
        }

        val completed = DownloadProgress(
            isDownloading = false,
            progressFraction = 1f,
            percentage = 100,
            bytesDownloaded = totalExpected,
            totalBytes = totalExpected,
            statusMessage = "Completed"
        )
        _downloadProgress.value = completed
        onProgress(completed)

        Result.success(Unit)
    }

    private suspend fun downloadFileWithProgress(
        url: String,
        targetFile: File,
        startOffsetBytes: Long,
        grandTotalBytes: Long,
        onProgress: (bytesDownloaded: Long, totalBytes: Long, fraction: Float, percentage: Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        var response: Response? = null
        val tempFile = File(modelsDir, "${targetFile.name}.download_tmp")
        try {
            if (tempFile.exists()) tempFile.delete()

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "AunikVoiceTyping/1.3")
                .build()

            response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                return@withContext Result.failure(IOException("Server error HTTP ${response.code}"))
            }

            val body = response.body ?: return@withContext Result.failure(IOException("Empty response body"))

            var bytesCopied = 0L
            var lastUpdateMs = 0L

            body.byteStream().use { input ->
                FileOutputStream(tempFile).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var bytesRead: Int

                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        bytesCopied += bytesRead

                        val totalCopied = startOffsetBytes + bytesCopied
                        val frac = if (grandTotalBytes > 0) {
                            (totalCopied.toFloat() / grandTotalBytes.toFloat()).coerceIn(0f, 1f)
                        } else 0f
                        val pct = (frac * 100).toInt().coerceIn(0, 100)

                        val now = System.currentTimeMillis()
                        if (now - lastUpdateMs >= 100) {
                            lastUpdateMs = now
                            onProgress(totalCopied, grandTotalBytes, frac, pct)
                        }
                    }
                    output.flush()
                }
            }

            val finalTotal = startOffsetBytes + bytesCopied
            val finalFrac = if (grandTotalBytes > 0) {
                (finalTotal.toFloat() / grandTotalBytes.toFloat()).coerceIn(0f, 1f)
            } else 0f
            onProgress(finalTotal, grandTotalBytes, finalFrac, (finalFrac * 100).toInt().coerceIn(0, 100))

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }

            Result.success(targetFile)
        } catch (e: Exception) {
            if (tempFile.exists()) tempFile.delete()
            Result.failure(e)
        } finally {
            response?.close()
        }
    }
}
