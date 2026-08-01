// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;

/**
 * The result of one module's build (see {@link WorkspaceBuildListener#onModuleFinish}).
 *
 * @param didWork {@code true} when at least one productive step (compile / test / package / …) did
 * real work rather than a cache hit or no-op. Used so the CLI can say "checked N modules, all
 * up to date" instead of "built N modules" when every re-entered module was fully cached
 * . Failures count as did-work (the module was not a pure check).
 */
public record ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis, boolean didWork) {

    /** Back-compat: assume work was done when the caller does not know (fail-open for "built"). */
    public ModuleOutcome(String coord, Path dir, boolean success, int exitCode, long millis) {
        this(coord, dir, success, exitCode, millis, true);
    }
}
