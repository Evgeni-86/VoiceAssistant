package com.voiceassistant.ai.openrouter;

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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.TimeUnit;

import com.voiceassistant.ai.AiMode;
import com.voiceassistant.ai.AiService;
import com.voiceassistant.ai.AiSettings;
import com.voiceassistant.ai.settings.AiSettingsService;
import com.voiceassistant.ai.conversation.ConversationContextService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Chat Completions OpenRouter с WAV: поток SSE читается в вызывающем фоновом потоке. */
public final class OpenRouterAudioAiService implements AiService {
    private static final Logger LOG = LoggerFactory.getLogger(OpenRouterAudioAiService.class);
    private static final URI ENDPOINT = URI.create("https://openrouter.ai/api/v1/chat/completions");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String AUDIO_REQUEST_INSTRUCTION = """
            Распознай речь пользователя из аудиозаписи максимально близко к исходной формулировке.
            Ответь на запрос пользователя согласно системной инструкции.
            Верни результат в двух блоках:
            <QUESTION>распознанная речь пользователя</QUESTION>
            <ANSWER>
            ответ на запрос пользователя
            </ANSWER>
            Не добавляй текст вне блоков. Метки блоков только разделяют вопрос и ответ.
            Внутри ANSWER сохраняй читаемую структуру: разделяй абзацы пустой строкой,
            каждый пункт списка и каждый пример кода начинай с новой строки.
            Не сжимай весь ответ в одну строку.
            """;

    private final AiSettings settings;
    private final AiSettingsService settingsService;
    private final HttpClient client;
    private final ConversationContextService.Snapshot context;
    private final AtomicReference<CompletableFuture<?>> activeResponse = new AtomicReference<>();
    private final AtomicReference<InputStream> activeBody = new AtomicReference<>();

    public OpenRouterAudioAiService(AiSettings settings, AiSettingsService settingsService, HttpClient client,
                                    ConversationContextService.Snapshot context) {
        this.settings = settings;
        this.settingsService = settingsService;
        this.client = client;
        this.context = context;
    }

    @Override
    public void streamMessage(String message, Consumer<String> onChunk, BooleanSupplier cancelled) {
        throw new UnsupportedOperationException("Для OpenRouter Audio необходима аудиозапись.");
    }

    @Override
    public void streamAudio(Path wav, AiSettings ignoredSettings, AiSettingsService ignoredSettingsService,
                            Consumer<String> onChunk, BooleanSupplier cancelled) throws Exception {
        streamAudio(wav, ignoredSettings, ignoredSettingsService, question -> { }, onChunk, cancelled);
    }

    public void streamAudio(Path wav, AiSettings ignoredSettings, AiSettingsService ignoredSettingsService,
                            Consumer<String> onQuestion, Consumer<String> onChunk,
                            BooleanSupplier cancelled) throws Exception {
        long startedAt = System.nanoTime();
        LOG.info("AI request started, mode=OPENROUTER_AUDIO, model={}", settings.openRouterAudioModel());
        LOG.debug("Context prepared, audioResponses={}, depth={}, streaming=true",
                context.audioResponses().size(), settings.contextDepth());
        if (wav == null || !Files.isRegularFile(wav) || !Files.isReadable(wav) || Files.size(wav) <= 44) {
            throw new IOException("WAV-файл отсутствует или не содержит аудиоданных.");
        }
        checkCancelled(cancelled);
        byte[] audio = Files.readAllBytes(wav);
        LOG.debug("Audio prepared, sizeBytes={}", audio.length);
        if (audio.length <= 44 || !hasWaveHeader(audio)) {
            throw new IOException("Аудиозапись не является корректным WAV-файлом.");
        }
        checkCancelled(cancelled);
        String key = settingsService.readApiKey(AiMode.OPENROUTER);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("Не настроен API-ключ OpenRouter. Откройте настройки ИИ.");
        }

        ObjectNode payload = JSON.createObjectNode();
        payload.put("model", settings.openRouterAudioModel());
        var messages = payload.putArray("messages");
        messages.addObject().put("role", "system").put("content", settings.systemPrompt());
        if (!context.audioResponses().isEmpty()) {
            messages.addObject().put("role", "system").put("content",
                    ConversationContextService.audioContextBlock(context.audioResponses()));
        }
        var content = messages.addObject().put("role", "user").putArray("content");
        content.addObject().put("type", "text").put("text", AUDIO_REQUEST_INSTRUCTION);
        content.addObject().put("type", "input_audio").putObject("input_audio")
                .put("data", Base64.getEncoder().encodeToString(audio)).put("format", "wav");
        payload.putArray("modalities").add("text");
        payload.put("stream", true);

