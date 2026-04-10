package com.micklab.whisper;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioRecorder {
    static final int SAMPLE_RATE = 16_000;

    private static final String TAG = "AudioRecorder";
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
    private static final long EMPTY_READ_SLEEP_MS = 10L;
    private static final long STOP_TIMEOUT_MS = 2_000L;

    private final Object dataLock = new Object();
    private final AtomicBoolean isRecording = new AtomicBoolean(false);
    private final List<short[]> recordedChunks = new ArrayList<>();

    private volatile AudioRecord audioRecord;
    private volatile Thread recordingThread;
    private volatile IllegalStateException recordingError;
    private int totalSamples;

    @SuppressLint("MissingPermission")
    synchronized void start() {
        if (isRecording.get()) {
            throw new IllegalStateException("すでに録音中です。");
        }

        clearCapturedAudio();
        recordingError = null;

        int minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);
        if (minBufferSize <= 0) {
            throw new IllegalStateException("録音バッファを初期化できません。");
        }

        int bufferSize = minBufferSize * 4;
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

        audioRecord = localRecord;
        isRecording.set(true);
        localRecord.startRecording();
        AppLogger.i(TAG, "録音を開始しました: minBuffer=" + minBufferSize + ", buffer=" + bufferSize);

        Thread localThread = new Thread(this::captureLoop, "WhisperRecorder");
        recordingThread = localThread;
        localThread.start();
    }

    synchronized float[] stopAndRead() {
        if (!isRecording.get() && audioRecord == null) {
            return new float[0];
        }

        isRecording.set(false);

        AudioRecord localRecord = audioRecord;
        audioRecord = null;
        stopAudioRecord(localRecord, false);

        joinRecordingThread();

        if (localRecord != null) {
            localRecord.release();
        }

        if (recordingError != null) {
            IllegalStateException failure = recordingError;
            recordingError = null;
            clearCapturedAudio();
            throw failure;
        }

        float[] result = toFloatArray();
        AppLogger.i(
                TAG,
                "録音を停止しました: samples=" + result.length
                        + ", durationMs=" + ((result.length * 1_000L) / SAMPLE_RATE)
        );
        clearCapturedAudio();
        return result;
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

        recordingError = null;
        clearCapturedAudio();
    }

    private void captureLoop() {
        AudioRecord localRecord = audioRecord;
        if (localRecord == null) {
            return;
        }

        short[] buffer = new short[2048];
        while (true) {
            boolean keepRecording = isRecording.get();
            int read = localRecord.read(buffer, 0, buffer.length, AudioRecord.READ_NON_BLOCKING);
            if (read > 0) {
                synchronized (dataLock) {
                    recordedChunks.add(Arrays.copyOf(buffer, read));
                    totalSamples += read;
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
    }

    private float[] toFloatArray() {
        synchronized (dataLock) {
            float[] audioData = new float[totalSamples];
            int offset = 0;
            for (short[] chunk : recordedChunks) {
                for (short sample : chunk) {
                    audioData[offset++] = sample / 32768.0f;
                }
            }
            return audioData;
        }
    }

    private void clearCapturedAudio() {
        synchronized (dataLock) {
            recordedChunks.clear();
            totalSamples = 0;
        }
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
}
