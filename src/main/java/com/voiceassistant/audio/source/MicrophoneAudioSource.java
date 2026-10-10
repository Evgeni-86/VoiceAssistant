package com.voiceassistant.audio.source;

import com.voiceassistant.audio.AudioSource;

import javax.sound.sampled.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class MicrophoneAudioSource implements AudioSource {

    private static final AudioFormat FORMAT = new AudioFormat(
            16000.0f,
            16,
            1,
            true,
            false
    );

    private TargetDataLine microphone;
    private CompletableFuture<Path> recordingFuture;

    /**
     * Запускает запись с микрофона.
     *
     * @return Future, который завершится после сохранения WAV.
     */
    public synchronized void startRecording(Path outputPath)
            throws LineUnavailableException {

        if (recordingFuture != null && !recordingFuture.isDone()) {
            throw new IllegalStateException(
                    "Запись микрофона уже выполняется"
            );
        }

        DataLine.Info info = new DataLine.Info(
                TargetDataLine.class,
                FORMAT
        );

        TargetDataLine line =
                (TargetDataLine) AudioSystem.getLine(info);

        try {
            line.open(FORMAT);
            line.start();
        } catch (LineUnavailableException | RuntimeException e) {
            line.close();
            throw e;
        }

        microphone = line;

        recordingFuture = CompletableFuture.supplyAsync(() -> {
            try (AudioInputStream stream =
                         new AudioInputStream(line)) {

                AudioSystem.write(
                        stream,
                        AudioFileFormat.Type.WAVE,
                        outputPath.toFile()
                );

                return outputPath;

            } catch (IOException e) {
                throw new CompletionException(e);
            } finally {
                line.close();
            }
        });
    }

    /**
     * Останавливает запись и ожидает сохранения файла.
     */
    public void stopRecording() {

        CompletableFuture<Path> future;
        TargetDataLine line;

        synchronized (this) {
            future = recordingFuture;
            line = microphone;
        }

        if (line == null || future == null) {
            return;
        }

        // Закрытие линии завершает блокирующее чтение.
        line.stop();
        line.close();

        // Ждём, пока AudioSystem.write завершит WAV.
        future.join();

        synchronized (this) {
            microphone = null;
            recordingFuture = null;
        }
    }
}