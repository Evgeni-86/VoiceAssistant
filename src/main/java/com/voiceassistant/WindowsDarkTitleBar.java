package com.voiceassistant;

import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.charset.StandardCharsets;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_BYTE;
import static java.lang.foreign.ValueLayout.JAVA_INT;

/** Applies the Windows DWM dark caption to JavaFX stages after their native windows exist. */
final class WindowsDarkTitleBar {
    private static final Logger LOG = LoggerFactory.getLogger(WindowsDarkTitleBar.class);
    private static final int DWMWA_USE_IMMERSIVE_DARK_MODE = 20;

    private WindowsDarkTitleBar() {
    }

    static void install() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            return;
        }
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                for (Window window : change.getAddedSubList()) {
                    if (window instanceof Stage stage) {
                        Platform.runLater(() -> apply(stage));
                    }
                }
            }
        });
    }

    private static void apply(Stage stage) {
        if (!stage.isShowing() || stage.getTitle() == null || stage.getTitle().isEmpty()) {
            return;
        }
        try (Arena arena = Arena.ofConfined()) {
            Search search = new Search(stage.getTitle(), arena);
            MethodHandle callback = MethodHandles.lookup().findVirtual(Search.class, "visit",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class)).bindTo(search);
            MemorySegment stub = Linker.nativeLinker().upcallStub(callback,
                    FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS), arena);
            NativeApi.ENUM_WINDOWS.invoke(stub, MemorySegment.NULL);
            if (!search.hwnd.equals(MemorySegment.NULL)) {
                MemorySegment enabled = arena.allocate(JAVA_INT);
                enabled.set(JAVA_INT, 0, 1);
                int result = (int) NativeApi.DWM_SET_WINDOW_ATTRIBUTE.invoke(
                        search.hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, enabled, (int) JAVA_INT.byteSize());
                if (result != 0) {
                    LOG.debug("Windows dark title bar is unavailable: HRESULT={}", result);
                }
            }
        } catch (Throwable error) {
            LOG.debug("Could not apply Windows dark title bar", error);
        }
    }

    private static final class NativeApi {
        private static final Linker LINKER = Linker.nativeLinker();
        private static final SymbolLookup USER32 = SymbolLookup.libraryLookup("User32", Arena.global());
        private static final SymbolLookup DWMAPI = SymbolLookup.libraryLookup("Dwmapi", Arena.global());
        private static final MethodHandle ENUM_WINDOWS = LINKER.downcallHandle(
                USER32.findOrThrow("EnumWindows"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle GET_WINDOW_THREAD_PROCESS_ID = LINKER.downcallHandle(
                USER32.findOrThrow("GetWindowThreadProcessId"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS));
        private static final MethodHandle GET_WINDOW_TEXT_LENGTH = LINKER.downcallHandle(
                USER32.findOrThrow("GetWindowTextLengthW"), FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle GET_WINDOW_TEXT = LINKER.downcallHandle(
                USER32.findOrThrow("GetWindowTextW"), FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        private static final MethodHandle DWM_SET_WINDOW_ATTRIBUTE = LINKER.downcallHandle(
                DWMAPI.findOrThrow("DwmSetWindowAttribute"),
                FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT));
    }

    private static final class Search {
        private final String title;
        private final Arena arena;
        private final long processId = ProcessHandle.current().pid();
        private MemorySegment hwnd = MemorySegment.NULL;

        private Search(String title, Arena arena) {
            this.title = title;
            this.arena = arena;
        }

        private int visit(MemorySegment candidate, MemorySegment ignored) {
            try {
                MemorySegment owner = arena.allocate(JAVA_INT);
                NativeApi.GET_WINDOW_THREAD_PROCESS_ID.invoke(candidate, owner);
                if (Integer.toUnsignedLong(owner.get(JAVA_INT, 0)) != processId) {
                    return 1;
                }
                int length = (int) NativeApi.GET_WINDOW_TEXT_LENGTH.invoke(candidate);
                if (length != title.length()) {
                    return 1;
                }
                MemorySegment buffer = arena.allocate((long) (length + 1) * Character.BYTES, Character.BYTES);
                int copied = (int) NativeApi.GET_WINDOW_TEXT.invoke(candidate, buffer, length + 1);
                String candidateTitle = new String(buffer.asSlice(0, (long) copied * Character.BYTES)
                        .toArray(JAVA_BYTE), StandardCharsets.UTF_16LE);
                if (title.equals(candidateTitle)) {
                    hwnd = candidate;
                    return 0;
                }
            } catch (Throwable error) {
                LOG.debug("Could not inspect a Windows title bar", error);
            }
            return 1;
        }
    }
}
