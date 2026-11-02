package org.roxycode.app.service;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.google.genai.types.Blob;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.roxycode.app.model.AppSettings;

class VoiceServiceTest {

    private static final String BT3 = String.valueOf((char) 96).repeat(3);

    private SettingsService settingsService;
    private AppSettings settings;
    private VoiceService voiceService;

    @BeforeEach
    void setUp() {
        settingsService = mock(SettingsService.class);
        settings = new AppSettings();
        settings.setGeminiApiKey("test-api-key");
        settings.setTtsVoice("Aoede");
        when(settingsService.getSettings()).thenReturn(settings);
        voiceService = new VoiceService(settingsService);
    }

    @AfterEach
    void tearDown() {
        voiceService.close();
    }

    @Test
    void testSanitizeMarkdownForSpeechStripsFormattingCodeBlocksAndCapsLength() {
        assertEquals("", VoiceService.sanitizeMarkdownForSpeech(null));
        assertEquals("", VoiceService.sanitizeMarkdownForSpeech("   "));

        String markdown = "## Architecture Summary\n"
                + "- Inspect [PromptService](src/main/java/PromptService.java) and <b>ChatPanel</b>.\n" + BT3
                + "java\nSystem.out.println('hello');\n" + BT3 + "\n" + "| Header 1 | Header 2 |\n" + "|---|---|\n"
                + "| Cell A | Cell B |\n" + "> Use *early* returns.";

        String sanitized = VoiceService.sanitizeMarkdownForSpeech(markdown);
        assertTrue(sanitized.contains("Architecture Summary"));
        assertTrue(sanitized.contains("Inspect PromptService and ChatPanel."));
        assertTrue(sanitized.contains("(See code block in chat.)"));
        assertTrue(sanitized.contains("Header 1 Header 2"));
        assertTrue(sanitized.contains("Cell A Cell B"));
        assertTrue(sanitized.contains("Use early returns."));
        assertFalse(sanitized.contains("System.out.println"));
        assertFalse(sanitized.contains("src/main/java/PromptService.java"));
        assertFalse(sanitized.contains("<b>"));
        assertFalse(sanitized.contains("##"));

        String longInput = "word ".repeat(500);
        String capped = VoiceService.sanitizeMarkdownForSpeech(longInput);
        assertTrue(capped.length() <= VoiceService.MAX_SPEECH_CHARS);
    }

