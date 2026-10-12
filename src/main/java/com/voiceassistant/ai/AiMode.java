package com.voiceassistant.ai;

public enum AiMode {
    STUB("Stub (тестовый режим)"),
    OPENROUTER("OpenRouter API"),
    DEEPSEEK("DeepSeek API");

    private final String label;

    AiMode(String label) {
        this.label = label;
    }

    @Override
    public String toString() {
        return label;
    }
}
