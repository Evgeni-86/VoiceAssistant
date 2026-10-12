package com.voiceassistant.ai;

/** Контракт отправки обычного текста в ИИ-сервис. */
public interface AiService {

    String sendMessage(String message);
}
