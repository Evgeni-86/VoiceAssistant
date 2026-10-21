# Voice Assistant

Настольное JavaFX-приложение для записи микрофона, системного звука Windows или обоих источников одновременно. Запись сохраняется в WAV-файл. В текстовых режимах речь локально распознаётся через `whisper.cpp`, после чего текст можно отправить в тестовый ИИ-сервис, OpenRouter API или DeepSeek API. Режимы OpenAI Audio и OpenRouter Audio обрабатывают записанный WAV напрямую; OpenAI Audio пока использует локальную заглушку.

## Требования

- Windows с поддержкой WASAPI loopback;
- JDK 25 (64-битный) и настроенная переменная `JAVA_HOME`;
- CMake 3.20 или новее;
- Visual Studio 2022 или Build Tools с компонентом **Desktop development with C++** (MSVC и Windows SDK).
- Для распознавания: `whisper-cli.exe` из [whisper.cpp](https://github.com/ggml-org/whisper.cpp) и многоязычная модель формата GGML.

Maven устанавливать отдельно не требуется: в проекте есть Maven Wrapper. Архитектура собранной DLL должна совпадать с архитектурой JVM.

## Сборка и запуск

Если `native\release\WasapiCapture.dll`, `whisper-cli.exe` и модель уже подготовлены, для повседневного запуска откройте `start.cmd` двойным щелчком. При первом запуске после сборки `whisper.cpp` выполните из корня проекта:

```powershell
.\prepare-whisper.ps1
```

Скрипт копирует `whisper-cli.exe`, необходимые DLL и `ggml-base.bin` из соседней папки `..\whisper.cpp` в локальную папку `whisper\`. При другом расположении исходников укажите `-WhisperRoot 'D:\путь\к\whisper.cpp'`. Исполняемый файл и DLL в `whisper\` отслеживаются Git, а файл модели `whisper\ggml-base.bin` исключён из Git и должен быть подготовлен отдельно. `start.cmd` сам подставляет локальные пути к Whisper и ищет JDK 25 в `JAVA_HOME` либо в `%USERPROFILE%\.jdks`.

Для первоначальной сборки компонентов выполните следующие шаги.

Откройте PowerShell в корне проекта. Для сборки C++ используйте терминал разработчика Visual Studio с доступными `cmake` и MSVC. Если JDK не определяется автоматически, укажите путь к нему через `JAVA_HOME` (к каталогу JDK, без `bin`): `$env:JAVA_HOME = 'C:\Path\To\jdk-25'`.

Сначала сконфигурируйте и соберите нативную библиотеку:

```powershell
cmake -S native -B native\build
cmake --build native\build --config Release
```

Сборка помещает готовую DLL сразу в `native\release\WasapiCapture.dll`. Этот файл можно хранить в Git; служебные файлы `native\build\`, включая `.lib` и `.exp`, в Git не добавляются. Если CMake по умолчанию выбирает другой генератор или архитектуру, задайте их при конфигурации: `cmake -S native -B native\build -G "Visual Studio 17 2022" -A x64`.

Затем соберите и запустите JavaFX-приложение:

```powershell
.\mvnw.cmd clean compile
.\mvnw.cmd javafx:run
```

Плагин JavaFX в `pom.xml` передаёт приложению путь к DLL через `java.library.path`. Класс `SystemAudioSource` вызывает `System.loadLibrary("WasapiCapture")` при создании сервиса записи, поэтому DLL нужна и для запуска с выбранным режимом «Микрофон». При запуске из IntelliJ IDEA укажите в VM options `-Djava.library.path=<абсолютный путь к native\release>`.

## Сборка Portable-приложения для Windows

Логи записываются в `%LOCALAPPDATA%\VoiceAssistant\logs\voice-assistant.log` (при отсутствии
`LOCALAPPDATA` — в `%USERPROFILE%\VoiceAssistant\logs`). Файл достигает 5 МБ, после чего
архивируется; хранятся пять архивов `voice-assistant.1.log.gz` —
`voice-assistant.5.log.gz`. Уровень по умолчанию — INFO. Для диагностики добавьте
`-Dvoiceassistant.log.level=DEBUG` к параметрам JVM в IDE или строку
`java-options=-Dvoiceassistant.log.level=DEBUG` в секцию `[JavaOptions]` файла
`dist\VoiceAssistant\app\VoiceAssistant.cfg` после упаковки. Для произвольной
конфигурации Logback доступен стандартный параметр JVM
`-Dlogback.configurationFile=<путь к файлу>`.

Portable-версия создаётся через `jpackage` из JDK 25 и запускается двойным щелчком по `VoiceAssistant.exe`, без `start.cmd` и отдельно установленной Java. Для сборки заранее подготовьте `native\release\WasapiCapture.dll` и папку `whisper\` с `whisper-cli.exe`, `ggml-base.bin` и необходимыми DLL. В проекте должен присутствовать класс запуска `com.voiceassistant.Launcher`, не наследующийся от `javafx.application.Application`.

Выполняйте команды по порядку в PowerShell из корня проекта.

1. Соберите JAR и скопируйте Maven-зависимости:

   ```powershell
   .\mvnw.cmd clean package -DskipTests
   .\mvnw.cmd dependency:copy-dependencies "-DoutputDirectory=target/dependency"
   ```

2. Подготовьте каталог входных файлов для `jpackage`:

   ```powershell
   New-Item -ItemType Directory -Path target\package-input -Force
   Copy-Item target\voiceassistant-1.0-SNAPSHOT.jar target\package-input\
   Copy-Item target\dependency\*.jar target\package-input\
   ```

3. Создайте приложение без консольного окна (не добавляйте `--win-console`):

   ```powershell
   jpackage `
     --type app-image `
     --name VoiceAssistant `
     --input target\package-input `
     --main-jar voiceassistant-1.0-SNAPSHOT.jar `
     --main-class com.voiceassistant.Launcher `
     --dest dist `
     --java-options '-Djava.library.path=$APPDIR\..\native' `
     --java-options '--enable-native-access=ALL-UNNAMED'
   ```

4. Скопируйте нативную библиотеку WASAPI и компоненты Whisper:

   ```powershell
   New-Item -ItemType Directory -Path dist\VoiceAssistant\native -Force
   Copy-Item native\release\WasapiCapture.dll dist\VoiceAssistant\native\
   Copy-Item whisper dist\VoiceAssistant\whisper -Recurse -Force
   ```

5. Проверьте наличие файлов:

   ```powershell
   Test-Path dist\VoiceAssistant\VoiceAssistant.exe
   Test-Path dist\VoiceAssistant\native\WasapiCapture.dll
   Test-Path dist\VoiceAssistant\whisper\whisper-cli.exe
   Test-Path dist\VoiceAssistant\whisper\ggml-base.bin
   ```

   Все четыре команды должны вернуть `True`. Для запуска перейдите в папку приложения и откройте EXE:

   ```powershell
   Set-Location dist\VoiceAssistant
   .\VoiceAssistant.exe
   ```

Готовая Portable-сборка находится в `dist\VoiceAssistant\`: рядом с `VoiceAssistant.exe` находятся каталоги `app\`, `runtime\`, `native\` и `whisper\`. Для переноса необходимо копировать **всю папку**. При текущей реализации поиск Whisper по умолчанию зависит от рабочей директории, поэтому запуск из другой рабочей директории может не найти файлы; при обычном запуске из папки приложения они доступны. Рекомендуется также проверить запуск двойным щелчком через Проводник. Для повторной сборки удалите или переименуйте старую папку `dist\VoiceAssistant\` после закрытия приложения.

## Настройка распознавания

Откройте терминал разработчика Visual Studio в корне скачанного [whisper.cpp](https://github.com/ggml-org/whisper.cpp) и соберите программу распознавания:

```powershell
cmake -S . -B build
cmake --build build --config Release --target whisper-cli
```

При генераторе Visual Studio результат находится в `build\bin\Release\whisper-cli.exe`. Затем скачайте [многоязычную модель GGML](https://github.com/ggml-org/whisper.cpp/blob/master/models/README.md):

```powershell
.\models\download-ggml-model.cmd base
```

Скрипт сообщает полный путь сохранённого `ggml-base.bin`; в текущей версии он находится в корне `whisper.cpp`. Модель с суффиксом `.en` предназначена для английского языка и для русской речи не подходит. После этого выполните `prepare-whisper.ps1` из корня `voiceassistant`. Приложение найдёт файлы в папке `whisper\` автоматически.

Если хотите хранить Whisper отдельно, можно вместо этого указать абсолютные пути в переменных окружения **процесса, запускающего приложение**:

```powershell
$env:VOICEASSISTANT_WHISPER_CLI = 'C:\Path\To\whisper.cpp\build\bin\Release\whisper-cli.exe'
$env:VOICEASSISTANT_WHISPER_MODEL = 'C:\Path\To\whisper.cpp\ggml-base.bin'
.\mvnw.cmd javafx:run
```

Для запуска из IntelliJ IDEA с отдельным расположением файлов добавьте эти переменные в Run Configuration. Для локальной папки `whisper\` переменные не требуются, если рабочая директория конфигурации — корень проекта. Приложение само преобразует WAV в моно PCM 16 кГц, который принимает `whisper-cli`; `ffmpeg` не требуется. Распознавание выполняется локально, после остановки записи, в фоновом потоке. Первый запуск может занимать больше времени из-за загрузки модели.

## Использование

1. Выберите источник: **Микрофон**, **Системный звук** или **Микрофон + системный звук**. По умолчанию выбран системный звук.
2. Нажмите «Начать запись», затем «Остановить запись».
3. Путь к сохранённому WAV появится в окне приложения. Для проверки системного звука запустите воспроизведение аудио в Windows во время записи.
4. В режимах Stub, OpenRouter API и DeepSeek API нажмите «Распознать речь». Результат появится в текстовом поле; при необходимости отредактируйте его.
5. В этих же режимах нажмите «Отправить в ИИ». Ответ выбранного сервиса будет постепенно появляться в поле «Ответ ИИ», где его можно выделить и скопировать уже во время генерации. Строка состояния ИИ показывает ход обработки и возможные ошибки.

Во время записи можно вместо «Остановить запись» нажать «Завершить и отправить в ИИ». В текстовых режимах приложение сохранит WAV, распознает речь через Whisper и отправит текст в `AiService`. В режимах OpenAI Audio и OpenRouter Audio сохранённый WAV передаётся в `AiService.streamAudio` без распознавания Whisper; кнопки «Распознать речь» и «Отправить в ИИ» для этих режимов недоступны. Ответ появляется в поле «Ответ ИИ». В текстовых режимах отдельные кнопки позволяют проверить и исправить текст перед отправкой.

На каждом этапе строка состояния показывает текущую операцию. При ошибке цепочка прекращается и сообщает, где она произошла. Если речь не распознана, пустой текст в ИИ не отправляется. Во время автоматической обработки конфликтующие кнопки блокируются; после завершения, ошибки или отмены их состояние восстанавливается. Тестовый ответ формируется локальным `StubAiService`: API-ключ и подключение к интернету не нужны.

## Настройки ИИ

Кнопка ⚙ в правом верхнем углу открывает окно «Настройки ИИ». В списке «Режим ИИ» доступны **Stub (тестовый режим)**, **OpenRouter API**, **DeepSeek API**, **OpenAI Audio** и **OpenRouter Audio**. При первом запуске выбран Stub; текущий режим показан рядом с кнопкой. DeepSeek API и OpenRouter API отправляют распознанный текст в облачные сервисы. OpenRouter Audio отправляет записанный WAV напрямую в OpenRouter; OpenAI Audio передаёт его локальной заглушке и не выполняет сетевых запросов.

Поле API-ключа выбранного провайдера скрывает ввод. Сохранённый ключ не подставляется обратно в поле: окно показывает «API-ключ сохранён». Для замены введите новый ключ и нажмите «Сохранить»; пустое поле оставляет прежний ключ. Кнопка «Удалить сохранённый ключ» помечает его к удалению после сохранения. Ключи защищаются Windows DPAPI для текущего пользователя и хранятся отдельно в `%APPDATA%\VoiceAssistant\openrouter-key.dpapi`, `%APPDATA%\VoiceAssistant\deepseek-key.dpapi` и `%APPDATA%\VoiceAssistant\openai-audio-key.dpapi`. OpenRouter API и OpenRouter Audio используют один ключ OpenRouter. Заглушке OpenAI Audio ключ не требуется.

В списке «Модель» для OpenRouter API доступна `openrouter/free` (по умолчанию); для DeepSeek — `deepseek-flash` (по умолчанию) и `deepseek-v4-pro`; для OpenAI Audio — `gpt-audio-mini` (по умолчанию) и `gpt-audio`; для OpenRouter Audio — `openai/gpt-audio-mini` (по умолчанию) и `openai/gpt-audio`. Выбор моделей каждого режима сохраняется отдельно. При выбранном Stub список неактивен. Многострочное поле «Системный промпт» позволяет менять инструкции модели с сохранением переносов строк. Кнопка «Восстановить промпт по умолчанию» меняет только текст в открытом окне; для записи нажмите «Сохранить». Заглушка OpenAI Audio получает промпт и выбранную модель, но не выполняет сетевых запросов.

Настройка «Использовать контекст диалога» по умолчанию выключена. При включении можно выбрать глубину от 1 до 10 завершённых обменов. Для текстовых запросов OpenRouter API и DeepSeek API в контекст включаются прежние текстовые запросы и ответы; для OpenRouter Audio передаются только прежние ответы, поскольку текст голосовых запросов в этом режиме не распознаётся.

Режим, модели, промпт и параметры контекста сохраняются между запусками в `%APPDATA%\VoiceAssistant\ai-settings.properties`. «Отмена» или закрытие окна кнопкой Windows отменяет несохранённые изменения. При ошибке сохранения окно остаётся открытым и показывает сообщение.

## Облачные режимы

Для OpenRouter API и OpenRouter Audio получите API-ключ в [настройках OpenRouter](https://openrouter.ai/settings/keys). Для DeepSeek API нужен отдельный ключ DeepSeek. Откройте ⚙ «Настройки ИИ», выберите режим, введите ключ выбранного провайдера, при необходимости измените модель и системный промпт, затем нажмите «Сохранить». Ключ можно заменить или удалить в этом же окне. Если ключ отсутствует, приложение сообщает об этом до начала автоматической остановки записи.

В режимах OpenRouter API и DeepSeek API текст после локального распознавания отправляется соответствующему облачному сервису. Ответ приходит постепенно через Server-Sent Events (SSE) и сразу появляется в поле «Ответ ИИ». При включённом контексте вместе с запросом передаются завершённые текстовые обмены из истории текущей сессии. Общая вкладка «История» сохраняет запрос, полученные фрагменты ответа и итоговый статус; при ошибке или отмене уже полученный текст остаётся доступным.

В режиме OpenRouter Audio WAV отправляется в OpenRouter напрямую вместе с системным промптом и, если включён контекст, предыдущими ответами из истории. Ответ также поступает постепенно. Возможны ошибки ключа, доступа, лимита запросов и кредитов, недоступности модели или провайдера, сети и обрыва потока. Бесплатные модели могут иметь ограничения доступности и количества запросов.

Итоговые файлы создаются в системном временном каталоге с именем `voiceassistant-<источник>-*.wav` и автоматически не удаляются. В режиме записи двух источников промежуточные файлы создаются в отдельном временном каталоге и удаляются после обработки. Если закрыть окно во время записи, приложение попытается остановить источник и завершить сохранение.

## История запросов и ответов

Нижняя часть окна содержит вкладки «Текущий запрос» и «История». Кнопка «Завершить и отправить в ИИ» расположена справа от «Остановить запись». На вкладке «Текущий запрос» кнопка «Распознать речь» находится справа от заголовка распознанного текста, а «Отправить в ИИ» — справа от состояния ИИ. Там же находятся редактируемый распознанный текст и последний ответ. Вкладка «История» сохраняет отправки как при ручной отправке, так и при нажатии «Завершить и отправить в ИИ». Новые записи появляются сверху сразу со статусом «Обрабатывается» и пополняются по мере генерации. После завершения они получают статус «Готово», «Ошибка» или «Отменено». При ошибке или отмене уже полученная часть ответа сохраняется; ошибка показывается рядом с ней.

В списке показаны время отправки и краткие фрагменты запроса и ответа. Выберите запись, чтобы прочитать и скопировать полные тексты в областях под списком. Кнопка «Очистить историю» удаляет все записи после подтверждения. Если в это время выполняется запрос, он продолжится, но удалённая запись не появится снова.

История хранится только в оперативной памяти и после закрытия приложения не восстанавливается. Если включён контекст диалога, завершённые записи из этой истории используются в последующих облачных запросах. Очистка истории удаляет и доступный контекст текущей сессии.

## Как устроен проект

| Путь | Назначение |
| --- | --- |
| `src/main/java/com/voiceassistant/VoiceAssistantApplication.java` | Точка входа JavaFX и запуск контроллера. |
| `src/main/java/com/voiceassistant/VoiceAssistantController.java` | Управление записью, распознаванием, ИИ-запросами и состоянием интерфейса. |
| `src/main/java/com/voiceassistant/MainView.java` | Компоненты основного окна и вкладки истории. |
| `src/main/java/com/voiceassistant/audio/` | Контракт источника, типы источников и асинхронный сервис записи. |
| `src/main/java/com/voiceassistant/audio/source/` | Захват микрофона через Java Sound, системного звука через JNI/WASAPI и совместная запись. |
| `src/main/java/com/voiceassistant/audio/processing/AudioMixer.java` | Сведение двух WAV в стереофайл. |
| `src/main/java/com/voiceassistant/speech/WhisperTranscriptionService.java` | Фоновый запуск `whisper-cli` и чтение текста. |
| `src/main/java/com/voiceassistant/ai/AiService.java` | Потоковый контракт передачи новых текстовых фрагментов и проверки отмены. |
| `src/main/java/com/voiceassistant/ai/StubAiService.java` | Локальная имитация генерации фрагментами без сетевых обращений. |
| `src/main/java/com/voiceassistant/ai/OpenRouterAiService.java` | HTTP-запрос Chat Completions и потоковое чтение SSE. |
| `src/main/java/com/voiceassistant/ai/DeepSeekAiService.java` | Потоковые запросы Chat Completions к DeepSeek API. |
| `src/main/java/com/voiceassistant/ai/OpenRouterAudioAiService.java` | Отправка WAV в OpenRouter и потоковое чтение ответа. |
| `src/main/java/com/voiceassistant/ai/OpenAiAudioStubService.java` | Локальная заглушка для режима OpenAI Audio. |
| `src/main/java/com/voiceassistant/ai/ConversationContextService.java` | Подготовка контекста из завершённых записей текущей сессии. |
| `src/main/java/com/voiceassistant/ai/AiSettingsService.java` | Сохранение пользовательских настроек и доступ к защищённому ключу. |
| `src/main/java/com/voiceassistant/ai/WindowsDpapiSecretStore.java` | Защита API-ключа через Windows DPAPI. |
| `src/main/java/com/voiceassistant/AiSettingsDialog.java` | Отдельное модальное окно настроек ИИ. |
| `src/main/java/com/voiceassistant/ai/AiHistoryEntry.java` | Запись истории запроса, ответа и состояния обработки в памяти. |
| `native/` | C++-реализация захвата системного звука и CMake-конфигурация DLL. |

Сервис записи выполняет запуск и остановку источников в одном фоновом потоке. Для режима «Микрофон + системный звук» оба источника записываются отдельно, затем `AudioMixer` сводит их в итоговый WAV. Перед распознаванием WAV всех режимов приводится к формату mono PCM 16 кГц.

После локального распознавания Whisper возвращает обычный текст в интерфейс JavaFX. Пользователь может его исправить и отправить через `AiService.streamMessage(String, Consumer<String>, BooleanSupplier)` либо запустить автоматическую последовательность остановки, распознавания и отправки одной кнопкой. Для OpenAI Audio и OpenRouter Audio автоматическая последовательность передаёт WAV в `AiService.streamAudio` без Whisper. `AiServiceSelector` выбирает реализацию по режиму: `StubAiService`, `OpenRouterAiService`, `DeepSeekAiService`, `OpenAiAudioStubService` или `OpenRouterAudioAiService`.

`AiService` передаёт через callback только очередной новый фрагмент текста. Нормальный возврат метода означает успешное завершение; исключение сообщает об ошибке, `CancellationException` — об отмене. Переданный признак отмены позволяет остановить генерацию. Контракт не зависит от способа получения фрагментов. `StubAiService` формирует тестовый ответ локально, отдаёт его частями примерно по восемь символов с паузой 50 мс в фоновом потоке и регулярно проверяет отмену.

Контроллер складывает фрагменты в буфер и добавляет их в существующее поле JavaFX и запись истории пакетами примерно раз в 75 мс. Перед финальной сменой статуса остаток буфера обязательно переносится в ответ. Это ограничивает число обновлений JavaFX даже при множестве мелких фрагментов. Пустой ответ считается ошибкой. При закрытии приложения активная задача отменяется, Stub прекращает выдачу текста, а уже полученные фрагменты остаются в записи истории до завершения приложения. Отдельной кнопки отмены пока нет.

`DeepSeekAiService` и `OpenRouterAiService` отправляют распознанный текст в соответствующий API и передают фрагменты ответа из SSE через общий callback. `OpenRouterAudioAiService` отправляет WAV и так же постепенно передаёт ответ. Все облачные сервисы получают настройки из `AiSettings`, а ключи — из `AiSettingsService`. При включённом контексте `ConversationContextService` передаёт им снимок завершённых записей истории на момент отправки запроса.
