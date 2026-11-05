// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * {@code ~/.jk/config.toml} is not valid TOML. Raised by every read that decides where a request
 * goes or what it carries — repositories, {@code mirror-of}, {@code [mirrors]}, {@code [network]},
 * {@code [node]}, {@code [templates]}, {@code [plugins]} — because reading such a file as empty would
 * send traffic around the mirrors, proxies and credentials the user declared there, or run a shipped
 * plugin in place of the one the user pinned.
 *
 * <p>The position is the first error TOML reports, one-based.
 */
public final class UserConfigParseException extends JkBuildParseException {

    private final Path file;
    private final int line;
    private final int column;
    private final String reason;

    public UserConfigParseException(Path file, int line, int column, String reason) {
        super(message(file, line, column, reason));
        this.file = Objects.requireNonNull(file, "file");
        this.line = line;
        this.column = column;
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Path file() {
        return file;
    }

    public int line() {
        return line;
    }

    public int column() {
        return column;
    }

    /** What TOML said, without the position. */
    public String reason() {
        return reason;
    }

    private static String message(Path file, int line, int column, String reason) {
        return file + ":" + line + ":" + column + ": " + reason
                + " — jk reads no repositories, mirrors, proxies, credentials or plugin pins from a config.toml"
                + " that does not parse, so nothing is fetched or built until it is fixed (the next command sees"
                + " the fix; no engine restart)";
    }
}
