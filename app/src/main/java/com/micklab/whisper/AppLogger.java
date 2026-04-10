package com.micklab.whisper;

import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArraySet;

final class AppLogger {
    interface Listener {
        void onLogsChanged();
    }

    private static final int MAX_LINES = 2_000;
    private static final int MAX_CHARS = 200_000;

    private static final Object LOCK = new Object();
    private static final ArrayDeque<String> LOG_LINES = new ArrayDeque<>();
    private static final CopyOnWriteArraySet<Listener> LISTENERS = new CopyOnWriteArraySet<>();
    private static final ThreadLocal<SimpleDateFormat> TIMESTAMP_FORMAT = ThreadLocal.withInitial(
            () -> new SimpleDateFormat("HH:mm:ss.SSS", Locale.JAPAN)
    );

    private static int totalChars;

    private AppLogger() {
    }

    static void d(String tag, String message) {
        log(Log.DEBUG, tag, message, null);
    }

    static void i(String tag, String message) {
        log(Log.INFO, tag, message, null);
    }

    static void w(String tag, String message) {
        log(Log.WARN, tag, message, null);
    }

    static void w(String tag, String message, Throwable throwable) {
        log(Log.WARN, tag, message, throwable);
    }

    static void e(String tag, String message) {
        log(Log.ERROR, tag, message, null);
    }

    static void e(String tag, String message, Throwable throwable) {
        log(Log.ERROR, tag, message, throwable);
    }

    static void nativeLog(int nativeLevel, String message) {
        log(mapNativeLevel(nativeLevel), "WhisperNative", sanitizeMessage(message), null);
    }

    static void addListener(Listener listener) {
        LISTENERS.add(listener);
        listener.onLogsChanged();
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    static String snapshot() {
        synchronized (LOCK) {
            if (LOG_LINES.isEmpty()) {
                return "";
            }

            StringBuilder builder = new StringBuilder(totalChars + LOG_LINES.size());
            boolean first = true;
            for (String line : LOG_LINES) {
                if (!first) {
                    builder.append('\n');
                }
                builder.append(line);
                first = false;
            }
            return builder.toString();
        }
    }

    private static void log(int priority, String tag, String message, Throwable throwable) {
        String mergedMessage = sanitizeMessage(message);
        if (throwable != null) {
            String stackTrace = Log.getStackTraceString(throwable);
            if (!stackTrace.isEmpty()) {
                mergedMessage = mergedMessage.isEmpty() ? stackTrace : mergedMessage + '\n' + stackTrace;
            }
        }

        if (mergedMessage.isEmpty()) {
            return;
        }

        String[] lines = mergedMessage.replace("\r\n", "\n").split("\n");
        for (String line : lines) {
            String safeLine = line == null ? "" : line;
            Log.println(priority, tag, safeLine);
            appendLine(formatLine(priority, tag, safeLine));
        }

        notifyListeners();
    }

    private static void appendLine(String line) {
        synchronized (LOCK) {
            LOG_LINES.addLast(line);
            totalChars += line.length();

            while (!LOG_LINES.isEmpty() && (LOG_LINES.size() > MAX_LINES || totalChars > MAX_CHARS)) {
                String removed = LOG_LINES.removeFirst();
                totalChars -= removed.length();
            }
        }
    }

    private static void notifyListeners() {
        for (Listener listener : LISTENERS) {
            listener.onLogsChanged();
        }
    }

    private static String formatLine(int priority, String tag, String message) {
        String timestamp = TIMESTAMP_FORMAT.get().format(new Date());
        return String.format(Locale.JAPAN, "%s %s/%s: %s", timestamp, toPriorityLetter(priority), tag, message);
    }

    private static char toPriorityLetter(int priority) {
        switch (priority) {
            case Log.DEBUG:
                return 'D';
            case Log.INFO:
                return 'I';
            case Log.WARN:
                return 'W';
            case Log.ERROR:
                return 'E';
            default:
                return '?';
        }
    }

    private static int mapNativeLevel(int nativeLevel) {
        switch (nativeLevel) {
            case 1:
                return Log.DEBUG;
            case 2:
            case 5:
                return Log.INFO;
            case 3:
                return Log.WARN;
            case 4:
                return Log.ERROR;
            default:
                return Log.INFO;
        }
    }

    private static String sanitizeMessage(String message) {
        if (message == null) {
            return "";
        }

        int end = message.length();
        while (end > 0) {
            char lastChar = message.charAt(end - 1);
            if (lastChar != '\n' && lastChar != '\r') {
                break;
            }
            end--;
        }
        return message.substring(0, end);
    }
}
