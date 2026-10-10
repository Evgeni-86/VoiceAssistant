package com.voiceassistant.audio;

import java.nio.file.Path;

public interface AudioSource {

    void startRecording(Path outputPath) throws Exception;

    void stopRecording();
}
