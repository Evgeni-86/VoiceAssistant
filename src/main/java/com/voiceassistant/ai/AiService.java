package com.voiceassistant.ai;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Потоковая генерация обычного текста; вызывается в фоновом потоке. */
public interface AiService {

    /**
     * Передаёт только новые фрагменты в порядке получения. Возврат означает успешное
     * завершение; исключение сообщает об ошибке, CancellationException — об отмене.
     * Реализация должна регулярно проверять cancelled и прекращать выдачу фрагментов.
     */
    void streamMessage(String message, Consumer<String> onChunk, BooleanSupplier cancelled) throws Exception;
}
