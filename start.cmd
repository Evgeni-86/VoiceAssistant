@echo off
cd /d "%~dp0"

if not defined JAVA_HOME (
    for /d %%J in ("%USERPROFILE%\.jdks\ms-25*") do if exist "%%~fJ\bin\java.exe" set "JAVA_HOME=%%~fJ"
)

if not exist "%JAVA_HOME%\bin\java.exe" (
    echo JDK 25 not found. Set JAVA_HOME to the JDK 25 directory.
    pause
    exit /b 1
)

if not exist "native\release\WasapiCapture.dll" (
    echo native\release\WasapiCapture.dll not found. Build the native CMake project first.
    pause
    exit /b 1
)

if not exist "whisper\whisper-cli.exe" (
    echo whisper\whisper-cli.exe not found. Run prepare-whisper.ps1 first.
    pause
    exit /b 1
)

if not exist "whisper\ggml-base.bin" (
    echo whisper\ggml-base.bin not found. Run prepare-whisper.ps1 first.
    pause
    exit /b 1
)

set "VOICEASSISTANT_WHISPER_CLI=%~dp0whisper\whisper-cli.exe"
set "VOICEASSISTANT_WHISPER_MODEL=%~dp0whisper\ggml-base.bin"

call "%~dp0mvnw.cmd" javafx:run
if errorlevel 1 pause
