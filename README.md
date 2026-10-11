# Voice Assistant

Настольное JavaFX-приложение для записи микрофона, системного звука Windows или обоих источников одновременно. Запись сохраняется в WAV-файл; локальное распознавание выполняется через `whisper.cpp`. Отправка записи во внешний API пока не реализована.

## Требования

- Windows с поддержкой WASAPI loopback;
- JDK 25 (64-битный) и настроенная переменная `JAVA_HOME`;
- CMake 3.20 или новее;
- Visual Studio 2022 или Build Tools с компонентом **Desktop development with C++** (MSVC и Windows SDK).
- Для распознавания: `whisper-cli.exe` из [whisper.cpp](https://github.com/ggml-org/whisper.cpp) и многоязычная модель формата GGML.

Maven устанавливать отдельно не требуется: в проекте есть Maven Wrapper. Архитектура собранной DLL должна совпадать с архитектурой JVM.

## Сборка и запуск

Откройте PowerShell в корне проекта. Для сборки C++ используйте терминал разработчика Visual Studio с доступными `cmake` и MSVC. Если JDK не определяется автоматически, укажите путь к нему через `JAVA_HOME` (к каталогу JDK, без `bin`): `$env:JAVA_HOME = 'C:\Path\To\jdk-25'`.

Сначала сконфигурируйте и соберите нативную библиотеку:

```powershell
cmake -S native -B native\build
cmake --build native\build --config Release
```

При генераторе Visual Studio DLL будет находиться в `native\build\Release\WasapiCapture.dll`. Если CMake по умолчанию выбирает другой генератор или архитектуру, задайте их при конфигурации: `cmake -S native -B native\build -G "Visual Studio 17 2022" -A x64`. Конфигурация Maven ожидает DLL именно в этом каталоге.

Затем соберите и запустите JavaFX-приложение:

```powershell
.\mvnw.cmd clean compile
.\mvnw.cmd javafx:run
```

Плагин JavaFX в `pom.xml` передаёт приложению путь к DLL через `java.library.path`. Класс `SystemAudioSource` вызывает `System.loadLibrary("WasapiCapture")` при создании сервиса записи, поэтому DLL нужна и для запуска с выбранным режимом «Микрофон». При запуске из IntelliJ IDEA укажите в VM options `-Djava.library.path=<абсолютный путь к native\build\Release>`.

## Настройка распознавания

Соберите [whisper.cpp](https://github.com/ggml-org/whisper.cpp) по его инструкции для Windows и скачайте [многоязычную модель GGML](https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md), например `ggml-base.bin` для первого запуска. Команда `./models/download-ggml-model.cmd base` сообщает полный путь сохранённого файла; в текущей версии скрипта он находится в корне `whisper.cpp`. Модель с суффиксом `.en` предназначена для английского языка и для русской речи не подходит. Укажите абсолютные пути в переменных окружения **процесса, запускающего приложение**:

```powershell
$env:VOICEASSISTANT_WHISPER_CLI = 'C:\Path\To\whisper.cpp\build\bin\Release\whisper-cli.exe'
$env:VOICEASSISTANT_WHISPER_MODEL = 'C:\Path\To\whisper.cpp\ggml-base.bin'
.\mvnw.cmd javafx:run
```

Для запуска из IntelliJ IDEA добавьте эти переменные в Run Configuration. Приложение само преобразует WAV в моно PCM 16 кГц, который принимает `whisper-cli`; `ffmpeg` не требуется. Распознавание выполняется локально, после остановки записи, в фоновом потоке. Первый запуск может занимать больше времени из-за загрузки модели.

## Использование

1. Выберите источник: **Микрофон**, **Системный звук** или **Микрофон + системный звук**. По умолчанию выбран системный звук.
2. Нажмите «Начать запись», затем «Остановить запись».
3. Путь к сохранённому WAV появится в окне приложения. Для проверки системного звука запустите воспроизведение аудио в Windows во время записи.
4. Нажмите «Распознать речь». Результат появится в текстовом поле.

Итоговые файлы создаются в системном временном каталоге с именем `voiceassistant-<источник>-*.wav` и автоматически не удаляются. В режиме записи двух источников промежуточные файлы создаются в отдельном временном каталоге и удаляются после обработки. Если закрыть окно во время записи, приложение попытается остановить источник и завершить сохранение.

## Как устроен проект

| Путь | Назначение |
| --- | --- |
| `src/main/java/com/voiceassistant/VoiceAssistantApplication.java` | Интерфейс JavaFX и управление записью. |
| `src/main/java/com/voiceassistant/audio/` | Контракт источника, типы источников и асинхронный сервис записи. |
| `src/main/java/com/voiceassistant/audio/source/` | Захват микрофона через Java Sound, системного звука через JNI/WASAPI и совместная запись. |
| `src/main/java/com/voiceassistant/audio/processing/AudioMixer.java` | Сведение двух WAV в стереофайл. |
| `src/main/java/com/voiceassistant/speech/WhisperTranscriptionService.java` | Фоновый запуск `whisper-cli` и чтение текста. |
| `native/` | C++-реализация захвата системного звука и CMake-конфигурация DLL. |

Сервис записи выполняет запуск и остановку источников в одном фоновом потоке. Для режима «Микрофон + системный звук» оба источника записываются отдельно, затем `AudioMixer` сводит их в итоговый WAV. Перед распознаванием WAV всех режимов приводится к формату mono PCM 16 кГц. Интеграция с внешним API остаётся следующим этапом разработки.
