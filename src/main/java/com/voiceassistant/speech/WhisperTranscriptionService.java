package com.voiceassistant.speech;

import com.voiceassistant.audio.processing.AudioMixer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Запускает локальный whisper-cli для сохранённого WAV. */
public class WhisperTranscriptionService implements AutoCloseable {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AudioMixer audioMixer = new AudioMixer();

    public CompletableFuture<String> transcribe(Path recording) {
        return CompletableFuture.supplyAsync(() -> transcribeNow(recording), executor);
    }

    private String transcribeNow(Path recording) {
        Path cli = configuredPath("VOICEASSISTANT_WHISPER_CLI");
        Path model = configuredPath("VOICEASSISTANT_WHISPER_MODEL");

        if (!Files.isRegularFile(cli)) {
            throw new IllegalStateException("Не найден whisper-cli: " + cli);
        }
        if (!Files.isRegularFile(model)) {
            throw new IllegalStateException("Не найдена модель Whisper: " + model);
        }
        if (!Files.isRegularFile(recording)) {
            throw new IllegalStateException("Не найдена аудиозапись: " + recording);
        }

        Path workDir;
        try {
            workDir = Files.createTempDirectory("voiceassistant-whisper-");
        } catch (IOException e) {
            throw new IllegalStateException("Не удалось создать временный каталог", e);
        }

        try {
            Path input = workDir.resolve("input.wav");
            Path outputBase = workDir.resolve("transcript");
            Path log = workDir.resolve("whisper.log");
            audioMixer.convertForWhisper(recording, input);

            Process process = new ProcessBuilder(
                    cli.toString(),
                    "--model", model.toString(),
                    "--file", input.toString(),
                    "--language", "auto",
                    "--output-txt",
                    "--output-file", outputBase.toString(),
                    "--no-prints"
            ).redirectErrorStream(true).redirectOutput(log.toFile()).start();

            int code;
            try {
                code = process.waitFor();
            } catch (InterruptedException e) {
                process.destroyForcibly();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Распознавание прервано", e);
            }

            if (code != 0) {
                String details = Files.readString(log, StandardCharsets.UTF_8);
                throw new IllegalStateException("whisper-cli завершился с кодом " + code
                        + ": " + details.substring(0, Math.min(details.length(), 500)));
            }

            Path transcript = workDir.resolve("transcript.txt");
            if (!Files.isRegularFile(transcript)) {
                throw new IllegalStateException("whisper-cli не создал файл с текстом");
            }
            return Files.readString(transcript, StandardCharsets.UTF_8).trim();
        } catch (IOException e) {
            throw new IllegalStateException("Ошибка при запуске whisper-cli", e);
        } finally {
            try (var paths = Files.walk(workDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        path.toFile().deleteOnExit();
                    }
                });
            } catch (IOException ignored) {
                workDir.toFile().deleteOnExit();
            }
        }
    }

    private Path configuredPath(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Задайте переменную окружения " + name);
        }
        return Path.of(value).toAbsolutePath();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
