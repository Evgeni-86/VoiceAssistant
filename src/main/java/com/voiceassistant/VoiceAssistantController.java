package com.voiceassistant;

import com.voiceassistant.ai.AiService;
import com.voiceassistant.ai.conversation.AiHistoryEntry;
import com.voiceassistant.ai.AiMode;
import com.voiceassistant.ai.AiServiceSelector;
import com.voiceassistant.ai.AiSettings;
import com.voiceassistant.ai.settings.AiSettingsService;
import com.voiceassistant.ai.conversation.ConversationContextService;
import com.voiceassistant.ai.TranscriptionMode;
import com.voiceassistant.ai.openrouter.OpenRouterAudioAiService;
import com.voiceassistant.audio.AudioRecordingService;
import com.voiceassistant.speech.TranscriptionService;
import com.voiceassistant.speech.OpenRouterTranscriptionService;
import com.voiceassistant.speech.WhisperTranscriptionService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

final class VoiceAssistantController {
    private static final Logger LOG = LoggerFactory.getLogger(VoiceAssistantController.class);
    private enum State {
        IDLE, STARTING, RECORDING, STOPPING, TRANSCRIBING, SENDING_AI,
        AUTO_STOPPING, AUTO_TRANSCRIBING, AUTO_SENDING_AI, CLOSING
    }

