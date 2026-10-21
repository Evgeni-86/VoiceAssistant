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
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Chat Completions OpenRouter: поток SSE читается в вызывающем фоновом потоке. */
public final class OpenRouterAiService implements AiService {
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final AiSettings settings;
    private final AiSettingsService settingsService;
    private final HttpClient client;
    private final ConversationContextService.Snapshot context;
    private final AtomicReference<CompletableFuture<?>> activeResponse = new AtomicReference<>();
    private final AtomicReference<InputStream> activeBody = new AtomicReference<>();

    public OpenRouterAiService(AiSettings settings, AiSettingsService settingsService, HttpClient client,
                               ConversationContextService.Snapshot context) {
        this.settings = settings;
        this.settingsService = settingsService;
        this.client = client;
        this.context = context;
    }

    @Override
    public void streamMessage(String message, Consumer<String> onChunk, BooleanSupplier cancelled) throws Exception {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Введите текст сообщения для ИИ.");
        }
        checkCancelled(cancelled);
        String key = settingsService.readApiKey(AiMode.OPENROUTER);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Не настроен API-ключ OpenRouter. Откройте настройки ИИ.");
        }

        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", settings.openRouterModel());
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", settings.systemPrompt());
        for (var exchange : context.textExchanges()) {
            messages.addObject().put("role", "user").put("content", exchange.request());
            messages.addObject().put("role", "assistant").put("content", exchange.response());
        }
        messages.addObject().put("role", "user").put("content", message);
        payload.put("stream", true);
        payload.putObject("reasoning").put("enabled", false);

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofMinutes(10))
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
                throw new IOException(httpError(response.statusCode(), body));
            }
            readEvents(body, onChunk, cancelled);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Генерация ответа отменена.");
        } catch (ExecutionException error) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Генерация ответа отменена.");
            }
            throw networkError(error.getCause());
        } catch (IOException error) {
            if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("Генерация ответа отменена.");
            }
            if (error.getMessage() != null && (error.getMessage().startsWith("OpenRouter")
                    || error.getMessage().startsWith("Соединение с OpenRouter")
                    || error.getMessage().startsWith("Не удалось")
                    || error.getMessage().startsWith("Неверный")
                    || error.getMessage().startsWith("Недостаточно")
                    || error.getMessage().startsWith("Превышен")
                    || error.getMessage().startsWith("Модель")
                    || error.getMessage().startsWith("Ошибка сервера")
                    || error.getMessage().startsWith("Ошибка провайдера"))) {
                throw error;
            }
            throw new IOException("Соединение с OpenRouter прервалось во время генерации.", error);
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
        if (response != null) {
            response.cancel(true);
        }
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
                throw new IOException("Соединение с OpenRouter завершилось до конца ответа.");
            }
            if (!receivedText) {
                throw new IOException("OpenRouter вернул пустой ответ.");
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
            throw new IOException("OpenRouter отправил некорректный JSON в потоке.", error);
        }
        if (event == null || !event.isObject()) {
            throw new IOException("OpenRouter отправил некорректное SSE-событие.");
        }
        if (event.hasNonNull("error")) {
            throw new IOException("Ошибка генерации OpenRouter. Проверьте модель, лимиты и состояние сервиса.");
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
        if (error instanceof UnknownHostException) return new IOException("Не удалось найти сервер OpenRouter.", error);
        if (error instanceof ConnectException) return new IOException("Нет соединения с OpenRouter. Проверьте интернет.", error);
        if (error instanceof java.net.http.HttpTimeoutException || error instanceof java.net.SocketTimeoutException) {
            return new IOException("Превышено время ожидания OpenRouter.", error);
        }
        return new IOException("Ошибка соединения с OpenRouter.", error);
    }

    private static String httpError(int status, InputStream body) {
        String fallback = switch (status) {
            case 400 -> "OpenRouter отклонил запрос: проверьте настройки модели.";
            case 401 -> "Неверный API-ключ OpenRouter. Проверьте настройки ИИ.";
            case 402 -> "Недостаточно средств или кредитов OpenRouter.";
            case 403 -> "OpenRouter запретил доступ к запросу.";
            case 404 -> "Модель или API OpenRouter не найдены.";
            case 429 -> "Превышен лимит запросов OpenRouter. Повторите позже.";
            case 500 -> "Ошибка сервера OpenRouter. Повторите позже.";
            case 502 -> "Ошибка провайдера модели OpenRouter. Повторите позже.";
            case 503 -> "Модель или провайдер OpenRouter временно недоступны.";
            default -> "OpenRouter вернул HTTP " + status + ".";
        };
        try {
            JsonNode response = JSON.readTree(body.readNBytes(16 * 1024));
            if (response == null) return fallback;
            JsonNode error = response.path("error");
            String message = error.isTextual() ? error.textValue() : error.path("message").asText(null);
            if (message != null && !message.isBlank()) {
                String detail = message.replaceAll("[\\p{Cntrl}\\s]+", " ").trim();
                if (detail.length() > 500) detail = detail.substring(0, 500) + "…";
                return "OpenRouter HTTP " + status + ": " + detail;
            }
        } catch (IOException ignored) {
            // При пустом или некорректном теле ответа показываем сообщение по HTTP-статусу.
        }
        return fallback;
    }
}
