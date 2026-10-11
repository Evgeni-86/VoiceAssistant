package com.voiceassistant;

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
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class VoiceAssistantApplication extends Application {

    private final AudioRecordingService recordingService =
            new AudioRecordingService();
    private final WhisperTranscriptionService transcriptionService =
            new WhisperTranscriptionService();

    private Path lastRecording;

    private boolean closing = false;

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
        TextArea transcriptArea = new TextArea();
        transcriptArea.setEditable(false);
        transcriptArea.setWrapText(true);
        transcriptArea.setPromptText("Здесь появится текст записи");
        transcriptArea.setPrefRowCount(6);

        stopButton.setDisable(true);
        transcribeButton.setDisable(true);

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
            if (lastRecording == null) {
                return;
            }
            transcribeButton.setDisable(true);
            statusLabel.setText("Распознаём речь...");
            transcriptionService.transcribe(lastRecording)
                    .whenComplete((transcript, error) -> Platform.runLater(() -> {
                        if (closing) {
                            return;
                        }
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

        VBox root = new VBox(
                20,
                statusLabel,
                new Label("Источник записи:"),
                sourceComboBox,
                recordButton,
                stopButton,
                transcribeButton,
                transcriptArea
        );

        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));

        Scene scene = new Scene(root, 650, 500);

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
            sourceComboBox.setDisable(true);

            statusLabel.setText("Завершаем приложение...");

            transcriptionService.close();
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
