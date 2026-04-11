package com.micklab.whisper;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTimestamp;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.AutomaticGainControl;
import android.media.audiofx.NoiseSuppressor;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioRecorder {
    interface ChunkListener {
        void onChunkReady(StreamChunk chunk);
    }

    static final class StreamChunk {
        final int chunkIndex;
        final long startTimeMs;
        final long endTimeMs;
        final float[] samples;
        final float rms;
        final float peak;

        StreamChunk(int chunkIndex, long startTimeMs, long endTimeMs, float[] samples, float rms, float peak) {
            this.chunkIndex = chunkIndex;
            this.startTimeMs = startTimeMs;
            this.endTimeMs = endTimeMs;
            this.samples = samples;
            this.rms = rms;
            this.peak = peak;
        }
    }

    static final class RecordingSummary {
        final int totalSamples;
        final int deliveredChunks;
        final int skippedSilentChunks;
        final int droppedLowVolumeChunks;

        RecordingSummary(int totalSamples, int deliveredChunks, int skippedSilentChunks, int droppedLowVolumeChunks) {
            this.totalSamples = totalSamples;
            this.deliveredChunks = deliveredChunks;
            this.skippedSilentChunks = skippedSilentChunks;
            this.droppedLowVolumeChunks = droppedLowVolumeChunks;
        }
    }

    private static final class ProcessedChunk {
        final float[] samples;
        final float rms;
        final float peak;
        final float gateThreshold;
        final float activeRatio;

        ProcessedChunk(float[] samples, float rms, float peak, float gateThreshold, float activeRatio) {
            this.samples = samples;
            this.rms = rms;
            this.peak = peak;
            this.gateThreshold = gateThreshold;
            this.activeRatio = activeRatio;
        }
    }

    private static final class AudioEffects {
        NoiseSuppressor noiseSuppressor;
        AutomaticGainControl automaticGainControl;
        AcousticEchoCanceler acousticEchoCanceler;

        void release() {
            if (noiseSuppressor != null) {
                noiseSuppressor.release();
                noiseSuppressor = null;
            }
            if (automaticGainControl != null) {
                automaticGainControl.release();
                automaticGainControl = null;
            }
            if (acousticEchoCanceler != null) {
                acousticEchoCanceler.release();
                acousticEchoCanceler = null;
            }
        }
    }

    static final int SAMPLE_RATE = 16_000;

    private static final String TAG = "AudioRecorder";
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final long EMPTY_READ_SLEEP_MS = 10L;
    private static final long STOP_TIMEOUT_MS = 2_000L;
    private static final int CHUNK_DURATION_MS = 1_500;
    private static final int CHUNK_SAMPLES = (SAMPLE_RATE * CHUNK_DURATION_MS) / 1_000;
    private static final int MIN_FINAL_CHUNK_SAMPLES = SAMPLE_RATE / 2;
    private static final float MIN_INPUT_RMS = 0.006f;
    private static final float MIN_INPUT_PEAK = 0.015f;
    private static final float NOISE_GATE_FLOOR = 0.008f;
    private static final float NOISE_GATE_CEILING = 0.040f;
    private static final float MIN_ACTIVE_RATIO = 0.06f;
    private static final float MIN_SPEECH_PEAK = 0.025f;

    private final Object stateLock = new Object();
    private final AtomicBoolean isRecording = new AtomicBoolean(false);

    private volatile AudioRecord audioRecord;
    private volatile Thread recordingThread;
    private volatile IllegalStateException recordingError;
    private volatile ChunkListener chunkListener;
    private volatile AudioEffects audioEffects;

    private int totalSamplesRead;
    private int deliveredChunks;
    private int skippedSilentChunks;
    private int droppedLowVolumeChunks;

    @SuppressLint("MissingPermission")
    synchronized void start(ChunkListener listener) {
        if (isRecording.get()) {
            throw new IllegalStateException("すでに録音中です。");
        }

        clearRecordingState();
        recordingError = null;
        chunkListener = listener;

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferSize <= 0) {
            throw new IllegalStateException("録音バッファを初期化できません。");
        }

        int bufferSize = Math.max(minBufferSize * 4, CHUNK_SAMPLES * 2);
        AudioRecord localRecord = new AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
        );

        if (localRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            localRecord.release();
            throw new IllegalStateException("マイクを初期化できません。");
        }

        audioEffects = createAudioEffects(localRecord.getAudioSessionId());
        audioRecord = localRecord;
        isRecording.set(true);
        localRecord.startRecording();
        AppLogger.i(
                TAG,
                "録音を開始しました: minBuffer=" + minBufferSize
                        + ", buffer=" + bufferSize
                        + ", bufferFrames=" + localRecord.getBufferSizeInFrames()
                        + ", sessionId=" + localRecord.getAudioSessionId()
        );

        Thread localThread = new Thread(this::captureLoop, "WhisperRecorder");
        recordingThread = localThread;
        localThread.start();
    }

    synchronized RecordingSummary stop() {
        if (!isRecording.get() && audioRecord == null) {
            return new RecordingSummary(0, 0, 0, 0);
        }

        isRecording.set(false);

        AudioRecord localRecord = audioRecord;
        audioRecord = null;
        stopAudioRecord(localRecord, false);

        joinRecordingThread();

        if (localRecord != null) {
            localRecord.release();
        }
        releaseAudioEffects();

        if (recordingError != null) {
            IllegalStateException failure = recordingError;
            recordingError = null;
            clearRecordingState();
            throw failure;
        }

        RecordingSummary summary;
        synchronized (stateLock) {
            summary = new RecordingSummary(
                    totalSamplesRead,
                    deliveredChunks,
                    skippedSilentChunks,
                    droppedLowVolumeChunks
            );
        }

        AppLogger.i(
                TAG,
                "録音を停止しました: samples=" + summary.totalSamples
                        + ", deliveredChunks=" + summary.deliveredChunks
                        + ", skippedSilentChunks=" + summary.skippedSilentChunks
                        + ", droppedLowVolumeChunks=" + summary.droppedLowVolumeChunks
        );
        clearRecordingState();
        return summary;
    }

    synchronized void cancel() {
        isRecording.set(false);

        AudioRecord localRecord = audioRecord;
        audioRecord = null;
        stopAudioRecord(localRecord, true);

        joinRecordingThreadQuietly();

        if (localRecord != null) {
            localRecord.release();
        }
        releaseAudioEffects();

        recordingError = null;
        clearRecordingState();
    }

    private void captureLoop() {
        AudioRecord localRecord = audioRecord;
        ChunkListener localListener = chunkListener;
        if (localRecord == null) {
            return;
        }

        short[] readBuffer = new short[2048];
        short[] chunkBuffer = new short[CHUNK_SAMPLES];
        AudioTimestamp audioTimestamp = new AudioTimestamp();
        int chunkFill = 0;
        int chunkIndex = 0;
        int chunkStartSample = 0;
        long totalFramesRead = 0L;

        while (true) {
            boolean keepRecording = isRecording.get();
            int read = localRecord.read(readBuffer, 0, readBuffer.length, AudioRecord.READ_NON_BLOCKING);
            if (read > 0) {
                totalFramesRead += read;
                addReadSamples(read);

                int readOffset = 0;
                while (readOffset < read) {
                    int copyCount = Math.min(CHUNK_SAMPLES - chunkFill, read - readOffset);
                    System.arraycopy(readBuffer, readOffset, chunkBuffer, chunkFill, copyCount);
                    chunkFill += copyCount;
                    readOffset += copyCount;

                    if (chunkFill == CHUNK_SAMPLES) {
                        long latencyMs = estimateBufferLatencyMs(localRecord, audioTimestamp, totalFramesRead);
                        short[] completedChunk = Arrays.copyOf(chunkBuffer, chunkFill);
                        emitChunk(localListener, completedChunk, chunkIndex, chunkStartSample, latencyMs, false);
                        chunkStartSample += chunkFill;
                        chunkFill = 0;
                        chunkIndex++;
                    }
                }
                continue;
            }

            if (!keepRecording) {
                break;
            }

            if (read == 0) {
                if (!sleepBetweenReads()) {
                    break;
                }
                continue;
            }

            AppLogger.e(TAG, "音声の読み取りに失敗しました: code=" + read);
            recordingError = new IllegalStateException("音声の読み取りに失敗しました: " + read);
            isRecording.set(false);
            break;
        }

        if (chunkFill > 0 && (chunkFill >= MIN_FINAL_CHUNK_SAMPLES || chunkIndex == 0)) {
            long latencyMs = estimateBufferLatencyMs(localRecord, audioTimestamp, totalFramesRead);
            short[] completedChunk = Arrays.copyOf(chunkBuffer, chunkFill);
            emitChunk(localListener, completedChunk, chunkIndex, chunkStartSample, latencyMs, true);
        }
    }

    private void emitChunk(
            ChunkListener listener,
            short[] pcmChunk,
            int chunkIndex,
            int chunkStartSample,
            long bufferLatencyMs,
            boolean finalChunk) {
        ProcessedChunk processedChunk = preprocessChunk(pcmChunk, chunkIndex, finalChunk);
        if (processedChunk == null) {
            return;
        }

        long startTimeMs = toMillis(chunkStartSample);
        long endTimeMs = toMillis(chunkStartSample + pcmChunk.length);
        StreamChunk chunk = new StreamChunk(
                chunkIndex,
                startTimeMs,
                endTimeMs,
                processedChunk.samples,
                processedChunk.rms,
                processedChunk.peak
        );

        synchronized (stateLock) {
            deliveredChunks++;
        }

        AppLogger.i(
                TAG,
                "チャンク送信: index=" + chunkIndex
                        + ", samples=" + pcmChunk.length
                        + ", startMs=" + startTimeMs
                        + ", endMs=" + endTimeMs
                        + ", rms=" + processedChunk.rms
                        + ", peak=" + processedChunk.peak
                        + ", gate=" + processedChunk.gateThreshold
                        + ", activeRatio=" + processedChunk.activeRatio
                        + ", bufferLatencyMs=" + bufferLatencyMs
                        + ", final=" + finalChunk
        );

        if (listener == null) {
            return;
        }

        try {
            listener.onChunkReady(chunk);
        } catch (RuntimeException e) {
            recordingError = new IllegalStateException("音声チャンクの転送に失敗しました。", e);
            isRecording.set(false);
        }
    }

    private ProcessedChunk preprocessChunk(short[] pcmChunk, int chunkIndex, boolean finalChunk) {
        float[] samples = new float[pcmChunk.length];
        double sumSquares = 0.0d;
        float peak = 0.0f;

        for (int i = 0; i < pcmChunk.length; i++) {
            float sample = pcmChunk[i] / 32768.0f;
            samples[i] = sample;
            float absolute = Math.abs(sample);
            peak = Math.max(peak, absolute);
            sumSquares += sample * sample;
        }

        float rms = (float) Math.sqrt(sumSquares / Math.max(1, pcmChunk.length));
        if (rms < MIN_INPUT_RMS && peak < MIN_INPUT_PEAK) {
            synchronized (stateLock) {
                droppedLowVolumeChunks++;
            }
            AppLogger.i(
                    TAG,
                    "音量が小さすぎるためチャンクを破棄します: index=" + chunkIndex
                            + ", rms=" + rms
                            + ", peak=" + peak
            );
            return null;
        }

        float gateThreshold = Math.max(
                NOISE_GATE_FLOOR,
                Math.min(NOISE_GATE_CEILING, Math.max(rms * 1.35f, peak * 0.08f))
        );
        double gatedSumSquares = 0.0d;
        float gatedPeak = 0.0f;
        int activeSamples = 0;

        for (int i = 0; i < samples.length; i++) {
            float absolute = Math.abs(samples[i]);
            if (absolute < gateThreshold) {
                samples[i] = 0.0f;
                continue;
            }

            activeSamples++;
            gatedPeak = Math.max(gatedPeak, absolute);
            gatedSumSquares += samples[i] * samples[i];
        }

        float gatedRms = (float) Math.sqrt(gatedSumSquares / Math.max(1, samples.length));
        float activeRatio = activeSamples / (float) Math.max(1, samples.length);
        if (activeRatio < MIN_ACTIVE_RATIO && gatedPeak < MIN_SPEECH_PEAK) {
            synchronized (stateLock) {
                skippedSilentChunks++;
            }
            AppLogger.i(
                    TAG,
                    "無音チャンクをスキップします: index=" + chunkIndex
                            + ", rms=" + gatedRms
                            + ", peak=" + gatedPeak
                            + ", activeRatio=" + activeRatio
                            + ", final=" + finalChunk
            );
            return null;
        }

        return new ProcessedChunk(samples, gatedRms, gatedPeak, gateThreshold, activeRatio);
    }

    private AudioEffects createAudioEffects(int audioSessionId) {
        AudioEffects effects = new AudioEffects();
        effects.noiseSuppressor = createNoiseSuppressor(audioSessionId);
        effects.automaticGainControl = createAutomaticGainControl(audioSessionId);
        effects.acousticEchoCanceler = createAcousticEchoCanceler(audioSessionId);
        return effects;
    }

    private NoiseSuppressor createNoiseSuppressor(int audioSessionId) {
        if (!NoiseSuppressor.isAvailable()) {
            AppLogger.w(TAG, "NoiseSuppressor は利用できないためスキップします。");
            return null;
        }

        try {
            NoiseSuppressor suppressor = NoiseSuppressor.create(audioSessionId);
            if (suppressor == null) {
                AppLogger.w(TAG, "NoiseSuppressor の初期化に失敗したためスキップします。");
                return null;
            }
            suppressor.setEnabled(true);
            AppLogger.i(TAG, "NoiseSuppressor enabled=" + suppressor.getEnabled());
            return suppressor;
        } catch (IllegalStateException | UnsupportedOperationException e) {
            AppLogger.w(TAG, "NoiseSuppressor を有効化できませんでした。", e);
            return null;
        }
    }

    private AutomaticGainControl createAutomaticGainControl(int audioSessionId) {
        if (!AutomaticGainControl.isAvailable()) {
            AppLogger.w(TAG, "AutomaticGainControl は利用できないためスキップします。");
            return null;
        }

        try {
            AutomaticGainControl gainControl = AutomaticGainControl.create(audioSessionId);
            if (gainControl == null) {
                AppLogger.w(TAG, "AutomaticGainControl の初期化に失敗したためスキップします。");
                return null;
            }
            gainControl.setEnabled(true);
            AppLogger.i(TAG, "AutomaticGainControl enabled=" + gainControl.getEnabled());
            return gainControl;
        } catch (IllegalStateException | UnsupportedOperationException e) {
            AppLogger.w(TAG, "AutomaticGainControl を有効化できませんでした。", e);
            return null;
        }
    }

    private AcousticEchoCanceler createAcousticEchoCanceler(int audioSessionId) {
        if (!AcousticEchoCanceler.isAvailable()) {
            AppLogger.w(TAG, "AcousticEchoCanceler は利用できないためスキップします。");
            return null;
        }

        try {
            AcousticEchoCanceler echoCanceler = AcousticEchoCanceler.create(audioSessionId);
            if (echoCanceler == null) {
                AppLogger.w(TAG, "AcousticEchoCanceler の初期化に失敗したためスキップします。");
                return null;
            }
            echoCanceler.setEnabled(true);
            AppLogger.i(TAG, "AcousticEchoCanceler enabled=" + echoCanceler.getEnabled());
            return echoCanceler;
        } catch (IllegalStateException | UnsupportedOperationException e) {
            AppLogger.w(TAG, "AcousticEchoCanceler を有効化できませんでした。", e);
            return null;
        }
    }

    private long estimateBufferLatencyMs(AudioRecord localRecord, AudioTimestamp audioTimestamp, long totalFramesRead) {
        if (localRecord == null) {
            return 0L;
        }

        int status = localRecord.getTimestamp(audioTimestamp, AudioTimestamp.TIMEBASE_MONOTONIC);
        if (status == AudioRecord.SUCCESS) {
            long bufferedFrames = Math.max(0L, audioTimestamp.framePosition - totalFramesRead);
            return (bufferedFrames * 1_000L) / SAMPLE_RATE;
        }

        long fallbackFrames = localRecord.getBufferSizeInFrames();
        return (fallbackFrames * 1_000L) / SAMPLE_RATE;
    }

    private void addReadSamples(int samplesRead) {
        synchronized (stateLock) {
            totalSamplesRead += samplesRead;
        }
    }

    private void clearRecordingState() {
        synchronized (stateLock) {
            totalSamplesRead = 0;
            deliveredChunks = 0;
            skippedSilentChunks = 0;
            droppedLowVolumeChunks = 0;
        }
        chunkListener = null;
    }

    private void joinRecordingThread() {
        Thread localThread = recordingThread;
        recordingThread = null;
        if (localThread == null) {
            return;
        }

        try {
            localThread.join(STOP_TIMEOUT_MS);
            if (localThread.isAlive()) {
                localThread.interrupt();
                throw new IllegalStateException("録音スレッドの停止がタイムアウトしました。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("録音スレッドの停止に失敗しました。", e);
        }
    }

    private void joinRecordingThreadQuietly() {
        Thread localThread = recordingThread;
        recordingThread = null;
        if (localThread == null) {
            return;
        }

        try {
            localThread.join(STOP_TIMEOUT_MS);
            if (localThread.isAlive()) {
                localThread.interrupt();
                AppLogger.w(TAG, "録音スレッドが停止時間内に終了しませんでした。");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AppLogger.w(TAG, "録音スレッド停止待ち中に割り込まれました。", e);
        }
    }

    private boolean sleepBetweenReads() {
        try {
            Thread.sleep(EMPTY_READ_SLEEP_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (isRecording.get()) {
                recordingError = new IllegalStateException("録音スレッドが中断されました。", e);
                isRecording.set(false);
            }
            return false;
        }
    }

    private void stopAudioRecord(AudioRecord localRecord, boolean suppressErrors) {
        if (localRecord == null || localRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            return;
        }

        try {
            localRecord.stop();
        } catch (IllegalStateException e) {
            if (!suppressErrors) {
                throw new IllegalStateException("録音を停止できません。", e);
            }
            AppLogger.w(TAG, "録音停止中に例外が発生しました。", e);
        }
    }

    private void releaseAudioEffects() {
        AudioEffects effects = audioEffects;
        audioEffects = null;
        if (effects != null) {
            effects.release();
        }
    }

    private long toMillis(int sampleIndex) {
        return (sampleIndex * 1_000L) / SAMPLE_RATE;
    }
}
