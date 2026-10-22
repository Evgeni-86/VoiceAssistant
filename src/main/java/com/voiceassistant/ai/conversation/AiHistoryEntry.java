package com.voiceassistant.ai.conversation;

import java.time.Instant;
import java.util.UUID;

/** Один запрос к ИИ и результат его обработки; хранится только в памяти приложения. */
public class AiHistoryEntry {

    public enum Status {
        PROCESSING, COMPLETED, FAILED, CANCELLED
    }

    private final UUID id = UUID.randomUUID();
    private final Instant createdAt = Instant.now();
    private final String request;
    private final boolean textRequest;
    private final StringBuilder response = new StringBuilder();
    private String error;
    private Status status = Status.PROCESSING;

    public AiHistoryEntry(String request) {
        this(request, true);
    }

    public AiHistoryEntry(String request, boolean textRequest) {
        this.request = request;
        this.textRequest = textRequest;
    }

    public UUID getId() {
        return id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public String getRequest() {
        return request;
    }

    public boolean isTextRequest() {
        return textRequest;
    }

    public String getResponse() {
        return response.toString();
    }

    public String getError() {
        return error;
    }

    public Status getStatus() {
        return status;
    }

    public void complete() {
        this.status = Status.COMPLETED;
    }

    public void appendResponse(String chunk) {
        this.response.append(chunk);
    }

    public void fail(String error) {
        this.error = error;
        this.status = Status.FAILED;
    }

    public void cancel() {
        this.status = Status.CANCELLED;
    }
}
