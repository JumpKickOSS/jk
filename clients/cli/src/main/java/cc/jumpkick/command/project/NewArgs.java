// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import java.nio.file.Path;
import java.util.Optional;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

/** How {@code jk new} reads its positional directory: the target and the name it implies. */
@NullMarked
public final class NewArgs {

    private NewArgs() {}

    static Optional<String> presetName(@Nullable Path directoryArg, Path cwd) {
        if (directoryArg == null) return Optional.empty();
        if (isCurrentDirArg(directoryArg)) {
            var leaf = cwd.getFileName();
            if (leaf == null) return Optional.empty();
            var s = leaf.toString();
            return (s.isBlank() || s.equals(".")) ? Optional.empty() : Optional.of(s);
        }
        var leaf = directoryArg.getFileName();
        if (leaf == null) return Optional.empty();
        var s = leaf.toString();
        return (s.isBlank() || s.equals(".")) ? Optional.empty() : Optional.of(s);
    }

    /**
     * Final target directory, given the positional arg, the cwd, and the resolved project name.
     * Package-private for unit testing.
     */
    static Path resolveTarget(@Nullable Path directoryArg, Path cwd, String projectName) {
        if (directoryArg != null) {
            if (isCurrentDirArg(directoryArg)) return cwd;
            return cwd.resolve(directoryArg).normalize();
        }
        // No positional → create a subdir under cwd named after the project.
        return cwd.resolve(projectName);
    }

    /** Match the literal {@code "."} forms the user might type. */
    static boolean isCurrentDirArg(Path arg) {
        var raw = arg.toString();
        return raw.equals(".") || raw.equals("./") || raw.equals(".\\");
    }
}
