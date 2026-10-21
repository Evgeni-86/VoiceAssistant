package com.voiceassistant.speech;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.voiceassistant.ai.AiMode;
import com.voiceassistant.ai.settings.AiSettingsService;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Отправляет сохранённый WAV в специализированный API транскрипции OpenRouter. */
public final class OpenRouterTranscriptionService implements TranscriptionService {
    private static final Logger LOG = LoggerFactory.getLogger(OpenRouterTranscriptionService.class);
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/audio/transcriptions");
    private static final long MAX_WAV_BYTES = 25L * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiSettingsService settingsService;
    private final HttpClient client;
    private final Executor executor;
    private final String model;

    public OpenRouterTranscriptionService(AiSettingsService settingsService, HttpClient client, Executor executor,
                                          String model) {
        this.settingsService = settingsService;
        this.client = client;
        this.executor = executor;
        this.model = model;
    }

    @Override
    public CompletableFuture<String> transcribe(Path recording) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return transcribeNow(recording);
            } catch (IOException error) {
                LOG.error("Recognition failed, provider=OPENROUTER, errorType={}", error.getClass().getName());
                throw new CompletionException(error);
            } catch (CancellationException error) {
                LOG.warn("Recognition cancelled, provider=OPENROUTER");
                throw error;
            }
        }, executor);
    }

    private String transcribeNow(Path recording) throws IOException {
        long startedAt = System.nanoTime();
        LOG.info("Recognition started, provider=OPENROUTER, model={}", model);
        if (recording == null || !Files.isRegularFile(recording) || !Files.isReadable(recording)) {
            throw new IOException("WAV-файл отсутствует или недоступен.");
        }
        long size = Files.size(recording);
        if (size <= 44) {
            throw new IOException("WAV-файл пуст или не содержит аудиоданных.");
        }
        if (size > MAX_WAV_BYTES) {
            throw new IOException("WAV-файл превышает лимит приложения 25 МБ для удалённой транскрипции.");
        }
        byte[] audio = Files.readAllBytes(recording);
        if (audio.length <= 44 || !hasWaveHeader(audio)) {
            throw new IOException("Аудиозапись не является корректным WAV-файлом.");
        }

        String key = settingsService.readApiKey(AiMode.OPENROUTER);
        if (key == null || key.isBlank()) {
            throw new IOException("Не настроен API-ключ OpenRouter. Откройте настройки ИИ.");
        }
        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", model);
        payload.putObject("input_audio")
                .put("data", Base64.getEncoder().encodeToString(audio))
                .put("format", "wav");

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofSeconds(75))
                .header("Authorization", "Bearer " + key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            LOG.debug("Recognition HTTP request sent, provider=OPENROUTER, sizeBytes={}", size);
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException error) {
            throw new IOException("Превышено время ожидания транскрипции OpenRouter.", error);
        } catch (UnknownHostException error) {
            throw new IOException("Не удалось найти сервер OpenRouter.", error);
        } catch (ConnectException error) {
            throw new IOException("Нет соединения с OpenRouter. Проверьте интернет.", error);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Транскрипция прервана.");
        } catch (IOException error) {
            throw new IOException("Ошибка соединения с OpenRouter при транскрипции.", error);
        }

        LOG.info("Recognition HTTP response received, provider=OPENROUTER, status={}, durationMs={}",
                response.statusCode(), TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException(httpError(response.statusCode()));
        }
        String transcript = parseTranscript(response.body());
        LOG.info("Recognition completed, provider=OPENROUTER, durationMs={}",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        return transcript;
    }

    static String parseTranscript(String body) throws IOException {
        JsonNode result;
        try {
            result = JSON.readTree(body);
        } catch (IOException error) {
            throw new IOException("OpenRouter вернул некорректный JSON транскрипции.", error);
        }
        if (result == null || !result.isObject() || !result.path("text").isTextual()) {
            throw new IOException("В ответе OpenRouter отсутствует текст транскрипции.");
        }
        String text = result.path("text").textValue();
        if (text.isBlank()) {
            throw new IOException("OpenRouter вернул пустую транскрипцию.");
        }
        return text;
    }

    private static boolean hasWaveHeader(byte[] audio) {
        return audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F'
                && audio[8] == 'W' && audio[9] == 'A' && audio[10] == 'V' && audio[11] == 'E'
                && Integer.toUnsignedLong(ByteBuffer.wrap(audio).order(ByteOrder.LITTLE_ENDIAN).getInt(4)) + 8
                == audio.length;
    }

    private static String httpError(int status) {
        return switch (status) {
            case 400 -> "OpenRouter отклонил WAV или параметры транскрипции.";
            case 401 -> "Неверный API-ключ OpenRouter. Проверьте настройки ИИ.";
            case 402 -> "Недостаточно средств или кредитов OpenRouter.";
            case 403 -> "OpenRouter запретил доступ к транскрипции.";
            case 404 -> "Модель или API транскрипции OpenRouter не найдены.";
            case 413 -> "OpenRouter отклонил слишком большой аудиофайл.";
            case 429 -> "Превышен лимит запросов OpenRouter. Повторите позже.";
            case 500, 502, 503, 504 -> "Ошибка сервера OpenRouter при транскрипции. Повторите позже.";
            default -> "OpenRouter вернул HTTP " + status + " при транскрипции.";
        };
    }
}
