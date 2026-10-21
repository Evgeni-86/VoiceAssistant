package com.voiceassistant;

import javafx.application.Application;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VoiceAssistantApplication extends Application {
    private static final Logger LOG = LoggerFactory.getLogger(VoiceAssistantApplication.class);

    @Override
    public void start(Stage stage) {
        LOG.info("Application starting, version={}", getClass().getPackage().getImplementationVersion() == null
                ? "1.0-SNAPSHOT" : getClass().getPackage().getImplementationVersion());
        Thread.UncaughtExceptionHandler previousHandler = Thread.currentThread().getUncaughtExceptionHandler();
        Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> {
            LOG.error("Uncaught JavaFX error", safeStack(error));
            if (previousHandler != null) previousHandler.uncaughtException(thread, error);
        });
        try {
            new VoiceAssistantController().start(stage);
            LOG.info("Core services initialized");
        } catch (RuntimeException | Error error) {
            LOG.error("Application initialization failed", safeStack(error));
            throw error;
        }
    }

    @Override
    public void stop() {
        LOG.info("Application stopped");
    }

    private static RuntimeException safeStack(Throwable error) {
        RuntimeException diagnostic = new RuntimeException("errorType=" + error.getClass().getName());
        diagnostic.setStackTrace(error.getStackTrace());
        return diagnostic;
    }

    public static void main(String[] args) {
        launch(args);
    }
}
