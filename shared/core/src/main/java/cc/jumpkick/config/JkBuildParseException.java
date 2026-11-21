// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.run.SourceLocation;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlPosition;
import org.tomlj.TomlTable;

/**
 * Raised when a {@code jk.toml} file cannot be parsed or fails minimal validation, or when {@code
 * ~/.jk/config.toml} does not parse ({@link UserConfigParseException}).
 *
 * <p>{@link #location()} says where in the manifest: the line and column of a syntax error or of
 * the key a check refused, and the file once {@link JkBuildParser} knows which one it was reading,
 * so an editor can mark the spot. {@code null} for a failure no file owns. Once the location names
 * a file and a line, the message leads with them as {@code file:line:column: }, so every place that
 * prints the message says where.
 */
public sealed class JkBuildParseException extends RuntimeException permits UserConfigParseException {

    private @Nullable SourceLocation location;

    public JkBuildParseException(@Nullable String message) {
        super(message);
    }

    public JkBuildParseException(@Nullable String message, Throwable cause) {
        super(message, cause);
    }

    public JkBuildParseException(@Nullable String message, @Nullable SourceLocation location) {
        super(message);
        this.location = location;
    }

    /** A refusal of {@code key} in {@code table}, located at the key as the file spells it. */
    public static JkBuildParseException at(TomlTable table, String key, String message) {
        return new JkBuildParseException(message, positionOf(table, key));
    }

    /** Where {@code key} of {@code table} sits in the text the table was parsed from, when tomlj kept it. */
    static @Nullable SourceLocation positionOf(TomlTable table, String key) {
        TomlPosition at;
        try {
            at = table.inputPositionOf(List.of(key));
        } catch (RuntimeException e) {
            return null;
        }
        return at == null ? null : SourceLocation.at(at.line(), at.column());
    }

    public @Nullable SourceLocation location() {
        return location;
    }

    @Override
    public @Nullable String getMessage() {
        String message = super.getMessage();
        SourceLocation at = location;
        if (at == null || at.file().isEmpty() || at.line() == 0) return message;
        String spot = at.file() + ":" + at.line() + (at.column() == 0 ? "" : ":" + at.column());
        return message == null ? spot : spot + ": " + message;
    }

    /**
     * Name {@code file} as the manifest this failure is in, unless the location already names one,
     * and return this exception for rethrowing. A failure with no position becomes the whole file.
     */
    public JkBuildParseException locatedIn(Path file) {
        SourceLocation at = location;
        location = at == null ? SourceLocation.of(file) : at.in(file);
        return this;
    }
}
