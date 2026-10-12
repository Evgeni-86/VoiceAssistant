package com.voiceassistant.ai;

import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Локальная заглушка до подключения настоящего ИИ-сервиса. */
public class StubAiService implements AiService {
    private static final int CHUNK_SIZE = 8;
    private static final long CHUNK_DELAY_MS = 50;

    @Override
    public void streamMessage(String message, Consumer<String> onChunk, BooleanSupplier cancelled)
            throws InterruptedException {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Введите текст сообщения для ИИ.");
        }

        String response = "Это тестовый ответ ИИ. Получено сообщение: " + message.trim()
                + ". В будущем здесь будет ответ модели DeepSeek.".repeat(21);
        for (int offset = 0; offset < response.length(); offset += CHUNK_SIZE) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Генерация ответа отменена");
            }
            onChunk.accept(response.substring(offset, Math.min(offset + CHUNK_SIZE, response.length())));
            if (offset + CHUNK_SIZE < response.length()) {
                Thread.sleep(CHUNK_DELAY_MS);
            }
        }
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Генерация ответа отменена");
        }
    }
}
