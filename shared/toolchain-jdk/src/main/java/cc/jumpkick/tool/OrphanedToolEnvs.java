// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import cc.jumpkick.host.PathUtil;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The one rule for what a root wipe does to installed tools, shared by {@code jk self nuke} and
 * {@code jk storage nuke}: a deleted root orphans a tool env when the env itself lies under it (the
 * state root holds every env) or when any entry of the classpath its {@code env.json} records does
 * (the store holds the jars). An orphaned env is dead either way — its launcher execs a classpath
 * that is gone, and {@code jk tool list} would keep naming a tool nothing can run — so the env goes
 * with the root, and so do the launchers {@code bin} carries for it, in both the POSIX and the
 * {@code .cmd} spelling.
 *
 * <p>Only env directories whose names pass {@link LauncherName} count: jk's own client sits in
 * {@code bin} under a reserved stem, and a directory under that name never names a launcher.
 */
public final class OrphanedToolEnvs {

    private OrphanedToolEnvs() {}

    /**
     * One installed tool a wipe leaves unrunnable.
     *
     * @param envDir the {@code <envsRoot>/<name>} directory holding {@code env.json}
     * @param launchers the launchers present in {@code bin} for it, absolute; empty when none is
     * @param orphanedBy the deleted root that kills it — the first of the roots given that reaches
     *     the env or its classpath
     */
    public record Orphan(String name, Path envDir, List<Path> launchers, Path orphanedBy) {}

    /**
     * The tools under {@code envsRoot} that deleting {@code roots} orphans, by name. Empty when no
     * root is scheduled, when there are no envs, or when the envs directory cannot be listed — the
     * caller's own delete of the root reports that.
     */
    public static List<Orphan> under(Path envsRoot, Path binDir, List<Path> roots) {
        if (roots.isEmpty() || !Files.isDirectory(envsRoot)) return List.of();
        List<Path> deleted =
                roots.stream().map(r -> r.toAbsolutePath().normalize()).toList();
        List<String> names = new ArrayList<>();
        try {
            PathUtil.forEachChild(envsRoot, (env, attrs) -> {
                String name = env.getFileName().toString();
                if (attrs.isDirectory() && LauncherName.validationError(name).isEmpty()) names.add(name);
                return true;
            });
        } catch (IOException unreadable) {
            return List.of();
        }
        names.sort(null);
        List<Orphan> out = new ArrayList<>();
        for (String name : names) {
            Path envDir = envsRoot.resolve(name).toAbsolutePath().normalize();
            Path by = orphanedBy(envDir, InstalledToolEnvs.recordedClasspath(envsRoot, name), deleted);
            if (by == null) continue;
            List<Path> launchers = new ArrayList<>();
            for (String leaf : List.of(name, name + ".cmd")) {
                Path launcher = LauncherName.resolveChild(binDir, leaf);
                if (Files.exists(launcher, LinkOption.NOFOLLOW_LINKS)) {
                    launchers.add(launcher.toAbsolutePath().normalize());
                }
            }
            out.add(new Orphan(name, envDir, List.copyOf(launchers), by));
        }
        return List.copyOf(out);
    }

    /** Remove one orphan from disk: the env directory, then every launcher. */
    public static void remove(Orphan orphan) throws IOException {
        PathUtil.deleteRecursivelyOrThrow(orphan.envDir());
        for (Path launcher : orphan.launchers()) Files.deleteIfExists(launcher);
    }

    private static @Nullable Path orphanedBy(Path envDir, List<Path> classpath, List<Path> deleted) {
        for (Path root : deleted) {
            if (envDir.startsWith(root)) return root;
        }
        for (Path entry : classpath) {
            Path abs = entry.toAbsolutePath().normalize();
            for (Path root : deleted) {
                if (abs.startsWith(root)) return root;
            }
        }
        return null;
    }
}
