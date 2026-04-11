package com.micklab.whisper;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

final class WhisperContext implements Closeable {
    private static final String TAG = "WhisperContext";
    private static final String LANGUAGE_CODE = "ja";
    private static final int MIN_TRANSCRIBE_TIMEOUT_MS = 120_000;
    private static final int MAX_TRANSCRIBE_TIMEOUT_MS = 600_000;
    private static final int MIN_STREAM_TIMEOUT_MS = 15_000;
    private static final int MAX_STREAM_TIMEOUT_MS = 90_000;
    private static final int AUDIO_TIMEOUT_MULTIPLIER = 12;
    private static final int STREAM_TIMEOUT_MULTIPLIER = 8;

    static final class ChunkResult {
        final int chunkIndex;
        final long chunkStartMs;
        final long chunkEndMs;
        final String transcript;
        final int segmentCount;

        ChunkResult(int chunkIndex, long chunkStartMs, long chunkEndMs, String transcript, int segmentCount) {
            this.chunkIndex = chunkIndex;
            this.chunkStartMs = chunkStartMs;
            this.chunkEndMs = chunkEndMs;
            this.transcript = transcript;
            this.segmentCount = segmentCount;
        }
    }

    private long nativeContext;
    private long nativeStreamState;
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

    synchronized void beginStreaming() throws IOException {
        ensureOpen();
        if (nativeStreamState != 0L) {
            return;
        }

        // 録音チャンク間で同じ WhisperState を再利用して decoder context を残します。
        nativeStreamState = WhisperJni.initState(nativeContext);
        if (nativeStreamState == 0L) {
            throw new IOException("Whisper のストリーミング状態を初期化できませんでした。");
        }
        AppLogger.i(TAG, "WhisperState を開始しました。");
    }

    synchronized void endStreaming() {
        if (nativeStreamState != 0L) {
            AppLogger.i(TAG, "WhisperState を解放します。");
            WhisperJni.freeState(nativeStreamState);
            nativeStreamState = 0L;
        }
    }

    synchronized String transcribe(float[] audioData, boolean includeTimestamps) throws IOException {
        ensureOpen();
        endStreaming();
        if (audioData.length == 0) {
            return "";
        }

        int threadCount = getRecommendedThreadCount();
        int timeoutMs = getBatchTimeoutMs(audioData.length);
        AppLogger.i(
                TAG,
                String.format(
                        Locale.JAPAN,
                        "文字起こし開始: model=%s, samples=%d, seconds=%.2f, threads=%d, timeout=%ds, language=%s, timestamps=%s, mode=parallel",
                        modelFile.getName(),
                        audioData.length,
                        audioData.length / (float) AudioRecorder.SAMPLE_RATE,
                        threadCount,
                        timeoutMs / 1000,
                        LANGUAGE_CODE,
                        includeTimestamps
                )
        );

        int result = WhisperJni.fullTranscribe(
                nativeContext,
                threadCount,
                audioData,
                includeTimestamps,
                timeoutMs
        );
        handleResult(result);

        String transcript = readTranscriptFromContext(includeTimestamps);
        AppLogger.i(
                TAG,
                "文字起こし完了: segments=" + WhisperJni.getTextSegmentCount(nativeContext)
                        + ", transcriptChars=" + transcript.length()
        );
        return transcript;
    }

