package com.voiceassistant.ai;

import com.voiceassistant.ai.settings.AiSettingsService;

import java.nio.file.Path;
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

    /** Прямая передача WAV аудиосервису; настройки и хранилище доступны будущей реализации API. */
    default void streamAudio(Path wav, AiSettings settings, AiSettingsService settingsService,
                             Consumer<String> onChunk, BooleanSupplier cancelled) throws Exception {
        throw new UnsupportedOperationException("Этот режим не принимает аудио напрямую.");
    }

    /** Закрывает активный сетевой поток при отмене. Локальным реализациям не требуется. */
    default void cancelActive() {
    }
}
