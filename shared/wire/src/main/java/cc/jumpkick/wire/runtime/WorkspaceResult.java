// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.wire.runtime;

import java.util.List;

/**
 * The whole workspace build result.
 *
 * @param errors graph-resolution errors (composite deps); non-empty ⇒ nothing built
 * @param cancelled user/deadline canceldistinct from a plain failure so clients can
 * render "cancelled" rather than "disconnected" / generic fail
 */
public record WorkspaceResult(
        boolean success, int exitCode, List<ModuleOutcome> modules, List<String> errors, boolean cancelled) {

    /** Compatibility constructor — not cancelled. */
    public WorkspaceResult(boolean success, int exitCode, List<ModuleOutcome> modules, List<String> errors) {
        this(success, exitCode, modules, errors, false);
    }
}
