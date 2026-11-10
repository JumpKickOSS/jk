// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.rules;

import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * One positioned load problem. Errors stop the rule from loading (and the lanes from running with
 * a half-read file); warnings load and are reported once.
 */
public record LoadError(
        Severity severity, Path file, int line, @Nullable String ruleId, String message) {

    public enum Severity {
        ERROR,
        WARNING,
        /**
         * A rule this jk is too old to read ({@code requires-jk}): left out of the set, and reported
         * as a notice rather than as an error or a red scan.
         */
        HELD
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }

    /** {@code file:line: [id] message}, the shape a terminal and an agent both parse. */
    public String render() {
        StringBuilder sb = new StringBuilder();
        sb.append(file.getFileName()).append(':').append(line).append(": ");
        if (ruleId != null) sb.append('[').append(ruleId).append("] ");
        return sb.append(message).toString();
    }
}