        HttpRequest request = HttpRequest.newBuilder(ENDPOINT)
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + key)
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(payload), StandardCharsets.UTF_8))
                .build();
        key = null;

        CompletableFuture<HttpResponse<InputStream>> responseFuture =
                client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        LOG.debug("HTTP request sent");
        activeResponse.set(responseFuture);
        InputStream body = null;
        try {
            checkCancelled(cancelled);
            HttpResponse<InputStream> response = responseFuture.get();
            body = response.body();
            activeBody.set(body);
            checkCancelled(cancelled);
            LOG.info("HTTP response received, status={}, durationMs={}", response.statusCode(),
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
            if (response.statusCode() != 200) {
                if (response.statusCode() >= 500) LOG.error("AI provider HTTP failure, status={}", response.statusCode());
                else LOG.warn("AI provider HTTP rejection, status={}", response.statusCode());
                throw new IOException(httpError(response.statusCode(), body));
            }
            readEvents(body, onQuestion, onChunk, cancelled);
            LOG.info("AI request completed, durationMs={}",
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Генерация ответа отменена.");
        } catch (ExecutionException error) {
            if (cancelled.getAsBoolean()) {
                throw new CancellationException("Генерация ответа отменена.");
            }
            LOG.error("AI connection failed, causeType={}", error.getCause() == null
                    ? "unknown" : error.getCause().getClass().getName());
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

    private static void readEvents(InputStream body, Consumer<String> onQuestion, Consumer<String> onChunk,
                                   BooleanSupplier cancelled) throws IOException {
        boolean done = false;
        boolean receivedText = false;
        boolean receivedAudio = false;
        StructuredResponse structured = new StructuredResponse(onQuestion, onChunk);
        LOG.debug("Structured audio response processing started");
        StringBuilder data = new StringBuilder();
        boolean hasData = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8))) {
            String line;
            while (!done && (line = reader.readLine()) != null) {
                checkCancelled(cancelled);
                if (line.isEmpty()) {
                    if (hasData) {
                        EventResult result = dispatch(data.toString(), structured, cancelled);
                        done = result.done();
                        receivedText |= result.text();
                        receivedAudio |= result.audio();
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
                LOG.warn("Structured audio response ended before SSE completion");
                throw new IOException("Соединение с OpenRouter завершилось до конца ответа.");
            }
            if (!receivedText) {
                throw new IOException(receivedAudio
                        ? "OpenRouter вернул только аудио без текстового ответа."
                        : "OpenRouter вернул пустой ответ.");
            }
            structured.finish();
        }
    }

    private static final class StructuredResponse {
        private static final String QUESTION_OPEN = "<QUESTION>";
        private static final String QUESTION_CLOSE = "</QUESTION>";
        private static final String ANSWER_OPEN = "<ANSWER>";
        private static final String ANSWER_CLOSE = "</ANSWER>";

        private enum Part { QUESTION_OPEN, QUESTION, ANSWER_OPEN, ANSWER, COMPLETE }

        private final Consumer<String> onQuestion;
        private final Consumer<String> onAnswer;
        private final StringBuilder pending = new StringBuilder();
        private final StringBuilder question = new StringBuilder();
        private Part part = Part.QUESTION_OPEN;
        private boolean answerHasText;

        private StructuredResponse(Consumer<String> onQuestion, Consumer<String> onAnswer) {
            this.onQuestion = onQuestion;
            this.onAnswer = onAnswer;
        }

        private void accept(String chunk) throws IOException {
            pending.append(chunk);
            while (true) {
                switch (part) {
                    case QUESTION_OPEN -> {
                        if (!opening(QUESTION_OPEN)) return;
                        part = Part.QUESTION;
                    }
                    case QUESTION -> {
                        int end = pending.indexOf(QUESTION_CLOSE);
                        if (end >= 0) {
                            question.append(pending, 0, end);
                            pending.delete(0, end + QUESTION_CLOSE.length());
                            String recognized = question.toString().trim();
                            if (recognized.isEmpty()) throw malformed("пустой блок QUESTION");
                            if (recognized.contains(QUESTION_OPEN) || recognized.contains(ANSWER_OPEN)
                                    || recognized.contains(ANSWER_CLOSE)) {
                                throw malformed("маркер в блоке QUESTION нарушает последовательность");
                            }
                            onQuestion.accept(recognized);
                            LOG.debug("Structured audio QUESTION received");
                            part = Part.ANSWER_OPEN;
                        } else {
                            holdMarkerTail(QUESTION_CLOSE, question::append);
                            return;
                        }
                    }
                    case ANSWER_OPEN -> {
                        if (!opening(ANSWER_OPEN)) return;
                        LOG.debug("Structured audio ANSWER streaming started");
                        part = Part.ANSWER;
                    }
                    case ANSWER -> {
                        int end = pending.indexOf(ANSWER_CLOSE);
                        int wrong = firstMarker(QUESTION_OPEN, QUESTION_CLOSE, ANSWER_OPEN);
                        if (wrong >= 0 && (end < 0 || wrong < end)) {
                            throw malformed("маркер в блоке ANSWER нарушает последовательность");
                        }
                        int possibleClose = pending.indexOf("</AN");
                        if (possibleClose >= 0) {
                            int length = Math.min(pending.length() - possibleClose, ANSWER_CLOSE.length());
                            if (!ANSWER_CLOSE.startsWith(pending.substring(possibleClose, possibleClose + length))) {
                                throw malformed("некорректный закрывающий маркер ANSWER");
                            }
                        }
                        if (end >= 0) {
                            emit(pending.substring(0, end));
                            pending.delete(0, end + ANSWER_CLOSE.length());
                            part = Part.COMPLETE;
                        } else {
                            holdMarkerTail(this::emit, ANSWER_CLOSE, QUESTION_OPEN, QUESTION_CLOSE, ANSWER_OPEN);
                            return;
                        }
                    }
                    case COMPLETE -> {
                        if (!pending.toString().isBlank()) throw malformed("текст после блока ANSWER");
                        pending.setLength(0);
                        return;
                    }
                }
            }
        }

        private boolean opening(String marker) throws IOException {
            while (!pending.isEmpty() && Character.isWhitespace(pending.charAt(0))) pending.deleteCharAt(0);
            int length = Math.min(pending.length(), marker.length());
            if (!marker.startsWith(pending.substring(0, length))) throw malformed("нарушен порядок блоков");
            if (pending.length() < marker.length()) return false;
            pending.delete(0, marker.length());
            return true;
        }

        private void holdMarkerTail(String marker, Consumer<String> output) {
            holdMarkerTail(output, marker);
        }

        private int firstMarker(String... markers) {
            int first = -1;
            for (String marker : markers) {
                int index = pending.indexOf(marker);
                if (index >= 0 && (first < 0 || index < first)) first = index;
            }
            return first;
        }

        private void holdMarkerTail(Consumer<String> output, String... markers) {
            int held = 0;
            for (String marker : markers) {
                int max = Math.min(pending.length(), marker.length() - 1);
                for (int size = max; size > held; size--) {
                    if (marker.startsWith(pending.substring(pending.length() - size))) {
                        held = size;
                        break;
                    }
                }
            }
            int ready = pending.length() - held;
            if (ready > 0) {
                output.accept(pending.substring(0, ready));
                pending.delete(0, ready);
            }
        }

        private void emit(String text) {
            if (!text.isEmpty()) {
                if (!text.isBlank()) answerHasText = true;
                onAnswer.accept(text);
            }
        }

        private void finish() throws IOException {
            if (part != Part.COMPLETE || !answerHasText) {
                LOG.warn("Structured audio response incomplete, part={}", part);
                throw malformed("отсутствует или не завершён блок QUESTION или ANSWER");
            }
        }

        private static IOException malformed(String reason) {
            LOG.warn("Structured audio marker parsing failed, reason={}", reason);
            return new IOException("OpenRouter вернул ответ в неожиданном формате: " + reason + ".");
        }
    }

    private record EventResult(boolean done, boolean text, boolean audio) { }

    private static EventResult dispatch(String data, StructuredResponse structured,
                                        BooleanSupplier cancelled) throws IOException {
        if (data.isBlank()) return new EventResult(false, false, false);
        if (data.trim().equals("[DONE]")) return new EventResult(true, false, false);
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
        JsonNode choice = choices.isArray() && !choices.isEmpty() ? choices.get(0) : null;
        JsonNode finishReason = choice == null ? null : choice.path("finish_reason");
        if (finishReason != null && !finishReason.isMissingNode() && !finishReason.isNull()) {
            String reason = finishReason.asText();
            if (!"stop".equals(reason)) {
                LOG.warn("Audio generation stopped before normal completion, reason={}", reason);
                throw new IOException(switch (reason) {
                    case "length" -> "OpenRouter прервал генерацию: достигнут лимит токенов.";
                    case "content_filter" -> "OpenRouter прервал генерацию из-за фильтра содержимого.";
                    default -> "OpenRouter прервал генерацию до нормального завершения.";
                });
            }
            LOG.debug("Audio generation finished normally");
        }
        JsonNode delta = choice == null ? null : choice.path("delta");
        JsonNode content = delta == null ? null : delta.path("content");
        if (content != null && content.isTextual() && !content.textValue().isEmpty()) {
            checkCancelled(cancelled);
            structured.accept(content.textValue());
            return new EventResult(false, true, false);
        }
        return new EventResult(false, false, delta != null && delta.hasNonNull("audio"));
    }

    private static boolean hasWaveHeader(byte[] audio) {
        return audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F'
                && audio[8] == 'W' && audio[9] == 'A' && audio[10] == 'V' && audio[11] == 'E';
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
