package com.example.audio

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Decodes various audio formats (MP3, AAC/M4A, WAV, OGG, FLAC, MP4/3GP audio tracks)
 * from Android content URIs or local files into 16 kHz 16-bit Mono PCM samples for offline STT.
 */
object AudioFileDecoder {
    private const val TAG = "AudioFileDecoder"
    private const val TARGET_SAMPLE_RATE = 16000
    private const val TIMEOUT_US = 10000L

    data class DecodedAudio(
        val samples: ShortArray,
        val durationMs: Long,
        val originalSampleRate: Int,
        val originalChannels: Int
    )

    /**
     * Writes 16-bit PCM samples into a standard RIFF/WAVE file header + data on disk.
     */
    fun writeWavFile(
        pcmSamples: ShortArray,
        outputFile: File,
        sampleRate: Int = 16000,
        channels: Int = 1
    ) {
        val byteRate = sampleRate * channels * 2
        val totalAudioLen = pcmSamples.size * 2
        val totalDataLen = totalAudioLen + 36

        FileOutputStream(outputFile).use { out ->
            val header = ByteArray(44)
            // RIFF chunk descriptor
            header[0] = 'R'.code.toByte()
            header[1] = 'I'.code.toByte()
            header[2] = 'F'.code.toByte()
            header[3] = 'F'.code.toByte()
            header[4] = (totalDataLen and 0xff).toByte()
            header[5] = ((totalDataLen shr 8) and 0xff).toByte()
            header[6] = ((totalDataLen shr 16) and 0xff).toByte()
            header[7] = ((totalDataLen shr 24) and 0xff).toByte()
            header[8] = 'W'.code.toByte()
            header[9] = 'A'.code.toByte()
            header[10] = 'V'.code.toByte()
            header[11] = 'E'.code.toByte()
            // 'fmt ' sub-chunk
            header[12] = 'f'.code.toByte()
            header[13] = 'm'.code.toByte()
            header[14] = 't'.code.toByte()
            header[15] = ' '.code.toByte()
            header[16] = 16 // Subchunk1Size for PCM
            header[17] = 0
            header[18] = 0
            header[19] = 0
            header[20] = 1 // AudioFormat 1 = PCM
            header[21] = 0
            header[22] = channels.toByte()
            header[23] = 0
            header[24] = (sampleRate and 0xff).toByte()
            header[25] = ((sampleRate shr 8) and 0xff).toByte()
            header[26] = ((sampleRate shr 16) and 0xff).toByte()
            header[27] = ((sampleRate shr 24) and 0xff).toByte()
            header[28] = (byteRate and 0xff).toByte()
            header[29] = ((byteRate shr 8) and 0xff).toByte()
            header[30] = ((byteRate shr 16) and 0xff).toByte()
            header[31] = ((byteRate shr 24) and 0xff).toByte()
            header[32] = (channels * 2).toByte() // BlockAlign
            header[33] = 0
            header[34] = 16 // BitsPerSample
            header[35] = 0
            // 'data' sub-chunk
            header[36] = 'd'.code.toByte()
            header[37] = 'a'.code.toByte()
            header[38] = 't'.code.toByte()
            header[39] = 'a'.code.toByte()
            header[40] = (totalAudioLen and 0xff).toByte()
            header[41] = ((totalAudioLen shr 8) and 0xff).toByte()
            header[42] = ((totalAudioLen shr 16) and 0xff).toByte()
            header[43] = ((totalAudioLen shr 24) and 0xff).toByte()

            out.write(header, 0, 44)

            val byteBuf = ByteBuffer.allocate(pcmSamples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (sample in pcmSamples) {
                byteBuf.putShort(sample)
            }
            out.write(byteBuf.array())
            out.flush()
        }
    }

    /**
     * Decodes a local audio file (MP3, WAV, M4A, AAC, etc.) into 16kHz mono PCM.
     */
    fun decodeAudioFile(
        file: File,
        onProgress: (Float) -> Unit = {}
    ): DecodedAudio {
        if (!file.exists() || file.length() == 0L) {
            throw IllegalArgumentException("Audio file does not exist or is empty: ${file.absolutePath}")
        }

        // Fast path for WAV files
        if (file.name.endsWith(".wav", ignoreCase = true) && file.length() >= 44) {
            try {
                val direct = decodeWavDirect(file)
                if (direct != null && direct.samples.isNotEmpty()) {
                    onProgress(0.5f)
                    return direct
                }
            } catch (e: Exception) {
                Log.w(TAG, "Direct WAV decoding failed, falling back to MediaCodec", e)
            }
        }

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting data source for file: ${file.absolutePath}", e)
            throw IllegalArgumentException("Could not open audio file: ${e.localizedMessage}")
        }
        return decodeWithExtractor(extractor, onProgress)
    }

