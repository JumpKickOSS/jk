// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Scope;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Cross-package feature selection (ticket-1006, design C + path): when a consumer selects
 * {@code features} / {@code default-features} on a path dependency whose target has {@code
 * jk.toml}, activate that library's optional deps into the consumer's resolve graph.
 */
final class CrossPackageFeatures {

    private CrossPackageFeatures() {}

    /**
     * @param projectDir directory of the consuming {@code jk.toml} (resolves relative path=)
     * @return extra non-optional roots to merge into the main graph, keyed by module
     */
    static Result expand(Path projectDir, Collection<Dependency> declared) {
        Map<String, Dependency> extras = new LinkedHashMap<>();
        Map<String, List<String>> activatedByLibraryModule = new LinkedHashMap<>();
        if (projectDir == null) {
            for (Dependency d : declared) {
                if (d.hasFeatureSelection()) {
                    throw new IllegalArgumentException("dependency `"
                            + d.library()
                            + "` selects features, but the project directory is unknown — cannot load the library"
                            + " jk.toml");
                }
            }
            return new Result(extras, activatedByLibraryModule);
        }
        for (Dependency d : declared) {
            if (!d.hasFeatureSelection()) continue;
            if (!d.isPath()) {
                throw new IllegalArgumentException("dependency `"
                        + d.library()
                        + "` selects features, but only path= dependencies support cross-package features yet"
                        + " (workspace/git/Maven sidecar come later)");
            }
            Path libToml = projectDir.resolve(d.pathSource().rawPath()).normalize().resolve("jk.toml");
            if (!Files.isRegularFile(libToml)) {
                throw new IllegalArgumentException("dependency `"
                        + d.library()
                        + "` selects features, but "
                        + libToml
                        + " is missing — path target must be a jk project");
            }
            JkBuild lib;
            try {
                lib = JkBuildParser.parse(libToml);
            } catch (IOException e) {
                throw new IllegalArgumentException(
                        "dependency `" + d.library() + "`: cannot read " + libToml + ": " + e.getMessage(), e);
            }
            Set<String> activated;
            try {
                activated = lib.features()
                        .activate(new LinkedHashSet<>(d.requestedFeatures()), d.defaultFeatures());
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "dependency `" + d.library() + "` (" + PackageLabel.of(d) + "): " + e.getMessage(), e);
            }
            activatedByLibraryModule.put(d.module(), List.copyOf(activated));
            for (String depName : lib.features().requestedDepNames(activated)) {
                Dependency opt = findOptional(lib, depName);
                if (opt == null) {
                    throw new IllegalArgumentException("feature dependency '"
                            + depName
                            + "' on library `"
                            + d.library()
                            + "` is not a declared optional dependency of that library");
                }
                // Pull into the consumer as a normal root (not optional).
                extras.putIfAbsent(opt.module(), opt.withOptional(false));
            }
        }
        return new Result(extras, activatedByLibraryModule);
    }

    private static Dependency findOptional(JkBuild lib, String libraryHandle) {
        for (Scope scope : Scope.values()) {
            if (scope == Scope.PLATFORM) continue;
            for (Dependency dep : lib.dependencies().of(scope)) {
                if (dep.optional() && dep.library().equals(libraryHandle)) return dep;
            }
        }
        return null;
    }

    record Result(Map<String, Dependency> extraRoots, Map<String, List<String>> activatedFeaturesByModule) {
        Result {
            extraRoots = Map.copyOf(extraRoots);
            activatedFeaturesByModule = Map.copyOf(activatedFeaturesByModule);
        }

        List<Dependency> extrasList() {
            return new ArrayList<>(extraRoots.values());
        }
    }

    /** Small helper so error messages stay readable without depending on PackageId for path: names. */
    private static final class PackageLabel {
        static String of(Dependency d) {
            return d.module();
        }
    }
}
