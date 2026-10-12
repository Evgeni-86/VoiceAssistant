package com.voiceassistant.ai;

/** Единственная точка выбора реализации; DeepSeek можно подключить здесь позднее. */
public final class AiServiceSelector {
    public static final String DEEPSEEK_UNAVAILABLE =
            "Интеграция DeepSeek API пока не реализована. Выберите тестовый режим Stub.";

    private final AiService stubService = new StubAiService();

    public AiService serviceFor(AiSettings settings) {
        return switch (settings.mode()) {
            case STUB -> stubService;
            case DEEPSEEK -> throw new IllegalStateException(DEEPSEEK_UNAVAILABLE);
        };
    }
}
