// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import cc.jumpkick.run.SourceLocation;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlPosition;

/**
 * Where a manifest declares something, for a diagnostic to point at: the key of a dependency
 * entry, found in the project's own {@code jk.toml} or, in a workspace, the member's that declares
 * it. Reads the memoized documents {@link JkBuildParser} already holds.
 */
public final class ManifestLocator {

    private ManifestLocator() {}

    /**
     * The first declaration, in the order {@code coordinates} names them, of a dependency on one of
     * {@code coordinates} ({@code group:artifact}) in the project at {@code projectDir}: its key in
     * the scope table, {@code null} when no manifest there declares any or a manifest does not parse.
     */
    public static @Nullable SourceLocation dependency(Path projectDir, List<String> coordinates) {
        if (coordinates.isEmpty()) return null;
        List<Path> manifests;
        try {
            manifests = manifests(projectDir);
        } catch (UserConfigParseException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        for (String coordinate : coordinates) {
            for (Path manifest : manifests) {
                SourceLocation at = declaration(manifest, coordinate);
                if (at != null) return at;
            }
        }
        return null;
    }

    /** The root's manifest, then every member's. */
    private static List<Path> manifests(Path projectDir) throws IOException {
        Path rootFile = ManifestPaths.manifestIn(projectDir);
        List<Path> out = new ArrayList<>(List.of(rootFile));
        JkBuild root = JkBuildParser.parse(rootFile);
        for (Map.Entry<Path, JkBuild> member :
                WorkspaceLoader.loadModules(projectDir, root).entrySet()) {
            out.add(ManifestPaths.manifestIn(member.getKey()));
        }
        return out;
    }

    private static @Nullable SourceLocation declaration(Path manifest, String coordinate) {
        try {
            JkBuild build = JkBuildParser.parse(manifest);
            TomlParseResult document = JkBuildParser.document(manifest);
            for (Map.Entry<Scope, List<Dependency>> scope :
                    build.dependencies().byScope().entrySet()) {
                for (Dependency d : scope.getValue()) {
                    if (!coordinate.equals(d.module())) continue;
                    TomlPosition at =
                            document.inputPositionOf(List.of(scope.getKey().tomlSection(), d.library()));
                    if (at != null) {
                        return new SourceLocation(
                                manifest.toAbsolutePath().normalize().toString(), at.line(), at.column());
                    }
                }
            }
        } catch (UserConfigParseException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            return null;
        }
        return null;
    }
}
