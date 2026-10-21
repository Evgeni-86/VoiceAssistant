package com.voiceassistant.ai.conversation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.voiceassistant.ai.AiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Снимок завершённых обменов из существующей истории текущей сессии. */
public final class ConversationContextService {
    private static final Logger LOG = LoggerFactory.getLogger(ConversationContextService.class);
    public record Exchange(String request, String response) { }

    public record Snapshot(List<Exchange> textExchanges, List<String> audioResponses) {
        public static final Snapshot EMPTY = new Snapshot(List.of(), List.of());
    }

    private final List<AiHistoryEntry> history;

    public ConversationContextService(List<AiHistoryEntry> history) {
        this.history = history;
    }

    /** Вызывать на потоке JavaFX, которому принадлежит список истории. */
    public Snapshot snapshot(AiSettings settings) {
        if (!settings.useConversationContext()) {
            LOG.debug("Context disabled, requestedDepth={}", settings.contextDepth());
            return Snapshot.EMPTY;
        }
        List<Exchange> exchanges = new ArrayList<>();
        List<String> responses = new ArrayList<>();
        for (AiHistoryEntry entry : history) { // история хранится от новых записей к старым
            if (entry.getStatus() == AiHistoryEntry.Status.COMPLETED && entry.getResponse().isBlank()) {
                LOG.warn("Completed history entry has no response; skipped for context");
            }
            if (entry.getStatus() != AiHistoryEntry.Status.COMPLETED
                    || entry.getResponse().isBlank()) continue;
            if (entry.isTextRequest() && (entry.getRequest() == null || entry.getRequest().isBlank())) {
                LOG.warn("Text history entry has no request; text context cannot include it");
            }
            if (responses.size() < settings.contextDepth()) {
                responses.add(entry.getResponse());
            }
            if (entry.isTextRequest() && entry.getRequest() != null
                    && !entry.getRequest().isBlank() && exchanges.size() < settings.contextDepth()) {
                exchanges.add(new Exchange(entry.getRequest(), entry.getResponse()));
            }
            if (responses.size() == settings.contextDepth()
                    && exchanges.size() == settings.contextDepth()) break;
        }
        Collections.reverse(exchanges);
        Collections.reverse(responses);
        LOG.debug("Context prepared, enabled=true, requestedDepth={}, textPairs={}, audioResponses={}",
                settings.contextDepth(), exchanges.size(), responses.size());
        return new Snapshot(List.copyOf(exchanges), List.copyOf(responses));
    }

    public static String audioContextBlock(List<String> responses) {
        if (responses.isEmpty()) return "";
        StringBuilder block = new StringBuilder("Ниже приведены твои предыдущие ответы в текущем разговоре "
                + "в хронологическом порядке. Используй их как дополнительный контекст для понимания "
                + "нового голосового вопроса. Предыдущие вопросы пользователя недоступны, поэтому "
                + "не делай необоснованных предположений об их точных формулировках.");
        for (int i = 0; i < responses.size(); i++) {
            block.append("\n\nПредыдущий ответ ").append(i + 1).append(": ").append(responses.get(i));
        }
        return block.toString();
    }

    /** Очищает ту же историю, что показывается во вкладке приложения. */
    public void clear() {
        history.clear();
    }
}
