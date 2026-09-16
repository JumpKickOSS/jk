// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.guard.eval;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Where a module's build output lives, for the output lane: the main jar, its sidecar POM, the
 * native binary and the coverage report the {@code coverage.*} measures read. Derived from the
 * manifest through {@link BuildLayout}, never guessed from a directory listing; a path that does not
 * exist is reported as such and never read.
 */
public final class OutputArtifacts {

    /** The coverage XML a module's test run leaves when nothing names another: under the module's reports. */
    public static final String DEFAULT_COVERAGE = "jacoco.xml";

    private OutputArtifacts() {}

    /**
     * One module's artefact paths; every path is where the artefact would be, whether or not it exists.
     *
     * @param module the workspace-relative module path, {@code ""} at the root
     */
    public record Module(String module, Path dir, Path jar, Path pom, Path nativeBinary, Path coverage) {
        public @Nullable Path existingJar() {
            return Files.isRegularFile(jar) ? jar : null;
        }

        public @Nullable Path existingPom() {
            return Files.isRegularFile(pom) ? pom : null;
        }

        public @Nullable Path existingNative() {
            return Files.isRegularFile(nativeBinary) ? nativeBinary : null;
        }

        public @Nullable Path existingCoverage() {
            return Files.isRegularFile(coverage) ? coverage : null;
        }

        public boolean any() {
            return existingJar() != null || existingPom() != null || existingNative() != null;
        }

        public String label() {
            return module.isEmpty() ? "root" : module;
        }
    }

    /**
     * The artefacts of every module the context covers (the root alone when it lists none). A module
     * whose manifest does not parse contributes nothing: the build has already said so.
     *
     * @param coverageReport {@code [guards] coverage-report}, workspace-relative, or {@code null}
     */
    public static List<Module> of(Path root, List<Path> modules, @Nullable String coverageReport) throws IOException {
        List<Path> dirs = new ArrayList<>();
        if (modules.isEmpty()) dirs.add(root);
        else dirs.addAll(modules);
        List<Module> out = new ArrayList<>();
        for (Path dir : dirs) {
            Path manifest = ManifestPaths.manifestIn(dir);
            if (!Files.isRegularFile(manifest)) continue;
            String rel = WorkspaceModel.rel(root, dir);
            JkBuild build;
            try {
                // the full parse, as the engine's own layout does: a member inherits its version from
                // the root, and the jar is named after the inherited one
                build = JkBuildParser.parse(manifest);
            } catch (RuntimeException unparseable) {
                continue;
            }
            BuildLayout layout = BuildLayout.of(dir, build);
            Path jar = layout.mainJar();
            String name = jar.getFileName().toString();
            Path pom = jar.resolveSibling(
                    name.endsWith(".jar") ? name.substring(0, name.length() - 4) + ".pom" : name + ".pom");
            Path coverage = coverageReport != null
                    ? root.resolve(coverageReport)
                    : layout.reportsDir().resolve(DEFAULT_COVERAGE);
            out.add(new Module(rel, dir, jar, pom, layout.nativeBinary(), coverage));
        }
        return out;
    }

    /** {@code module/}-prefixed workspace-relative spelling of an artefact, for a site's file. */
    public static String rel(Path root, Path file) {
        return WorkspaceModel.rel(root, file);
    }
}
