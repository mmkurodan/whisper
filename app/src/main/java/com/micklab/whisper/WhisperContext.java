package com.micklab.whisper;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

final class WhisperContext implements Closeable {
    private static final String TAG = "WhisperContext";
    private static final int MIN_TRANSCRIBE_TIMEOUT_MS = 120_000;
    private static final int MAX_TRANSCRIBE_TIMEOUT_MS = 600_000;
    private static final int AUDIO_TIMEOUT_MULTIPLIER = 12;

    private long nativeContext;
    private final File modelFile;

    private WhisperContext(long nativeContext, File modelFile) {
        this.nativeContext = nativeContext;
        this.modelFile = modelFile;
    }

    static WhisperContext create(File modelFile) throws IOException {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            throw new IOException("モデルファイルが見つかりません。");
        }

        AppLogger.i(
                TAG,
                "Whisper コンテキストを初期化します: " + modelFile.getAbsolutePath()
                        + " (" + modelFile.length() + " bytes)"
        );
        long context = WhisperJni.initContext(modelFile.getAbsolutePath());
        if (context == 0L) {
            throw new IOException("Whisper モデルを読み込めませんでした。");
        }

        return new WhisperContext(context, modelFile);
    }

    synchronized String transcribe(float[] audioData, String language, boolean includeTimestamps) throws IOException {
        ensureOpen();
        if (audioData.length == 0) {
            return "";
        }

        int threadCount = getRecommendedThreadCount();
        int timeoutMs = getTranscriptionTimeoutMs(audioData.length);
        AppLogger.i(
                TAG,
                String.format(
                        Locale.JAPAN,
                        "文字起こし開始: model=%s, samples=%d, seconds=%.2f, threads=%d, timeout=%ds, language=%s, timestamps=%s",
                        modelFile.getName(),
                        audioData.length,
                        audioData.length / (float) AudioRecorder.SAMPLE_RATE,
                        threadCount,
                        timeoutMs / 1000,
                        language,
                        includeTimestamps
                )
        );

        int result = WhisperJni.fullTranscribe(
                nativeContext,
                threadCount,
                audioData,
                language,
                includeTimestamps,
                timeoutMs
        );
        if (result == WhisperJni.RESULT_TIMEOUT) {
            throw new IOException("文字起こしが長時間応答しないため中断しました。詳細ログを確認してください。");
        }
        if (result == WhisperJni.RESULT_CANCELLED) {
            throw new IOException("文字起こしを中断しました。");
        }
        if (result != 0) {
            throw new IOException("Whisper 文字起こしに失敗しました。詳細ログを確認してください。");
        }

        int segmentCount = WhisperJni.getTextSegmentCount(nativeContext);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segmentCount; i++) {
            if (includeTimestamps) {
                builder.append('[')
                        .append(toTimestamp(WhisperJni.getTextSegmentT0(nativeContext, i)))
                        .append(" --> ")
                        .append(toTimestamp(WhisperJni.getTextSegmentT1(nativeContext, i)))
                        .append("] ");
            }

            String segment = WhisperJni.getTextSegment(nativeContext, i);
            if (segment != null) {
                builder.append(segment);
            }

            if (includeTimestamps && i + 1 < segmentCount) {
                builder.append('\n');
            }
        }

        String transcript = builder.toString().trim();
        AppLogger.i(
                TAG,
                "文字起こし完了: segments=" + segmentCount + ", transcriptChars=" + transcript.length()
        );
        return transcript;
    }

    private void ensureOpen() throws IOException {
        if (nativeContext == 0L) {
            throw new IOException("Whisper コンテキストは解放済みです。");
        }
    }

    private int getRecommendedThreadCount() {
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
    }

    private int getTranscriptionTimeoutMs(int sampleCount) {
        long audioDurationMs = Math.max(1_000L, (sampleCount * 1_000L) / AudioRecorder.SAMPLE_RATE);
        long timeoutMs = Math.max(
                MIN_TRANSCRIBE_TIMEOUT_MS,
                Math.min(MAX_TRANSCRIBE_TIMEOUT_MS, audioDurationMs * AUDIO_TIMEOUT_MULTIPLIER)
        );
        return (int) timeoutMs;
    }

    private String toTimestamp(long timestampUnits) {
        long totalMilliseconds = timestampUnits * 10L;
        long hours = totalMilliseconds / 3_600_000L;
        long minutes = (totalMilliseconds % 3_600_000L) / 60_000L;
        long seconds = (totalMilliseconds % 60_000L) / 1_000L;
        long milliseconds = totalMilliseconds % 1_000L;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds);
    }

    synchronized void cancelTranscription() {
        if (nativeContext != 0L) {
            AppLogger.w(TAG, "Whisper 文字起こしの中断を要求しました。");
            WhisperJni.cancelTranscription(nativeContext);
        }
    }

    @Override
    public synchronized void close() {
        if (nativeContext != 0L) {
            WhisperJni.cancelTranscription(nativeContext);
            WhisperJni.freeContext(nativeContext);
            nativeContext = 0L;
        }
    }
}
