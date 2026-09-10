package com.example

import com.example.audio.AudioFileDecoder
import com.example.floating.FloatingUiState
import com.example.floating.FloatingVoiceController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import kotlin.math.sin

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioFileBatchTranscriptionTest {

    @Test
    fun testWavFileWriteAndDecodeAndAutoDelete() {
        // 1. Generate 1 second of 16kHz mono 440Hz sine wave PCM samples
        val sampleRate = 16000
        val numSamples = sampleRate // 1 second
        val pcm = ShortArray(numSamples) { i ->
            val angle = 2.0 * Math.PI * 440.0 * i / sampleRate
            (sin(angle) * 16000).toInt().toShort()
        }

        val tempWavFile = File.createTempFile("test_voice_record_", ".wav")
        try {
            // 2. Write to WAV audio file
            AudioFileDecoder.writeWavFile(
                pcmSamples = pcm,
                outputFile = tempWavFile,
                sampleRate = sampleRate,
                channels = 1
            )

            assertTrue("WAV file must exist", tempWavFile.exists())
            assertEquals("WAV file size must be 44 header + 32000 data bytes", 32044L, tempWavFile.length())

            // 3. Decode audio file using AudioFileDecoder
            val decoded = AudioFileDecoder.decodeAudioFile(tempWavFile)
            assertNotNull(decoded)
            assertEquals("Decoded sample rate must be 16000", 16000, decoded.originalSampleRate)
            assertEquals("Decoded channels must be 1 (mono)", 1, decoded.originalChannels)
            assertEquals("Decoded sample count must match input", numSamples, decoded.samples.size)
            assertEquals("Decoded duration must be 1000ms", 1000L, decoded.durationMs)

            // Verify samples fidelity
            for (i in 0 until 100) {
                assertEquals(pcm[i], decoded.samples[i])
            }
        } finally {
            // 4. Test auto-deletion
            val deleted = tempWavFile.delete()
            assertTrue("Temporary audio file must be successfully deleted", deleted)
            assertFalse("File must no longer exist after deletion", tempWavFile.exists())
        }
    }

    @Test
    fun testFloatingIconStateTransitions_NoLivePreviewTextLeaked() {
        FloatingVoiceController.resetToIdle()
        assertEquals(FloatingUiState.IDLE, FloatingVoiceController.uiState.value)
        assertEquals("", FloatingVoiceController.livePreviewText.value)

        // Start recording
        FloatingVoiceController.setUiState(FloatingUiState.RECORDING, null)
        assertEquals(FloatingUiState.RECORDING, FloatingVoiceController.uiState.value)
        // Ensure live preview text remains blank
        assertEquals("", FloatingVoiceController.livePreviewText.value)

        // Finish recording and transition to processing
        FloatingVoiceController.setUiState(FloatingUiState.PROCESSING, null)
        assertEquals(FloatingUiState.PROCESSING, FloatingVoiceController.uiState.value)
        assertEquals("", FloatingVoiceController.livePreviewText.value)

        // Reset to idle
        FloatingVoiceController.resetToIdle()
        assertEquals(FloatingUiState.IDLE, FloatingVoiceController.uiState.value)
        assertEquals("", FloatingVoiceController.livePreviewText.value)
    }
}
