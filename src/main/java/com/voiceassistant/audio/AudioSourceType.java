package com.voiceassistant.audio;

public enum AudioSourceType {

    MICROPHONE("Микрофон"),
    SYSTEM("Системный звук"),
    BOTH("Микрофон + системный звук");

    private final String displayName;

    AudioSourceType(String displayName) {
        this.displayName = displayName;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
