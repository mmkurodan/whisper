package com.micklab.whisper;

final class WhisperJni {
    static final int RESULT_OK = 0;
    static final int RESULT_TIMEOUT = -2;
    static final int RESULT_CANCELLED = -3;

    static {
        System.loadLibrary("whisper-jni");
    }

    private WhisperJni() {
    }

    static native long initContext(String modelPath);

    static native void freeContext(long contextPointer);

    static native void cancelTranscription(long contextPointer);

    static native int fullTranscribe(
            long contextPointer,
            int numThreads,
            float[] audioData,
            String language,
            boolean includeTimestamps,
            int timeoutMs
    );

    static native int getTextSegmentCount(long contextPointer);

    static native String getTextSegment(long contextPointer, int index);

    static native long getTextSegmentT0(long contextPointer, int index);

    static native long getTextSegmentT1(long contextPointer, int index);

    static void dispatchNativeLog(int nativeLevel, String message) {
        AppLogger.nativeLog(nativeLevel, message);
    }
}
