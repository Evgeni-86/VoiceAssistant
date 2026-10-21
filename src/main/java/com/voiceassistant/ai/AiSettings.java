package com.voiceassistant.ai;

import java.util.List;
import java.util.Objects;

public record AiSettings(AiMode mode, String openRouterModel,
                         String openRouterAudioModel, String systemPrompt,
                         boolean useConversationContext, int contextDepth,
                         TranscriptionMode transcriptionMode, String transcriptionModel) {
    public static final List<String> OPENROUTER_MODELS = OpenRouterModel.modelIds();
    public static final List<String> OPENROUTER_AUDIO_MODELS = List.of("openai/gpt-audio-mini", "openai/gpt-audio");
    public static final List<String> TRANSCRIPTION_MODELS = List.of(
            "openai/whisper-large-v3-turbo", "openai/whisper-large-v3", "openai/whisper-1");
    public static final String DEFAULT_PROMPT = "Ты полезный русскоязычный голосовой ассистент. "
            + "Отвечай понятно, точно и по существу. Если пользователь задаёт технический вопрос, "
            + "по возможности объясняй на примерах.";

    public AiSettings {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(transcriptionMode);
        Objects.requireNonNull(systemPrompt);
        if (!OPENROUTER_MODELS.contains(openRouterModel)) {
            throw new IllegalArgumentException("Указана неподдерживаемая модель OpenRouter.");
        }
        if (!OPENROUTER_AUDIO_MODELS.contains(openRouterAudioModel)) {
            throw new IllegalArgumentException("Указана неподдерживаемая модель OpenRouter Audio.");
        }
        if (!TRANSCRIPTION_MODELS.contains(transcriptionModel)) {
            throw new IllegalArgumentException("Указана неподдерживаемая модель распознавания речи.");
        }
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException("Системный промпт не должен быть пустым.");
        }
        if (contextDepth < 1 || contextDepth > 10) {
            throw new IllegalArgumentException("Глубина контекста должна быть от 1 до 10.");
        }
    }

    public static AiSettings defaults() {
        return new AiSettings(AiMode.STUB, OPENROUTER_MODELS.getFirst(),
                OPENROUTER_AUDIO_MODELS.getFirst(), DEFAULT_PROMPT, false, 3,
                TranscriptionMode.LOCAL, TRANSCRIPTION_MODELS.getFirst());
    }

    public String model() {
        return switch (mode) {
            case OPENROUTER -> openRouterModel;
            case OPENROUTER_AUDIO -> openRouterAudioModel;
            case STUB -> openRouterModel;
        };
    }
}
