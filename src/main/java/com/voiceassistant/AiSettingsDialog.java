package com.voiceassistant;

import com.voiceassistant.ai.AiMode;
import com.voiceassistant.ai.AiSettings;
import com.voiceassistant.ai.settings.AiSettingsService;
import com.voiceassistant.ai.TranscriptionMode;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
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

        ComboBox<TranscriptionMode> transcriptionMode = new ComboBox<>();
        transcriptionMode.getItems().setAll(TranscriptionMode.values());
        transcriptionMode.setValue(current.transcriptionMode());
        transcriptionMode.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> transcriptionModel = new ComboBox<>();
        transcriptionModel.getItems().setAll(AiSettings.TRANSCRIPTION_MODELS);
        transcriptionModel.setValue(current.transcriptionModel());
        transcriptionModel.setMaxWidth(Double.MAX_VALUE);
        transcriptionModel.disableProperty().bind(
                transcriptionMode.valueProperty().isNotEqualTo(TranscriptionMode.REMOTE));

        ComboBox<String> model = new ComboBox<>();
        model.setMaxWidth(Double.MAX_VALUE);
        final String[] openRouterModel = {current.openRouterModel()};
        final String[] openRouterAudioModel = {current.openRouterAudioModel()};
        model.valueProperty().addListener((obs, oldValue, value) -> {
            if (value == null) return;
            if (mode.getValue() == AiMode.OPENROUTER) openRouterModel[0] = value;
            else if (mode.getValue() == AiMode.OPENROUTER_AUDIO) openRouterAudioModel[0] = value;
        });

        PasswordField keyField = new PasswordField();
        Label keyTitle = new Label();
        Label keyState = new Label();
        keyState.setWrapText(true);
        Button removeKey = new Button("Удалить сохранённый ключ");
        removeKey.getStyleClass().add("danger-button");
        java.util.Map<AiMode, String> newKeys = new java.util.EnumMap<>(AiMode.class);
        java.util.Set<AiMode> deletedKeys = java.util.EnumSet.noneOf(AiMode.class);
        Runnable updateProvider = () -> {
            AiMode selected = mode.getValue();
            model.getItems().setAll(switch (selected) {
                case OPENROUTER -> AiSettings.OPENROUTER_MODELS;
                case OPENROUTER_AUDIO -> AiSettings.OPENROUTER_AUDIO_MODELS;
                case STUB -> AiSettings.OPENROUTER_MODELS;
            });
            model.setValue(switch (selected) {
                case OPENROUTER -> openRouterModel[0];
                case OPENROUTER_AUDIO -> openRouterAudioModel[0];
                case STUB -> openRouterModel[0];
            });
            model.setDisable(selected == AiMode.STUB);
            boolean usesKey = selected != AiMode.STUB;
            keyTitle.setVisible(usesKey);
            keyField.setVisible(usesKey);
            keyState.setVisible(usesKey);
            removeKey.setVisible(usesKey);
            keyTitle.setManaged(usesKey);
            keyField.setManaged(usesKey);
            keyState.setManaged(usesKey);
            removeKey.setManaged(usesKey);
            if (usesKey) {
                AiMode keyMode = selected == AiMode.OPENROUTER_AUDIO ? AiMode.OPENROUTER : selected;
                String provider = "OpenRouter";
                keyTitle.setText("API-ключ " + provider);
                keyField.setPromptText("Новый API-ключ " + provider);
                keyField.setText(newKeys.getOrDefault(keyMode, ""));
                boolean exists = service.hasApiKey(keyMode);
                keyState.setText(deletedKeys.contains(keyMode) ? "API-ключ будет удалён после сохранения настроек"
                        : !keyField.getText().isEmpty() ? "Новый API-ключ будет сохранён после нажатия «Сохранить»"
                        : exists ? "API-ключ сохранён" : "API-ключ не сохранён");
                removeKey.setDisable(!exists || deletedKeys.contains(keyMode));
            }
        };
        mode.valueProperty().addListener((obs, oldValue, value) -> updateProvider.run());
        updateProvider.run();
        removeKey.setOnAction(event -> {
            AiMode keyMode = mode.getValue() == AiMode.OPENROUTER_AUDIO ? AiMode.OPENROUTER : mode.getValue();
            deletedKeys.add(keyMode);
            newKeys.remove(keyMode);
            keyField.clear();
            keyState.setText("API-ключ будет удалён после сохранения настроек");
            removeKey.setDisable(true);
        });
        keyField.textProperty().addListener((observable, oldValue, newValue) -> {
            AiMode selected = mode.getValue();
            if (selected == AiMode.STUB) return;
            AiMode keyMode = selected == AiMode.OPENROUTER_AUDIO ? AiMode.OPENROUTER : selected;
            if (!newValue.isEmpty()) {
                newKeys.put(keyMode, newValue);
                deletedKeys.remove(keyMode);
                keyState.setText("Новый API-ключ будет сохранён после нажатия «Сохранить»");
                removeKey.setDisable(!service.hasApiKey(keyMode));
            } else if (!deletedKeys.contains(keyMode)) {
                newKeys.remove(keyMode);
                keyState.setText(service.hasApiKey(keyMode) ? "API-ключ сохранён" : "API-ключ не сохранён");
                removeKey.setDisable(!service.hasApiKey(keyMode));
            }
        });

        TextArea prompt = new TextArea(current.systemPrompt());
        prompt.setWrapText(true);
        prompt.setPrefRowCount(7);
        VBox.setVgrow(prompt, Priority.ALWAYS);
        Button resetPrompt = new Button("Восстановить промпт по умолчанию");
        resetPrompt.setOnAction(event -> prompt.setText(AiSettings.DEFAULT_PROMPT));

        CheckBox useContext = new CheckBox("Использовать контекст диалога");
        useContext.setSelected(current.useConversationContext());
        Spinner<Integer> contextDepth = new Spinner<>(1, 10, current.contextDepth());
        contextDepth.disableProperty().bind(useContext.selectedProperty().not());
        HBox contextDepthRow = new HBox(10, new Label("Глубина контекста"), contextDepth);
        contextDepthRow.setAlignment(Pos.CENTER_LEFT);

        Label error = new Label();
        error.setWrapText(true);
        error.getStyleClass().add("error-label");
        Button save = new Button("Сохранить");
        save.getStyleClass().add("primary-button");
        Button cancel = new Button("Отмена");
        cancel.setOnAction(event -> stage.close());
        HBox actions = new HBox(10, save, cancel);
        actions.setAlignment(Pos.CENTER_RIGHT);

        save.setOnAction(event -> {
            AiSettings settings;
            try {
                settings = new AiSettings(mode.getValue(), openRouterModel[0],
                        openRouterAudioModel[0], prompt.getText(),
                        useContext.isSelected(), contextDepth.getValue(), transcriptionMode.getValue(),
                        transcriptionModel.getValue());
            } catch (IllegalArgumentException | NullPointerException validationError) {
                error.setText(validationError.getMessage() == null
                        ? "Заполните все настройки ИИ." : validationError.getMessage());
                return;
            }
            save.setDisable(true);
            cancel.setDisable(true);
            stage.setOnCloseRequest(closeEvent -> closeEvent.consume());
            error.setText("Сохраняем настройки…");
            java.util.Map<AiMode, String> keysToSave = new java.util.EnumMap<>(AiMode.class);
            keysToSave.putAll(newKeys);
            java.util.Set<AiMode> keysToDelete = java.util.EnumSet.noneOf(AiMode.class);
            keysToDelete.addAll(deletedKeys);
            CompletableFuture.runAsync(() -> {
                try {
                    service.save(settings, keysToSave, keysToDelete);
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
                new Label("Способ распознавания речи"), transcriptionMode,
                new Label("Модель распознавания речи"), transcriptionModel,
                keyTitle, keyField, keyState, removeKey,
                new Label("Модель"), model,
                new Label("Системный промпт"), prompt, resetPrompt,
                new Separator(), new Label("Контекст диалога"), useContext, contextDepthRow,
                error, actions);
        content.setPadding(new Insets(16));
        Scene scene = new Scene(content, 520, 680);
        scene.getStylesheets().add(AiSettingsDialog.class.getResource("/response.css").toExternalForm());
        stage.setScene(scene);

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
