package com.voiceassistant.ai;

import java.net.http.HttpClient;
import java.time.Duration;

/** Единственная точка выбора реализации; DeepSeek можно подключить здесь позднее. */
public final class AiServiceSelector {
    public static final String DEEPSEEK_UNAVAILABLE =
            "Интеграция DeepSeek API пока не реализована. Выберите Stub или OpenRouter.";

    private final AiService stubService = new StubAiService();
    private final AiService openAiAudioStub = new OpenAiAudioStubService();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final AiSettingsService settingsService;

    public AiServiceSelector(AiSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public AiService serviceFor(AiSettings settings) {
        return switch (settings.mode()) {
            case STUB -> stubService;
            case OPENROUTER -> new OpenRouterAiService(settings, settingsService, httpClient);
            case DEEPSEEK -> throw new IllegalStateException(DEEPSEEK_UNAVAILABLE);
            case OPENAI_AUDIO -> openAiAudioStub;
        };
    }

    public void close() {
        httpClient.shutdownNow();
    }
}
