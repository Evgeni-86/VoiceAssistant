package com.voiceassistant.ai;

import java.util.List;
import java.util.Objects;

public record AiSettings(AiMode mode, String model, String systemPrompt) {
    public static final List<String> MODELS = List.of("deepseek-flash", "deepseek-v4-pro");
    public static final String DEFAULT_PROMPT = "Ты полезный русскоязычный голосовой ассистент. "
            + "Отвечай понятно, точно и по существу. Если пользователь задаёт технический вопрос, "
            + "по возможности объясняй на примерах.";

    public AiSettings {
        Objects.requireNonNull(mode);
        Objects.requireNonNull(systemPrompt);
        if (!MODELS.contains(model)) {
            throw new IllegalArgumentException("Указана неподдерживаемая модель DeepSeek.");
        }
        if (systemPrompt.isBlank()) {
            throw new IllegalArgumentException("Системный промпт не должен быть пустым.");
        }
    }

    public static AiSettings defaults() {
        return new AiSettings(AiMode.STUB, MODELS.getFirst(), DEFAULT_PROMPT);
    }
}
