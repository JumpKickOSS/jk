// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.model;

import java.util.Locale;

/**
 * {@code [build] debug}: how much debug information javac writes into each class file.
 *
 * <p>{@link #FULL} is the default, and what Gradle and Maven compile with. javac's own default
 * is {@link #LINES}, which leaves a debugger attached to the running program without local
 * variable names. A compile input: the level rides the javac argv into the action key.
 */
public enum DebugInfo {
    /** {@code -g}: source file, line numbers and local variables. */
    FULL("-g"),
    /** {@code -g:source,lines}: stack traces resolve to lines; locals are not named. */
    LINES("-g:source,lines"),
    /** {@code -g:none}: the smallest class files; stack traces carry no line numbers. */
    NONE("-g:none");

    private final String javacFlag;

    DebugInfo(String javacFlag) {
        this.javacFlag = javacFlag;
    }

    /** The javac argument that selects this level. */
    public String javacFlag() {
        return javacFlag;
    }

    /** Parse {@code full} / {@code lines} / {@code none} (case-insensitive). */
    public static DebugInfo parse(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "full" -> FULL;
            case "lines" -> LINES;
            case "none" -> NONE;
            default ->
                throw new IllegalArgumentException("unknown debug level `" + raw + "` (want full, lines or none)");
        };
    }

    public String wireName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
