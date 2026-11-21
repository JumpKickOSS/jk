// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.ManifestLocator;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.resolver.pubgrub.Diagnostics;
import cc.jumpkick.resolver.pubgrub.UnsatisfiableException;
import cc.jumpkick.run.SourceLocation;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Where in the project a failure that ended a run points: a manifest that does not parse or
 * validate at the spot the parser named, an unsatisfiable resolve at the declaration of the
 * dependency it derives from. One reading for every verb's run record, so the dashboard, MCP and
 * an editor's problem list all mark the same line.
 */
public final class FailureLocation {

    private FailureLocation() {}

    /** The location {@code failure}, or a cause of it, names in the project at {@code projectDir}; {@code null} when none. */
    public static @Nullable SourceLocation of(Throwable failure, Path projectDir) {
        for (Throwable t : Errors.chain(failure)) {
            if (t instanceof JkBuildParseException parse && parse.location() != null) {
                return parse.location().in(ManifestPaths.manifestIn(projectDir));
            }
            if (t instanceof UnsatisfiableException unsat) {
                return ManifestLocator.dependency(projectDir, Diagnostics.rootConstraints(unsat.rootCause()));
            }
        }
        return null;
    }
}
