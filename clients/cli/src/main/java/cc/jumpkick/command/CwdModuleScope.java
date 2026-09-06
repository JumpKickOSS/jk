// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.wire.protocol.ProjectInfo;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * When the working directory is a workspace member and the user did not pass {@code -m}/{@code
 * --modules}, treat the invocation as {@code -m <this-module>}.
 *
 * <p>Uses the engine {@link ProjectInfo} peek — the CLI does not parse {@code jk.toml}. Explicit
 * {@code -m} still wins. {@code --affected-since} intersects with the inferred selector the same
 * way it intersects with an explicit {@code -m}.
 */
public final class CwdModuleScope {

    private CwdModuleScope() {}

    /**
     * @param workspaceRoot workspace root when {@link #workspaceMember()} is true; otherwise {@link
     *     #workingDir()}
     * @param workingDir the command's working directory (already normalized)
     * @param modulesSpec effective {@code -m} spec — explicit, or the member's relative path when
     *     inferred
     * @param inferredFromCwd true when {@code modulesSpec} was filled in from the working directory
     * @param focusLabel project {@code name} from the member peek (path fallback); set only when
     *     inferred
     * @param workspaceMember true when {@code workingDir} is a listed workspace member
     */
    public record Resolved(
            Path workspaceRoot,
            Path workingDir,
            @Nullable String modulesSpec,
            boolean inferredFromCwd,
            @Nullable String focusLabel,
            boolean workspaceMember) {

        /** True when a module selector is in effect (explicit or inferred). */
        public boolean scoped() {
            return modulesSpec != null && !modulesSpec.isBlank();
        }
    }

    /**
     * Resolve cwd-as-module-scope from an engine project summary. {@code modulesSpec} is the raw
     * {@code -m}/{@code --modules} value (nullable / blank = unset). {@code peek} may be null when
     * the engine is unavailable.
     */
    public static Resolved resolve(Path workingDir, @Nullable String modulesSpec, @Nullable ProjectInfo peek) {
        if (peek == null) {
            return resolve(workingDir, modulesSpec, false, "", null);
        }
        return resolve(workingDir, modulesSpec, peek.workspaceRoot(), peek.workspaceRootDir(), peek.name());
    }

    /**
     * Same as {@link #resolve(Path, String, ProjectInfo)} from already-unpacked peek fields (tests
     * do not construct a full {@link ProjectInfo}).
     */
    public static Resolved resolve(
            Path workingDir,
            @Nullable String modulesSpec,
            @Nullable boolean workspaceRoot,
            String workspaceRootDir,
            @Nullable String projectName) {
        Path cwd = workingDir.toAbsolutePath().normalize();
        String spec = modulesSpec == null || modulesSpec.isBlank() ? null : modulesSpec;
        if (workspaceRoot) {
            Path root = blank(workspaceRootDir)
                    ? cwd
                    : Path.of(workspaceRootDir).toAbsolutePath().normalize();
            return new Resolved(root, cwd, spec, false, null, false);
        }
        if (blank(workspaceRootDir)) {
            return new Resolved(cwd, cwd, spec, false, null, false);
        }
        Path root = Path.of(workspaceRootDir).toAbsolutePath().normalize();
        if (root.equals(cwd)) {
            return new Resolved(root, cwd, spec, false, null, false);
        }
        String rel = relPath(root, cwd);
        boolean inferred = spec == null;
        String label = inferred ? (blank(projectName) ? rel : projectName) : null;
        return new Resolved(root, cwd, inferred ? rel : spec, inferred, label, true);
    }

    /** Workspace-relative POSIX path of {@code dir} under {@code root}. */
    static String relPath(Path root, Path dir) {
        String rel = root.relativize(dir).toString().replace('\\', '/');
        return rel.isEmpty() ? "." : rel;
    }

    private static boolean blank(@Nullable String s) {
        return s == null || s.isBlank();
    }
}
