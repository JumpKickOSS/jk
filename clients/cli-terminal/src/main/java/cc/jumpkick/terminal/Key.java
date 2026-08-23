// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.terminal;

/**
 * One decoded input event. CSI arrows and ESC-peek live in {@code Keys} (JK-2374 expands
 * parse); the type is here so {@link TerminalSession#readKey} has a stable return.
 */
public sealed interface Key
        permits Key.CtrlC,
                Key.CtrlO,
                Key.CtrlX,
                Key.Enter,
                Key.Space,
                Key.Tab,
                Key.Backspace,
                Key.Escape,
                Key.Up,
                Key.Down,
                Key.Left,
                Key.Right,
                Key.Char,
                Key.Unknown {

    record CtrlC() implements Key {
        public static final CtrlC INSTANCE = new CtrlC();
    }

    record CtrlO() implements Key {
        public static final CtrlO INSTANCE = new CtrlO();
    }

    record CtrlX() implements Key {
        public static final CtrlX INSTANCE = new CtrlX();
    }

    record Enter() implements Key {
        public static final Enter INSTANCE = new Enter();
    }

    record Space() implements Key {
        public static final Space INSTANCE = new Space();
    }

    record Tab() implements Key {
        public static final Tab INSTANCE = new Tab();
    }

    record Backspace() implements Key {
        public static final Backspace INSTANCE = new Backspace();
    }

    record Escape() implements Key {
        public static final Escape INSTANCE = new Escape();
    }

    record Up() implements Key {
        public static final Up INSTANCE = new Up();
    }

    record Down() implements Key {
        public static final Down INSTANCE = new Down();
    }

    record Left() implements Key {
        public static final Left INSTANCE = new Left();
    }

    record Right() implements Key {
        public static final Right INSTANCE = new Right();
    }

    record Char(char c) implements Key {}

    record Unknown(int code) implements Key {}
}
