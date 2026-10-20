package com.voiceassistant.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Chat Completions DeepSeek: поток SSE читается в вызывающем фоновом потоке. */
public final class DeepSeekAiService implements AiService {
    private static final URI ENDPOINT = URI.create("https://api.deepseek.com/chat/completions");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiSettings settings;
    private final AiSettingsService settingsService;
    private final HttpClient client;
    private final AtomicReference<CompletableFuture<?>> activeResponse = new AtomicReference<>();
    private final AtomicReference<InputStream> activeBody = new AtomicReference<>();

    public DeepSeekAiService(AiSettings settings, AiSettingsService settingsService, HttpClient client) {
        this.settings = settings;
        this.settingsService = settingsService;
        this.client = client;
    }

    @Override
    public void streamMessage(String message, Consumer<String> onChunk, BooleanSupplier cancelled) throws Exception {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Введите текст сообщения для ИИ.");
        }
        checkCancelled(cancelled);
        String key = settingsService.readApiKey(AiMode.DEEPSEEK);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Не настроен API-ключ DeepSeek. Откройте настройки ИИ.");
        }
        for (int i = 0; i < key.length(); i++) {
            if (key.charAt(i) < '!' || key.charAt(i) > '~') {
                throw new IllegalStateException("API-ключ DeepSeek содержит недопустимые символы. Проверьте настройки ИИ.");
            }
        }

        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", settings.deepSeekModel());
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", settings.systemPrompt());
        messages.addObject().put("role", "user").put("content", message);
        payload.put("stream", true);
        // У приложения нет настройки глубины рассуждений; для краткого голосового ответа
        // выбираем поддерживаемый DeepSeek обычный режим без скрытого текста рассуждений.
        payload.putObject("thinking").put("type", "disabled");

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        key = null;

        CompletableFuture<HttpResponse<InputStream>> responseFuture =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        activeResponse.set(responseFuture);
        InputStream body = null;
        try {
            checkCancelled(cancelled);
            HttpResponse<InputStream> response = responseFuture.get();
            body = response.body();
            activeBody.set(body);
            checkCancelled(cancelled);
            if (response.statusCode() != 200) {
                throw new IOException(httpError(response.statusCode()));
            }
            readEvents(body, onChunk, cancelled);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Генерация ответа отменена.");
        } catch (ExecutionException error) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Генерация ответа отменена.");
            }
            throw networkError(error.getCause());
        } catch (IOException error) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Генерация ответа отменена.");
            }
            throw error;
        } finally {
            activeResponse.compareAndSet(responseFuture, null);
            activeBody.compareAndSet(body, null);
            if (body != null) {
                try { body.close(); } catch (IOException ignored) { }
            }
            responseFuture.cancel(true);
        }
    }

    @Override
    public void cancelActive() {
        CompletableFuture<?> response = activeResponse.get();
        if (response != null) response.cancel(true);
        InputStream body = activeBody.get();
        if (body != null) {
            try { body.close(); } catch (IOException ignored) { }
        }
    }

    private static void readEvents(InputStream body, Consumer<String> onChunk,
                                   BooleanSupplier cancelled) throws IOException {
        boolean done = false;
        boolean receivedText = false;
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while (!done && (line = reader.readLine()) != null) {
                checkCancelled(cancelled);
                if (line.isEmpty()) {
                    if (hasData) {
                        EventResult result = dispatch(data.toString(), onChunk, cancelled);
                        done = result.done();
                        receivedText |= result.text();
                        data.setLength(0);
                        hasData = false;
                    }
                } else if (line.startsWith("data:")) {
                    if (hasData) data.append('\n');
                    hasData = true;
                    String value = line.substring(5);
                    data.append(value.startsWith(" ") ? value.substring(1) : value);
                }
            }
            checkCancelled(cancelled);
            if (!done) {
                throw new IOException("Соединение с DeepSeek завершилось до конца ответа.");
            }
            if (!receivedText) {
                throw new IOException("DeepSeek вернул пустой ответ.");
            }
        }
    }

    private record EventResult(boolean done, boolean text) { }

    private static EventResult dispatch(String data, Consumer<String> onChunk,
                                        BooleanSupplier cancelled) throws IOException {
        if (data.isBlank()) return new EventResult(false, false);
        if (data.trim().equals("[DONE]")) return new EventResult(true, false);
        JsonNode event;
        try {
            event = JSON.readTree(data);
        } catch (IOException error) {
            throw new IOException("DeepSeek отправил некорректный JSON в потоке.", error);
        }
        if (event == null || !event.isObject()) {
            throw new IOException("DeepSeek отправил некорректное SSE-событие.");
        }
        if (event.hasNonNull("error")) {
            throw new IOException("Ошибка генерации DeepSeek. Проверьте модель, лимиты и состояние сервиса.");
        }
        JsonNode choices = event.path("choices");
        JsonNode content = choices.isArray() && !choices.isEmpty()
                ? choices.get(0).path("delta").path("content") : null;
        if (content != null && content.isTextual() && !content.textValue().isEmpty()) {
            checkCancelled(cancelled);
            onChunk.accept(content.textValue());
            return new EventResult(false, true);
        }
        return new EventResult(false, false);
    }

    private static void checkCancelled(BooleanSupplier cancelled) {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Генерация ответа отменена.");
        }
    }

    private static IOException networkError(Throwable error) {
        if (error instanceof UnknownHostException) return new IOException("Не удалось найти сервер DeepSeek.");
        if (error instanceof ConnectException) return new IOException("Нет соединения с DeepSeek. Проверьте интернет.");
        if (error instanceof HttpTimeoutException || error instanceof java.net.SocketTimeoutException) {
            return new IOException("Превышено время ожидания DeepSeek.");
        }
        return new IOException("Ошибка соединения с DeepSeek.");
    }

    private static String httpError(int status) {
        return switch (status) {
            case 400, 422 -> "DeepSeek отклонил запрос. Проверьте выбранную модель и настройки ИИ.";
            case 401 -> "Неверный API-ключ DeepSeek. Проверьте настройки ИИ.";
            case 402 -> "Недостаточно средств на балансе DeepSeek API.";
            case 403 -> "DeepSeek запретил доступ к запросу.";
            case 404 -> "Выбранная модель DeepSeek недоступна.";
            case 408 -> "Превышено время ожидания DeepSeek. Повторите запрос.";
            case 429 -> "Превышен лимит запросов DeepSeek. Повторите позже.";
            case 500, 502, 503, 504 -> "Сервер DeepSeek временно недоступен. Повторите позже.";
            default -> "DeepSeek вернул HTTP " + status + ".";
        };
    }
}
