package com.micklab.whisper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.HttpURLConnection;
import java.net.URL;

final class ModelDownloader {
    interface ProgressListener {
        void onProgress(long downloadedBytes, long totalBytes);
    }

    File download(String urlString, File destinationFile, ProgressListener progressListener) throws IOException {
        File parentDirectory = destinationFile.getParentFile();
        if (parentDirectory == null) {
            throw new IOException("モデルの保存先を作成できません。");
        }
        if (!parentDirectory.exists() && !parentDirectory.mkdirs()) {
            throw new IOException("モデル保存用ディレクトリを作成できません。");
        }

        File tempFile = new File(parentDirectory, destinationFile.getName() + ".part");
        boolean success = false;

        HttpURLConnection connection = (HttpURLConnection) new URL(urlString).openConnection();
        connection.setInstanceFollowRedirects(true);
        connection.setConnectTimeout(15_000);
        connection.setReadTimeout(30_000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        connection.setRequestProperty("User-Agent", "WhisperAndroid/1.0");

        try {
            connection.connect();
            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                throw new IOException("モデルのダウンロードに失敗しました: HTTP " + responseCode);
            }

            long totalBytes = connection.getContentLengthLong();
            if (progressListener != null) {
                progressListener.onProgress(0L, totalBytes);
            }

            try (InputStream inputStream = connection.getInputStream();
                 FileOutputStream outputStream = new FileOutputStream(tempFile)) {
                byte[] buffer = new byte[8192];
                long downloadedBytes = 0L;
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedIOException("モデルのダウンロードを中断しました。");
                    }

                    outputStream.write(buffer, 0, read);
                    downloadedBytes += read;

                    if (progressListener != null) {
                        progressListener.onProgress(downloadedBytes, totalBytes);
                    }
                }
                outputStream.flush();
            }

            if (destinationFile.exists() && !destinationFile.delete()) {
                throw new IOException("既存モデルを置き換えられませんでした。");
            }
            if (!tempFile.renameTo(destinationFile)) {
                throw new IOException("ダウンロード済みモデルを保存できませんでした。");
            }

            success = true;
            return destinationFile;
        } finally {
            connection.disconnect();
            if (!success && tempFile.exists() && !tempFile.delete()) {
                tempFile.deleteOnExit();
            }
        }
    }
}
