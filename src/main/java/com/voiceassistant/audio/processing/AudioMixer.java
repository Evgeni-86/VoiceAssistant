package com.voiceassistant.audio.processing;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

public class AudioMixer {

    private static final int SAMPLE_RATE = 48_000;
    private static final int CHANNELS = 2;
    private static final int BITS = 16;
    private static final int BLOCK_ALIGN = CHANNELS * BITS / 8;

    public void mix(Path microphone, Path system, Path output) {
        try {
            WavData mic = readWav(microphone);
            WavData sys = readWav(system);

            double duration = Math.max(
                    mic.duration(),
                    sys.duration()
            );

            long frames = (long) Math.ceil(duration * SAMPLE_RATE);
            long dataBytes = frames * BLOCK_ALIGN;

            if (dataBytes > Integer.MAX_VALUE) {
                throw new IOException(
                        "Запись слишком длинная для этого WAV-микшера"
                );
            }

            try (DataOutputStream out = new DataOutputStream(
                    new BufferedOutputStream(
                            Files.newOutputStream(output)))) {

                writeHeader(out, (int) dataBytes);

                for (long frame = 0; frame < frames; frame++) {
                    double time = (double) frame / SAMPLE_RATE;

                    for (int channel = 0; channel < CHANNELS; channel++) {
                        float micSample = mic.sampleAt(time, channel);
                        float sysSample = sys.sampleAt(time, channel);

                        // Уменьшаем громкость обоих источников,
                        // чтобы снизить риск клиппинга.
                        float mixed = 0.5f * micSample
                                + 0.5f * sysSample;

                        mixed = Math.max(-1f, Math.min(1f, mixed));

                        int pcm = Math.round(mixed * 32767f);
                        write16(out, pcm);
                    }
                }
            }

        } catch (IOException e) {
            throw new IllegalStateException(
                    "Ошибка объединения аудиофайлов", e
            );
        }
    }

    private WavData readWav(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);

        if (bytes.length < 44
                || !ascii(bytes, 0, "RIFF")
                || !ascii(bytes, 8, "WAVE")) {
            throw new IOException("Некорректный WAV: " + path);
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes)
                .order(ByteOrder.LITTLE_ENDIAN);

        int format = -1;
        int channels = 0;
        int sampleRate = 0;
        int bits = 0;
        int blockAlign = 0;

        int dataOffset = -1;
        int dataLength = 0;

        int pos = 12;

        while (pos + 8 <= bytes.length) {
            long chunkSize = Integer.toUnsignedLong(
                    buffer.getInt(pos + 4)
            );

            long next = (long) pos + 8 + chunkSize
                    + (chunkSize & 1);

            if (next > bytes.length || next > Integer.MAX_VALUE) {
                throw new IOException("Повреждённый WAV chunk");
            }

            int chunkStart = pos + 8;

            if (ascii(bytes, pos, "fmt ")) {
                if (chunkSize < 16) {
                    throw new IOException("Некорректный fmt chunk");
                }

                format = Short.toUnsignedInt(
                        buffer.getShort(chunkStart)
                );
                channels = Short.toUnsignedInt(
                        buffer.getShort(chunkStart + 2)
                );
                sampleRate = buffer.getInt(chunkStart + 4);
                blockAlign = Short.toUnsignedInt(
                        buffer.getShort(chunkStart + 12)
                );
                bits = Short.toUnsignedInt(
                        buffer.getShort(chunkStart + 14)
                );

                // WAVE_FORMAT_EXTENSIBLE:
                // subtype 1 = PCM, subtype 3 = IEEE Float.
                if (format == 0xFFFE) {
                    if (chunkSize < 40) {
                        throw new IOException(
                                "Некорректный расширенный WAV"
                        );
                    }

                    int cbSize = Short.toUnsignedInt(
                            buffer.getShort(chunkStart + 16)
                    );

                    if (cbSize < 22) {
                        throw new IOException(
                                "Некорректное расширение WAV"
                        );
                    }

                    // Проверяем стандартный GUID аудиоподтипа.
                    byte[] suffix = {
                            0x00, 0x00, 0x10, 0x00,
                            (byte) 0x80, 0x00, 0x00, (byte) 0xAA,
                            0x00, 0x38, (byte) 0x9B, 0x71
                    };

                    int guid = chunkStart + 24;
                    boolean validGuid = true;

                    for (int i = 0; i < suffix.length; i++) {
                        if (bytes[guid + 4 + i] != suffix[i]) {
                            validGuid = false;
                            break;
                        }
                    }

                    if (!validGuid) {
                        throw new IOException(
                                "Неподдерживаемый аудиоподтип"
                        );
                    }

                    format = buffer.getInt(guid);
                }
            } else if (ascii(bytes, pos, "data")) {
                dataOffset = chunkStart;
                dataLength = (int) chunkSize;
            }

            pos = (int) next;
        }