    @Test
    void testMicrophoneRecordingEncodesValid16kHzMonoWavAndHandlesLineErrors() throws Exception {
        byte[] samplePcm = new byte[3200];
        for (int i = 0; i < samplePcm.length; i++) {
            samplePcm[i] = (byte) (i & 0xFF);
        }
        CountDownLatch chunkReadLatch = new CountDownLatch(1);
        AtomicBoolean lineClosed = new AtomicBoolean(false);

        voiceService.setCaptureLineSupplier(format -> new VoiceService.AudioCaptureLine() {
            private int offset = 0;

            @Override
            public void start() {
            }

            @Override
            public int read(byte[] buffer, int off, int len) {
                if (offset >= samplePcm.length) {
                    chunkReadLatch.countDown();
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return 0;
                }
                int toCopy = Math.min(len, samplePcm.length - offset);
                System.arraycopy(samplePcm, offset, buffer, off, toCopy);
                offset += toCopy;
                if (offset >= samplePcm.length) {
                    chunkReadLatch.countDown();
                }
                return toCopy;
            }

            @Override
            public void stop() {
            }

            @Override
            public void close() {
                lineClosed.set(true);
            }
        });

        voiceService.startRecording();
        assertTrue(chunkReadLatch.await(5, TimeUnit.SECONDS));
        byte[] wavBytes = voiceService.stopRecordingAndGetWav();
        assertFalse(voiceService.isRecording());
        assertTrue(lineClosed.get());
        assertTrue(wavBytes.length > samplePcm.length);
        assertEquals("RIFF", new String(wavBytes, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("WAVE", new String(wavBytes, 8, 4, StandardCharsets.US_ASCII));

        try (AudioInputStream ais = AudioSystem.getAudioInputStream(new ByteArrayInputStream(wavBytes))) {
            AudioFormat format = ais.getFormat();
            assertEquals(VoiceService.CAPTURE_SAMPLE_RATE, format.getSampleRate(), 0.01f);
            assertEquals(VoiceService.SAMPLE_SIZE_BITS, format.getSampleSizeInBits());
            assertEquals(VoiceService.CHANNELS, format.getChannels());
            byte[] decodedPcm = ais.readAllBytes();
            assertArrayEquals(samplePcm, decodedPcm);
        }

        voiceService.setCaptureLineSupplier(format -> {
            throw new LineUnavailableException("No mic hardware");
        });
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> voiceService.startRecording());
        assertTrue(ex.getMessage().contains("Microphone is unavailable"));
    }

    @Test
    void testTranscribeAudioAndApiKeyValidation() {
        assertEquals("", voiceService.transcribeAudio(null));
        assertEquals("", voiceService.transcribeAudio(new byte[0]));

        AtomicReference<String> capturedKey = new AtomicReference<>();
        AtomicReference<String> capturedModel = new AtomicReference<>();
        AtomicReference<byte[]> capturedWav = new AtomicReference<>();
        voiceService.setSttClient((apiKey, modelName, wavBytes, prompt) -> {
            capturedKey.set(apiKey);
            capturedModel.set(modelName);
            capturedWav.set(wavBytes);
            assertEquals(VoiceService.TRANSCRIBE_PROMPT, prompt);
            return "  Refactor ChatController.finishTurn  ";
        });

        byte[] dummyWav = new byte[]{1, 2, 3, 4};
        String transcript = voiceService.transcribeAudio(dummyWav);
        assertEquals("Refactor ChatController.finishTurn", transcript);
        assertEquals("test-api-key", capturedKey.get());
        assertEquals("gemini-3.5-transcribe", VoiceService.STT_MODEL);
        assertEquals(VoiceService.STT_MODEL, capturedModel.get());
        assertArrayEquals(dummyWav, capturedWav.get());

        settings.setSttModel("custom-stt-model");
        voiceService.transcribeAudio(dummyWav);
        assertEquals("custom-stt-model", capturedModel.get());

        settings.setGeminiApiKey("   ");
        assertThrows(IllegalStateException.class, () -> voiceService.transcribeAudio(dummyWav));
    }

    @Test
    void testSpeakAsyncStreamsPcmAndSupportsStopPlaybackCancellation() throws Exception {
        byte[] expectedPcm = new byte[]{10, 20, 30, 40, 50, 60};
        GenerateContentResponse mockResponse = GenerateContentResponse.builder()
                .candidates(List.of(Candidate.builder().content(Content.builder()
                        .parts(List.of(Part.builder()
                                .inlineData(Blob.builder().mimeType("audio/L16;rate=24000").data(expectedPcm).build())
                                .build()))
                        .build()).build()))
                .build();
        assertArrayEquals(expectedPcm, VoiceService.extractInlineAudioBytes(mockResponse));
        assertEquals(0, VoiceService.extractInlineAudioBytes(null).length);

        ByteArrayOutputStream playedBytes = new ByteArrayOutputStream();
        List<String> lifecycleEvents = new java.util.ArrayList<>();
        AtomicReference<String> usedVoice = new AtomicReference<>();

        AtomicReference<String> usedTtsModel = new AtomicReference<>();
        voiceService.setTtsClient((apiKey, ttsModel, voiceName, text) -> {
            assertEquals("gemini-3.8-flash-lite-tts", VoiceService.TTS_MODEL);
            usedTtsModel.set(ttsModel);
            usedVoice.set(voiceName);
            return expectedPcm;
        });
        voiceService.setPlaybackLineSupplier(format -> new VoiceService.AudioPlaybackLine() {
            @Override
            public void start() {
                lifecycleEvents.add("start");
            }

            @Override
            public int write(byte[] buffer, int offset, int length) {
                playedBytes.write(buffer, offset, length);
                return length;
            }

            @Override
            public void drain() {
                lifecycleEvents.add("drain");
            }

            @Override
            public void stop() {
                lifecycleEvents.add("stop");
            }

            @Override
            public void close() {
                lifecycleEvents.add("close");
            }
        });

        AtomicBoolean started = new AtomicBoolean(false);
        AtomicBoolean finished = new AtomicBoolean(false);
        CompletableFuture<Void> future = voiceService.speakAsync("Hello **Roxy**!", () -> started.set(true),
                () -> finished.set(true), err -> fail("Unexpected error: " + err));
        future.get(5, TimeUnit.SECONDS);

        assertTrue(started.get());
        assertTrue(finished.get());
        assertEquals(List.of("start", "drain", "stop", "close"), lifecycleEvents);
        assertEquals(VoiceService.TTS_MODEL, usedTtsModel.get());
        assertEquals("Aoede", usedVoice.get());
        assertArrayEquals(VoiceService.preparePcmForPlayback(expectedPcm), playedBytes.toByteArray());

        CountDownLatch writeStarted = new CountDownLatch(1);
        CountDownLatch allowWriteFinish = new CountDownLatch(1);
        AtomicBoolean stoppedLine = new AtomicBoolean(false);

        voiceService.setTtsClient((apiKey, ttsModel, voiceName, text) -> new byte[8192]);
        voiceService.setPlaybackLineSupplier(format -> new VoiceService.AudioPlaybackLine() {
            @Override
            public void start() {
            }

            @Override
            public int write(byte[] buffer, int offset, int length) {
                writeStarted.countDown();
                try {
                    allowWriteFinish.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return length;
            }

            @Override
            public void drain() {
            }

            @Override
            public void stop() {
                stoppedLine.set(true);
                allowWriteFinish.countDown();
            }

            @Override
            public void close() {
            }
        });

        CompletableFuture<Void> cancelFuture = voiceService.speakAsync("Streaming response to interrupt", null, null,
                null);
        assertTrue(writeStarted.await(5, TimeUnit.SECONDS));
        assertTrue(voiceService.isPlaying());
        voiceService.stopPlayback();
        assertFalse(voiceService.isPlaying());
        assertTrue(stoppedLine.get());
        cancelFuture.exceptionally(ex -> null).get(5, TimeUnit.SECONDS);
    }

    @Test
    void testPreparePcmForPlaybackUnwrapsWavAlignsFadesTailAndAppendsSilence() {
        assertEquals(0, VoiceService.preparePcmForPlayback(null).length);
        assertEquals(0, VoiceService.preparePcmForPlayback(new byte[0]).length);
        assertEquals(0, VoiceService.preparePcmForPlayback(new byte[]{42}).length);

        int sampleCount = 500;
        byte[] rawPcmWithOddByte = new byte[sampleCount * 2 + 1];
        for (int i = 0; i < sampleCount; i++) {
            short amplitude = 10000;
            rawPcmWithOddByte[i * 2] = (byte) (amplitude & 0xFF);
            rawPcmWithOddByte[i * 2 + 1] = (byte) ((amplitude >> 8) & 0xFF);
        }
        rawPcmWithOddByte[rawPcmWithOddByte.length - 1] = 0x7F;

        byte[] prepared = VoiceService.preparePcmForPlayback(rawPcmWithOddByte);
        int expectedLength = sampleCount * 2 + VoiceService.PLAYBACK_TRAILING_SILENCE_BYTES;
        assertEquals(expectedLength, prepared.length);
        assertEquals(0, prepared.length % 2);

        short firstSample = readLeShort(prepared, 0);
        int preFadeSampleIdx = sampleCount - VoiceService.PLAYBACK_FADE_OUT_SAMPLES - 1;
        short lastUnfadedSample = readLeShort(prepared, preFadeSampleIdx * 2);
        short midFadeSample = readLeShort(prepared, (sampleCount - VoiceService.PLAYBACK_FADE_OUT_SAMPLES / 2) * 2);
        short finalAudioSample = readLeShort(prepared, (sampleCount - 1) * 2);

        assertEquals(10000, firstSample);
        assertEquals(10000, lastUnfadedSample);
        assertTrue(midFadeSample > 0 && midFadeSample < 10000);
        assertEquals(0, finalAudioSample);

        for (int i = sampleCount * 2; i < prepared.length; i++) {
            assertEquals(0, prepared[i]);
        }

        byte[] alignedPcm = new byte[sampleCount * 2];
        System.arraycopy(rawPcmWithOddByte, 0, alignedPcm, 0, alignedPcm.length);
        byte[] wavWrapped = VoiceService.encodePcmToWav(alignedPcm, VoiceService.PLAYBACK_FORMAT);
        byte[] preparedFromWav = VoiceService.preparePcmForPlayback(wavWrapped);
        assertArrayEquals(prepared, preparedFromWav);
    }

    private static short readLeShort(byte[] bytes, int byteOffset) {
        int low = bytes[byteOffset] & 0xFF;
        int high = bytes[byteOffset + 1];
        return (short) ((high << 8) | low);
    }
}
