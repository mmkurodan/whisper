package com.micklab.whisper;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

final class WhisperContext implements Closeable {
    private long nativeContext;

    private WhisperContext(long nativeContext) {
        this.nativeContext = nativeContext;
    }

    static WhisperContext create(File modelFile) throws IOException {
        if (!modelFile.exists() || modelFile.length() == 0L) {
            throw new IOException("モデルファイルが見つかりません。");
        }

        long context = WhisperJni.initContext(modelFile.getAbsolutePath());
        if (context == 0L) {
            throw new IOException("Whisper モデルを読み込めませんでした。");
        }

        return new WhisperContext(context);
    }

    synchronized String transcribe(float[] audioData, String language, boolean includeTimestamps) throws IOException {
        ensureOpen();
        if (audioData.length == 0) {
            return "";
        }

        int result = WhisperJni.fullTranscribe(
                nativeContext,
                getRecommendedThreadCount(),
                audioData,
                language,
                includeTimestamps
        );
        if (result != 0) {
            throw new IOException("Whisper 文字起こしに失敗しました。");
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

        return builder.toString().trim();
    }

    private void ensureOpen() throws IOException {
        if (nativeContext == 0L) {
            throw new IOException("Whisper コンテキストは解放済みです。");
        }
    }

    private int getRecommendedThreadCount() {
        return Math.max(1, Math.min(Runtime.getRuntime().availableProcessors(), 4));
    }

    private String toTimestamp(long timestampUnits) {
        long totalMilliseconds = timestampUnits * 10L;
        long hours = totalMilliseconds / 3_600_000L;
        long minutes = (totalMilliseconds % 3_600_000L) / 60_000L;
        long seconds = (totalMilliseconds % 60_000L) / 1_000L;
        long milliseconds = totalMilliseconds % 1_000L;
        return String.format(Locale.US, "%02d:%02d:%02d.%03d", hours, minutes, seconds, milliseconds);
    }

    @Override
    public synchronized void close() {
        if (nativeContext != 0L) {
            WhisperJni.freeContext(nativeContext);
            nativeContext = 0L;
        }
    }
}