    /**
     * Direct RIFF/WAV parser extracting PCM samples cleanly.
     */
    fun decodeWavDirect(file: File): DecodedAudio? {
        val bytes = file.readBytes()
        if (bytes.size < 44) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        // Check RIFF header
        val riff = ByteArray(4)
        buffer.get(riff)
        if (String(riff) != "RIFF") return null

        buffer.getInt() // skip file size
        val wave = ByteArray(4)
        buffer.get(wave)
        if (String(wave) != "WAVE") return null

        var channels = 1
        var sampleRate = 16000
        var bitsPerSample = 16
        var pcmBytes: ByteArray? = null

        while (buffer.remaining() >= 8) {
            val chunkIdBytes = ByteArray(4)
            buffer.get(chunkIdBytes)
            val chunkId = String(chunkIdBytes)
            val chunkSize = buffer.getInt()

            if (chunkSize < 0 || chunkSize > buffer.remaining()) break

            when (chunkId) {
                "fmt " -> {
                    buffer.getShort() // formatTag (1 = PCM)
                    channels = buffer.getShort().toInt()
                    sampleRate = buffer.getInt()
                    buffer.getInt() // byteRate
                    buffer.getShort() // blockAlign
                    bitsPerSample = buffer.getShort().toInt()
                    val extra = chunkSize - 16
                    if (extra > 0 && extra <= buffer.remaining()) {
                        buffer.position(buffer.position() + extra)
                    }
                }
                "data" -> {
                    val data = ByteArray(chunkSize)
                    buffer.get(data)
                    pcmBytes = data
                    break
                }
                else -> {
                    buffer.position(buffer.position() + chunkSize)
                }
            }
        }

        val rawPcm = pcmBytes ?: return null
        if (bitsPerSample != 16) return null

        val shortBuf = ByteBuffer.wrap(rawPcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val decodedShorts = ShortArray(shortBuf.remaining())
        shortBuf.get(decodedShorts)

        // Mono downmix
        val monoShorts = if (channels > 1) {
            val monoLength = decodedShorts.size / channels
            val mono = ShortArray(monoLength)
            for (i in 0 until monoLength) {
                var sum = 0
                for (ch in 0 until channels) {
                    sum += decodedShorts[i * channels + ch]
                }
                mono[i] = (sum / channels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            mono
        } else {
            decodedShorts
        }

        // Resample to 16000 Hz if necessary
        val resampledShorts = if (sampleRate != TARGET_SAMPLE_RATE && sampleRate > 0) {
            val ratio = TARGET_SAMPLE_RATE.toDouble() / sampleRate.toDouble()
            val targetLength = (monoShorts.size * ratio).toInt()
            val resampled = ShortArray(targetLength)
            for (i in 0 until targetLength) {
                val origIdx = i / ratio
                val left = origIdx.toInt()
                val right = (left + 1).coerceAtMost(monoShorts.size - 1)
                val frac = origIdx - left
                val sample = ((1.0 - frac) * monoShorts[left] + frac * monoShorts[right]).toInt()
                resampled[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            resampled
        } else {
            monoShorts
        }

        val durationMs = (resampledShorts.size * 1000L) / TARGET_SAMPLE_RATE
        return DecodedAudio(
            samples = resampledShorts,
            durationMs = durationMs,
            originalSampleRate = sampleRate,
            originalChannels = channels
        )
    }

    /**
     * Extracts and decodes audio from a content Uri into a 16kHz mono 16-bit PCM ShortArray.
     */
    fun decodeAudioUri(
        context: Context,
        uri: Uri,
        onProgress: (Float) -> Unit = {}
    ): DecodedAudio {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
        } catch (e: Exception) {
            Log.e(TAG, "Error setting data source for URI: $uri", e)
            throw IllegalArgumentException("Could not open audio file: ${e.localizedMessage}")
        }
        return decodeWithExtractor(extractor, onProgress)
    }

    private fun decodeWithExtractor(
        extractor: MediaExtractor,
        onProgress: (Float) -> Unit
    ): DecodedAudio {

        var audioTrackIndex = -1
        var format: MediaFormat? = null

        for (i in 0 until extractor.trackCount) {
            val trackFormat = extractor.getTrackFormat(i)
            val mime = trackFormat.getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith("audio/")) {
                audioTrackIndex = i
                format = trackFormat
                break
            }
        }

        if (audioTrackIndex == -1 || format == null) {
            extractor.release()
            throw IllegalArgumentException("No audio track found in the selected file.")
        }

        extractor.selectTrack(audioTrackIndex)

        val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
        val originalSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) format.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100
        val originalChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 1
        val durationUs = if (format.containsKey(MediaFormat.KEY_DURATION)) format.getLong(MediaFormat.KEY_DURATION) else 0L

        Log.i(TAG, "Found audio track: mime=$mime, sampleRate=$originalSampleRate, channels=$originalChannels, duration=${durationUs / 1000}ms")

        val decoder: MediaCodec
        try {
            decoder = MediaCodec.createDecoderByType(mime)
            decoder.configure(format, null, null, 0)
            decoder.start()
        } catch (e: Exception) {
            extractor.release()
            throw IllegalStateException("Failed to initialize audio decoder for format $mime: ${e.localizedMessage}")
        }

        val rawPcmBuffer = ByteArrayOutputStream()
        val bufferInfo = MediaCodec.BufferInfo()
        var isExtractorEOS = false
        var isDecoderEOS = false

        var actualSampleRate = originalSampleRate
        var actualChannels = originalChannels

        try {
            while (!isDecoderEOS) {
                if (!isExtractorEOS) {
                    val inIndex = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val inputBuffer = decoder.getInputBuffer(inIndex)
                        if (inputBuffer != null) {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                decoder.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                isExtractorEOS = true
                            } else {
                                val sampleTime = extractor.sampleTime
                                decoder.queueInputBuffer(inIndex, 0, sampleSize, sampleTime, 0)
                                extractor.advance()

                                if (durationUs > 0) {
                                    val progress = (sampleTime.toFloat() / durationUs.toFloat()).coerceIn(0f, 0.9f)
                                    onProgress(progress * 0.5f) // Decoding is 0% to 50% of total audio prep
                                }
                            }
                        }
                    }
                }

                val outIndex = decoder.dequeueOutputBuffer(bufferInfo, TIMEOUT_US)
                if (outIndex >= 0) {
                    val outputBuffer = decoder.getOutputBuffer(outIndex)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val chunk = ByteArray(bufferInfo.size)
                        outputBuffer.get(chunk)
                        rawPcmBuffer.write(chunk)
                    }
                    decoder.releaseOutputBuffer(outIndex, false)

                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isDecoderEOS = true
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = decoder.outputFormat
                    if (newFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        actualSampleRate = newFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                    }
                    if (newFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        actualChannels = newFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    }
                    Log.i(TAG, "Audio decoder output format changed: sampleRate=$actualSampleRate, channels=$actualChannels")
                }
            }
        } finally {
            try {
                decoder.stop()
                decoder.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing decoder", e)
            }
            try {
                extractor.release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing extractor", e)
            }
        }

