package com.voiceassistant;

import com.voiceassistant.ai.AiService;
import com.voiceassistant.ai.StubAiService;
import com.voiceassistant.audio.AudioSourceType;
import com.voiceassistant.audio.AudioRecordingService;
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
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceAssistantApplication extends Application {

    private final AudioRecordingService recordingService =
            new AudioRecordingService();
    private final WhisperTranscriptionService transcriptionService =
            new WhisperTranscriptionService();
    // Единственная точка выбора реализации: позже здесь можно создать DeepSeekAiService.
    private final AiService aiService = new StubAiService();
    private final ExecutorService aiExecutor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "voiceassistant-ai");
        thread.setDaemon(true);
        return thread;
    });

    private Path lastRecording;

    private boolean closing = false;
    private boolean transcribing = false;
    private boolean aiRequestInProgress = false;

    @Override
    public void start(Stage stage) {

        Label statusLabel = new Label("Готов к записи");

        ComboBox<AudioSourceType> sourceComboBox =
                new ComboBox<>();

        sourceComboBox.getItems().addAll(
                AudioSourceType.values()
        );

        sourceComboBox.setValue(AudioSourceType.SYSTEM);

        Button recordButton = new Button("Начать запись");
        Button stopButton = new Button("Остановить запись");
        Button transcribeButton = new Button("Распознать речь");
        Button sendToAiButton = new Button("Отправить в ИИ");
        TextArea transcriptArea = new TextArea();
        transcriptArea.setWrapText(true);
        transcriptArea.setPromptText("Здесь появится текст записи. Его можно отредактировать перед отправкой в ИИ.");
        transcriptArea.setPrefRowCount(5);

        TextArea aiResponseArea = new TextArea();
        aiResponseArea.setEditable(false);
        aiResponseArea.setWrapText(true);
        aiResponseArea.setPromptText("Здесь появится тестовый ответ ИИ");
        aiResponseArea.setPrefRowCount(5);
        Label aiStatusLabel = new Label("ИИ: готов к отправке");

        Runnable updateAiButton = () -> sendToAiButton.setDisable(
                closing || transcribing || aiRequestInProgress
                        || transcriptArea.getText().isBlank()
        );
        transcriptArea.textProperty().addListener((observable, oldText, newText) -> updateAiButton.run());

        stopButton.setDisable(true);
        transcribeButton.setDisable(true);
        updateAiButton.run();

        recordButton.setOnAction(event -> {

            lastRecording = null;

            recordButton.setDisable(true);
            sourceComboBox.setDisable(true);
            transcribeButton.setDisable(true);
            transcriptArea.clear();

            statusLabel.setText("Запускаем запись...");

            recordingService
                    .startRecording(sourceComboBox.getValue())
                    .whenComplete((path, error) ->
                            Platform.runLater(() -> {

                                if (error != null) {
                                    recordButton.setDisable(false);
                                    sourceComboBox.setDisable(false);

                                    statusLabel.setText(
                                            "Ошибка: " +
                                                    error.getMessage()
                                    );
                                    return;
                                }

                                stopButton.setDisable(false);
                                statusLabel.setText(
                                        "Идёт запись..."
                                );
                            })
                    );
        });

        stopButton.setOnAction(event -> {

            stopButton.setDisable(true);
            statusLabel.setText("Сохраняем запись...");

            recordingService.stopRecording()
                    .whenComplete((path, error) ->
                            Platform.runLater(() -> {

                                recordButton.setDisable(false);
                                sourceComboBox.setDisable(false);

                                if (error != null) {
                                    statusLabel.setText(
                                            "Ошибка: " +
                                                    error.getMessage()
                                    );
                                    return;
                                }

                                lastRecording = path;
                                transcribeButton.setDisable(false);

                                statusLabel.setText(
                                        "Сохранено: " + path
                                );
                            })
                    );
        });

        transcribeButton.setOnAction(event -> {
            if (lastRecording == null || transcribing) {
                return;
            }
            transcribing = true;
            updateAiButton.run();
            transcribeButton.setDisable(true);
            statusLabel.setText("Распознаём речь...");
            transcriptionService.transcribe(lastRecording)
                    .whenComplete((transcript, error) -> Platform.runLater(() -> {
                        if (closing) {
                            return;
                        }
                        transcribing = false;
                        updateAiButton.run();
                        transcribeButton.setDisable(false);
                        if (error != null) {
                            Throwable cause = error.getCause() != null
                                    ? error.getCause() : error;
                            statusLabel.setText("Ошибка распознавания: " + cause.getMessage());
                            return;
                        }
                        transcriptArea.setText(transcript);
                        statusLabel.setText(transcript.isBlank()
                                ? "Речь в записи не обнаружена"
                                : "Речь распознана");
                    }));
        });

        sendToAiButton.setOnAction(event -> {
            if (closing || transcribing || aiRequestInProgress
                    || transcriptArea.getText().isBlank()) {
                return;
            }

            String message = transcriptArea.getText().trim();
            aiRequestInProgress = true;
            updateAiButton.run();
            aiResponseArea.clear();
            aiStatusLabel.setText("Обработка запроса ИИ...");

            CompletableFuture.supplyAsync(() -> aiService.sendMessage(message), aiExecutor)
                    .whenComplete((response, error) -> Platform.runLater(() -> {
                        if (closing) {
                            return;
                        }
                        aiRequestInProgress = false;
                        updateAiButton.run();
                        if (error != null) {
                            Throwable cause = error.getCause() != null
                                    ? error.getCause() : error;
                            String details = cause.getMessage();
                            aiStatusLabel.setText("Ошибка обработки запроса ИИ: "
                                    + (details == null || details.isBlank()
                                    ? "не удалось получить ответ" : details));
                            return;
                        }
                        aiResponseArea.setText(response);
                        aiStatusLabel.setText("Ответ ИИ получен.");
                    }));
        });

        VBox root = new VBox(
                12,
                statusLabel,
                new Label("Источник записи:"),
                sourceComboBox,
                recordButton,
                stopButton,
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

        Scene scene = new Scene(root, 650, 650);

        stage.setTitle("Voice Assistant");
        stage.setScene(scene);

        stage.setOnCloseRequest(event -> {
            event.consume();

            if (closing) {
                return;
            }

            closing = true;

            recordButton.setDisable(true);
            stopButton.setDisable(true);
            transcribeButton.setDisable(true);
            sendToAiButton.setDisable(true);
            sourceComboBox.setDisable(true);

            statusLabel.setText("Завершаем приложение...");

            transcriptionService.close();
            aiExecutor.shutdownNow();
            CompletableFuture.runAsync(recordingService::close)
                    .whenComplete((result, error) ->
                            Platform.runLater(() -> {

                                if (error != null) {
                                    System.err.println(
                                            "Ошибка завершения приложения: "
                                                    + error.getMessage()
                                    );
                                }

                                // Не блокируем закрытие окна навсегда.
                                stage.setOnCloseRequest(null);
                                stage.close();
                            })
                    );
        });

        stage.show();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
