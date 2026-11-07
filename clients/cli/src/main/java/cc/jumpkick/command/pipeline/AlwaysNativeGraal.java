// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.GraalResolver;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.config.WorkspaceModules;
import cc.jumpkick.host.WorkingDirectory;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * The GraalVM home each always-native module builds with, resolved on the client before the request
 * ships — the one owner for {@code jk build}, {@code jk run}, {@code jk install} and {@code jk native}. The engine is a daemon: a
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
        Path abs = WorkingDirectory.resolve(root);
        List<Path> dirs = WorkspaceModules.memberDirs(abs);
        if (dirs.isEmpty()) dirs = List.of(abs);
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
        for (String dir : selectedDirs) selected.add(WorkingDirectory.resolve(dir));
        List<Module> out = new ArrayList<>();
        for (Module m : modules) {
            if (selected.contains(WorkingDirectory.resolve(m.dir()))) out.add(m);
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
     * The summary is one engine round-trip for the whole workspace. Without it (no engine answer)
     * the manifest values stand.
     */
    static List<Module> withEffectiveReleases(List<Module> modules, @Nullable ProjectInfo rootInfo) {
        if (rootInfo == null || modules.isEmpty()) return modules;
        Map<Path, Integer> byDir = new LinkedHashMap<>();
        for (var listed : rootInfo.moduleToolchains().entrySet()) {
            byDir.put(
                    WorkingDirectory.resolve(listed.getKey()), listed.getValue().javaRelease());
        }
        if (!rootInfo.workspaceRootDir().isBlank()) {
            byDir.putIfAbsent(WorkingDirectory.resolve(rootInfo.workspaceRootDir()), rootInfo.javaRelease());
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
            int effective = byDir.getOrDefault(WorkingDirectory.resolve(m.dir()), 0);
            out.add(effective > 0 ? new Module(m.dir(), m.graalSpec(), effective) : m);
        }
        return List.copyOf(out);
    }

    /** {@code GraalResolver::resolve} — a module's directory, its spec, and the release it targets. */
    @FunctionalInterface
    interface Resolve {
        Optional<GraalResolver.Choice> apply(Path dir, @Nullable String spec, int javaRelease);
    }

    /**
     * Each module's GraalVM: the {@code homes} found on this machine and the specs the engine
     * {@code installs} as part of the run. Keyed by the module dir the engine plans.
     */
    record Graal(Map<Path, Path> homes, Map<Path, String> installs) {
        static final Graal NONE = new Graal(Map.of(), Map.of());

        /** {@code spec} with these GraalVM homes and installs. */
        WorkspaceSpec onto(WorkspaceSpec spec) {
            return spec.withGraalByDir(homes).withGraalInstalls(installs);
        }
    }

    /**
     * The GraalVM of every always-native module a build of {@code entryDir} reaches, decided before any
     * progress UI opens: the resolver may prompt or install, and the request carries the answer (the
     * engine is a daemon and must not pick one from the shell that started it). A member the selection
     * leaves out is not asked about. Empty when the resolver could not decide; it printed why.
     */
    static Optional<Graal> reached(BuildRun run, Path entryDir, BuildRun.Selection selection) {
        return reached(
                run.standalone(),
                entryDir,
                selection,
                () -> ProjectInfos.orNull(entryDir),
                new GraalResolver(run.flags().jdksDir(), run.global().yes, run.mode())::resolve);
    }

    /**
     * {@link #reached(BuildRun, Path, BuildRun.Selection)} over its inputs: {@code rootInfo} is the
     * engine's summary of {@code entryDir}, asked only when some module links a native image.
     */
    static Optional<Graal> reached(
            boolean standalone,
            Path entryDir,
            BuildRun.Selection selection,
            Supplier<@Nullable ProjectInfo> rootInfo,
            Resolve resolve) {
        List<Module> members;
        if (standalone) {
            Module self = fromManifest(entryDir);
            members = self == null ? List.of() : List.of(self);
        } else {
            members = fromManifests(entryDir);
            if (selection.confines()) members = within(members, selection.dirs());
        }
        // A member that inherits its `java` from the root declares none of its own; the summary has it.
        if (!members.isEmpty()) members = withEffectiveReleases(members, rootInfo.get());
        return homes(members, resolve);
    }

    /**
     * The GraalVM of each of {@code dirs}, modules already known to link a native image, gathered from
     * the engine's summary of each: {@code jk native}'s native-eligible modules and {@code jk install}'s
     * always-native ones. A module that pins no {@code graal} resolves with {@link #DEFAULT_SPEC}, and
     * one the engine could not summarize targets no release. Empty when the resolver could not
     * decide; it printed why.
     */
    static Optional<Graal> reached(
            Collection<Path> dirs, Function<Path, @Nullable ProjectInfo> summary, Resolve resolve) {
        List<Module> modules = new ArrayList<>();
        for (Path dir : dirs) modules.add(fromSummary(dir, summary.apply(dir)));
        return homes(modules, resolve);
    }

    /** Whether the engine's summary of a module says its build always links a native image. */
    static boolean alwaysNative(@Nullable ProjectInfo info) {
        return info != null && info.error() == null && "ALWAYS".equals(info.nativeMode());
    }

    /** {@code dir} as a native module, its spec and release read from the engine's summary {@code info}. */
    static Module fromSummary(Path dir, @Nullable ProjectInfo info) {
        if (info == null) return new Module(dir, DEFAULT_SPEC, 0);
        String spec = info.graal().isBlank() ? DEFAULT_SPEC : info.graal();
        return new Module(dir, spec, info.javaRelease());
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
     * Every module's GraalVM, found or to install, or empty when one could not be decided: {@code resolve} (the
     * {@code GraalResolver}) has already printed why, and a build that would fail at its native tail
     * minutes later stops here instead. Homes are keyed by the module dir the engine plans.
     */
    static Optional<Graal> homes(List<Module> modules, Resolve resolve) {
        Map<Path, Path> homes = new LinkedHashMap<>();
        Map<Path, String> installs = new LinkedHashMap<>();
        for (Module m : modules) {
            Optional<GraalResolver.Choice> choice = resolve.apply(m.dir(), m.graalSpec(), m.javaRelease());
            if (choice.isEmpty()) return Optional.empty();
            GraalResolver.Choice c = choice.get();
            if (c.home() != null) homes.put(m.dir(), c.home());
            else if (c.install() != null) installs.put(m.dir(), c.install());
        }
        return Optional.of(new Graal(Map.copyOf(homes), Map.copyOf(installs)));
    }
}
