package com.voiceassistant;

import com.voiceassistant.ai.AiService;
import com.voiceassistant.ai.AiHistoryEntry;
import com.voiceassistant.ai.StubAiService;
import com.voiceassistant.audio.AudioRecordingService;
import com.voiceassistant.audio.AudioSourceType;
import com.voiceassistant.speech.WhisperTranscriptionService;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
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
import javafx.scene.text.Text;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VoiceAssistantApplication extends Application {

    private static final DateTimeFormatter HISTORY_TIME_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
                    .withZone(ZoneId.systemDefault());

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
    private final ObservableList<AiHistoryEntry> history = FXCollections.observableArrayList();

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
    private ListView<AiHistoryEntry> historyListView;
    private Label historyInfoLabel;
    private TextArea historyRequestArea;
    private TextArea historyResponseArea;

    @Override
    public void start(Stage stage) {
        statusLabel = new Label("Готов к записи");
        statusLabel.setWrapText(true);
        statusLabel.setMaxWidth(Double.MAX_VALUE);
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
        transcriptArea.setPromptText("Здесь появится текст записи");
        transcriptArea.setPrefRowCount(1);
        transcriptArea.setMinHeight(Region.USE_PREF_SIZE);
        transcriptArea.setMaxHeight(Region.USE_PREF_SIZE);
        transcriptArea.textProperty().addListener((observable, oldText, newText) -> {
            updateControls();
            updateTextAreaRows(transcriptArea, 6);
        });
        transcriptArea.widthProperty().addListener((observable, oldWidth, newWidth) ->
                updateTextAreaRows(transcriptArea, 6));

        aiResponseArea = new TextArea();
        aiResponseArea.setEditable(false);
        aiResponseArea.setWrapText(true);
        aiResponseArea.setPromptText("Здесь появится тестовый ответ ИИ");
        aiResponseArea.setPrefRowCount(5);

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

        Label transcriptLabel = new Label("Распознанный текст:");
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

        VBox currentRequestContent = new VBox(
                12,
                transcriptHeader,
                transcriptArea,
                aiHeader,
                new Label("Ответ ИИ:"),
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

        HBox stopActions = new HBox(12, stopButton, finishAndSendButton);
        stopActions.setAlignment(Pos.CENTER);

        HBox sourceSelector = new HBox(12, new Label("Источник записи:"), sourceComboBox);
        sourceSelector.setAlignment(Pos.CENTER);

        VBox root = new VBox(
                12,
                sourceSelector,
                recordButton,
                stopActions,
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
            case PROCESSING -> "Обрабатывается...";
            case COMPLETED -> preview(entry.getResponse());
            case FAILED -> "Ошибка: " + preview(entry.getError());
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
        };
    }

    private void showHistoryDetails(AiHistoryEntry entry) {
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
            case PROCESSING -> "Обрабатывается...";
            case COMPLETED -> entry.getResponse();
            case FAILED -> "Ошибка: " + entry.getError();
        });
    }

    private void refreshHistoryEntry(AiHistoryEntry entry) {
        historyListView.refresh();
        if (historyListView.getSelectionModel().getSelectedItem() == entry) {
            showHistoryDetails(entry);
        }
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
        AiHistoryEntry entry = new AiHistoryEntry(message);
        history.add(0, entry);
        if (historyListView.getSelectionModel().getSelectedItem() == null) {
            historyListView.getSelectionModel().select(entry);
        }

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
                        if (history.contains(entry)) {
                            entry.fail(details);
                            refreshHistoryEntry(entry);
                        }
                        String messageText = "Ошибка обработки запроса ИИ: " + details;
                        aiStatusLabel.setText(messageText);
                        if (automatic) {
                            statusLabel.setText(messageText);
                        }
                    } else {
                        if (history.contains(entry)) {
                            entry.complete(response);
                            refreshHistoryEntry(entry);
                        }
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
