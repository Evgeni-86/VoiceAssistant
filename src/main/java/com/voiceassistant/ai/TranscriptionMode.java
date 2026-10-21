package com.voiceassistant.ai;

public enum TranscriptionMode {
    LOCAL("Локально (Whisper.cpp)"),
    REMOTE("Удалённо (API)");

    private final String label;

    TranscriptionMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
