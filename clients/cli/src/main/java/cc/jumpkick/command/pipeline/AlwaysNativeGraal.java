// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceModules;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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

    /**
     * A module whose build links a native image, with its {@code [native] graal} spec and the
     * {@code java} release it targets.
     *
     * <p>The release is the floor the GraalVM has to clear. Without it the spec a module that
     * pins no {@code graal} resolves with is the bare flavour {@code "graalvm"}, which matches any
     * installed major — so a module targeting Java 25 was satisfied by an installed GraalVM 21,
     * and when nothing was installed the newest GraalVM was downloaded whatever the module asked
     * for. {@code 0} means the manifest declares none, and then nothing is constrained.
     */
    record Module(Path dir, String graalSpec, int javaRelease) {}

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

    /**
     * {@code modules} with each one's {@code java} release taken from the engine's summary of the
     * workspace root, where the root's inheritance has already been applied.
     *
     * <p>{@link #fromManifests} reads each member's own manifest, which is the bootstrap read and
     * knows nothing of a release the root declares for everybody. A member that inherits its
     * {@code java} would carry 0 from that read — no floor — and be served by any GraalVM at all.
     * The summary is one engine round-trip for the whole workspace, the same one
     * {@code JdkPreflight} makes for the JDK side. Without it (no engine answer) the manifest
     * values stand.
     */
    static List<Module> withEffectiveReleases(List<Module> modules, @Nullable ProjectInfo rootInfo) {
        if (rootInfo == null || modules.isEmpty()) return modules;
        Map<Path, Integer> byDir = new LinkedHashMap<>();
        for (var listed : rootInfo.moduleToolchains().entrySet()) {
            byDir.put(
                    Path.of(listed.getKey()).toAbsolutePath().normalize(),
                    listed.getValue().javaRelease());
        }
        if (!rootInfo.workspaceRootDir().isBlank()) {
            byDir.putIfAbsent(
                    Path.of(rootInfo.workspaceRootDir()).toAbsolutePath().normalize(), rootInfo.javaRelease());
        }
        return withReleases(modules, byDir);
    }

    /**
     * The rule {@link #withEffectiveReleases} applies, over a plain map: a release the summary
     * knows wins, and a module the summary says nothing about keeps what its manifest declared.
     */
    static List<Module> withReleases(List<Module> modules, Map<Path, Integer> byDir) {
        List<Module> out = new ArrayList<>();
        for (Module m : modules) {
            int effective = byDir.getOrDefault(m.dir().toAbsolutePath().normalize(), 0);
            out.add(effective > 0 ? new Module(m.dir(), m.graalSpec(), effective) : m);
        }
        return List.copyOf(out);
    }

    /** {@code GraalResolver::resolve} — a module's directory, its spec, and the release it targets. */
    @FunctionalInterface
    interface Resolve {
        Optional<Path> apply(Path dir, @Nullable String spec, int javaRelease);
    }

    /** {@code dir} as an always-native module, or null when its build links no native image. */
    static @Nullable Module fromManifest(Path dir) {
        TomlScan scan = TomlScan.scan(
                ManifestPaths.manifestIn(dir), "application.native", "native.enabled", "native.graal", "java");
        boolean always = EnvValues.parseBool(scan.get("application.native")).orElse(false)
                || "always".equalsIgnoreCase(scan.get("native.enabled"));
        if (!always) return null;
        String spec = scan.get("native.graal");
        // The module's own `java`, not the workspace root's inherited one: this is the bootstrap
        // read, and a member that declares none is left unconstrained rather than guessed at.
        return new Module(dir, spec == null || spec.isBlank() ? DEFAULT_SPEC : spec, scan.getInt("java", 0));
    }

    /**
     * Every module's home, or empty when one could not be resolved: {@code resolve} (the
     * {@code GraalResolver}) has already printed why, and a build that would fail at its native tail
     * minutes later stops here instead. Homes are keyed by the module dir the engine plans.
     */
    static Optional<Map<Path, Path>> homes(List<Module> modules, Resolve resolve) {
        Map<Path, Path> out = new LinkedHashMap<>();
        for (Module m : modules) {
            Optional<Path> home = resolve.apply(m.dir(), m.graalSpec(), m.javaRelease());
            if (home.isEmpty()) return Optional.empty();
            out.put(m.dir(), home.get());
        }
        return Optional.of(Map.copyOf(out));
    }
}
