package com.voiceassistant.audio.source;

import com.voiceassistant.audio.AudioSource;
import com.voiceassistant.audio.processing.AudioMixer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class MixedAudioSource implements AudioSource {

    private final MicrophoneAudioSource microphone =
            new MicrophoneAudioSource();

    private final SystemAudioSource system =
            new SystemAudioSource();

    private final AudioMixer audioMixer = new AudioMixer();

    private Path microphonePath;
    private Path systemPath;
    private Path outputPath;
    private Path tempDirectory;

    private boolean recording;

    @Override
    public synchronized void startRecording(Path outputPath) {
        if (recording) {
            throw new IllegalStateException(
                    "Совместная запись уже выполняется"
            );
        }

        boolean microphoneStarted = false;

        try {
            this.outputPath = outputPath.toAbsolutePath();

            // Для каждой записи создаём отдельную директорию.
            tempDirectory = Files.createTempDirectory(
                    "voiceassistant-mix-"
            );

            microphonePath = tempDirectory.resolve("microphone.wav");
            systemPath = tempDirectory.resolve("system.wav");

            microphone.startRecording(microphonePath);
            microphoneStarted = true;

            system.startRecording(systemPath);

            recording = true;

        } catch (Exception e) {

            if (microphoneStarted) {
                try {
                    microphone.stopRecording();
                } catch (Exception stopError) {
                    e.addSuppressed(stopError);
                }
            }

            try {
                system.stopRecording();
            } catch (Exception stopError) {
                e.addSuppressed(stopError);
            }

            // Удаляем файлы при неудачном запуске.
            cleanup();

            throw new IllegalStateException(
                    "Не удалось запустить совместную запись", e
            );
        }
    }

    @Override
    public synchronized void stopRecording() {
        if (!recording) {
            return;
        }

        recording = false;

        RuntimeException failure = null;

        try {
            // Пытаемся остановить оба источника.
            try {
                microphone.stopRecording();
            } catch (RuntimeException e) {
                failure = e;
            }

            try {
                system.stopRecording();
            } catch (RuntimeException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            }

            if (failure != null) {
                throw failure;
            }

            // Объединяем WAV только после успешной остановки.
            audioMixer.mix(
                    microphonePath,
                    systemPath,
                    outputPath
            );

        } finally {
            // Очистка выполняется даже при ошибках.
            cleanup();
        }
    }

    /**
     * Удаляет временные WAV и их директорию.
     * Ошибки очистки не должны скрывать основную ошибку.
     */
    private void cleanup() {
        deleteQuietly(microphonePath);
        deleteQuietly(systemPath);
        deleteQuietly(tempDirectory);

        microphonePath = null;
        systemPath = null;
        tempDirectory = null;
    }

    private void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }

        try {
            Files.deleteIfExists(path);
        } catch (IOException e) {
            System.err.println(
                    "Не удалось удалить временный файл: "
                            + path + ". " + e.getMessage()
            );
        }
    }
}
