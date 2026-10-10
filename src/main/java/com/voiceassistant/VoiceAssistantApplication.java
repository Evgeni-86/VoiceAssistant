package com.voiceassistant;

import com.voiceassistant.audio.AudioSourceType;
import com.voiceassistant.audio.AudioRecordingService;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

public class VoiceAssistantApplication extends Application {

    private final AudioRecordingService recordingService =
            new AudioRecordingService();

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
        Button sendButton = new Button("Отправить");

        stopButton.setDisable(true);
        sendButton.setDisable(true);

        recordButton.setOnAction(event -> {

            lastRecording = null;

            recordButton.setDisable(true);
            sourceComboBox.setDisable(true);
            sendButton.setDisable(true);

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
                                sendButton.setDisable(false);

                                statusLabel.setText(
                                        "Сохранено: " + path
                                );
                            })
                    );
        });

        sendButton.setOnAction(event -> {
            if (lastRecording != null) {
                statusLabel.setText(
                        "Отправка пока не реализована: " +
                                lastRecording.getFileName()
                );
            }
        });

        VBox root = new VBox(
                20,
                statusLabel,
                new Label("Источник записи:"),
                sourceComboBox,
                recordButton,
                stopButton,
                sendButton
        );

        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(30));

        Scene scene = new Scene(root, 550, 400);

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
            sendButton.setDisable(true);
            sourceComboBox.setDisable(true);

            statusLabel.setText("Завершаем приложение...");

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