package com.micklab.whisper;

import android.annotation.SuppressLint;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

final class AudioRecorder {
    static final int SAMPLE_RATE = 16_000;

    private static final String TAG = "AudioRecorder";
    private static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
    private static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;

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
        if (localRecord != null && localRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            localRecord.stop();
        }

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
        clearCapturedAudio();
        return result;
    }

    synchronized void cancel() {
        isRecording.set(false);

        AudioRecord localRecord = audioRecord;
        audioRecord = null;
        if (localRecord != null && localRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
            localRecord.stop();
        }

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
        while (isRecording.get()) {
            int read = localRecord.read(buffer, 0, buffer.length);
            if (read > 0) {
                synchronized (dataLock) {
                    recordedChunks.add(Arrays.copyOf(buffer, read));
                    totalSamples += read;
                }
                continue;
            }

            if (!isRecording.get()) {
                break;
            }

            recordingError = new IllegalStateException("音声の読み取りに失敗しました: " + read);
            isRecording.set(false);
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
            localThread.join();
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
            localThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.w(TAG, "Interrupted while stopping recorder", e);
        }
    }
}
