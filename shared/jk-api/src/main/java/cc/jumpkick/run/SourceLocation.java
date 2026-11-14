// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.run;

import java.nio.file.Path;

/**
 * Where a diagnostic points: an absolute {@code file} ({@code ""} until the file is known), a
 * 1-based {@code line} and {@code column}, each {@code 0} when unknown. A location with a file and
 * no line is the whole file.
 */
public record SourceLocation(String file, int line, int column) {

    public SourceLocation {
        line = Math.max(0, line);
        column = Math.max(0, column);
    }

    /** {@code line}:{@code column} with the file still unknown. */
    public static SourceLocation at(int line, int column) {
        return new SourceLocation("", line, column);
    }

    /** The whole of {@code file}. */
    public static SourceLocation of(Path file) {
        return new SourceLocation(file.toAbsolutePath().normalize().toString(), 0, 0);
    }

    /** This location in {@code file}, unless it already names one. */
    public SourceLocation in(Path file) {
        if (!this.file.isEmpty()) return this;
        return new SourceLocation(file.toAbsolutePath().normalize().toString(), line, column);
    }
}