        if (dataOffset < 0 || channels <= 0
                || sampleRate <= 0 || blockAlign <= 0) {
            throw new IOException("WAV не содержит нужных данных");
        }

        boolean supported =
                (format == 1 && (bits == 16 || bits == 24 || bits == 32))
                        || (format == 3 && bits == 32);

        if (!supported || blockAlign != channels * (bits / 8)) {
            throw new IOException(
                    "Неподдерживаемый формат: " + format
                            + ", " + bits + " бит"
            );
        }

        return new WavData(
                buffer, dataOffset, dataLength,
                format, channels, sampleRate, bits, blockAlign
        );
    }

    private boolean ascii(byte[] bytes, int pos, String value) {
        if (pos < 0 || pos + value.length() > bytes.length) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            if (bytes[pos + i] != (byte) value.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private void writeHeader(DataOutputStream out, int dataSize)
            throws IOException {

        out.writeBytes("RIFF");
        write32(out, 36 + dataSize);
        out.writeBytes("WAVE");

        out.writeBytes("fmt ");
        write32(out, 16);
        write16(out, 1);
        write16(out, CHANNELS);
        write32(out, SAMPLE_RATE);
        write32(out, SAMPLE_RATE * BLOCK_ALIGN);
        write16(out, BLOCK_ALIGN);
        write16(out, BITS);

        out.writeBytes("data");
        write32(out, dataSize);
    }

    private void write16(DataOutputStream out, int value)
            throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
    }

    private void write32(DataOutputStream out, int value)
            throws IOException {
        out.writeByte(value & 0xFF);
        out.writeByte((value >>> 8) & 0xFF);
        out.writeByte((value >>> 16) & 0xFF);
        out.writeByte((value >>> 24) & 0xFF);
    }

    private record WavData(
            ByteBuffer buffer,
            int offset,
            int length,
            int format,
            int channels,
            int sampleRate,
            int bits,
            int blockAlign
    ) {

        double duration() {
            return (double) (length / blockAlign) / sampleRate;
        }

        float sampleAt(double time, int outputChannel) {
            double sourceFrame = time * sampleRate;
            long first = (long) sourceFrame;
            double fraction = sourceFrame - first;

            float a = sample(first, outputChannel);
            float b = sample(first + 1, outputChannel);

            return (float) (a + (b - a) * fraction);
        }

        private float sample(long frame, int outputChannel) {
            long totalFrames = length / blockAlign;

            if (frame < 0 || frame >= totalFrames) {
                return 0f;
            }

            // Моно копируем в оба выходных канала.
            // Для стерео сохраняем левый и правый.
            int channel = Math.min(outputChannel, channels - 1);

            int index = offset
                    + (int) frame * blockAlign
                    + channel * (bits / 8);

            if (format == 3) {
                return buffer.getFloat(index);
            }

            return switch (bits) {
                case 16 -> buffer.getShort(index) / 32768f;

                case 24 -> {
                    int value =
                            (buffer.get(index) & 0xFF)
                                    | ((buffer.get(index + 1) & 0xFF) << 8)
                                    | (buffer.get(index + 2) << 16);
                    yield value / 8388608f;
                }

                case 32 -> buffer.getInt(index) / 2147483648f;

                default -> 0f;
            };
        }
    }
}