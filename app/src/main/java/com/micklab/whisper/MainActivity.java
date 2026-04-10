package com.micklab.whisper;

import android.Manifest;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

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
    private static final String TAG = "MainActivity";
    private static final int REQUEST_RECORD_AUDIO = 1001;
    private static final String MODEL_URL =
            "https://huggingface.co/oxide-lab/whisper-small-GGUF/resolve/main/whisper.cpp/whisper-small-q5_1.gguf?download=true";
    private static final String MODEL_FILE_NAME = "whisper-small-q5_1.gguf";
    private static final String LEGACY_MODEL_FILE_NAME = "whisper-medium-q8_0.gguf";
    private static final String LANGUAGE_CODE = "ja";

    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor();
    private final ModelDownloader modelDownloader = new ModelDownloader();
    private final AudioRecorder audioRecorder = new AudioRecorder();
    private final AppLogger.Listener logListener = this::scheduleLogRefresh;

    private Button downloadButton;
    private Button recordButton;
    private Button copyLogsButton;
    private ProgressBar progressBar;
    private TextView modelInfoText;
    private TextView statusText;
    private TextView transcriptText;
    private TextView logText;
    private ScrollView logScrollView;

    private File modelFile;
    private File legacyModelFile;
    private WhisperContext whisperContext;
    private boolean busy;
    private boolean recording;
    private boolean pendingRecordingStart;
    private volatile boolean destroyed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        File modelsDirectory = new File(getFilesDir(), "models");
        modelFile = new File(modelsDirectory, MODEL_FILE_NAME);
        legacyModelFile = new File(modelsDirectory, LEGACY_MODEL_FILE_NAME);

        downloadButton = findViewById(R.id.downloadButton);
        recordButton = findViewById(R.id.recordButton);
        copyLogsButton = findViewById(R.id.copyLogsButton);
        progressBar = findViewById(R.id.progressBar);
        modelInfoText = findViewById(R.id.modelInfoText);
        statusText = findViewById(R.id.statusText);
        transcriptText = findViewById(R.id.transcriptText);
        logText = findViewById(R.id.logText);
        logScrollView = findViewById(R.id.logScrollView);

        downloadButton.setOnClickListener(view -> beginModelDownload());
        recordButton.setOnClickListener(view -> onRecordButtonPressed());
        copyLogsButton.setOnClickListener(view -> copyLogsToClipboard());

        transcriptText.setText(R.string.transcript_placeholder);
        logText.setText(R.string.log_placeholder);
        AppLogger.i(TAG, "MainActivity を初期化しました。");
        AppLogger.addListener(logListener);
        refreshModelInfo();
        statusText.setText(getInitialStatusText());
        updateUiState();
    }

    private void beginModelDownload() {
        if (busy || recording) {
            return;
        }

        busy = true;
        updateUiState();
        updateDownloadProgress(0L, -1L);
        AppLogger.i(TAG, "モデルダウンロードを開始します。");

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
                AppLogger.i(TAG, "モデルダウンロードが完了しました。");
            } catch (IOException e) {
                AppLogger.e(TAG, "モデルダウンロードに失敗しました。", e);
                postToUi(() -> {
                    busy = false;
                    hideProgress();
                    refreshModelInfo();
                    statusText.setText(getMessageOrFallback(e, R.string.model_not_downloaded));
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
            if (legacyModelFile.exists()) {
                statusText.setText(R.string.legacy_model_status);
            } else {
                statusText.setText(R.string.model_required);
            }
            return;
        }

        if (!hasAudioPermission()) {
            pendingRecordingStart = true;
            AppLogger.i(TAG, "マイク権限を要求します。");
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
            AppLogger.i(TAG, "録音を開始しました。");
        } catch (IllegalStateException e) {
            AppLogger.e(TAG, "録音開始に失敗しました。", e);
            statusText.setText(getMessageOrFallback(e, R.string.permission_required));
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
        AppLogger.i(TAG, "録音停止と文字起こしを開始します。");

        backgroundExecutor.execute(() -> {
            try {
                float[] audioSamples = audioRecorder.stopAndRead();
                if (audioSamples.length == 0) {
                    throw new IllegalStateException(getString(R.string.recording_too_short));
                }

                AppLogger.i(
                        TAG,
                        "音声データを取得しました: samples=" + audioSamples.length
                                + ", duration=" + formatDuration(audioSamples.length)
                );
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
                AppLogger.i(TAG, "文字起こしが完了しました。");
            } catch (IOException | IllegalStateException e) {
                AppLogger.e(TAG, "文字起こしに失敗しました。", e);
                postToUi(() -> {
                    busy = false;
                    hideProgress();
                    refreshModelInfo();
                    statusText.setText(getMessageOrFallback(e, R.string.transcribe_failed));
                    updateUiState();
                });
            }
        });
    }

    private WhisperContext getOrCreateContext() throws IOException {
        if (whisperContext == null) {
            AppLogger.i(TAG, "Whisper コンテキストを作成します。");
            whisperContext = WhisperContext.create(modelFile);
        }
        return whisperContext;
    }

    private void closeWhisperContext() {
        if (whisperContext != null) {
            AppLogger.i(TAG, "Whisper コンテキストを解放します。");
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
                    .append(whisperContext == null ? "高速モデルを保存済み" : "推論準備完了");
        } else if (legacyModelFile.exists()) {
            builder.append("状態: 旧モデルを検出\n")
                    .append("旧モデル: ")
                    .append(legacyModelFile.getName())
                    .append('\n')
                    .append("旧モデルサイズ: ")
                    .append(Formatter.formatFileSize(this, legacyModelFile.length()))
                    .append('\n')
                    .append("備考: 速度改善のため新しい既定モデルをダウンロードしてください。");
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
        copyLogsButton.setEnabled(true);
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

    private CharSequence getInitialStatusText() {
        if (modelFile.exists()) {
            return getString(R.string.model_ready_status);
        }
        if (legacyModelFile.exists()) {
            return getString(R.string.legacy_model_status);
        }
        return getString(R.string.model_not_downloaded);
    }

    private void copyLogsToClipboard() {
        ClipboardManager clipboardManager = ContextCompat.getSystemService(this, ClipboardManager.class);
        if (clipboardManager == null) {
            AppLogger.w(TAG, "ClipboardManager を取得できませんでした。");
            return;
        }

        String logs = AppLogger.snapshot();
        clipboardManager.setPrimaryClip(ClipData.newPlainText("whisper-logs", logs));
        Toast.makeText(this, R.string.logs_copied, Toast.LENGTH_SHORT).show();
        AppLogger.i(TAG, "詳細ログをクリップボードへコピーしました。");
    }

    private void scheduleLogRefresh() {
        postToUi(this::refreshLogText);
    }

    private void refreshLogText() {
        if (logText == null) {
            return;
        }

        String logs = AppLogger.snapshot();
        logText.setText(logs.isEmpty() ? getString(R.string.log_placeholder) : logs);
        if (logScrollView != null) {
            logScrollView.post(() -> logScrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private String getMessageOrFallback(Exception exception, int fallbackResId) {
        return exception.getMessage() == null ? getString(fallbackResId) : exception.getMessage();
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
            AppLogger.i(TAG, "マイク権限が許可されたため録音を開始します。");
            startRecording();
            return;
        }

        pendingRecordingStart = false;
        AppLogger.w(TAG, "マイク権限が拒否されました。");
        statusText.setText(R.string.permission_required);
        updateUiState();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        AppLogger.i(TAG, "MainActivity を破棄します。");
        AppLogger.removeListener(logListener);
        if (whisperContext != null) {
            whisperContext.cancelTranscription();
        }
        audioRecorder.cancel();
        if (!busy) {
            closeWhisperContext();
        }
        backgroundExecutor.shutdownNow();
        super.onDestroy();
    }
}