    private final AudioRecordingService recordingService = new AudioRecordingService();
    private final WhisperTranscriptionService transcriptionService =
            new WhisperTranscriptionService();
    private final AiSettingsService settingsService = new AiSettingsService();
    private final AiServiceSelector aiServiceSelector = new AiServiceSelector(settingsService);
    private final ExecutorService settingsExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "voiceassistant-settings");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "voiceassistant-ai");
        thread.setDaemon(true);
        return thread;
    });

    private Path lastRecording;
    private State state = State.IDLE;
    private final ObservableList<AiHistoryEntry> history = FXCollections.observableArrayList();
    private final ConversationContextService contextService = new ConversationContextService(history);
    private MainView view;
    private Stage stage;
    private AiSettings settings;
    private AiSettingsDialog settingsDialog;
    private AiRequest activeAiRequest;
    private Timeline aiFlushTimer;

    private static final class AiRequest {
        final AiHistoryEntry entry;
        final String requestId;
        final AiService service;
        final boolean automatic;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean finished = new AtomicBoolean();
        final StringBuilder pending = new StringBuilder();
        Future<?> task;

        AiRequest(AiHistoryEntry entry, AiService service, boolean automatic, String requestId) {
            this.entry = entry;
            this.service = service;
            this.automatic = automatic;
            this.requestId = requestId;
        }

        void accept(String chunk) {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            synchronized (pending) {
                if (!cancelled.get()) {
                    pending.append(chunk);
                }
            }
        }

        String drain() {
            synchronized (pending) {
                String chunks = pending.toString();
                pending.setLength(0);
                return chunks;
            }
        }

        void cancel() {
            cancelled.set(true);
            service.cancelActive();
            if (task != null) {
                task.cancel(true);
            }
        }
    }

    void start(Stage stage) {
        this.stage = stage;
        view = new MainView(stage, history, this::updateControls, this::saveResponseFontSize);
        view.setResponseFontSize(settingsService.loadResponseFontSize(
                (int) Math.round(view.aiResponseArea.getFont().getSize())));
        view.aiSettingsButton.setOnAction(event -> openSettings());
        view.recordButton.setOnAction(event -> startRecording());
        view.stopButton.setOnAction(event -> stopRecording(false));
        view.finishAndSendButton.setOnAction(event -> stopRecording(true));
        view.transcribeButton.setOnAction(event -> {
            if (state == State.IDLE && lastRecording != null && settings != null
                    && !directAudioMode()) {
                transcribe(lastRecording, false);
            }
        });
        view.sendToAiButton.setOnAction(event -> {
            if (state == State.IDLE && settings != null && !directAudioMode()
                    && !view.transcriptArea.getText().isBlank()) {
                sendToAi(view.transcriptArea.getText().trim(), false);
            }
        });

        stage.setOnCloseRequest(event -> {
            event.consume();
            if (state == State.CLOSING) {
                return;
            }

            state = State.CLOSING;
            if (activeAiRequest != null) {
                activeAiRequest.cancel();
                finishAiRequest(activeAiRequest, new CancellationException("Генерация ответа отменена"));
            }
            updateControls();
            view.statusLabel.setText("Завершаем приложение...");
            transcriptionService.close();
            aiExecutor.shutdownNow();
            aiServiceSelector.close();
            settingsExecutor.shutdownNow();
            CompletableFuture.runAsync(recordingService::close)
                    .whenComplete((result, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            LOG.error("Application shutdown failed, errorType={}", error.getClass().getName());
                            System.err.println("Ошибка завершения приложения: " + error.getMessage());
                        }
                        stage.setOnCloseRequest(null);
                        contextService.clear();
                        stage.close();
                    }));
        });

        updateControls();
        view.show(stage);
        CompletableFuture.supplyAsync(() -> {
            try {
                return settingsService.load();
            } catch (Exception error) {
                throw new CompletionException(error);
            }
        }, settingsExecutor).whenComplete((loaded, error) -> Platform.runLater(() -> {
            if (state == State.CLOSING) {
                return;
            }
            settings = error == null ? loaded : AiSettings.defaults();
            view.aiModeLabel.setText("ИИ: " + modeName(settings.mode()));
            if (error != null) {
                Throwable source = error;
                while (source instanceof CompletionException && source.getCause() != null) {
                    source = source.getCause();
                }
                IllegalStateException diagnostic = new IllegalStateException(
                        "Settings initialization failed, errorType=" + source.getClass().getName());
                diagnostic.setStackTrace(source.getStackTrace());
                LOG.error("Settings initialization failed", diagnostic);
                view.statusLabel.setText("Не удалось загрузить настройки ИИ: " + errorMessage(error)
                        + " Используются значения по умолчанию.");
            }
            updateControls();
        }));
    }

    private void saveResponseFontSize(int size) {
        try {
            settingsService.saveResponseFontSize(size);
        } catch (IOException error) {
            LOG.error("Response font size save failed, errorType={}", error.getClass().getName());
            view.statusLabel.setText("Не удалось сохранить размер шрифта ответа: " + error.getMessage());
        }
    }

    private void openSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.focus();
            return;
        }
        settingsDialog = new AiSettingsDialog(stage, settings, settingsService, settingsExecutor, saved -> {
            if (settings != null && settings.mode() != saved.mode()) {
                LOG.info("AI mode changed, from={}, to={}", settings.mode(), saved.mode());
            }
            settings = saved;
            view.aiModeLabel.setText("ИИ: " + modeName(saved.mode()));
            updateControls();
        });
        settingsDialog.show();
    }

    private void updateControls() {
        boolean idle = state == State.IDLE;
        boolean recording = state == State.RECORDING;
        view.aiSettingsButton.setDisable(settings == null || state == State.CLOSING);
        view.recordButton.setDisable(!idle);
        view.sourceComboBox.setDisable(!idle);
        view.stopButton.setDisable(!recording);
        view.finishAndSendButton.setDisable(!recording || settings == null);
        boolean audioMode = directAudioMode();
        view.transcribeButton.setDisable(!idle || lastRecording == null || audioMode);
        view.sendToAiButton.setDisable(!idle || settings == null || audioMode
                || view.transcriptArea.getText().isBlank());
        view.transcriptArea.setEditable(idle);
    }

    private void startRecording() {
        if (state != State.IDLE) {
            return;
        }

        state = State.STARTING;
        lastRecording = null;
        view.transcriptArea.clear();
        view.aiResponseArea.clear();
        view.aiStatusLabel.setText("ИИ: готов к отправке");
        view.statusLabel.setText("Запускаем запись...");
        updateControls();

        recordingService.startRecording(view.sourceComboBox.getValue())
                .whenComplete((path, error) -> Platform.runLater(() -> {
                    if (state == State.CLOSING) {
                        return;
                    }
                    if (error != null) {
                        state = State.IDLE;
                        view.statusLabel.setText("Ошибка запуска записи: " + errorMessage(error));
                    } else {
                        state = State.RECORDING;
                        view.statusLabel.setText("Идёт запись...");
                    }
                    updateControls();
                }));
    }

    private CompletableFuture<Path> stopRecordingFile() {
        return recordingService.stopRecording().thenApply(path -> {
            if (path == null || !Files.isRegularFile(path) || !Files.isReadable(path)) {
                throw new IllegalStateException("WAV-файл отсутствует или недоступен");
            }
            return path;
        });
    }

    private void stopRecording(boolean automatic) {
        if (state != State.RECORDING) {
            return;
        }
        if (automatic && !aiAvailable()) {
            return;
        }

        state = automatic ? State.AUTO_STOPPING : State.STOPPING;
        view.statusLabel.setText(automatic
                ? "Остановка записи и сохранение аудио..." : "Сохраняем запись...");
        if (automatic) {
            view.transcriptArea.clear();
            view.aiResponseArea.clear();
            view.aiStatusLabel.setText(directAudioMode()
                    ? "ИИ: ожидает обработку аудио" : "ИИ: ожидает распознавания речи");
        }
        updateControls();

        stopRecordingFile().whenComplete((path, error) -> Platform.runLater(() -> {
            if (state == State.CLOSING) {
                return;
            }
            if (error != null) {
                state = State.IDLE;
                view.statusLabel.setText("Ошибка остановки и сохранения аудио: " + errorMessage(error));
                if (automatic) {
                    view.aiStatusLabel.setText("ИИ: запрос не отправлен");
                }
                updateControls();
                return;
            }

            lastRecording = path;
            if (automatic) {
                if (directAudioMode()) {
                    sendAiRequest("Аудиозапись: " + path.getFileName(), true, path);
                } else {
                    transcribe(path, true);
                }
            } else {
                state = State.IDLE;
                view.statusLabel.setText("Сохранено: " + path);
                updateControls();
            }
        }));
    }

    private void transcribe(Path recording, boolean automatic) {
        TranscriptionMode mode = settings.transcriptionMode();
        LOG.info("Recognition requested, mode={}, automatic={}", mode, automatic);
        state = automatic ? State.AUTO_TRANSCRIBING : State.TRANSCRIBING;
        view.transcriptArea.clear();
        view.statusLabel.setText(automatic ? "Распознавание речи..." : "Распознаём речь...");
        updateControls();

        CompletableFuture<String> transcription;
        try {
            transcription = transcriptionServiceFor(mode).transcribe(recording);
        } catch (RuntimeException error) {
            transcription = CompletableFuture.failedFuture(error);
        }
        transcription
                .whenComplete((transcript, error) -> Platform.runLater(() -> {
                    if (state == State.CLOSING) {
                        return;
                    }
                    if (error != null) {
                        LOG.error("Recognition failed, mode={}, errorType={}", mode,
                                error.getClass().getName());
                        state = State.IDLE;
                        view.statusLabel.setText("Ошибка распознавания речи: " + errorMessage(error));
                        if (automatic) {
                            view.aiStatusLabel.setText("ИИ: запрос не отправлен");
                        }
                        updateControls();
                        return;
                    }

                    view.transcriptArea.setText(transcript == null ? "" : transcript);
                    if (transcript == null || transcript.isBlank()) {
                        LOG.warn("Recognition returned no speech, mode={}", mode);
                        state = State.IDLE;
                        view.statusLabel.setText(automatic
                                ? "Речь не распознана. Отправка в ИИ отменена."
                                : "Речь в записи не обнаружена");
                        if (automatic) {
                            view.aiStatusLabel.setText("ИИ: запрос не отправлен");
                        }
                        updateControls();
                        return;
                    }

                    if (automatic) {
                        sendToAi(transcript, true);
                    } else {
                        state = State.IDLE;
                        view.statusLabel.setText("Речь распознана");
                        updateControls();
                    }
                }));
    }

    private TranscriptionService transcriptionServiceFor(TranscriptionMode mode) {
        return switch (mode) {
            case LOCAL -> transcriptionService;
            case REMOTE -> new OpenRouterTranscriptionService(
                    settingsService, aiServiceSelector.httpClient(), aiExecutor, settings.transcriptionModel());
        };
    }

    private void sendToAi(String message, boolean automatic) {
        sendAiRequest(message, automatic, null);
    }

    private void sendAiRequest(String message, boolean automatic, Path audio) {
        if (activeAiRequest != null || (state != State.IDLE && state != State.AUTO_TRANSCRIBING
                && !(audio != null && state == State.AUTO_STOPPING))) {
            return;
        }
        if (!aiAvailable()) {
            state = State.IDLE;
            updateControls();
            return;
        }
        String requestId = UUID.randomUUID().toString();
        MDC.put("requestId", requestId);
        AiService aiService;
        try {
            LOG.debug("AI data preparation started");
            aiService = aiServiceSelector.serviceFor(settings, contextService.snapshot(settings));
            LOG.debug("AI context preparation completed");
        } finally {
            MDC.remove("requestId");
        }
        AiSettings requestSettings = settings;
        AiHistoryEntry entry = new AiHistoryEntry(message, audio == null);
        AiRequest request = new AiRequest(entry, aiService, automatic, requestId);
        activeAiRequest = request;
        history.add(0, entry);
        if (view.historyListView.getSelectionModel().getSelectedItem() == null) {
            view.historyListView.getSelectionModel().select(entry);
        }

        state = automatic ? State.AUTO_SENDING_AI : State.SENDING_AI;
        if (audio != null && aiService instanceof OpenRouterAudioAiService) {
            view.transcriptArea.clear();
        }
        view.aiResponseArea.clear();
        view.aiStatusLabel.setText("Генерация ответа ИИ...");
        if (automatic) {
            view.statusLabel.setText("Генерация ответа ИИ...");
        }
        updateControls();

        aiFlushTimer = new Timeline(new KeyFrame(Duration.millis(75), event -> flushAiChunks(request)));
        aiFlushTimer.setCycleCount(Timeline.INDEFINITE);
        aiFlushTimer.play();
        try {
            request.task = aiExecutor.submit(() -> {
                MDC.put("requestId", request.requestId);
                Throwable failure = null;
                try {
                    if (audio == null) {
                        aiService.streamMessage(message, request::accept, request.cancelled::get);
                    } else if (aiService instanceof OpenRouterAudioAiService audioService) {
                        audioService.streamAudio(audio, requestSettings, settingsService,
                                question -> Platform.runLater(() -> {
                                    if (activeAiRequest == request && !request.cancelled.get()
                                            && state != State.CLOSING) {
                                        view.transcriptArea.setText(question);
                                    }
                                }), request::accept, request.cancelled::get);
                    } else {
                        aiService.streamAudio(audio, requestSettings, settingsService,
                                request::accept, request.cancelled::get);
                    }
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    failure = new CancellationException("Генерация ответа отменена");
                } catch (Throwable error) {
                    failure = error;
                    if (error instanceof CancellationException) {
                        LOG.warn("AI request cancelled");
                    } else {
                        LOG.error("AI request failed, errorType={}", error.getClass().getName());
                    }
                } finally {
                    MDC.remove("requestId");
                }
                Throwable result = failure;
                Platform.runLater(() -> finishAiRequest(request, result));
            });
        } catch (RejectedExecutionException error) {
            LOG.error("AI executor rejected request, errorType={}", error.getClass().getName());
            finishAiRequest(request, error);
        }
    }

    private void flushAiChunks(AiRequest request) {
        String chunks = request.drain();
        if (chunks.isEmpty()) {
            return;
        }
        request.entry.appendResponse(chunks);
        if (state != State.CLOSING) {
            view.aiResponseArea.appendText(chunks);
            if (history.contains(request.entry)) {
                view.refreshHistoryEntry(request.entry);
            }
        }
    }

    private void finishAiRequest(AiRequest request, Throwable failure) {
        MDC.put("requestId", request.requestId);
        try {
            finishAiRequestOnFx(request, failure);
        } finally {
            MDC.remove("requestId");
        }
    }

    private void finishAiRequestOnFx(AiRequest request, Throwable failure) {
        if (!request.finished.compareAndSet(false, true)) {
            return;
        }
        if (aiFlushTimer != null) {
            aiFlushTimer.stop();
            aiFlushTimer = null;
        }
        flushAiChunks(request);
        activeAiRequest = null;

        AiHistoryEntry entry = request.entry;
        if (request.cancelled.get() || failure instanceof CancellationException) {
            entry.cancel();
            if (state != State.CLOSING) {
                view.aiStatusLabel.setText("Генерация ответа ИИ отменена.");
                if (request.automatic) {
                    view.statusLabel.setText("Генерация ответа ИИ отменена.");
                }
            }
        } else if (failure != null || entry.getResponse().isBlank()) {
            String details = failure == null ? "сервис вернул пустой ответ" : errorMessage(failure);
            entry.fail(details);
            if (state != State.CLOSING) {
                String messageText = "Ошибка обработки запроса ИИ: " + details;
                view.aiStatusLabel.setText(messageText);
                if (request.automatic) {
                    view.statusLabel.setText(messageText);
                }
            }
        } else {
            entry.complete();
            LOG.debug("AI response processing completed");
            if (state != State.CLOSING) {
                view.aiStatusLabel.setText("Ответ ИИ получен.");
                if (request.automatic) {
                    view.statusLabel.setText("Готово. Ответ ИИ получен.");
                }
            }
        }
        if (state != State.CLOSING) {
            if (history.contains(entry)) {
                view.refreshHistoryEntry(entry);
            }
            state = State.IDLE;
            updateControls();
        }
    }

    private boolean aiAvailable() {
        if (settings == null) return false;
        if (settings.mode() == AiMode.STUB) return true;
        AiMode mode = settings.mode();
        if (!settingsService.hasApiKey(mode)) {
            view.aiStatusLabel.setText("Не настроен API-ключ OpenRouter. Откройте настройки ИИ.");
            return false;
        }
        return true;
    }

    private boolean directAudioMode() {
        return settings != null && settings.mode() == AiMode.OPENROUTER_AUDIO;
    }

    private static String modeName(AiMode mode) {
        return switch (mode) {
            case STUB -> "Stub";
            case OPENROUTER -> "OpenRouter";
            case OPENROUTER_AUDIO -> "OpenRouter Audio";
        };
    }

    private String errorMessage(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String details = cause.getMessage();
        return details == null || details.isBlank() ? "неизвестная ошибка" : details;
    }

}
