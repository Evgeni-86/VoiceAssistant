package com.voiceassistant;

import com.voiceassistant.ai.AiHistoryEntry;
import com.voiceassistant.audio.AudioSourceType;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.scene.text.Text;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.IntConsumer;

final class MainView {
    private static final DateTimeFormatter HISTORY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

    final Label statusLabel;
    final Label aiStatusLabel;
    final Label aiModeLabel;
    final Button aiSettingsButton;
    final ComboBox<AudioSourceType> sourceComboBox;
    final Button recordButton;
    final Button stopButton;
    final Button finishAndSendButton;
    final Button transcribeButton;
    final Button sendToAiButton;
    final TextArea transcriptArea;
    final TextArea aiResponseArea;
    private final Button decreaseResponseFontButton;
    private final Button increaseResponseFontButton;
    final ListView<AiHistoryEntry> historyListView;
    private final Label historyInfoLabel;
    private final TextArea historyRequestArea;
    private final TextArea historyResponseArea;

    MainView(Stage stage, ObservableList<AiHistoryEntry> history, Runnable controlsChanged,
             IntConsumer responseFontSizeChanged) {
        statusLabel = new Label("Готов к записи");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
        aiModeLabel = new Label("ИИ: загрузка настроек…");
        aiSettingsButton = new Button("⚙");
        aiSettingsButton.setTooltip(new javafx.scene.control.Tooltip("Настройки ИИ"));
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
        transcriptArea.setPromptText("Здесь появится распознанный вопрос");
        transcriptArea.setPrefRowCount(1);
        transcriptArea.setMinHeight(Region.USE_PREF_SIZE);
        transcriptArea.setMaxHeight(Region.USE_PREF_SIZE);
        transcriptArea.textProperty().addListener((observable, oldText, newText) -> {
            controlsChanged.run();
            updateTextAreaRows(transcriptArea, 6);
        });
        transcriptArea.widthProperty().addListener((observable, oldWidth, newWidth) ->
                updateTextAreaRows(transcriptArea, 6));

        aiResponseArea = new TextArea();
        aiResponseArea.setEditable(false);
        aiResponseArea.setWrapText(true);
        aiResponseArea.setPromptText("Здесь появится тестовый ответ ИИ");
        aiResponseArea.setPrefRowCount(5);
        decreaseResponseFontButton = new Button("A−");
        increaseResponseFontButton = new Button("A+");
        decreaseResponseFontButton.setPadding(new Insets(2, 6, 2, 6));
        increaseResponseFontButton.setPadding(new Insets(2, 6, 2, 6));
        decreaseResponseFontButton.setOnAction(event -> changeResponseFontSize(-1, responseFontSizeChanged));
        increaseResponseFontButton.setOnAction(event -> changeResponseFontSize(1, responseFontSizeChanged));
        setResponseFontSize((int) Math.round(aiResponseArea.getFont().getSize()));

        historyListView = new ListView<>(history);
        historyListView.setPlaceholder(new Label("История запросов пуста"));
        historyListView.setPrefHeight(165);
        historyListView.setMinHeight(90);
        historyListView.setMaxHeight(Region.USE_PREF_SIZE);
        historyListView.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(AiHistoryEntry entry, boolean empty) {
                super.updateItem(entry, empty);
                setText(empty || entry == null ? null : historySummary(entry));
                setWrapText(true);
            }
        });

        historyInfoLabel = new Label("Выберите запрос в списке");
        historyInfoLabel.setWrapText(true);
        historyRequestArea = new TextArea();
        historyRequestArea.setEditable(false);
        historyRequestArea.setWrapText(true);
        historyRequestArea.setPrefRowCount(1);
        historyRequestArea.setMinHeight(Region.USE_PREF_SIZE);
        historyRequestArea.setMaxHeight(Region.USE_PREF_SIZE);
        historyRequestArea.textProperty().addListener((observable, oldText, newText) ->
                updateTextAreaRows(historyRequestArea, 5));
        historyRequestArea.widthProperty().addListener((observable, oldWidth, newWidth) ->
                updateTextAreaRows(historyRequestArea, 5));
        historyResponseArea = new TextArea();
        historyResponseArea.setEditable(false);
        historyResponseArea.setWrapText(true);
        historyResponseArea.setPrefRowCount(3);
        historyListView.getSelectionModel().selectedItemProperty()
                .addListener((observable, oldEntry, newEntry) -> showHistoryDetails(newEntry));

        Button clearHistoryButton = new Button("Очистить историю");
        clearHistoryButton.setOnAction(event -> {
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION);
            confirmation.initOwner(stage);
            confirmation.setTitle("Очистка истории");
            confirmation.setHeaderText(null);
            confirmation.setContentText("Удалить всю историю запросов и ответов?");
            if (confirmation.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                history.clear();
                historyListView.getSelectionModel().clearSelection();
                showHistoryDetails(null);
            }
        });

        Label transcriptLabel = new Label("Распознанный вопрос:");
        transcriptLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(transcriptLabel, Priority.ALWAYS);
        HBox transcriptHeader = new HBox(12, transcriptLabel, transcribeButton);
        transcriptHeader.setAlignment(Pos.CENTER_LEFT);

        aiStatusLabel.setWrapText(true);
        aiStatusLabel.setMinWidth(0);
        aiStatusLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(aiStatusLabel, Priority.ALWAYS);
        HBox aiHeader = new HBox(12, aiStatusLabel, sendToAiButton);
        aiHeader.setAlignment(Pos.CENTER_LEFT);

        Label responseLabel = new Label("Ответ ИИ:");
        responseLabel.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(responseLabel, Priority.ALWAYS);
        HBox responseHeader = new HBox(4, responseLabel,
                decreaseResponseFontButton, increaseResponseFontButton);
        responseHeader.setAlignment(Pos.CENTER_LEFT);

        VBox currentRequestContent = new VBox(
                12,
                transcriptHeader,
                transcriptArea,
                aiHeader,
                responseHeader,
                aiResponseArea
        );
        currentRequestContent.setPadding(new Insets(6, 6, 6, 6));
        VBox.setVgrow(aiResponseArea, Priority.ALWAYS);

        VBox historyContent = new VBox(
                8,
                historyListView,
                historyInfoLabel,
                new Label("Текст запроса:"),
                historyRequestArea,
                new Label("Ответ ИИ:"),
                historyResponseArea,
                clearHistoryButton
        );
        historyContent.setPadding(new Insets(6, 6, 6, 6));
        VBox.setVgrow(historyResponseArea, Priority.ALWAYS);

        Tab currentRequestTab = new Tab("Текущий запрос", currentRequestContent);
        currentRequestTab.setClosable(false);
        Tab historyTab = new Tab("История", historyContent);
        historyTab.setClosable(false);
        TabPane tabPane = new TabPane(currentRequestTab, historyTab);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        HBox recordActions = new HBox(12, recordButton, stopButton, finishAndSendButton);
        recordActions.setAlignment(Pos.CENTER);

        HBox sourceSelector = new HBox(12, new Label("Источник записи:"), sourceComboBox);
        sourceSelector.setAlignment(Pos.CENTER_LEFT);

        Region settingsSpacer = new Region();
        HBox.setHgrow(settingsSpacer, Priority.ALWAYS);
        HBox sourceAndSettings = new HBox(8,
                sourceSelector, settingsSpacer, aiModeLabel, aiSettingsButton);
        sourceAndSettings.setAlignment(Pos.CENTER_LEFT);

        VBox root = new VBox(
                12,
                sourceAndSettings,
                recordActions,
                tabPane,
                statusLabel
        );

        root.setAlignment(Pos.CENTER);
        root.setPadding(new Insets(15, 15, 15, 15));

        Scene scene = new Scene(root, 700, 858);
        stage.setTitle("Voice Assistant");
        stage.setScene(scene);
        stage.setMinWidth(620);
        stage.setMinHeight(680);
    }

    void setResponseFontSize(int size) {
        int boundedSize = Math.clamp(size, 10, 30);
        aiResponseArea.setFont(Font.font(aiResponseArea.getFont().getFamily(), boundedSize));
        decreaseResponseFontButton.setDisable(boundedSize == 10);
        increaseResponseFontButton.setDisable(boundedSize == 30);
    }

    private void changeResponseFontSize(int delta, IntConsumer onChanged) {
        int size = (int) Math.round(aiResponseArea.getFont().getSize()) + delta;
        setResponseFontSize(size);
        onChanged.accept(size);
    }

    void show(Stage stage) {
        stage.show();
        stage.centerOnScreen();

        Screen screen = Screen.getScreensForRectangle(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()
        ).stream().findFirst().orElse(Screen.getPrimary());
        Rectangle2D screenBounds = screen.getBounds();
        Rectangle2D visibleBounds = screen.getVisualBounds();
        double centeredY = screenBounds.getMinY()
                + (screenBounds.getHeight() - stage.getHeight()) / 2;
        double lowestVisibleY = visibleBounds.getMaxY() - stage.getHeight();
        stage.setY(Math.max(visibleBounds.getMinY(),
                Math.min(centeredY, lowestVisibleY)));
    }

    private String historySummary(AiHistoryEntry entry) {
        String result = switch (entry.getStatus()) {
            case PROCESSING -> entry.getResponse().isEmpty()
                    ? "Обрабатывается..." : preview(entry.getResponse());
            case COMPLETED -> preview(entry.getResponse());
            case FAILED -> entry.getResponse().isEmpty()
                    ? "Ошибка: " + preview(entry.getError()) : preview(entry.getResponse());
            case CANCELLED -> entry.getResponse().isEmpty()
                    ? "Отменено" : preview(entry.getResponse());
        };
        return HISTORY_TIME_FORMAT.format(entry.getCreatedAt()) + " · " + historyStatus(entry)
                + "\nЗапрос: " + preview(entry.getRequest())
                + "\nОтвет: " + result;
    }

    private String preview(String text) {
        String compact = text.replaceAll("\\s+", " ").trim();
        return compact.length() <= 80 ? compact : compact.substring(0, 80) + "…";
    }

    private String historyStatus(AiHistoryEntry entry) {
        return switch (entry.getStatus()) {
            case PROCESSING -> "Обрабатывается";
            case COMPLETED -> "Готово";
            case FAILED -> "Ошибка";
            case CANCELLED -> "Отменено";
        };
    }

    void showHistoryDetails(AiHistoryEntry entry) {
        if (entry == null) {
            historyInfoLabel.setText("Выберите запрос в списке");
            historyRequestArea.clear();
            historyResponseArea.clear();
            return;
        }

        historyInfoLabel.setText("Отправлено: " + HISTORY_TIME_FORMAT.format(entry.getCreatedAt())
                + " · " + historyStatus(entry));
        historyRequestArea.setText(entry.getRequest());
        historyResponseArea.setText(switch (entry.getStatus()) {
            case PROCESSING -> entry.getResponse().isEmpty()
                    ? "Обрабатывается..." : entry.getResponse();
            case COMPLETED -> entry.getResponse();
            case FAILED -> entry.getResponse().isEmpty()
                    ? "Ошибка: " + entry.getError()
                    : entry.getResponse() + "\n\nОшибка: " + entry.getError();
            case CANCELLED -> entry.getResponse().isEmpty()
                    ? "Генерация ответа отменена" : entry.getResponse();
        });
    }

    void refreshHistoryEntry(AiHistoryEntry entry) {
        historyListView.refresh();
        if (historyListView.getSelectionModel().getSelectedItem() == entry) {
            showHistoryDetails(entry);
        }
    }

    private void updateTextAreaRows(TextArea area, int maxRows) {
        double width = area.getWidth();
        if (width <= 40) {
            return;
        }

        String content = area.getText();
        if (content.length() > 1000) {
            area.setPrefRowCount(maxRows);
            return;
        }

        Text sample = new Text(content.isEmpty() ? " " : content + " ");
        sample.setFont(area.getFont());
        sample.setWrappingWidth(width - 32);

        Text oneLine = new Text("А");
        oneLine.setFont(area.getFont());
        int rows = (int) Math.ceil(sample.getLayoutBounds().getHeight()
                / oneLine.getLayoutBounds().getHeight());
        area.setPrefRowCount(Math.clamp(rows, 1, maxRows));
    }

}
