package org.roxycode.app.service;

import com.google.genai.Client;
import com.google.genai.types.Blob;
import com.google.genai.types.Candidate;
import com.google.genai.types.Content;
import com.google.genai.types.GenerateContentConfig;
import com.google.genai.types.GenerateContentResponse;
import com.google.genai.types.Part;
import com.google.genai.types.PrebuiltVoiceConfig;
import com.google.genai.types.SpeechConfig;
import com.google.genai.types.VoiceConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.TargetDataLine;
import org.apache.commons.lang3.StringUtils;
import org.roxycode.app.ai.AiConfig;
import org.roxycode.app.model.AppSettings;
import org.roxycode.app.util.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

@Service
@SuppressWarnings({"PMD.NullAssignment", "PMD.CloseResource"})
public class VoiceService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(VoiceService.class);

    public static final float CAPTURE_SAMPLE_RATE = 16000.0f;
    public static final float PLAYBACK_SAMPLE_RATE = 24000.0f;
    public static final int SAMPLE_SIZE_BITS = 16;
    public static final int CHANNELS = 1;
    public static final int MAX_RECORDING_SECONDS = 120;
    public static final int MAX_RECORDING_BYTES = (int) CAPTURE_SAMPLE_RATE * (SAMPLE_SIZE_BITS / 8) * CHANNELS
            * MAX_RECORDING_SECONDS;
    public static final int MAX_SPEECH_CHARS = 1500;
    public static final String STT_MODEL = AppSettings.DEFAULT_STT_MODEL;
    public static final String TTS_MODEL = AppSettings.DEFAULT_TTS_MODEL;
    public static final String AUDIO_WAV_MIME_TYPE = "audio/wav";
    public static final String CODE_BLOCK_CUE = " (See code block in chat.) ";
    public static final String TRANSCRIBE_PROMPT = "Transcribe this developer voice recording accurately into text. "
            + "Preserve technical terms, class names, and camelCase identifiers where applicable. "
            + "Return ONLY the transcribed text without quotes or commentary. "
            + "If the audio is silent or unintelligible, return an empty string.";

    static final AudioFormat CAPTURE_FORMAT = new AudioFormat(CAPTURE_SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true,
            false);
    static final AudioFormat PLAYBACK_FORMAT = new AudioFormat(PLAYBACK_SAMPLE_RATE, SAMPLE_SIZE_BITS, CHANNELS, true,
            false);

    private static final String BS = String.valueOf((char) 92);
    private static final String BT = String.valueOf((char) 96);
    private static final int AUDIO_BUFFER_SIZE = 4096;
    private static final int WAV_HEADER_OVERHEAD = 44;
    private static final Pattern FENCED_CODE_BLOCK_PATTERN = Pattern.compile("(?s)" + BT + "{3}.*?" + BT + "{3}");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern MARKDOWN_LINK_PATTERN = Pattern
            .compile(BS + "[([^" + BS + "]]+)" + BS + "]" + BS + "([^)]+" + BS + ")");
    private static final Pattern TABLE_DIVIDER_PATTERN = Pattern
            .compile("(?m)^" + BS + "s*" + BS + "|?[" + BS + "s:-]+" + BS + "|[" + BS + "s|:-]*$");
    private static final Pattern HEADING_PREFIX_PATTERN = Pattern.compile("(?m)^" + BS + "s*[#]{1,6}" + BS + "s+");
    private static final Pattern LIST_QUOTE_PREFIX_PATTERN = Pattern.compile("(?m)^" + BS + "s*[-*>+]+" + BS + "s+");
    private static final Pattern TABLE_PIPE_PATTERN = Pattern.compile(BS + "|+");
    private static final Pattern MARKDOWN_SYMBOLS_PATTERN = Pattern.compile("[*_~" + BT + "]+");
    private static final Pattern WHITESPACE_RUN_PATTERN = Pattern.compile(BS + "s+");
    interface AudioCaptureLine extends AutoCloseable {
        void start();

        int read(byte[] buffer, int offset, int length);

        void stop();

        @Override
        void close();
    }

    @FunctionalInterface
    interface CaptureLineSupplier {
        AudioCaptureLine openCaptureLine(AudioFormat format) throws LineUnavailableException;
    }

    interface AudioPlaybackLine extends AutoCloseable {
        void start();

        int write(byte[] buffer, int offset, int length);

        void drain();

        void stop();

        @Override
        void close();
    }

    @FunctionalInterface
    interface PlaybackLineSupplier {
        AudioPlaybackLine openPlaybackLine(AudioFormat format) throws LineUnavailableException;
    }

    @FunctionalInterface
    interface SttClient {
        String transcribe(String apiKey, String modelName, byte[] wavBytes, String prompt) throws Exception;
    }

    @FunctionalInterface
    interface TtsClient {
        byte[] synthesize(String apiKey, String ttsModel, String voiceName, String text) throws Exception;
    }

    private final SettingsService settingsService;
    private final ExecutorService audioExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean recording = new AtomicBoolean(false);
    private final AtomicBoolean playing = new AtomicBoolean(false);
    private final AtomicLong playbackSequence = new AtomicLong(0);

    private CaptureLineSupplier captureLineSupplier = VoiceService::openDefaultCaptureLine;
    private PlaybackLineSupplier playbackLineSupplier = VoiceService::openDefaultPlaybackLine;
    private SttClient sttClient = VoiceService::executeDefaultTranscribe;
    private TtsClient ttsClient = VoiceService::executeDefaultSynthesize;

    private volatile AudioCaptureLine activeCaptureLine;
    private volatile ByteArrayOutputStream capturedPcmBuffer;
    private volatile Future<?> activeCaptureFuture;

    private volatile AudioPlaybackLine activePlaybackLine;
    private volatile Future<?> activePlaybackFuture;
    private volatile long activePlaybackId;

    @Autowired
    public VoiceService(SettingsService settingsService) {
        this.settingsService = settingsService;
    }

    void setCaptureLineSupplier(CaptureLineSupplier captureLineSupplier) {
        this.captureLineSupplier = Objects.requireNonNull(captureLineSupplier);
    }

    void setPlaybackLineSupplier(PlaybackLineSupplier playbackLineSupplier) {
        this.playbackLineSupplier = Objects.requireNonNull(playbackLineSupplier);
    }

    void setSttClient(SttClient sttClient) {
        this.sttClient = Objects.requireNonNull(sttClient);
    }

    void setTtsClient(TtsClient ttsClient) {
        this.ttsClient = Objects.requireNonNull(ttsClient);
    }

    public boolean isRecording() {
        return recording.get();
    }

    public boolean isPlaying() {
        return playing.get();
    }

    public synchronized void startRecording() {
        if (recording.get()) {
            return;
        }
        stopPlayback();
        AudioCaptureLine line;
        try {
            line = captureLineSupplier.openCaptureLine(CAPTURE_FORMAT);
        } catch (LineUnavailableException e) {
            throw new IllegalStateException("Microphone is unavailable: " + ExceptionUtils.getMessageOrSimpleName(e),
                    e);
        }
        ByteArrayOutputStream pcmBuffer = new ByteArrayOutputStream();
        activeCaptureLine = line;
        capturedPcmBuffer = pcmBuffer;
        recording.set(true);
        line.start();
        activeCaptureFuture = audioExecutor.submit(() -> drainCaptureLine(line, pcmBuffer));
    }

    private void drainCaptureLine(AudioCaptureLine line, ByteArrayOutputStream pcmBuffer) {
        byte[] buffer = new byte[AUDIO_BUFFER_SIZE];
        int totalBytes = 0;
        try {
            while (recording.get() && totalBytes < MAX_RECORDING_BYTES) {
                int remaining = MAX_RECORDING_BYTES - totalBytes;
                int toRead = Math.min(buffer.length, remaining);
                int bytesRead = line.read(buffer, 0, toRead);
                if (bytesRead <= 0) {
                    break;
                }
                synchronized (pcmBuffer) {
                    pcmBuffer.write(buffer, 0, bytesRead);
                }
                totalBytes += bytesRead;
            }
        } catch (Exception e) {
            log.debug("Microphone capture loop ended: {}", e.getMessage());
        } finally {
            recording.set(false);
        }
    }

    public byte[] stopRecordingAndGetWav() {
        AudioCaptureLine line;
        Future<?> captureFuture;
        ByteArrayOutputStream pcmBuffer;
        synchronized (this) {
            recording.set(false);
            line = activeCaptureLine;
            activeCaptureLine = null;
            captureFuture = activeCaptureFuture;
            activeCaptureFuture = null;
            pcmBuffer = capturedPcmBuffer;
            capturedPcmBuffer = null;
        }
        if (line != null) {
            try {
                line.stop();
            } catch (Exception e) {
                log.debug("Error stopping capture line: {}", e.getMessage());
            }
            try {
                line.close();
            } catch (Exception e) {
                log.debug("Error closing capture line: {}", e.getMessage());
            }
        }
        if (captureFuture != null) {
            try {
                captureFuture.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                captureFuture.cancel(true);
            }
        }
        if (pcmBuffer == null) {
            return new byte[0];
        }
        byte[] pcmBytes;
        synchronized (pcmBuffer) {
            pcmBytes = pcmBuffer.toByteArray();
        }
        if (pcmBytes.length == 0) {
            return new byte[0];
        }
        return encodePcmToWav(pcmBytes, CAPTURE_FORMAT);
    }

    static byte[] encodePcmToWav(byte[] pcmBytes, AudioFormat format) {
        int frameSize = Math.max(1, format.getFrameSize());
        long frameLength = pcmBytes.length / frameSize;
        ByteArrayInputStream bais = new ByteArrayInputStream(pcmBytes);
        ByteArrayOutputStream wavOut = new ByteArrayOutputStream(pcmBytes.length + WAV_HEADER_OVERHEAD);
        try (AudioInputStream ais = new AudioInputStream(bais, format, frameLength)) {
            AudioSystem.write(ais, AudioFileFormat.Type.WAVE, wavOut);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to encode WAV audio: " + e.getMessage(), e);
        }
        return wavOut.toByteArray();
    }

    public String transcribeAudio(byte[] wavBytes) {
        if (wavBytes == null || wavBytes.length == 0) {
            return "";
        }
        String apiKey = resolveApiKey();
        if (apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "Please configure a Gemini API Key in Global Settings before using voice input.");
        }
        String sttModel = resolveSttModel();
        try {
            String transcript = sttClient.transcribe(apiKey, sttModel, wavBytes, TRANSCRIBE_PROMPT);
            return StringUtils.trimToEmpty(transcript);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Speech transcription failed: " + ExceptionUtils.getRootCauseMessage(e), e);
        }
    }

    public static String sanitizeMarkdownForSpeech(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return "";
        }
        String sanitized = FENCED_CODE_BLOCK_PATTERN.matcher(markdown).replaceAll(CODE_BLOCK_CUE);
        sanitized = HTML_TAG_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = MARKDOWN_LINK_PATTERN.matcher(sanitized).replaceAll("$1");
        sanitized = TABLE_DIVIDER_PATTERN.matcher(sanitized).replaceAll(" ");
        sanitized = HEADING_PREFIX_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = LIST_QUOTE_PREFIX_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = TABLE_PIPE_PATTERN.matcher(sanitized).replaceAll(" ");
        sanitized = MARKDOWN_SYMBOLS_PATTERN.matcher(sanitized).replaceAll("");
        sanitized = WHITESPACE_RUN_PATTERN.matcher(sanitized).replaceAll(" ").trim();
        if (sanitized.length() > MAX_SPEECH_CHARS) {
            sanitized = sanitized.substring(0, MAX_SPEECH_CHARS).trim();
        }
        return sanitized;
    }

    public CompletableFuture<Void> speakAsync(String markdownText, @Nullable Runnable onStart,
            @Nullable Runnable onFinish, @Nullable Consumer<String> onError) {
        stopPlayback();
        String sanitized = sanitizeMarkdownForSpeech(markdownText);
        if (sanitized.isEmpty()) {
            if (onFinish != null) {
                onFinish.run();
            }
            return CompletableFuture.completedFuture(null);
        }

        long playbackId = playbackSequence.incrementAndGet();
        activePlaybackId = playbackId;
        playing.set(true);
        if (onStart != null) {
            onStart.run();
        }

        CompletableFuture<Void> completion = new CompletableFuture<>();
        Future<?> taskFuture = audioExecutor.submit(() -> {
            try {
                executeSpeechPlayback(playbackId, sanitized);
                completion.complete(null);
            } catch (Exception e) {
                if (activePlaybackId == playbackId && !ExceptionUtils.isCancellation(e)) {
                    log.warn("TTS playback failed: {}", e.getMessage());
                    if (onError != null) {
                        onError.accept(ExceptionUtils.getRootCauseMessage(e));
                    }
                }
                completion.completeExceptionally(e);
            } finally {
                if (activePlaybackId == playbackId) {
                    playing.set(false);
                    activePlaybackLine = null;
                    activePlaybackFuture = null;
                }
                if (onFinish != null) {
                    onFinish.run();
                }
            }
        });
        activePlaybackFuture = taskFuture;
        return completion;
    }

    private void executeSpeechPlayback(long playbackId, String sanitizedText) throws Exception {
        String apiKey = resolveApiKey();
        if (apiKey.isEmpty()) {
            throw new IllegalStateException(
                    "Please configure a Gemini API Key in Global Settings before using voice output.");
        }
        String ttsModel = resolveTtsModel();
        String voiceName = resolveTtsVoice();
        byte[] pcmBytes = ttsClient.synthesize(apiKey, ttsModel, voiceName, sanitizedText);
        if (activePlaybackId != playbackId || Thread.currentThread().isInterrupted()) {
            return;
        }
        if (pcmBytes == null || pcmBytes.length == 0) {
            throw new IllegalStateException("Gemini TTS returned empty audio data.");
        }

        try (AudioPlaybackLine line = playbackLineSupplier.openPlaybackLine(PLAYBACK_FORMAT)) {
            activePlaybackLine = line;
            line.start();
            int offset = 0;
            while (offset < pcmBytes.length && activePlaybackId == playbackId
                    && !Thread.currentThread().isInterrupted()) {
                int chunkLength = Math.min(AUDIO_BUFFER_SIZE, pcmBytes.length - offset);
                int written = line.write(pcmBytes, offset, chunkLength);
                if (written <= 0) {
                    break;
                }
                offset += written;
            }
            if (activePlaybackId == playbackId && !Thread.currentThread().isInterrupted()) {
                line.drain();
            }
        }
    }

    public synchronized void stopPlayback() {
        activePlaybackId = playbackSequence.incrementAndGet();
        playing.set(false);
        AudioPlaybackLine line = activePlaybackLine;
        activePlaybackLine = null;
        if (line != null) {
            try {
                line.stop();
            } catch (Exception e) {
                log.debug("Error stopping playback line: {}", e.getMessage());
            }
            try {
                line.close();
            } catch (Exception e) {
                log.debug("Error closing playback line: {}", e.getMessage());
            }
        }
        Future<?> future = activePlaybackFuture;
        activePlaybackFuture = null;
        if (future != null) {
            future.cancel(true);
        }
    }

    @Override
    public void close() {
        stopRecordingAndGetWav();
        stopPlayback();
        audioExecutor.close();
    }

    private String resolveApiKey() {
        if (settingsService != null && settingsService.getSettings() != null) {
            return StringUtils.trimToEmpty(settingsService.getSettings().getGeminiApiKey());
        }
        return "";
    }

    private String resolveSttModel() {
        if (settingsService != null && settingsService.getSettings() != null) {
            return StringUtils.trimToEmpty(settingsService.getSettings().getSttModel());
        }
        return STT_MODEL;
    }

    private String resolveTtsModel() {
        if (settingsService != null && settingsService.getSettings() != null) {
            return StringUtils.trimToEmpty(settingsService.getSettings().getTtsModel());
        }
        return TTS_MODEL;
    }

    private String resolveTtsVoice() {
        if (settingsService != null && settingsService.getSettings() != null) {
            return settingsService.getSettings().getTtsVoice();
        }
        return AppSettings.DEFAULT_TTS_VOICE;
    }

    private static AudioCaptureLine openDefaultCaptureLine(AudioFormat format) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new IllegalStateException("No compatible 16 kHz microphone input device was found on this system.");
        }
        TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
        line.open(format);
        return new TargetDataLineAdapter(line);
    }

    private static AudioPlaybackLine openDefaultPlaybackLine(AudioFormat format) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        if (!AudioSystem.isLineSupported(info)) {
            throw new IllegalStateException("No compatible 24 kHz audio output device was found on this system.");
        }
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        return new SourceDataLineAdapter(line);
    }

    private static String executeDefaultTranscribe(String apiKey, String modelName, byte[] wavBytes, String prompt) {
        try (Client client = AiConfig.createGenAiClient(apiKey, AiConfig.DEFAULT_HTTP_TIMEOUT_MS)) {
            Content content = Content.fromParts(Part.fromBytes(wavBytes, AUDIO_WAV_MIME_TYPE), Part.fromText(prompt));
            GenerateContentResponse response = client.models.generateContent(modelName, content, null);
            if (response == null) {
                return "";
            }
            return StringUtils.trimToEmpty(response.text());
        }
    }

    private static byte[] executeDefaultSynthesize(String apiKey, String ttsModel, String voiceName, String text) {
        try (Client client = AiConfig.createGenAiClient(apiKey, AiConfig.DEFAULT_HTTP_TIMEOUT_MS)) {
            GenerateContentConfig config = GenerateContentConfig.builder().responseModalities(List.of("AUDIO"))
                    .speechConfig(
                            SpeechConfig.builder()
                                    .voiceConfig(VoiceConfig.builder()
                                            .prebuiltVoiceConfig(
                                                    PrebuiltVoiceConfig.builder().voiceName(voiceName).build())
                                            .build())
                                    .build())
                    .build();
            GenerateContentResponse response = client.models.generateContent(ttsModel, text, config);
            return extractInlineAudioBytes(response);
        }
    }

    static byte[] extractInlineAudioBytes(GenerateContentResponse response) {
        if (response == null) {
            return new byte[0];
        }
        Optional<List<Candidate>> candidatesOpt = response.candidates();
        if (candidatesOpt.isEmpty()) {
            return new byte[0];
        }
        for (Candidate candidate : candidatesOpt.get()) {
            Optional<Content> contentOpt = candidate.content();
            if (contentOpt.isEmpty()) {
                continue;
            }
            Optional<List<Part>> partsOpt = contentOpt.get().parts();
            if (partsOpt.isEmpty()) {
                continue;
            }
            for (Part part : partsOpt.get()) {
                Optional<Blob> inlineDataOpt = part.inlineData();
                if (inlineDataOpt.isPresent()) {
                    Optional<byte[]> dataOpt = inlineDataOpt.get().data();
                    if (dataOpt.isPresent() && dataOpt.get().length > 0) {
                        return dataOpt.get();
                    }
                }
            }
        }
        return new byte[0];
    }

    private record TargetDataLineAdapter(TargetDataLine delegate) implements AudioCaptureLine {
        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return delegate.read(buffer, offset, length);
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    private record SourceDataLineAdapter(SourceDataLine delegate) implements AudioPlaybackLine {
        @Override
        public void start() {
            delegate.start();
        }

        @Override
        public int write(byte[] buffer, int offset, int length) {
            return delegate.write(buffer, offset, length);
        }

        @Override
        public void drain() {
            delegate.drain();
        }

        @Override
        public void stop() {
            delegate.stop();
        }

        @Override
        public void close() {
            delegate.close();
        }
    }
}
