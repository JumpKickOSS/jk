// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.generate;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The located lines in a tool's output: {@code path:line[:col]: message}, the shape compilers
 * and most generators print. A line that carries no location is log, not a diagnostic.
 */
final class ToolDiagnostics {

    /** A path token without whitespace, a line, an optional column, a separator, the message. */
    private static final Pattern LOCATED = Pattern.compile("^\\s*(\\S+?):(\\d+)(?::(\\d+))?:?\\s+(.*)$");

    private static final Pattern SEVERITY =
            Pattern.compile("^(error|warning|warn|info)\\b[:\\s-]*", Pattern.CASE_INSENSITIVE);

    private ToolDiagnostics() {}

    /**
     * One located finding. {@code severity} is {@code error} or {@code warning} when the message
     * itself says so, else null — the caller decides from the tool's exit.
     */
    record Located(String file, int line, int col, @Nullable String severity, String message) {}

    static Optional<Located> parse(String line) {
        Matcher m = LOCATED.matcher(line);
        if (!m.matches()) return Optional.empty();
        String file = m.group(1);
        if (file.contains("://") || file.endsWith(":")) return Optional.empty(); // a URL, or `label: 12`
        String message = m.group(4);
        String severity = null;
        Matcher sev = SEVERITY.matcher(message);
        if (sev.find()) {
            severity = sev.group(1).toLowerCase(Locale.ROOT).startsWith("err") ? "error" : "warning";
            message = message.substring(sev.end());
        }
        return Optional.of(new Located(
                file,
                Integer.parseInt(m.group(2)),
                m.group(3) == null ? 0 : Integer.parseInt(m.group(3)),
                severity,
                message));
    }
}
