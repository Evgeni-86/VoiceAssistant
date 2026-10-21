package com.voiceassistant.audio;

import com.voiceassistant.audio.source.MicrophoneAudioSource;
import com.voiceassistant.audio.source.MixedAudioSource;
import com.voiceassistant.audio.source.SystemAudioSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AudioRecordingService implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(AudioRecordingService.class);

    private final Map<AudioSourceType, AudioSource> sources =
            new EnumMap<>(AudioSourceType.class);

    // Один поток гарантирует последовательность операций
    // start/stop без гонок между ними.
    private final ExecutorService executor =
            Executors.newSingleThreadExecutor();

    private AudioSource activeSource;
    private Path recordingPath;
    private boolean recording;
    private boolean closed;
    private long startedAtNanos;

    public AudioRecordingService() {

        sources.put(
                AudioSourceType.MICROPHONE,
                new MicrophoneAudioSource()
        );

        sources.put(
                AudioSourceType.SYSTEM,
                new SystemAudioSource()
        );

        sources.put(
                AudioSourceType.BOTH,
                new MixedAudioSource()
        );
    }


    /**
     * Асинхронно запускает запись выбранного источника.
     * Для каждой записи создаётся уникальный временный WAV.
     */
    public CompletableFuture<Path> startRecording(AudioSourceType type) {
        return CompletableFuture.supplyAsync(() -> {

            if (recording) {
                throw new IllegalStateException(
                        "Запись уже выполняется"
                );
            }

            AudioSource source = sources.get(type);

            if (source == null) {
                throw new IllegalArgumentException(
                        "Неизвестный источник: " + type
                );
            }

            Path path = null;

            try {
                // Создаём уникальный временный файл.
                path = Files.createTempFile(
                        "voiceassistant-" + type.name().toLowerCase() + "-",
                        ".wav"
                );

                source.startRecording(path);

                activeSource = source;
                recordingPath = path;
                recording = true;
                startedAtNanos = System.nanoTime();
                LOG.info("Recording started, source={}", type);

                return recordingPath;

            } catch (Exception e) {
                LOG.error("Recording start failed, source={}, errorType={}", type, e.getClass().getName());

                // Если запуск не удался, удаляем пустой файл.
                if (path != null) {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException cleanupError) {
                        e.addSuppressed(cleanupError);
                    }
                }

                throw new CompletionException(e);
            }

        }, executor);
    }


    /**
     * Асинхронно останавливает запись и возвращает WAV.
     */
    public CompletableFuture<Path> stopRecording() {
        return CompletableFuture.supplyAsync(() -> {

            if (!recording || activeSource == null) {
                throw new IllegalStateException(
                        "Нет активной записи"
                );
            }

            try {
                activeSource.stopRecording();

                try {
                    if (!Files.isRegularFile(recordingPath)
                            || Files.size(recordingPath) <= 44) {
                        throw new IllegalStateException(
                                "WAV-файл отсутствует или не содержит аудиоданных"
                        );
                    }
                } catch (IOException e) {
                    throw new IllegalStateException(
                            "Не удалось проверить WAV-файл", e
                    );
                }

                LOG.info("Recording finished, durationMs={}, sizeBytes={}",
                        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAtNanos),
                        recordingPath.toFile().length());
                return recordingPath;

            } catch (Exception error) {
                LOG.error("Recording stop failed, errorType={}", error.getClass().getName());
                throw error;

            } finally {
                recording = false;
                activeSource = null;
            }

        }, executor);
    }

    /**
     * Корректно завершает запись и освобождает ресурсы.
     * <p>
     * Вызывать из фонового потока, поскольку метод
     * ожидает завершения операций записи.
     */
    @Override
    public synchronized void close() {

        if (closed) {
            return;
        }

        closed = true;

        Future<?> stopTask = executor.submit(() -> {
            if (recording && activeSource != null) {
                try {
                    activeSource.stopRecording();
                } finally {
                    recording = false;
                    activeSource = null;
                }
            }
        });

        executor.shutdown();

        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException(
                        "Не удалось завершить запись за 30 секунд"
                );
            }

            stopTask.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "Завершение записи прервано", e
            );

        } catch (ExecutionException e) {
            throw new IllegalStateException(
                    "Ошибка при завершении записи",
                    e.getCause()
            );
        }
    }


}
