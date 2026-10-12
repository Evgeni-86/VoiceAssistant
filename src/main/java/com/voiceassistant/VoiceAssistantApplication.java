package com.voiceassistant;

import javafx.application.Application;
import javafx.stage.Stage;

public class VoiceAssistantApplication extends Application {

    @Override
    public void start(Stage stage) {
        new VoiceAssistantController().start(stage);
    }

    public static void main(String[] args) {
        launch(args);
    }
}