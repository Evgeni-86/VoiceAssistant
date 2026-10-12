package com.voiceassistant;

import com.voiceassistant.ai.AiService;
import com.voiceassistant.ai.AiHistoryEntry;
import com.voiceassistant.ai.AiMode;
import com.voiceassistant.ai.AiServiceSelector;
import com.voiceassistant.ai.AiSettings;
import com.voiceassistant.ai.AiSettingsService;
import com.voiceassistant.audio.AudioRecordingService;
import com.voiceassistant.speech.WhisperTranscriptionService;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class VoiceAssistantController {
    private enum State {
        IDLE, STARTING, RECORDING, STOPPING, TRANSCRIBING, SENDING_AI,
        AUTO_STOPPING, AUTO_TRANSCRIBING, AUTO_SENDING_AI, CLOSING
    }

    private final AudioRecordingService recordingService = new AudioRecordingService();
    private final WhisperTranscriptionService transcriptionService =
            new WhisperTranscriptionService();
    private final AiServiceSelector aiServiceSelector = new AiServiceSelector();
    private final AiSettingsService settingsService = new AiSettingsService();
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
    private MainView view;
    private Stage stage;
    private AiSettings settings;
    private AiSettingsDialog settingsDialog;

    void start(Stage stage) {
        this.stage = stage;
        view = new MainView(stage, history, this::updateControls);
        view.aiSettingsButton.setOnAction(event -> openSettings());
        view.recordButton.setOnAction(event -> startRecording());
        view.stopButton.setOnAction(event -> stopRecording(false));
        view.finishAndSendButton.setOnAction(event -> stopRecording(true));
        view.transcribeButton.setOnAction(event -> {
            if (state == State.IDLE && lastRecording != null) {
                transcribe(lastRecording, false);
            }
        });
        view.sendToAiButton.setOnAction(event -> {
            if (state == State.IDLE && !view.transcriptArea.getText().isBlank()) {
                sendToAi(view.transcriptArea.getText().trim(), false);
            }
        });

        stage.setOnCloseRequest(event -> {
            event.consume();
            if (state == State.CLOSING) {
                return;
            }

            state = State.CLOSING;
            updateControls();
            view.statusLabel.setText("Завершаем приложение...");
            transcriptionService.close();
            aiExecutor.shutdownNow();
            settingsExecutor.shutdownNow();
            CompletableFuture.runAsync(recordingService::close)
                    .whenComplete((result, error) -> Platform.runLater(() -> {
                        if (error != null) {
                            System.err.println("Ошибка завершения приложения: " + error.getMessage());
                        }
                        stage.setOnCloseRequest(null);
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
            view.aiModeLabel.setText("ИИ: " + (settings.mode() == AiMode.STUB ? "Stub" : "DeepSeek"));
            if (error != null) {
                view.statusLabel.setText("Не удалось загрузить настройки ИИ: " + errorMessage(error)
                        + " Используются значения по умолчанию.");
            }
            updateControls();
        }));
    }

    private void openSettings() {
        if (settingsDialog != null && settingsDialog.isShowing()) {
            settingsDialog.focus();
            return;
        }
        settingsDialog = new AiSettingsDialog(stage, settings, settingsService, settingsExecutor, saved -> {
            settings = saved;
            view.aiModeLabel.setText("ИИ: " + (saved.mode() == AiMode.STUB ? "Stub" : "DeepSeek"));
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
        view.transcribeButton.setDisable(!idle || lastRecording == null);
        view.sendToAiButton.setDisable(!idle || settings == null || view.transcriptArea.getText().isBlank());
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
            view.aiStatusLabel.setText("ИИ: ожидает распознавания речи");
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
                transcribe(path, true);
            } else {
                state = State.IDLE;
                view.statusLabel.setText("Сохранено: " + path);
                updateControls();
            }
        }));
    }

    private void transcribe(Path recording, boolean automatic) {
        state = automatic ? State.AUTO_TRANSCRIBING : State.TRANSCRIBING;
        view.statusLabel.setText(automatic ? "Распознавание речи..." : "Распознаём речь...");
        updateControls();

        transcriptionService.transcribe(recording)
                .whenComplete((transcript, error) -> Platform.runLater(() -> {
                    if (state == State.CLOSING) {
                        return;
                    }
                    if (error != null) {
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

    private void sendToAi(String message, boolean automatic) {
        if (!aiAvailable()) {
            state = State.IDLE;
            updateControls();
            return;
        }
        AiService aiService = aiServiceSelector.serviceFor(settings);
        AiHistoryEntry entry = new AiHistoryEntry(message);
        history.add(0, entry);
        if (view.historyListView.getSelectionModel().getSelectedItem() == null) {
            view.historyListView.getSelectionModel().select(entry);
        }

        state = automatic ? State.AUTO_SENDING_AI : State.SENDING_AI;
        view.aiResponseArea.clear();
        view.aiStatusLabel.setText("Обработка запроса ИИ...");
        if (automatic) {
            view.statusLabel.setText("Обработка запроса ИИ...");
        }
        updateControls();

        CompletableFuture.supplyAsync(() -> aiService.sendMessage(message), aiExecutor)
                .whenComplete((response, error) -> Platform.runLater(() -> {
                    if (state == State.CLOSING) {
                        return;
                    }
                    state = State.IDLE;
                    if (error != null || response == null || response.isBlank()) {
                        String details = error == null
                                ? "сервис вернул пустой ответ" : errorMessage(error);
                        if (history.contains(entry)) {
                            entry.fail(details);
                            view.refreshHistoryEntry(entry);
                        }
                        String messageText = "Ошибка обработки запроса ИИ: " + details;
                        view.aiStatusLabel.setText(messageText);
                        if (automatic) {
                            view.statusLabel.setText(messageText);
                        }
                    } else {
                        if (history.contains(entry)) {
                            entry.complete(response);
                            view.refreshHistoryEntry(entry);
                        }
                        view.aiResponseArea.setText(response);
                        view.aiStatusLabel.setText("Ответ ИИ получен.");
                        if (automatic) {
                            view.statusLabel.setText("Готово. Ответ ИИ получен.");
                        }
                    }
                    updateControls();
                }));
    }

    private boolean aiAvailable() {
        if (settings != null && settings.mode() == AiMode.STUB) {
            return true;
        }
        view.aiStatusLabel.setText(AiServiceSelector.DEEPSEEK_UNAVAILABLE);
        return false;
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
