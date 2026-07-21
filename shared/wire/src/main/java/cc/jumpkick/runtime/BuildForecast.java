// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

/**
 * Pre-flight forecast: dirty module dirs, lock staleness, empty workspace, graph errors.
 */
public record BuildForecast(Set<Path> dirtyDirs, boolean lockStale, boolean empty, List<String> errors) {
    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    /** True when nothing needs to rebuild and the lock is trustworthy — the "all up to date" shortcut. */
    public boolean fullyCached() {
        return dirtyDirs.isEmpty() && !lockStale;
    }
}
