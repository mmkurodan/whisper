package com.micklab.whisper;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.File;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_RECORD_AUDIO = 1001;
    private static final String MODEL_URL =
            "https://huggingface.co/oxide-lab/whisper-medium-GGUF/resolve/main/whisper.cpp/whisper-medium-q8_0.gguf?download=true";
    private static final String MODEL_FILE_NAME = "whisper-medium-q8_0.gguf";
    private static final String LANGUAGE_CODE = "ja";

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final ModelDownloader modelDownloader = new ModelDownloader();
    private final AudioRecorder audioRecorder = new AudioRecorder();

    private Button downloadButton;
    private Button recordButton;
    private ProgressBar progressBar;
    private TextView modelInfoText;
    private TextView statusText;
    private TextView transcriptText;

    private File modelFile;
    private WhisperContext whisperContext;
    private boolean busy;
    private boolean recording;
    private boolean pendingRecordingStart;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        modelFile = new File(new File(getFilesDir(), "models"), MODEL_FILE_NAME);

        downloadButton = findViewById(R.id.downloadButton);
        recordButton = findViewById(R.id.recordButton);
        progressBar = findViewById(R.id.progressBar);
        modelInfoText = findViewById(R.id.modelInfoText);
        statusText = findViewById(R.id.statusText);
        transcriptText = findViewById(R.id.transcriptText);

        downloadButton.setOnClickListener(view -> beginModelDownload());
        recordButton.setOnClickListener(view -> onRecordButtonPressed());

        transcriptText.setText(R.string.transcript_placeholder);
        refreshModelInfo();
        statusText.setText(modelFile.exists() ? R.string.model_ready_status : R.string.model_not_downloaded);
        updateUiState();
    }

    private void beginModelDownload() {
        if (busy || recording) {
            return;
        }

        busy = true;
        updateUiState();
        updateDownloadProgress(0L, -1L);

        backgroundExecutor.execute(() -> {
            try {
                modelDownloader.download(MODEL_URL, modelFile, (downloadedBytes, totalBytes) ->
                        postToUi(() -> updateDownloadProgress(downloadedBytes, totalBytes))
                );
                closeWhisperContext();
                postToUi(() -> {
                    busy = false;
                    hideProgress();
                    refreshModelInfo();
                    statusText.setText(R.string.model_ready_status);
                    updateUiState();
                });
            } catch (IOException e) {
                postToUi(() -> {
                    busy = false;
                    hideProgress();
                    refreshModelInfo();
                    statusText.setText(e.getMessage());
                    updateUiState();
                });
            }
        });
    }

    private void onRecordButtonPressed() {
        if (recording) {
            stopRecordingAndTranscribe();
            return;
        }

        if (busy) {
            return;
        }

        if (!modelFile.exists()) {
            statusText.setText(R.string.model_required);
            return;
        }

        if (!hasAudioPermission()) {
            pendingRecordingStart = true;
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.RECORD_AUDIO},
                    REQUEST_RECORD_AUDIO
            );
            return;
        }

        startRecording();
    }

    private void startRecording() {
        try {
            audioRecorder.start();
            recording = true;
            hideProgress();
            statusText.setText(R.string.recording_status);
            updateUiState();
        } catch (IllegalStateException e) {
            statusText.setText(e.getMessage());
            updateUiState();
        }
    }

    private void stopRecordingAndTranscribe() {
        if (!recording) {
            return;
        }

        recording = false;
        busy = true;
        showIndeterminateProgress();
        statusText.setText(R.string.transcribing_status);
        updateUiState();

        backgroundExecutor.execute(() -> {
            try {
                float[] audioSamples = audioRecorder.stopAndRead();
                if (audioSamples.length == 0) {
                    throw new IllegalStateException(getString(R.string.recording_too_short));
                }

                String transcript = getOrCreateContext().transcribe(audioSamples, LANGUAGE_CODE, false);
                String finalTranscript = transcript.isEmpty()
                        ? getString(R.string.empty_transcript)
                        : transcript;
                String duration = formatDuration(audioSamples.length);

                postToUi(() -> {
                    busy = false;
                    hideProgress();
                    transcriptText.setText(finalTranscript);
                    refreshModelInfo();
                    statusText.setText(getString(R.string.transcribe_complete, duration));
                    updateUiState();
                });
            } catch (IOException | IllegalStateException e) {
                postToUi(() -> {
                    busy = false;
                    hideProgress();
                    refreshModelInfo();
                    statusText.setText(e.getMessage());
                    updateUiState();
                });
            }
        });
    }

    private WhisperContext getOrCreateContext() throws IOException {
        if (whisperContext == null) {
            whisperContext = WhisperContext.create(modelFile);
        }
        return whisperContext;
    }

    private void closeWhisperContext() {
        if (whisperContext != null) {
            whisperContext.close();
            whisperContext = null;
        }
    }

    private boolean hasAudioPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void refreshModelInfo() {
        StringBuilder builder = new StringBuilder();
        builder.append(MODEL_FILE_NAME)
                .append('\n')
                .append("保存先: ")
                .append(modelFile.getAbsolutePath())
                .append('\n');

        if (modelFile.exists()) {
            builder.append("サイズ: ")
                    .append(Formatter.formatFileSize(this, modelFile.length()))
                    .append('\n')
                    .append("状態: ")
                    .append(whisperContext == null ? "ダウンロード済み" : "推論準備完了");
        } else {
            builder.append("状態: 未ダウンロード");
        }

        modelInfoText.setText(builder.toString());
    }

    private void updateUiState() {
        downloadButton.setEnabled(!busy && !recording);
        downloadButton.setText(modelFile.exists() ? R.string.redownload_model : R.string.download_model);

        recordButton.setEnabled(recording || (!busy && modelFile.exists()));
        recordButton.setText(recording ? R.string.stop_recording : R.string.start_recording);
    }

    private void updateDownloadProgress(long downloadedBytes, long totalBytes) {
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(totalBytes <= 0L);

        if (totalBytes > 0L) {
            long percentage = (downloadedBytes * 100L) / totalBytes;
            progressBar.setMax(100);
            progressBar.setProgress((int) Math.min(percentage, 100L));
            statusText.setText(getString(
                    R.string.model_download_progress,
                    Formatter.formatFileSize(this, downloadedBytes),
                    Formatter.formatFileSize(this, totalBytes)
            ));
            return;
        }

        progressBar.setProgress(0);
        statusText.setText(getString(
                R.string.model_download_progress_unknown,
                Formatter.formatFileSize(this, downloadedBytes)
        ));
    }

    private void showIndeterminateProgress() {
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setIndeterminate(true);
        progressBar.setProgress(0);
    }

    private void hideProgress() {
        progressBar.setIndeterminate(false);
        progressBar.setProgress(0);
        progressBar.setVisibility(View.GONE);
    }

    private void postToUi(Runnable runnable) {
        if (destroyed) {
            return;
        }
        runOnUiThread(() -> {
            if (!destroyed) {
                runnable.run();
            }
        });
    }

    private String formatDuration(int sampleCount) {
        int seconds = Math.max(1, sampleCount / AudioRecorder.SAMPLE_RATE);
        int minutesPart = seconds / 60;
        int secondsPart = seconds % 60;
        return String.format(Locale.JAPAN, "%02d:%02d", minutesPart, secondsPart);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode != REQUEST_RECORD_AUDIO) {
            return;
        }

        boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (granted && pendingRecordingStart) {
            pendingRecordingStart = false;
            startRecording();
            return;
        }

        pendingRecordingStart = false;
        statusText.setText(R.string.permission_required);
        updateUiState();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        audioRecorder.cancel();
        if (!busy) {
            closeWhisperContext();
        }
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }
}
