package com.voiceassistant.ai;

import java.util.List;
import java.util.Objects;

public record AiSettings(AiMode mode, String deepSeekModel, String openRouterModel, String systemPrompt) {
    public static final List<String> DEEPSEEK_MODELS = List.of("deepseek-flash", "deepseek-v4-pro");
    public static final List<String> OPENROUTER_MODELS = List.of("openrouter/free");
    public static final String DEFAULT_PROMPT = "Ты полезный русскоязычный голосовой ассистент. "
            + "Отвечай понятно, точно и по существу. Если пользователь задаёт технический вопрос, "
            + "по возможности объясняй на примерах.";

    public AiSettings {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(systemPrompt);
        if (!DEEPSEEK_MODELS.contains(deepSeekModel)) {
            throw new IllegalArgumentException("Указана неподдерживаемая модель DeepSeek.");
        }
        if (!OPENROUTER_MODELS.contains(openRouterModel)) {
            throw new IllegalArgumentException("Указана неподдерживаемая модель OpenRouter.");
        }
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException("Системный промпт не должен быть пустым.");
        }
    }

    public static AiSettings defaults() {
        return new AiSettings(AiMode.STUB, DEEPSEEK_MODELS.getFirst(),
                OPENROUTER_MODELS.getFirst(), DEFAULT_PROMPT);
    }

    public String model() {
        return mode == AiMode.OPENROUTER ? openRouterModel : deepSeekModel;
    }
}
