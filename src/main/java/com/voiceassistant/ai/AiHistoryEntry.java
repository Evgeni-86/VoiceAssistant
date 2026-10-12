package com.voiceassistant.ai;

import java.time.Instant;
import java.util.UUID;

/** Один запрос к ИИ и результат его обработки; хранится только в памяти приложения. */
public class AiHistoryEntry {

    public enum Status {
        PROCESSING, COMPLETED, FAILED
    }

    private final UUID id = UUID.randomUUID();
    private final Instant createdAt = Instant.now();
    private final String request;
    private String response;
    private String error;
    private Status status = Status.PROCESSING;

    public AiHistoryEntry(String request) {
        this.request = request;
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

    public String getResponse() {
        return response;
    }

    public String getError() {
        return error;
    }

    public Status getStatus() {
        return status;
    }

    public void complete(String response) {
        this.response = response;
        this.status = Status.COMPLETED;
    }

    public void fail(String error) {
        this.error = error;
        this.status = Status.FAILED;
    }
}
