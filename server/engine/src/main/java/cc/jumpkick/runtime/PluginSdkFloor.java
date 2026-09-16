// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.compile.ClasspathResolver;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.PluginDeclaration;
import cc.jumpkick.model.Scope;
import cc.jumpkick.repo.RepoGroup;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The SDK floor a pinned third-party plugin forks with: {@code jk-plugin-sdk} and {@code jk-host}
 * at the plugin's SDK version, riding in the consumer's lock as ordinary {@link Scope#PLUGIN} rows
 * resolved from the consumer's declared repositories. The fork classpath is the plugin's jar plus
 * these rows; nothing of the module's own compiles or runs with them.
 *
 * <p>A plugin manifest carries no SDK version, so the floor is pinned at the running jk's version,
 * and the lock says so in its notes.
 */
public final class PluginSdkFloor {

    private PluginSdkFloor() {}

    /** The SDK floor's artifacts, in classpath order. */
    public static final List<String> ARTIFACTS = List.of("jk-plugin-sdk", "jk-host");

    /** True for a {@code [plugins]} declaration that forks third-party code: any pin that is not first-party. */
    public static boolean needsFloor(PluginDeclaration decl) {
        return !PluginJar.GROUP.equals(decl.group());
    }

    /**
     * The lock rows for {@code decl}'s SDK floor, fetched from {@code repos}. Each row names the
     * repository it came from and is pinned by the plugin's coordinate. A floor no declared
     * repository serves yields no rows and a note: a plugin without a code layer never forks, and
     * one that does is refused at the fork with the remedy ({@link #missing}).
     */
    public static List<Lockfile.Artifact> rows(RepoGroup repos, PluginDeclaration decl, Consumer<String> note)
            throws IOException, InterruptedException {
        String version = JkVersion.VERSION;
        note.accept("note: " + decl.coordinate() + " declares no SDK version; its SDK floor ("
                + String.join(", ", ARTIFACTS) + ") is pinned at the running jk, " + version);
        List<Lockfile.Artifact> out = new ArrayList<>();
        for (String artifact : ARTIFACTS) {
            Coordinate coord = Coordinate.of(PluginJar.GROUP, artifact, version);
            RepoGroup.RepoFetched hit = repos.tryFetchArtifact(coord).orElse(null);
            if (hit == null) {
                note.accept("note: " + missing(decl.coordinate(), version));
                return List.of();
            }
            repos.tryFetchArtifact(new Coordinate(coord.group(), coord.artifact(), coord.version(), null, "pom"));
            out.add(new Lockfile.Artifact(
                    PluginJar.GROUP + ":" + artifact + ":jar:",
                    version,
                    hit.repo().name() + "+" + hit.repo().baseUrl(),
                    "sha256:" + hit.fetched().sha256(),
                    null,
                    List.of(Scope.PLUGIN),
                    List.of(),
                    "plugin:" + decl.coordinate(),
                    null));
        }
        return List.copyOf(out);
    }

    /** The one sentence for a plugin whose SDK floor no declared repository serves. */
    public static String missing(String coordinate, String version) {
        return coordinate + " forks with the jk plugin SDK, and no declared repository serves " + PluginJar.GROUP
                + ":jk-plugin-sdk:" + version + " — add the repository that publishes it to [repositories] and run"
                + " `jk lock`";
    }

    /** {@code rows} merged over {@code lock}'s artifacts: a row with the same name and version is replaced. */
    public static Lockfile withRows(Lockfile lock, List<Lockfile.Artifact> rows) {
        if (rows.isEmpty()) return lock;
        List<Lockfile.Artifact> merged = new ArrayList<>();
        for (Lockfile.Artifact existing : lock.artifacts()) {
            boolean replaced = rows.stream()
                    .anyMatch(
                            r -> r.name().equals(existing.name()) && r.version().equals(existing.version()));
            if (!replaced) merged.add(existing);
        }
        merged.addAll(rows);
        return lock.withArtifacts(merged);
    }

    /** The jars the lock's {@link Scope#PLUGIN} rows resolve to on this machine, in row order. */
    public static List<Path> classpath(Lockfile lock, Cas cas) {
        return new ClasspathResolver(cas).classpathFor(lock, Set.of(Scope.PLUGIN));
    }
}
