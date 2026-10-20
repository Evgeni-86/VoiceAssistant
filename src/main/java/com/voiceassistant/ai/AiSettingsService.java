package com.voiceassistant.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.Map;
import java.util.Set;
import java.util.List;

/** Пользовательская конфигурация и отдельно защищённый DPAPI ключ. */
public final class AiSettingsService {
    private static final String RESPONSE_FONT_SIZE = "responseFontSize";
    private final Path settingsFile;
    private final Map<AiMode, WindowsDpapiSecretStore> secretStores;

    public AiSettingsService() {
        String appData = System.getenv("APPDATA");
        Path directory = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Roaming", "VoiceAssistant")
                : Path.of(appData, "VoiceAssistant");
        settingsFile = directory.resolve("ai-settings.properties");
        secretStores = Map.of(
                AiMode.DEEPSEEK, new WindowsDpapiSecretStore(directory.resolve("deepseek-key.dpapi")),
                AiMode.OPENROUTER, new WindowsDpapiSecretStore(directory.resolve("openrouter-key.dpapi")),
                AiMode.OPENAI_AUDIO, new WindowsDpapiSecretStore(directory.resolve("openai-audio-key.dpapi")));
    }

    public AiSettings load() throws IOException {
        if (!Files.exists(settingsFile)) {
            return AiSettings.defaults();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(settingsFile)) {
            properties.load(input);
            return new AiSettings(
                    AiMode.valueOf(properties.getProperty("mode")),
                    properties.getProperty("deepSeekModel", properties.getProperty("model", AiSettings.DEEPSEEK_MODELS.getFirst())),
                    properties.getProperty("openRouterModel", AiSettings.OPENROUTER_MODELS.getFirst()),
                    properties.getProperty("openAiAudioModel", AiSettings.OPENAI_AUDIO_MODELS.getFirst()),
                    properties.getProperty("systemPrompt"));
        } catch (RuntimeException exception) {
            throw new IOException("Файл настроек ИИ повреждён или содержит неподдерживаемое значение.", exception);
        }
    }

    public int loadResponseFontSize(int defaultSize) {
        if (!Files.exists(settingsFile)) return defaultSize;
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(settingsFile)) {
            properties.load(input);
            int size = Integer.parseInt(properties.getProperty(RESPONSE_FONT_SIZE,
                    Integer.toString(defaultSize)));
            return Math.clamp(size, 10, 30);
        } catch (IOException | IllegalArgumentException exception) {
            return defaultSize;
        }
    }

    public synchronized void saveResponseFontSize(int size) throws IOException {
        Properties properties = new Properties();
        if (Files.exists(settingsFile)) {
            try (InputStream input = Files.newInputStream(settingsFile)) {
                properties.load(input);
            }
        } else {
            AiSettings defaults = AiSettings.defaults();
            properties.setProperty("mode", defaults.mode().name());
            properties.setProperty("deepSeekModel", defaults.deepSeekModel());
            properties.setProperty("openRouterModel", defaults.openRouterModel());
            properties.setProperty("openAiAudioModel", defaults.openAiAudioModel());
            properties.setProperty("systemPrompt", defaults.systemPrompt());
        }
        properties.setProperty(RESPONSE_FONT_SIZE, Integer.toString(Math.clamp(size, 10, 30)));
        writeProperties(properties);
    }

    public boolean hasApiKey(AiMode mode) {
        return store(mode).exists();
    }

    /** Проверяет возможность чтения ключа, не передавая его в интерфейс. */
    public void checkApiKey(AiMode mode) throws IOException {
        store(mode).verify();
    }

    public String readApiKey(AiMode mode) throws IOException {
        return store(mode).read();
    }

    public synchronized void save(AiSettings settings, Map<AiMode, String> newKeys,
                                  Set<AiMode> deletedKeys) throws IOException {
        Map<AiMode, byte[]> previousKeys = new java.util.EnumMap<>(AiMode.class);
        try {
            for (AiMode mode : List.of(AiMode.OPENROUTER, AiMode.DEEPSEEK, AiMode.OPENAI_AUDIO)) {
                String newKey = newKeys.get(mode);
                if (deletedKeys.contains(mode) || newKey != null && !newKey.isBlank()) {
                    WindowsDpapiSecretStore secretStore = store(mode);
                    previousKeys.put(mode, secretStore.snapshot());
                    if (deletedKeys.contains(mode)) secretStore.delete();
                    else secretStore.write(newKey);
                }
            }
            Properties properties = new Properties();
            if (Files.exists(settingsFile)) {
                try (InputStream input = Files.newInputStream(settingsFile)) {
                    properties.load(input);
                }
            }
            properties.setProperty("mode", settings.mode().name());
            properties.setProperty("deepSeekModel", settings.deepSeekModel());
            properties.setProperty("openRouterModel", settings.openRouterModel());
            properties.setProperty("openAiAudioModel", settings.openAiAudioModel());
            properties.setProperty("systemPrompt", settings.systemPrompt());
            writeProperties(properties);
        } catch (IOException | RuntimeException exception) {
            for (var previous : previousKeys.entrySet()) {
                try { store(previous.getKey()).restore(previous.getValue()); }
                catch (IOException rollbackError) { exception.addSuppressed(rollbackError); }
            }
            throw new IOException("Не удалось сохранить настройки ИИ.", exception);
        }
    }

    private void writeProperties(Properties properties) throws IOException {
        Files.createDirectories(settingsFile.getParent());
        Path temporary = Files.createTempFile(settingsFile.getParent(), "ai-settings-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "VoiceAssistant AI settings");
            }
            move(temporary, settingsFile);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private WindowsDpapiSecretStore store(AiMode mode) {
        WindowsDpapiSecretStore store = secretStores.get(mode);
        if (store == null) throw new IllegalArgumentException("Для Stub API-ключ не используется.");
        return store;
    }

    static void atomicWrite(Path file, byte[] data) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "ai-key-", ".tmp");
        try {
            Files.write(temporary, data);
            move(temporary, file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
