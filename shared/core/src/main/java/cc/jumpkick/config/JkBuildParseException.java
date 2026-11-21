// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.run.SourceLocation;
import cc.jumpkick.util.DirKeys;
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
 * a file, the message leads with it as {@code file:line:column: }, or {@code file: } for a refusal
 * of the whole file, so every place that prints the message says where; {@link #refusal()} is the
 * message without it, for a record that carries the location beside the text. The file is written
 * relative to the directory {@code jk} ran in when it lies under it: the bound {@link Request}'s
 * {@link Request#callerDir}, else the session's working directory.
 */
public sealed class JkBuildParseException extends RuntimeException permits UserConfigParseException {

    private @Nullable SourceLocation location;

    /** The location's file as the message writes it, fixed when {@link #locatedIn} names the file. */
    private @Nullable String shown;

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
        String message = refusal();
        SourceLocation at = location;
        if (at == null || at.file().isEmpty()) return message;
        String file = shown != null ? shown : at.file();
        // A refusal of the whole file that already names it, such as "jk.toml not found: <file>".
        if (at.line() == 0 && message != null && message.contains(at.file())) return message;
        String spot = at.line() == 0 ? file : file + ":" + at.line() + (at.column() == 0 ? "" : ":" + at.column());
        return message == null ? spot : spot + ": " + message;
    }

    /** What was refused, without the place {@link #getMessage()} leads with. */
    public @Nullable String refusal() {
        return super.getMessage();
    }

    /**
     * Name {@code file} as the manifest this failure is in, unless the location already names one,
     * and return this exception for rethrowing. A failure with no position becomes the whole file.
     */
    public JkBuildParseException locatedIn(Path file) {
        SourceLocation at = location;
        if (at != null && !at.file().isEmpty()) return this;
        location = at == null ? SourceLocation.of(file) : at.in(file);
        shown = shown(Path.of(location.file()));
        return this;
    }

    /** {@code file} relative to the directory {@code jk} ran in when it lies under it, else as given. */
    private static String shown(Path file) {
        Request request = Request.current();
        Path ranIn = request != null ? request.callerDir() : null;
        Path cwd = (ranIn != null ? ranIn : SessionContext.current().workingDir())
                .toAbsolutePath()
                .normalize();
        if (!file.startsWith(cwd) || file.equals(cwd)) return file.toString();
        return DirKeys.slashes(cwd.relativize(file).toString());
    }
}
