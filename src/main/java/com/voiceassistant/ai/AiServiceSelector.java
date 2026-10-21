package com.voiceassistant.ai;

import com.voiceassistant.ai.conversation.ConversationContextService;
import com.voiceassistant.ai.openrouter.OpenRouterAiService;
import com.voiceassistant.ai.openrouter.OpenRouterAudioAiService;
import com.voiceassistant.ai.settings.AiSettingsService;

import java.net.http.HttpClient;
import java.time.Duration;

/** Единственная точка выбора реализации ИИ. */
public final class AiServiceSelector {
    private final AiService stubService = new StubAiService();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    private final AiSettingsService settingsService;

    public AiServiceSelector(AiSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    public AiService serviceFor(AiSettings settings, ConversationContextService.Snapshot context) {
        return switch (settings.mode()) {
            case STUB -> stubService;
            case OPENROUTER -> new OpenRouterAiService(settings, settingsService, httpClient, context);
            case OPENROUTER_AUDIO -> new OpenRouterAudioAiService(settings, settingsService, httpClient, context);
        };
    }

    public HttpClient httpClient() {
        return httpClient;
    }

    public void close() {
        httpClient.shutdownNow();
    }
}
