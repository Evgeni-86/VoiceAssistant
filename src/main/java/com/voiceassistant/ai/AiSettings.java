package com.voiceassistant.ai;

import java.util.List;
import java.util.Objects;

public record AiSettings(AiMode mode, String deepSeekModel, String openRouterModel,
                         String openAiAudioModel, String openRouterAudioModel, String systemPrompt,
                         boolean useConversationContext, int contextDepth,
                         TranscriptionMode transcriptionMode) {
    public static final List<String> DEEPSEEK_MODELS = List.of("deepseek-flash", "deepseek-v4-pro");
    public static final List<String> OPENROUTER_MODELS = List.of("openrouter/free");
    public static final List<String> OPENAI_AUDIO_MODELS = List.of("gpt-audio-mini", "gpt-audio");
    public static final List<String> OPENROUTER_AUDIO_MODELS = List.of("openai/gpt-audio-mini", "openai/gpt-audio");
    public static final String DEFAULT_PROMPT = "Ты полезный русскоязычный голосовой ассистент. "
            + "Отвечай понятно, точно и по существу. Если пользователь задаёт технический вопрос, "
            + "по возможности объясняй на примерах.";

    public AiSettings {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(transcriptionMode);
        Objects.requireNonNull(systemPrompt);
        if (!DEEPSEEK_MODELS.contains(deepSeekModel)) {
            throw new IllegalArgumentException("Указана неподдерживаемая модель DeepSeek.");
        }
        if (!OPENROUTER_MODELS.contains(openRouterModel)) {
            throw new IllegalArgumentException("Указана неподдерживаемая модель OpenRouter.");
        }
        if (!OPENAI_AUDIO_MODELS.contains(openAiAudioModel)) {
            throw new IllegalArgumentException("Указана неподдерживаемая модель OpenAI Audio.");
        }
        if (!OPENROUTER_AUDIO_MODELS.contains(openRouterAudioModel)) {
            throw new IllegalArgumentException("Указана неподдерживаемая модель OpenRouter Audio.");
        }
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException("Системный промпт не должен быть пустым.");
        }
        if (contextDepth < 1 || contextDepth > 10) {
            throw new IllegalArgumentException("Глубина контекста должна быть от 1 до 10.");
        }
    }

    public static AiSettings defaults() {
        return new AiSettings(AiMode.STUB, DEEPSEEK_MODELS.getFirst(),
                OPENROUTER_MODELS.getFirst(), OPENAI_AUDIO_MODELS.getFirst(),
                OPENROUTER_AUDIO_MODELS.getFirst(), DEFAULT_PROMPT, false, 3,
                TranscriptionMode.LOCAL);
    }

    public String model() {
        return switch (mode) {
            case OPENROUTER -> openRouterModel;
            case OPENAI_AUDIO -> openAiAudioModel;
            case OPENROUTER_AUDIO -> openRouterAudioModel;
            case STUB, DEEPSEEK -> deepSeekModel;
        };
    }
}