        val rawPcmBytes = rawPcmBuffer.toByteArray()
        if (rawPcmBytes.isEmpty()) {
            throw IllegalStateException("Decoded audio is empty.")
        }

        // Convert PCM bytes to 16-bit short samples
        val shortBuffer = ByteBuffer.wrap(rawPcmBytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val decodedShorts = ShortArray(shortBuffer.remaining())
        shortBuffer.get(decodedShorts)

        // 1. Channel downmixing to Mono if necessary
        val monoShorts: ShortArray = if (actualChannels > 1) {
            val monoLength = decodedShorts.size / actualChannels
            val mono = ShortArray(monoLength)
            for (i in 0 until monoLength) {
                var sum = 0
                for (ch in 0 until actualChannels) {
                    sum += decodedShorts[i * actualChannels + ch]
                }
                mono[i] = (sum / actualChannels).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            mono
        } else {
            decodedShorts
        }

        // 2. Resample to 16000 Hz if necessary (Linear Interpolation)
        val resampledShorts: ShortArray = if (actualSampleRate != TARGET_SAMPLE_RATE && actualSampleRate > 0) {
            val ratio = TARGET_SAMPLE_RATE.toDouble() / actualSampleRate.toDouble()
            val targetLength = (monoShorts.size * ratio).toInt()
            val resampled = ShortArray(targetLength)

            for (i in 0 until targetLength) {
                val origIdx = i / ratio
                val left = origIdx.toInt()
                val right = (left + 1).coerceAtMost(monoShorts.size - 1)
                val frac = origIdx - left

                val sample = ((1.0 - frac) * monoShorts[left] + frac * monoShorts[right]).toInt()
                resampled[i] = sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            }
            resampled
        } else {
            monoShorts
        }

        val totalDurationMs = (resampledShorts.size * 1000L) / TARGET_SAMPLE_RATE

        return DecodedAudio(
            samples = resampledShorts,
            durationMs = totalDurationMs,
            originalSampleRate = actualSampleRate,
            originalChannels = actualChannels
        )
    }
}
