package com.micklab.whisper;

final class WhisperJni {
    static {
        System.loadLibrary("whisper-jni");
    }

    private WhisperJni() {
    }

    static native long initContext(String modelPath);

    static native void freeContext(long contextPointer);

    static native int fullTranscribe(long contextPointer, int numThreads, float[] audioData, String language, boolean includeTimestamps);

    static native int getTextSegmentCount(long contextPointer);

    static native String getTextSegment(long contextPointer, int index);

    static native long getTextSegmentT0(long contextPointer, int index);

    static native long getTextSegmentT1(long contextPointer, int index);
}
