package com.voiceassistant.ai;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Пользовательская конфигурация и отдельно защищённый DPAPI ключ. */
public final class AiSettingsService {
    private final Path settingsFile;
    private final WindowsDpapiSecretStore secretStore;

    public AiSettingsService() {
        String appData = System.getenv("APPDATA");
        Path directory = appData == null || appData.isBlank()
                ? Path.of(System.getProperty("user.home"), "AppData", "Roaming", "VoiceAssistant")
                : Path.of(appData, "VoiceAssistant");
        settingsFile = directory.resolve("ai-settings.properties");
        secretStore = new WindowsDpapiSecretStore(directory.resolve("deepseek-key.dpapi"));
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
                    properties.getProperty("model"),
                    properties.getProperty("systemPrompt"));
        } catch (RuntimeException exception) {
            throw new IOException("Файл настроек ИИ повреждён или содержит неподдерживаемое значение.", exception);
        }
    }

    public boolean hasApiKey() {
        return secretStore.exists();
    }

    /** Проверяет возможность чтения ключа, не передавая его в интерфейс. */
    public void checkApiKey() throws IOException {
        secretStore.verify();
    }

    /** Точка доступа к ключу для будущего DeepSeekAiService; Stub его не получает. */
    public String readApiKey() throws IOException {
        return secretStore.read();
    }

    public synchronized void save(AiSettings settings, String newKey, boolean deleteKey) throws IOException {
        byte[] previousKey = null;
        boolean changeKey = deleteKey || newKey != null && !newKey.isBlank();
        if (changeKey) {
            previousKey = secretStore.snapshot();
            if (deleteKey) {
                secretStore.delete();
            } else {
                secretStore.write(newKey);
            }
        }
        try {
            Properties properties = new Properties();
            properties.setProperty("mode", settings.mode().name());
            properties.setProperty("model", settings.model());
            properties.setProperty("systemPrompt", settings.systemPrompt());
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
        } catch (IOException | RuntimeException exception) {
            if (changeKey) {
                try {
                    secretStore.restore(previousKey);
                } catch (IOException rollbackError) {
                    exception.addSuppressed(rollbackError);
                }
            }
            throw new IOException("Не удалось сохранить настройки ИИ.", exception);
        }
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
