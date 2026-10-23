package com.voiceassistant;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Назначения клавиш записи; null означает отключённое действие. */
public record HotkeySettings(KeyCodeCombination start, KeyCodeCombination send,
                             KeyCodeCombination stop) {
    public HotkeySettings {
        Set<KeyCodeCombination> assigned = new HashSet<>();
        for (KeyCodeCombination combination : new KeyCodeCombination[]{start, send, stop}) {
            if (combination != null && !assigned.add(combination)) {
                throw new IllegalArgumentException("Одна комбинация назначена нескольким действиям.");
            }
        }
    }

    public static HotkeySettings defaults() {
        return new HotkeySettings(
                new KeyCodeCombination(KeyCode.R, KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN),
                new KeyCodeCombination(KeyCode.ENTER, KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN),
                new KeyCodeCombination(KeyCode.S, KeyCombination.CONTROL_DOWN, KeyCombination.ALT_DOWN));
    }

    public static KeyCodeCombination parse(String saved) {
        if (saved == null || saved.isEmpty()) return null;
        KeyCombination parsed = KeyCombination.valueOf(saved);
        if (!(parsed instanceof KeyCodeCombination combination)) {
            throw new IllegalArgumentException("Ожидается сочетание с клавишей.");
        }
        validate(combination);
        return combination;
    }

    public static KeyCodeCombination fromEvent(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code.isModifierKey()) return null;
        List<KeyCombination.Modifier> modifiers = new ArrayList<>();
        if (event.isShiftDown()) modifiers.add(KeyCombination.SHIFT_DOWN);
        if (event.isControlDown()) modifiers.add(KeyCombination.CONTROL_DOWN);
        if (event.isAltDown()) modifiers.add(KeyCombination.ALT_DOWN);
        if (event.isMetaDown()) modifiers.add(KeyCombination.META_DOWN);
        KeyCodeCombination combination = new KeyCodeCombination(code,
                modifiers.toArray(KeyCombination.Modifier[]::new));
        validate(combination);
        return combination;
    }

    public static void validate(KeyCodeCombination combination) {
        KeyCode code = combination.getCode();
        boolean ctrl = combination.getControl() == KeyCombination.ModifierValue.DOWN;
        boolean alt = combination.getAlt() == KeyCombination.ModifierValue.DOWN;
        boolean shift = combination.getShift() == KeyCombination.ModifierValue.DOWN;
        boolean meta = combination.getMeta() == KeyCombination.ModifierValue.DOWN;
        if (meta || code.isModifierKey() || code == KeyCode.UNDEFINED) {
            throw new IllegalArgumentException("Эта клавиша зарезервирована системой.");
        }
        if ((alt && (code == KeyCode.F4 || code == KeyCode.TAB || code == KeyCode.ESCAPE
                || code == KeyCode.SPACE)) || (ctrl && code == KeyCode.ESCAPE)
                || (ctrl && alt && code == KeyCode.DELETE) || code == KeyCode.PRINTSCREEN) {
            throw new IllegalArgumentException("Это системное сочетание нельзя назначить.");
        }
        if (!alt && ctrl && (code == KeyCode.A || code == KeyCode.C || code == KeyCode.V
                || code == KeyCode.X || code == KeyCode.Z || code == KeyCode.Y)
                || !alt && ctrl && code == KeyCode.INSERT || !ctrl && shift &&
                (code == KeyCode.INSERT || code == KeyCode.DELETE)) {
            throw new IllegalArgumentException("Это сочетание используется при редактировании текста.");
        }
    }
}
