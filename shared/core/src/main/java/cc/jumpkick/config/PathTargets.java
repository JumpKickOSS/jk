// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PathSource;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** The projects {@code path} dependencies name, each resolved from the directory that declares it. */
public final class PathTargets {

    private PathTargets() {}

    /**
     * The {@code group:artifact} the jk project at {@code source}'s target publishes, or null when
     * the target is no readable jk project (a Maven or Gradle build reveals its coordinate only once
     * built).
     */
    public static @Nullable String coordinate(Path declaringDir, PathSource source) {
        Path manifest = ManifestPaths.manifestIn(source.dir(declaringDir));
        if (!Files.isRegularFile(manifest)) return null;
        try {
            JkBuild target = JkBuildParser.parse(manifest);
            return target.project().group() + ":" + target.project().name();
        } catch (UserConfigParseException unparsed) {
            throw unparsed; // config.toml's own error, with its position — never this site's fallback
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /**
     * {@code build} with each path dependency's {@code module} the coordinate its target publishes,
     * the name its lock row carries. A dependency whose target names none keeps its own.
     */
    public static JkBuild named(JkBuild build, Path declaringDir) {
        Map<Scope, List<Dependency>> byScope = build.dependencies().byScope();
        if (byScope.values().stream().flatMap(List::stream).noneMatch(Dependency::isPath)) return build;
        Map<Scope, List<Dependency>> out = new EnumMap<>(Scope.class);
        byScope.forEach((scope, deps) -> {
            List<Dependency> named = new ArrayList<>(deps.size());
            for (Dependency d : deps) {
                PathSource source = d.pathSource();
                String coordinate = source == null ? null : coordinate(declaringDir, source);
                named.add(coordinate == null ? d : d.withModule(coordinate));
            }
            out.put(scope, named);
        });
        return build.withDependencies(new JkBuild.Dependencies(out));
    }
}
