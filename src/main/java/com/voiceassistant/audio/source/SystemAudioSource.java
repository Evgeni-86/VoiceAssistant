package com.voiceassistant.audio.source;

import com.voiceassistant.audio.AudioSource;

import java.nio.file.Path;
import java.util.Objects;

public class SystemAudioSource implements AudioSource {

    static {
        System.loadLibrary("WasapiCapture");
    }

    /**
     * Начинает запись системного звука.
     * Нативный метод запускает отдельный поток.
     */
    private native void startNative(String outputPath);

    /**
     * Останавливает запись и завершает WAV-файл.
     */
    private native void stopNative();

    public void startRecording(Path outputPath) {
        Objects.requireNonNull(outputPath);
        startNative(outputPath.toAbsolutePath().toString());
    }

    public void stopRecording() {
        stopNative();
    }
}
