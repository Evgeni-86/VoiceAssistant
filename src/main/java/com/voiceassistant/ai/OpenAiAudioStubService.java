package com.voiceassistant.ai;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Локальная заглушка прямой обработки WAV без распознавания и сетевых запросов. */
public final class OpenAiAudioStubService implements AiService {
    @Override
    public void streamMessage(String message, Consumer<String> onChunk, BooleanSupplier cancelled) {
        throw new UnsupportedOperationException("OpenAI Audio ожидает запись WAV.");
    }

    @Override
    public void streamAudio(Path wav, AiSettings settings, AiSettingsService settingsService,
                            Consumer<String> onChunk, BooleanSupplier cancelled) throws Exception {
        if (!Files.isRegularFile(wav) || !Files.isReadable(wav)) {
            throw new IllegalArgumentException("WAV-файл отсутствует или недоступен.");
        }
        try (var input = Files.newInputStream(wav)) {
            byte[] header = input.readNBytes(12);
            if (header.length != 12 || !Arrays.equals(Arrays.copyOfRange(header, 0, 4),
                    new byte[]{'R', 'I', 'F', 'F'}) || !Arrays.equals(Arrays.copyOfRange(header, 8, 12),
                    new byte[]{'W', 'A', 'V', 'E'})) {
                throw new IllegalArgumentException("Ожидался WAV-файл.");
            }
        }
        if (settings.systemPrompt().isBlank()) {
            throw new IllegalArgumentException("Системный промпт не задан.");
        }
        // settings.openAiAudioModel() и settingsService.readApiKey(OPENAI_AUDIO)
        // доступны будущему обработчику; заглушка ключ не читает.
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Генерация ответа отменена");
        }
        onChunk.accept("Это тестовый ответ OpenAI Audio на записанный WAV-файл.");
    }
}
