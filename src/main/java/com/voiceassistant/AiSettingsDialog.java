package com.voiceassistant;

import com.voiceassistant.ai.AiMode;
import com.voiceassistant.ai.AiSettings;
import com.voiceassistant.ai.AiSettingsService;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

final class AiSettingsDialog {
    private final Stage stage = new Stage();
    private boolean deleteKey;

    AiSettingsDialog(Stage owner, AiSettings current, AiSettingsService service,
                     Executor executor, Consumer<AiSettings> onSaved) {
        stage.initOwner(owner);
        stage.initModality(Modality.WINDOW_MODAL);
        stage.setTitle("Настройки ИИ");
        stage.setMinWidth(480);
        stage.setMinHeight(470);

        ComboBox<AiMode> mode = new ComboBox<>();
        mode.getItems().setAll(AiMode.values());
        mode.setValue(current.mode());
        mode.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> model = new ComboBox<>();
        model.getItems().setAll(AiSettings.MODELS);
        model.setValue(current.model());
        model.setMaxWidth(Double.MAX_VALUE);
        model.disableProperty().bind(mode.valueProperty().isEqualTo(AiMode.STUB));

        PasswordField keyField = new PasswordField();
        keyField.setPromptText("Новый API-ключ DeepSeek");
        Label keyState = new Label(service.hasApiKey() ? "Проверяем сохранённый API-ключ…" : "API-ключ не сохранён");
        keyState.setWrapText(true);
        Button removeKey = new Button("Удалить сохранённый ключ");
        removeKey.setDisable(!service.hasApiKey());
        removeKey.setOnAction(event -> {
            deleteKey = true;
            keyField.clear();
            keyState.setText("API-ключ будет удалён после сохранения настроек");
            removeKey.setDisable(true);
        });
        keyField.textProperty().addListener((observable, oldValue, newValue) -> {
            if (!newValue.isEmpty()) {
                deleteKey = false;
                keyState.setText("Новый API-ключ будет сохранён после нажатия «Сохранить»");
                removeKey.setDisable(!service.hasApiKey());
            } else if (!deleteKey) {
                keyState.setText(service.hasApiKey() ? "API-ключ сохранён" : "API-ключ не сохранён");
                removeKey.setDisable(!service.hasApiKey());
            }
        });

        TextArea prompt = new TextArea(current.systemPrompt());
        prompt.setWrapText(true);
        prompt.setPrefRowCount(7);
        VBox.setVgrow(prompt, Priority.ALWAYS);
        Button resetPrompt = new Button("Восстановить промпт по умолчанию");
        resetPrompt.setOnAction(event -> prompt.setText(AiSettings.DEFAULT_PROMPT));

        Label error = new Label();
        error.setWrapText(true);
        error.setStyle("-fx-text-fill: #b00020;");
        Button save = new Button("Сохранить");
        Button cancel = new Button("Отмена");
        cancel.setOnAction(event -> stage.close());
        HBox actions = new HBox(10, save, cancel);
        actions.setAlignment(Pos.CENTER_RIGHT);

        save.setOnAction(event -> {
            AiSettings settings;
            try {
                settings = new AiSettings(mode.getValue(), model.getValue(), prompt.getText());
            } catch (IllegalArgumentException | NullPointerException validationError) {
                error.setText(validationError.getMessage() == null
                        ? "Заполните все настройки ИИ." : validationError.getMessage());
                return;
            }
            save.setDisable(true);
            cancel.setDisable(true);
            stage.setOnCloseRequest(closeEvent -> closeEvent.consume());
            error.setText("Сохраняем настройки…");
            String newKey = keyField.getText();
            boolean remove = deleteKey;
            CompletableFuture.runAsync(() -> {
                try {
                    service.save(settings, newKey, remove);
                } catch (Exception saveError) {
                    throw new java.util.concurrent.CompletionException(saveError);
                }
            }, executor).whenComplete((ignored, failure) -> Platform.runLater(() -> {
                if (failure == null) {
                    keyField.clear();
                    onSaved.accept(settings);
                    stage.close();
                } else {
                    error.setText("Не удалось сохранить настройки ИИ: " + rootMessage(failure));
                    save.setDisable(false);
                    cancel.setDisable(false);
                    stage.setOnCloseRequest(null);
                }
            }));
        });

        VBox content = new VBox(8,
                new Label("Режим ИИ"), mode,
                new Label("API-ключ DeepSeek"), keyField, keyState, removeKey,
                new Label("Модель"), model,
                new Label("Системный промпт"), prompt, resetPrompt,
                error, actions);
        content.setPadding(new Insets(16));
        stage.setScene(new Scene(content, 520, 540));

        if (service.hasApiKey()) {
            CompletableFuture.runAsync(() -> {
                try {
                    service.checkApiKey();
                } catch (Exception readError) {
                    throw new java.util.concurrent.CompletionException(readError);
                }
            }, executor).whenComplete((ignored, failure) -> Platform.runLater(() -> {
                if (deleteKey || !keyField.getText().isEmpty()) {
                    return;
                }
                keyState.setText(failure == null ? "API-ключ сохранён"
                        : "Не удалось прочитать сохранённый API-ключ: " + rootMessage(failure));
            }));
        }
    }

    void show() {
        stage.show();
        stage.centerOnScreen();
    }

    void focus() {
        stage.toFront();
        stage.requestFocus();
    }

    boolean isShowing() {
        return stage.isShowing();
    }

    private static String rootMessage(Throwable error) {
        Throwable cause = error;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? "неизвестная ошибка" : cause.getMessage();
    }
}
