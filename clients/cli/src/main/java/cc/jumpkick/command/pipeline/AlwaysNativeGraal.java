// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceModules;
import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * The GraalVM home each always-native module builds with, resolved on the client before the request
 * ships — the one owner for {@code jk build} and {@code jk install}. The engine is a daemon: a
 * GraalVM it found for itself would answer from the shell that started it, and a prompt or an
 * install belongs to the terminal the user is looking at. So the client's resolver decides (pin,
 * lock, inventory, then prompt or install) and the request carries the answer per module.
 */
final class AlwaysNativeGraal {

    private AlwaysNativeGraal() {}

    /** A module whose build links a native image, with its {@code [native] graal} spec. */
    record Module(Path dir, String graalSpec) {}

    /** The spec {@code JkBuild.graal()} answers when a native module pins none. */
    static final String DEFAULT_SPEC = "graalvm";

    /**
     * The always-native members of the workspace at {@code root} — the root itself when it declares
     * no members — read from the bootstrap TOML: {@code [application] native = true} or
     * {@code [native] enabled = "always"}, the two spellings {@code JkBuild.nativeMode()} maps to
     * ALWAYS.
     */
    static List<Module> fromManifests(Path root) {
        Path abs = root.toAbsolutePath().normalize();
        List<String> rels = TomlScan.scan(ManifestPaths.manifestIn(abs), "workspace.modules")
                .stringArray("workspace.modules");
        List<Path> dirs = new ArrayList<>();
        if (rels.isEmpty()) {
            dirs.add(abs);
        } else {
            for (String rel : WorkspaceModules.expand(abs, rels))
                dirs.add(abs.resolve(rel).normalize());
        }
        List<Module> out = new ArrayList<>();
        for (Path dir : dirs) {
            Module m = fromManifest(dir);
            if (m != null) out.add(m);
        }
        return List.copyOf(out);
    }

    /**
     * The members of {@code modules} whose dir is one of {@code selectedDirs} — the modules a
     * selector confined the build to. Dirs are compared absolute and normalized, the way {@link
     * #fromManifests} keys them, so a relative selector dir and an absolute one agree.
     */
    static List<Module> within(List<Module> modules, List<String> selectedDirs) {
        Set<Path> selected = new HashSet<>();
        for (String dir : selectedDirs)
            selected.add(Path.of(dir).toAbsolutePath().normalize());
        List<Module> out = new ArrayList<>();
        for (Module m : modules) {
            if (selected.contains(m.dir().toAbsolutePath().normalize())) out.add(m);
        }
        return List.copyOf(out);
    }

    /** {@code dir} as an always-native module, or null when its build links no native image. */
    static @Nullable Module fromManifest(Path dir) {
        TomlScan scan = TomlScan.scan(
                ManifestPaths.manifestIn(dir), "application.native", "native.enabled", "native.graal");
        boolean always = EnvValues.parseBool(scan.get("application.native")).orElse(false)
                || "always".equalsIgnoreCase(scan.get("native.enabled"));
        if (!always) return null;
        String spec = scan.get("native.graal");
        return new Module(dir, spec == null || spec.isBlank() ? DEFAULT_SPEC : spec);
    }

    /**
     * Every module's home, or empty when one could not be resolved: {@code resolve} (the
     * {@code GraalResolver}) has already printed why, and a build that would fail at its native tail
     * minutes later stops here instead. Homes are keyed by the module dir the engine plans.
     */
    static Optional<Map<Path, Path>> homes(List<Module> modules, BiFunction<Path, String, Optional<Path>> resolve) {
        Map<Path, Path> out = new LinkedHashMap<>();
        for (Module m : modules) {
            Optional<Path> home = resolve.apply(m.dir(), m.graalSpec());
            if (home.isEmpty()) return Optional.empty();
            out.put(m.dir(), home.get());
        }
        return Optional.of(Map.copyOf(out));
    }
}
