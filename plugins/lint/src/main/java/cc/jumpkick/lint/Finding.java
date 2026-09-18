// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.lint;

import org.jspecify.annotations.Nullable;

/**
 * One located finding a lint tool reported: where, how severe, which rule, and what it said. The
 * step reports each as a diagnostic — {@code file:line:col: message [rule]} — so the rule id
 * travels with the message into jk-results.md and the MCP diagnostics.
 *
 * @param severity {@code error} or {@code warning}
 * @param file the file as the tool named it — absolute, or relative to a source root — or null
 *     when the tool located nothing
 * @param line 1-based, 0 when unknown
 * @param col 1-based, 0 when unknown
 * @param rule the tool's rule id: Checkstyle's check name, PMD's rule, SpotBugs's bug pattern,
 *     detekt's rule
 * @param message the tool's own text
 */
public record Finding(String severity, @Nullable String file, int line, int col, String rule, String message) {

    static final String ERROR = "error";
    static final String WARNING = "warning";

    /** The diagnostic's message: the tool's text with the rule id after it. */
    String text() {
        return rule.isEmpty() ? message : message + " [" + rule + "]";
    }

    boolean isError() {
        return ERROR.equals(severity);
    }
}
