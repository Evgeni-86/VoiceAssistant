
#define NOMINMAX

#include <jni.h>
#include <windows.h>
#include <mmdeviceapi.h>
#include <audioclient.h>
#include <wrl/client.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <future>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>
#include <filesystem>

using Microsoft::WRL::ComPtr;

namespace {

std::thread recordingThread;
std::atomic<bool> stopRequested{false};
std::mutex stateMutex;
std::string recordingError;

void check(HRESULT result, const char* message) {
    if (FAILED(result)) {
        throw std::runtime_error(
            std::string(message) +
            " HRESULT: " + std::to_string(result)
        );
    }
}

// Записываем 32-битное число в little-endian.
void writeU32(std::ofstream& file, uint32_t value) {
    file.write(reinterpret_cast<const char*>(&value), 4);
}

// Записываем WAV-заголовок.
void writeWavHeader(
    std::ofstream& file,
    const WAVEFORMATEX* format,
    uint32_t dataSize
) {
    // PCM допускает базовый 16-байтовый fmt chunk.
    // Для остальных форматов сохраняем расширение.
    uint32_t fmtSize =
        format->wFormatTag == WAVE_FORMAT_PCM
            ? 16
            : 18 + format->cbSize;

    uint32_t fmtPadding = fmtSize % 2;
    uint32_t riffSize =
        4 + 8 + fmtSize + fmtPadding + 8 + dataSize;

    file.seekp(0);

    file.write("RIFF", 4);
    writeU32(file, riffSize);
    file.write("WAVE", 4);

    file.write("fmt ", 4);
    writeU32(file, fmtSize);

    // Первые 16 байт WAVEFORMATEX — базовый формат.
    file.write(reinterpret_cast<const char*>(format), 16);

    if (fmtSize > 16) {
        file.write(
            reinterpret_cast<const char*>(format) + 16,
            fmtSize - 16
        );
    }

    if (fmtPadding) {
        char zero = 0;
        file.write(&zero, 1);
    }

    file.write("data", 4);
    writeU32(file, dataSize);
}

// Основная функция записи в отдельном потоке.
void captureAudio(
    std::wstring outputPath,
    std::promise<void> started
) {
    bool initializationReported = false;
    bool comInitialized = false;

    try {
        check(
            CoInitializeEx(nullptr, COINIT_MULTITHREADED),
            "COM initialization failed"
        );
        comInitialized = true;

        ComPtr<IMMDeviceEnumerator> enumerator;
        check(
            CoCreateInstance(
                __uuidof(MMDeviceEnumerator),
                nullptr,
                CLSCTX_ALL,
                IID_PPV_ARGS(&enumerator)
            ),
            "Cannot create device enumerator"
        );

        // Получаем устройство воспроизведения по умолчанию.
        ComPtr<IMMDevice> device;
        check(
            enumerator->GetDefaultAudioEndpoint(
                eRender,
                eConsole,
                &device
            ),
            "Cannot find default playback device"
        );

        ComPtr<IAudioClient> audioClient;
        check(
            device->Activate(
                __uuidof(IAudioClient),
                CLSCTX_ALL,
                nullptr,
                reinterpret_cast<void**>(
                    audioClient.GetAddressOf()
                )
            ),
            "Cannot activate audio client"
        );

        WAVEFORMATEX* rawFormat = nullptr;
        check(
            audioClient->GetMixFormat(&rawFormat),
            "Cannot get audio format"
        );

        // Автоматически освобождаем память COM.
        struct FormatGuard {
            WAVEFORMATEX* value;
            ~FormatGuard() {
                if (value) CoTaskMemFree(value);
            }
        } formatGuard{rawFormat};

        // Shared mode + LOOPBACK.
        check(
            audioClient->Initialize(
                AUDCLNT_SHAREMODE_SHARED,
                AUDCLNT_STREAMFLAGS_LOOPBACK,
                0,
                0,
                rawFormat,
                nullptr
            ),
            "Cannot initialize WASAPI Loopback"
        );

        ComPtr<IAudioCaptureClient> captureClient;
        check(
            audioClient->GetService(
                IID_PPV_ARGS(&captureClient)
            ),
            "Cannot get capture client"
        );

        std::ofstream file(
            std::filesystem::path(outputPath),
            std::ios::binary | std::ios::trunc
        );

        if (!file) {
            throw std::runtime_error(
                "Cannot create output WAV file"
            );
        }

        writeWavHeader(file, rawFormat, 0);

        check(
            audioClient->Start(),
            "Cannot start WASAPI"
        );

        // Сообщаем Java, что запись успешно запущена.
        started.set_value();
        initializationReported = true;

        uint32_t dataSize = 0;

        while (!stopRequested.load()) {
            UINT32 packetSize = 0;

            check(
                captureClient->GetNextPacketSize(&packetSize),
                "Cannot get packet size"
            );

            while (packetSize > 0) {
                BYTE* buffer = nullptr;
                UINT32 frames = 0;
                DWORD flags = 0;

                check(
                    captureClient->GetBuffer(
                        &buffer,
                        &frames,
                        &flags,
                        nullptr,
                        nullptr
                    ),
                    "Cannot read audio buffer"
                );

                uint32_t bytes =
                    frames * rawFormat->nBlockAlign;

                // Обычный RIFF WAV ограничен 4 ГБ.
                if (bytes > UINT32_MAX - dataSize - 256) {
                    captureClient->ReleaseBuffer(frames);
                    throw std::runtime_error(
                        "WAV file size limit reached"
                    );
                }

                if (flags & AUDCLNT_BUFFERFLAGS_SILENT) {
                    std::vector<char> silence(bytes, 0);
                    file.write(silence.data(), bytes);
                } else {
                    file.write(
                        reinterpret_cast<const char*>(buffer),
                        bytes
                    );
                }

                check(
                    captureClient->ReleaseBuffer(frames),
                    "Cannot release audio buffer"
                );

                if (!file) {
                    throw std::runtime_error(
                        "Cannot write audio data"
                    );
                }

                dataSize += bytes;

                check(
                    captureClient->GetNextPacketSize(&packetSize),
                    "Cannot get next packet"
                );
            }

            std::this_thread::sleep_for(
                std::chrono::milliseconds(10)
            );
        }

        check(
            audioClient->Stop(),
            "Cannot stop WASAPI"
        );

        // Обновляем размеры WAV после записи.
        writeWavHeader(file, rawFormat, dataSize);
        file.close();

        if (!file) {
            throw std::runtime_error(
                "Cannot finalize WAV file"
            );
        }

    } catch (...) {
        if (!initializationReported) {
            started.set_exception(std::current_exception());
        } else {
            try {
                throw;
            } catch (const std::exception& e) {
                recordingError = e.what();
            } catch (...) {
                recordingError = "Unknown capture error";
            }
        }
    }

    if (comInitialized) {
        CoUninitialize();
    }
}

void throwJavaException(JNIEnv* env, const std::string& message) {
    jclass exceptionClass =
        env->FindClass("java/lang/IllegalStateException");

    if (exceptionClass) {
        env->ThrowNew(exceptionClass, message.c_str());
    }
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_voiceassistant_audio_source_SystemAudioSource_startNative(
    JNIEnv* env,
    jobject,
    jstring path
) {
    if (!path) {
        throwJavaException(env, "Output path is null");
        return;
    }

    const jchar* chars = env->GetStringChars(path, nullptr);
    if (!chars) return;

    std::wstring outputPath(
        reinterpret_cast<const wchar_t*>(chars),
        env->GetStringLength(path)
    );

    env->ReleaseStringChars(path, chars);

    std::lock_guard<std::mutex> lock(stateMutex);

    if (recordingThread.joinable()) {
        throwJavaException(env, "Recording already active");
        return;
    }

    stopRequested = false;
    recordingError.clear();

    std::promise<void> started;
    auto future = started.get_future();

    try {
        recordingThread = std::thread(
            captureAudio,
            outputPath,
            std::move(started)
        );

        // Ждём только инициализации, не окончания записи.
        future.get();

    } catch (const std::exception& e) {
        stopRequested = true;

        if (recordingThread.joinable()) {
            recordingThread.join();
        }

        throwJavaException(env, e.what());
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_voiceassistant_audio_source_SystemAudioSource_stopNative(
    JNIEnv* env,
    jobject
) {
    std::lock_guard<std::mutex> lock(stateMutex);

    stopRequested = true;

    if (recordingThread.joinable()) {
        recordingThread.join();
    }

    if (!recordingError.empty()) {
        throwJavaException(env, recordingError);
        recordingError.clear();
    }
}
