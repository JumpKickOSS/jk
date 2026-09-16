// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.runtime.base.GuardSuiteLibrary;
import cc.jumpkick.runtime.base.LockGate;
import cc.jumpkick.version.Versions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/**
 * The {@code [[plugin]]} rows for plugins that ship inside jk. Such a row records the jk whose copy
 * the project built with; it never chooses bytes. The running jk's own copy satisfies it, and a
 * build under another jk rewrites the row in place — the library rows do not move, so a jk upgrade
 * is not a relock. Rows a {@code [plugins]} table declares, and rows pinned to a workspace module by
 * {@code path}, are not first-party rows and keep their pin.
 */
public final class FirstPartyPins {

    private FirstPartyPins() {}

    /** One row moved to the running jk: its coordinate and the version it named before. */
    public record Repin(String coordinate, String from) {}

    /**
     * The row this jk writes for one of its built-in plugins. A pre-release version is republished
     * with every rebuild, so the version is the whole pin; a stable release is immutable and the row
     * carries the digest of {@code jar}.
     */
    public static Lockfile.PluginEntry running(PluginJar plugin, Path jar) throws IOException {
        if (Versions.isPreRelease(JkVersion.VERSION)) return versionOnly(plugin);
        return new Lockfile.PluginEntry(coordinate(plugin), JkVersion.VERSION, "sha256:" + Hashing.sha256Hex(jar));
    }

    private static Lockfile.PluginEntry versionOnly(PluginJar plugin) {
        return Lockfile.PluginEntry.versionOnly(coordinate(plugin), JkVersion.VERSION);
    }

    private static String coordinate(PluginJar plugin) {
        return PluginJar.GROUP + ":" + plugin.artifactId();
    }

    /**
     * Rewrite every first-party row of the lock owning {@code dir} that another jk pinned so it names
     * this jk, and say which moved. Nothing else in the lock changes but {@code generated-by}. A lock
     * with no manifests digest is left alone: it is stale, and the relock that follows rewrites every
     * row anyway. {@code declared} is the {@code [plugins]} coordinates of the workspace, whose rows
     * are pinned by the declaration and not by jk.
     */
    public static List<Repin> follow(Path dir, Set<String> declared) throws IOException {
        Path lockFile = LockPaths.lockFile(dir);
        if (!Files.isRegularFile(lockFile)) return List.of();
        Path lockDir =
                Objects.requireNonNull(lockFile.toAbsolutePath().normalize().getParent(), "lock dir");
        synchronized (LockGate.monitorFor(lockDir)) {
            Lockfile lock = LockfileReader.read(lockFile);
            String manifestsSha = lock.manifestsSha256();
            if (manifestsSha == null) return List.of();
            Cas cas = JkStores.storeCas();
            List<Repin> moved = new ArrayList<>();
            List<Lockfile.PluginEntry> rows = new ArrayList<>();
            for (Lockfile.PluginEntry row : lock.plugins()) {
                Lockfile.PluginEntry current =
                        row.isWorkspace() || declared.contains(row.coordinate()) ? null : runningRow(row, lockDir, cas);
                if (current == null || current.equals(row)) {
                    rows.add(row);
                    continue;
                }
                rows.add(current);
                moved.add(new Repin(row.coordinate(), row.version()));
            }
            if (moved.isEmpty()) return List.of();
            LockfileWriter.write(
                    lock.withPlugins(rows).withGeneratedBy("jk " + JkVersion.VERSION), lockFile, manifestsSha);
            return List.copyOf(moved);
        }
    }

    /**
     * The row this jk writes for {@code row}'s coordinate, or {@code null} when the coordinate is not
     * a plugin this jk ships — or its jar is not on this machine to be hashed.
     */
    private static Lockfile.@Nullable PluginEntry runningRow(Lockfile.PluginEntry row, Path lockDir, Cas cas)
            throws IOException {
        if (GuardSuiteLibrary.COORDINATE.equals(row.coordinate())) return GuardSuiteLibrary.pin(lockDir, cas);
        int colon = row.coordinate().indexOf(':');
        if (colon < 0 || !PluginJar.GROUP.equals(row.coordinate().substring(0, colon))) return null;
        PluginJar plugin =
                PluginJar.byArtifactId(row.coordinate().substring(colon + 1)).orElse(null);
        if (plugin == null) return null;
        if (Versions.isPreRelease(JkVersion.VERSION)) return versionOnly(plugin);
        Path jar = plugin.locateStored(cas);
        return jar == null ? null : running(plugin, jar);
    }

    /** The one line a build says when rows moved. */
    public static String describe(List<Repin> moved) {
        String rows = moved.stream()
                .map(r -> r.coordinate().substring(r.coordinate().indexOf(':') + 1) + " " + r.from())
                .collect(Collectors.joining(", "));
        return "jk-lock.toml: " + rows + " → " + JkVersion.VERSION + " (first-party plugins follow the running jk)";
    }
}
