package com.voiceassistant.ai;

public enum AiMode {
    STUB("Stub (тестовый режим)"),
    OPENROUTER("OpenRouter API"),
    DEEPSEEK("DeepSeek API"),
    OPENAI_AUDIO("OpenAI Audio"),
    OPENROUTER_AUDIO("OpenRouter Audio");

    private final String label;

    AiMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
