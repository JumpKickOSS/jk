// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.WorkspaceClasspath;
import cc.jumpkick.host.Log;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.MemberRows;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Runtime jars for one module: its runtime classpath from the lock ({@link
 * ClasspathResolver#classpathFor(Lockfile, Set, boolean, JkBuild, Path)}) plus sibling thin jars —
 * a relocating sibling's fat jar in place of both its thin jar and its externals. Shared by
 * packaging (assembly) and thin-worker install.
 */
public final class ModuleRuntimeClasspath {

    private ModuleRuntimeClasspath() {}

    /**
     * @param moduleDir module root (directory with {@code jk.toml})
     * @param project parsed module manifest
     * @param lockFile workspace or project {@code jk-lock.toml} (may be missing)
     * @param cas content-addressed store used to resolve lock checksums to jar paths
     */
    public static List<Path> jars(Path moduleDir, JkBuild project, Path lockFile, Cas cas) throws IOException {
        return jars(moduleDir, project, lockFile, new ClasspathResolver(cas));
    }

    /**
     * As {@link #jars(Path, JkBuild, Path, Cas)} with the resolver — and so the artifact locator —
     * the caller chose.
     */
    public static List<Path> jars(Path moduleDir, JkBuild project, Path lockFile, ClasspathResolver resolver)
            throws IOException {
        return jars(moduleDir, project, lockFile, resolver, Files::exists);
    }

    /**
     * As above with {@code present} deciding which sibling jars of the declared closure are
     * listed. The build lists the jars on disk when it packages; a forecast after {@code jk clean}
     * lists those plus the wiped jars it knows the build restores first, so both hash the same set.
     */
    public static List<Path> jars(
            Path moduleDir, JkBuild project, Path lockFile, ClasspathResolver resolver, Predicate<Path> present)
            throws IOException {
        List<Path> depJars = new ArrayList<>();
        if (lockFile == null || !Files.exists(lockFile)) {
            try {
                WorkspaceClasspath.Result siblings =
                        WorkspaceClasspath.resolve(moduleDir, project, Set.of(Scope.EXPORT, Scope.MAIN));
                for (Path j : siblings.siblingClosureJars()) {
                    if (present.test(j) && !depJars.contains(j)) depJars.add(j);
                }
            } catch (Exception e) {
                /* best-effort */
                Log.debug("jars: best-effort", e);
            }
            return depJars;
        }
        Lockfile lock = MemberRows.view(LockfileReader.read(lockFile), lockFile, moduleDir);
        WorkspaceClasspath.Result siblings =
                WorkspaceClasspath.resolve(moduleDir, project, Set.of(Scope.EXPORT, Scope.MAIN));
        depJars.addAll(resolver.classpathFor(lock, ClasspathResolver.RUNTIME, false, project, moduleDir));
        for (Path j : siblings.siblingClosureJars()) {
            if (present.test(j) && !depJars.contains(j)) depJars.add(j);
        }
        return depJars;
    }

    /** Convenience when the lock path should be derived via {@link LockPaths#lockFile}. */
    public static List<Path> jars(Path moduleDir, JkBuild project, Cas cas) throws IOException {
        return jars(moduleDir, project, LockPaths.lockFile(moduleDir), cas);
    }
}
