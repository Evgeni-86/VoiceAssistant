package com.voiceassistant.speech;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/** Преобразует сохранённую аудиозапись в текст в фоновом потоке. */
public interface TranscriptionService {
    CompletableFuture<String> transcribe(Path recording);
}
