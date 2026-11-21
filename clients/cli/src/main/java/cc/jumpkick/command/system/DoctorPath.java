// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.command.system.DoctorCommand.Check;
import cc.jumpkick.command.system.DoctorCommand.Status;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** The doctor's {@code path} row: whether the {@code jk} a {@code PATH} runs is the installed one. */
final class DoctorPath {

    private DoctorPath() {}

    /**
     * The row for {@code path}: OK when the first {@code jk} on it is the one in {@code bin}; a
     * warning naming both when another is found first, since an update to the installed one would
     * not be the {@code jk} that runs, or when none is. Nothing to check when {@code bin} holds no
     * {@code jk}.
     */
    static Check check(@Nullable String path, Path bin, boolean windows) {
        List<String> names = windows ? List.of("jk.exe", "jk.cmd", "jk.bat") : List.of("jk");
        Path installed = firstLauncher(bin, names, windows);
        if (installed == null) return new Check(Status.OK, "path", "no jk in " + bin);
        for (String entry : SearchPath.entries(path)) {
            Path dir = SearchPath.path(entry);
            if (dir == null || !dir.isAbsolute()) continue;
            Path found = firstLauncher(dir, names, windows);
            if (found == null) continue;
            if (sameFile(found, installed)) return new Check(Status.OK, "path", "jk runs from " + installed);
            return new Check(Status.WARN, "path", found + " shadows " + installed + "; put " + bin + " first on PATH");
        }
        return new Check(Status.WARN, "path", bin + " is not on PATH; run `jk activate`");
    }

    /** The first of {@code names} in {@code dir} that this platform would run; null when none is. */
    private static @Nullable Path firstLauncher(Path dir, List<String> names, boolean windows) {
        for (String name : names) {
            Path candidate = dir.resolve(name);
            if (Files.isRegularFile(candidate) && (windows || PathUtil.isRunnable(candidate))) return candidate;
        }
        return null;
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException e) {
            return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
        }
    }
}
