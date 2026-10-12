package com.voiceassistant;

import com.voiceassistant.ai.AiService;
import com.voiceassistant.ai.StubAiService;
import com.voiceassistant.audio.AudioRecordingService;
import com.voiceassistant.audio.AudioSourceType;
import com.voiceassistant.speech.WhisperTranscriptionService;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceAssistantApplication extends Application {

    private enum State {
        IDLE, STARTING, RECORDING, STOPPING, TRANSCRIBING, SENDING_AI,
        AUTO_STOPPING, AUTO_TRANSCRIBING, AUTO_SENDING_AI, CLOSING
    }

    private final AudioRecordingService recordingService = new AudioRecordingService();
    private final WhisperTranscriptionService transcriptionService =
            new WhisperTranscriptionService();
    // Для будущей интеграции достаточно заменить реализацию в этом поле.
    private final AiService aiService = new StubAiService();
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "voiceassistant-ai");
        thread.setDaemon(true);
        return thread;
    });

    private Path lastRecording;
    private State state = State.IDLE;

    private Label statusLabel;
    private Label aiStatusLabel;
    private ComboBox<AudioSourceType> sourceComboBox;
    private Button recordButton;
    private Button stopButton;
    private Button finishAndSendButton;
    private Button transcribeButton;
    private Button sendToAiButton;
    private TextArea transcriptArea;
    private TextArea aiResponseArea;

    @Override
    public void start(Stage stage) {
        statusLabel = new Label("Готов к записи");
        aiStatusLabel = new Label("ИИ: готов к отправке");

        sourceComboBox = new ComboBox<>();
        sourceComboBox.getItems().addAll(AudioSourceType.values());
        sourceComboBox.setValue(AudioSourceType.SYSTEM);

        recordButton = new Button("Начать запись");
        stopButton = new Button("Остановить запись");
        finishAndSendButton = new Button("Завершить и отправить в ИИ");
        transcribeButton = new Button("Распознать речь");
        sendToAiButton = new Button("Отправить в ИИ");

        transcriptArea = new TextArea();
        transcriptArea.setWrapText(true);
        transcriptArea.setPromptText("Здесь появится текст записи. Его можно отредактировать перед отправкой в ИИ.");
        transcriptArea.setPrefRowCount(5);
        transcriptArea.textProperty().addListener((observable, oldText, newText) -> updateControls());

        aiResponseArea = new TextArea();
        aiResponseArea.setEditable(false);
        aiResponseArea.setWrapText(true);
        aiResponseArea.setPromptText("Здесь появится тестовый ответ ИИ");
        aiResponseArea.setPrefRowCount(5);

        recordButton.setOnAction(event -> startRecording());
        stopButton.setOnAction(event -> stopRecording(false));
        finishAndSendButton.setOnAction(event -> stopRecording(true));
        transcribeButton.setOnAction(event -> {
            if (state == State.IDLE && lastRecording != null) {
                transcribe(lastRecording, false);
            }
        });
        sendToAiButton.setOnAction(event -> {
            if (state == State.IDLE && !transcriptArea.getText().isBlank()) {
                sendToAi(transcriptArea.getText().trim(), false);
            }
        });

        VBox root = new VBox(
                12,
                statusLabel,
                new Label("Источник записи:"),
                sourceComboBox,
                recordButton,
                stopButton,
                finishAndSendButton,
                transcribeButton,
                new Label("Распознанный текст:"),
                transcriptArea,
                sendToAiButton,
                aiStatusLabel,
                new Label("Ответ ИИ:"),
                aiResponseArea
        );

        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));
        VBox.setVgrow(transcriptArea, Priority.ALWAYS);
        VBox.setVgrow(aiResponseArea, Priority.ALWAYS);

        Scene scene = new Scene(root, 650, 680);
        stage.setTitle("Voice Assistant");
        stage.setScene(scene);
        stage.setOnCloseRequest(event -> {
            event.consume();
            if (state == State.CLOSING) {
                return;
            }

            state = State.CLOSING;
            updateControls();
            statusLabel.setText("Завершаем приложение...");
            transcriptionService.close();
            aiExecutor.shutdownNow();
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
        stage.show();
    }

    private void updateControls() {
        boolean idle = state == State.IDLE;
        boolean recording = state == State.RECORDING;
        recordButton.setDisable(!idle);
        sourceComboBox.setDisable(!idle);
        stopButton.setDisable(!recording);
        finishAndSendButton.setDisable(!recording);
        transcribeButton.setDisable(!idle || lastRecording == null);
        sendToAiButton.setDisable(!idle || transcriptArea.getText().isBlank());
        transcriptArea.setEditable(idle);
    }

    private void startRecording() {
        if (state != State.IDLE) {
            return;
        }

        state = State.STARTING;
        lastRecording = null;
        transcriptArea.clear();
        aiResponseArea.clear();
        aiStatusLabel.setText("ИИ: готов к отправке");
        statusLabel.setText("Запускаем запись...");
        updateControls();

        recordingService.startRecording(sourceComboBox.getValue())
                .whenComplete((path, error) -> Platform.runLater(() -> {
                    if (state == State.CLOSING) {
                        return;
                    }
                    if (error != null) {
                        state = State.IDLE;
                        statusLabel.setText("Ошибка запуска записи: " + errorMessage(error));
                    } else {
                        state = State.RECORDING;
                        statusLabel.setText("Идёт запись...");
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

        state = automatic ? State.AUTO_STOPPING : State.STOPPING;
        statusLabel.setText(automatic
                ? "Остановка записи и сохранение аудио..." : "Сохраняем запись...");
        if (automatic) {
            transcriptArea.clear();
            aiResponseArea.clear();
            aiStatusLabel.setText("ИИ: ожидает распознавания речи");
        }
        updateControls();

        stopRecordingFile().whenComplete((path, error) -> Platform.runLater(() -> {
            if (state == State.CLOSING) {
                return;
            }
            if (error != null) {
                state = State.IDLE;
                statusLabel.setText("Ошибка остановки и сохранения аудио: " + errorMessage(error));
                if (automatic) {
                    aiStatusLabel.setText("ИИ: запрос не отправлен");
                }
                updateControls();
                return;
            }

            lastRecording = path;
            if (automatic) {
                transcribe(path, true);
            } else {
                state = State.IDLE;
                statusLabel.setText("Сохранено: " + path);
                updateControls();
            }
        }));
    }

    private void transcribe(Path recording, boolean automatic) {
        state = automatic ? State.AUTO_TRANSCRIBING : State.TRANSCRIBING;
        statusLabel.setText(automatic ? "Распознавание речи..." : "Распознаём речь...");
        updateControls();

        transcriptionService.transcribe(recording)
                .whenComplete((transcript, error) -> Platform.runLater(() -> {
                    if (state == State.CLOSING) {
                        return;
                    }
                    if (error != null) {
                        state = State.IDLE;
                        statusLabel.setText("Ошибка распознавания речи: " + errorMessage(error));
                        if (automatic) {
                            aiStatusLabel.setText("ИИ: запрос не отправлен");
                        }
                        updateControls();
                        return;
                    }

                    transcriptArea.setText(transcript == null ? "" : transcript);
                    if (transcript == null || transcript.isBlank()) {
                        state = State.IDLE;
                        statusLabel.setText(automatic
                                ? "Речь не распознана. Отправка в ИИ отменена."
                                : "Речь в записи не обнаружена");
                        if (automatic) {
                            aiStatusLabel.setText("ИИ: запрос не отправлен");
                        }
                        updateControls();
                        return;
                    }

                    if (automatic) {
                        sendToAi(transcript, true);
                    } else {
                        state = State.IDLE;
                        statusLabel.setText("Речь распознана");
                        updateControls();
                    }
                }));
    }

    private void sendToAi(String message, boolean automatic) {
        state = automatic ? State.AUTO_SENDING_AI : State.SENDING_AI;
        aiResponseArea.clear();
        aiStatusLabel.setText("Обработка запроса ИИ...");
        if (automatic) {
            statusLabel.setText("Обработка запроса ИИ...");
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
                        String messageText = "Ошибка обработки запроса ИИ: " + details;
                        aiStatusLabel.setText(messageText);
                        if (automatic) {
                            statusLabel.setText(messageText);
                        }
                    } else {
                        aiResponseArea.setText(response);
                        aiStatusLabel.setText("Ответ ИИ получен.");
                        if (automatic) {
                            statusLabel.setText("Готово. Ответ ИИ получен.");
                        }
                    }
                    updateControls();
                }));
    }

    private String errorMessage(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String details = cause.getMessage();
        return details == null || details.isBlank() ? "неизвестная ошибка" : details;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