    synchronized ChunkResult transcribeChunk(AudioRecorder.StreamChunk chunk, boolean includeTimestamps) throws IOException {
        ensureOpen();
        beginStreaming();
        if (chunk.samples.length == 0) {
            return new ChunkResult(chunk.chunkIndex, chunk.startTimeMs, chunk.endTimeMs, "", 0);
        }

        int threadCount = getRecommendedThreadCount();
        int timeoutMs = getStreamTimeoutMs(chunk.samples.length);
        AppLogger.i(
                TAG,
                String.format(
                        Locale.JAPAN,
                        "ストリーム文字起こし開始: chunk=%d, samples=%d, seconds=%.2f, threads=%d, timeout=%ds, language=%s, rms=%.4f, peak=%.4f",
                        chunk.chunkIndex,
                        chunk.samples.length,
                        chunk.samples.length / (float) AudioRecorder.SAMPLE_RATE,
                        threadCount,
                        timeoutMs / 1000,
                        LANGUAGE_CODE,
                        chunk.rms,
                        chunk.peak
                )
        );

        int result = WhisperJni.streamTranscribe(
                nativeContext,
                nativeStreamState,
                threadCount,
                chunk.chunkIndex,
                chunk.startTimeMs,
                chunk.samples,
                includeTimestamps,
                timeoutMs
        );
        handleResult(result);

        int segmentCount = WhisperJni.getTextSegmentCountFromState(nativeStreamState);
        String transcript = readTranscriptFromState(includeTimestamps);
        AppLogger.i(
                TAG,
                "ストリーム文字起こし完了: chunk=" + chunk.chunkIndex
                        + ", segments=" + segmentCount
                        + ", transcriptChars=" + transcript.length()
        );
        return new ChunkResult(chunk.chunkIndex, chunk.startTimeMs, chunk.endTimeMs, transcript, segmentCount);
    }

    private void handleResult(int result) throws IOException {
        if (result == WhisperJni.RESULT_TIMEOUT) {
            throw new IOException("文字起こしが長時間応答しないため中断しました。詳細ログを確認してください。");
        }
        if (result == WhisperJni.RESULT_CANCELLED) {
            throw new IOException("文字起こしを中断しました。");
        }
        if (result != 0) {
            throw new IOException("Whisper 文字起こしに失敗しました。詳細ログを確認してください。");
        }
    }

    private String readTranscriptFromContext(boolean includeTimestamps) {
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
        return builder.toString().trim();
    }

    private String readTranscriptFromState(boolean includeTimestamps) {
        int segmentCount = WhisperJni.getTextSegmentCountFromState(nativeStreamState);
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < segmentCount; i++) {
            if (includeTimestamps) {
                builder.append('[')
                        .append(toTimestamp(WhisperJni.getTextSegmentT0FromState(nativeStreamState, i)))
                        .append(" --> ")
                        .append(toTimestamp(WhisperJni.getTextSegmentT1FromState(nativeStreamState, i)))
                        .append("] ");
            }

            String segment = WhisperJni.getTextSegmentFromState(nativeStreamState, i);
            if (segment != null) {
                builder.append(segment);
            }

            if (includeTimestamps && i + 1 < segmentCount) {
                builder.append('\n');
            }
        }
        return builder.toString().trim();
    }

    private void ensureOpen() throws IOException {
        if (nativeContext == 0L) {
            throw new IOException("Whisper コンテキストは解放済みです。");
        }
    }

    private int getRecommendedThreadCount() {
        return Runtime.getRuntime().availableProcessors() >= 4 ? 2 : 1;
    }

    private int getBatchTimeoutMs(int sampleCount) {
        return getTimeoutMs(sampleCount, MIN_TRANSCRIBE_TIMEOUT_MS, MAX_TRANSCRIBE_TIMEOUT_MS, AUDIO_TIMEOUT_MULTIPLIER);
    }

    private int getStreamTimeoutMs(int sampleCount) {
        return getTimeoutMs(sampleCount, MIN_STREAM_TIMEOUT_MS, MAX_STREAM_TIMEOUT_MS, STREAM_TIMEOUT_MULTIPLIER);
    }

    private int getTimeoutMs(int sampleCount, int minTimeoutMs, int maxTimeoutMs, int multiplier) {
        long audioDurationMs = Math.max(1_000L, (sampleCount * 1_000L) / AudioRecorder.SAMPLE_RATE);
        long timeoutMs = Math.max(
                minTimeoutMs,
                Math.min(maxTimeoutMs, audioDurationMs * multiplier)
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
        endStreaming();
        if (nativeContext != 0L) {
            WhisperJni.cancelTranscription(nativeContext);
            WhisperJni.freeContext(nativeContext);
            nativeContext = 0L;
        }
    }
}
