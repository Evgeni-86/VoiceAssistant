package com.voiceassistant.ai.settings;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** DPAPI с привязкой к текущему пользователю Windows. Файл содержит только шифртекст. */
public final class WindowsDpapiSecretStore {
    private static final MemoryLayout BLOB = MemoryLayout.structLayout(
            JAVA_INT.withName("size"), MemoryLayout.paddingLayout(4), ADDRESS.withName("data"));
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup CRYPT32 = SymbolLookup.libraryLookup("Crypt32", Arena.global());
    private static final SymbolLookup KERNEL32 = SymbolLookup.libraryLookup("Kernel32", Arena.global());
    private static final MethodHandle PROTECT = LINKER.downcallHandle(
            CRYPT32.findOrThrow("CryptProtectData"),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle UNPROTECT = LINKER.downcallHandle(
            CRYPT32.findOrThrow("CryptUnprotectData"),
            FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, ADDRESS));
    private static final MethodHandle LOCAL_FREE = LINKER.downcallHandle(
            KERNEL32.findOrThrow("LocalFree"), FunctionDescriptor.of(ADDRESS, ADDRESS));

    private final Path file;

    public WindowsDpapiSecretStore(Path file) {
        this.file = file;
    }

    public boolean exists() {
        return Files.exists(file);
    }

    public String read() throws IOException {
        if (!exists()) {
            return null;
        }
        byte[] plaintext = crypt(Files.readAllBytes(file), false);
        try {
            return new String(plaintext, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public void verify() throws IOException {
        if (exists()) {
            byte[] plaintext = crypt(Files.readAllBytes(file), false);
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public void write(String key) throws IOException {
        byte[] plaintext = key.getBytes(StandardCharsets.UTF_8);
        try {
            writeEncrypted(crypt(plaintext, true));
        } finally {
            Arrays.fill(plaintext, (byte) 0);
        }
    }

    public byte[] snapshot() throws IOException {
        return exists() ? Files.readAllBytes(file) : null;
    }

    public void restore(byte[] encrypted) throws IOException {
        if (encrypted == null) {
            delete();
        } else {
            writeEncrypted(encrypted);
        }
    }

    public void delete() throws IOException {
        Files.deleteIfExists(file);
    }

    private void writeEncrypted(byte[] encrypted) throws IOException {
        AiSettingsService.atomicWrite(file, encrypted);
    }

    private static byte[] crypt(byte[] input, boolean encrypt) throws IOException {
        if (input.length == 0) {
            throw new IOException("Файл API-ключа пуст или ключ не задан.");
        }
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment data = arena.allocate(input.length);
            data.copyFrom(MemorySegment.ofArray(input));
            MemorySegment source = arena.allocate(BLOB);
            source.set(JAVA_INT, 0, input.length);
            source.set(ADDRESS, 8, data);
            MemorySegment result = arena.allocate(BLOB);
            try {
                int success = (int) (encrypt
                        ? PROTECT.invoke(source, MemorySegment.NULL, MemorySegment.NULL,
                                MemorySegment.NULL, MemorySegment.NULL, 1, result)
                        : UNPROTECT.invoke(source, MemorySegment.NULL, MemorySegment.NULL,
                                MemorySegment.NULL, MemorySegment.NULL, 1, result));
                if (success == 0) {
                    throw new IOException(encrypt
                            ? "Не удалось защитить API-ключ через Windows DPAPI."
                            : "Не удалось прочитать API-ключ через Windows DPAPI.");
                }
                int size = result.get(JAVA_INT, 0);
                MemorySegment pointer = result.get(ADDRESS, 8);
                if (size <= 0 || pointer.equals(MemorySegment.NULL)) {
                    throw new IOException("Windows DPAPI вернул пустые данные.");
                }
                return pointer.reinterpret(size).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE);
            } finally {
                MemorySegment pointer = result.get(ADDRESS, 8);
                if (!pointer.equals(MemorySegment.NULL)) {
                    LOCAL_FREE.invoke(pointer);
                }
                data.fill((byte) 0);
            }
        } catch (IOException exception) {
            throw exception;
        } catch (Throwable error) {
            throw new IOException("Не удалось обратиться к защищённому хранилищу Windows.", error);
        }
    }
}
