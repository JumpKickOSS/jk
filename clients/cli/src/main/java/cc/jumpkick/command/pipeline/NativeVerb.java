// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.GraalResolver;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.layout.NativePreflight;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import cc.jumpkick.wire.runtime.WorkspaceSpec;
import cc.jumpkick.wire.runtime.WorkspaceTarget;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk native}: the build, then {@code native-image} for every native-eligible module the
 * selection reaches. A standalone project is a cascade of one.
 */
final class NativeVerb implements BuildVerb {

    private static final Words WORDS = new Words("Native", "building", "build", "Building");

    private @Nullable String mainClass;
    private List<String> extraArgs = List.of();
    /**
     * Each native-eligible module's GraalVM, decided before the workspace run starts the way every
     * native build decides it ({@link GraalResolver}: the module's pin, the lock, what is installed,
     * $GRAALVM_HOME), found on this machine or for the engine to install as part of the run.
     */
    private AlwaysNativeGraal.Graal graal = AlwaysNativeGraal.Graal.NONE;

    /** How many modules link a native image this run. */
    private int nativeModules;

    @Override
    public String name() {
        return "native";
    }

    @Override
    public String description() {
        return "Build a native binary with GraalVM";
    }

    @Override
    public Words words() {
        return WORDS;
    }

    @Override
    public WorkspaceTarget target() {
        return WorkspaceTarget.NATIVE;
    }

    @Override
    public List<Opt> ownOptions() {
        return List.of(Opt.value("<class>", "Main class (jk.toml image.main / main)", "-T", "--main"));
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "native-image-args", Arity.ZERO_OR_MORE, "Extra arguments forwarded to\nnative-image (after --)."));
    }

    @Override
    public void parse(Invocation in, BuildRun run) {
        this.mainClass = in.value("main").orElse(null);
        this.extraArgs = in.positionals();
    }

    @Override
    public int prepareWorkspace(BuildRun run, Path entryDir, BuildRun.Selection selection) {
        ProjectInfo rootInfo = ProjectInfos.orNull(entryDir);
        if (rootInfo == null) {
            run.fail("could not read the workspace summary at " + entryDir);
            return Exit.CONFIG;
        }
        List<Path> selected = selection.confines()
                ? selection.dirs().stream()
                        .map(d -> Path.of(d).toAbsolutePath().normalize())
                        .toList()
                : null;
        List<Path> candidates = new ArrayList<>();
        if (run.standalone()) candidates.add(entryDir.toAbsolutePath().normalize());
        for (String rel : rootInfo.moduleDirs()) {
            Path moduleDir = Path.of(rel).isAbsolute()
                    ? Path.of(rel).normalize()
                    : entryDir.resolve(rel).toAbsolutePath().normalize();
            if (selected == null || selected.contains(moduleDir)) candidates.add(moduleDir);
        }
        Set<Path> eligible;
        try {
            eligible = nativeEligible(candidates, mainClass, ProjectInfos::orNull);
        } catch (AmbiguousMainException e) {
            run.fail(NativePreflight.MANY_MAINS);
            return Exit.CONFIG;
        }
        if (eligible.isEmpty()) {
            run.fail(NativePreflight.NO_MAIN);
            return Exit.CONFIG;
        }
        List<AlwaysNativeGraal.Module> modules = new ArrayList<>();
        for (Path dir : eligible) {
            ProjectInfo info = ProjectInfos.orNull(dir);
            String spec = info == null || info.graal() == null || info.graal().isBlank()
                    ? AlwaysNativeGraal.DEFAULT_SPEC
                    : info.graal();
            modules.add(new AlwaysNativeGraal.Module(dir, spec, info == null ? 0 : info.javaRelease()));
        }
        Optional<AlwaysNativeGraal.Graal> resolved = AlwaysNativeGraal.homes(
                modules, new GraalResolver(run.flags().jdksDir(), run.global().yes, run.mode())::resolve);
        if (resolved.isEmpty()) return Exit.FAILURE; // the resolver printed why
        this.graal = resolved.get();
        this.nativeModules = eligible.size();
        return -1;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        // The native targets are the engine selection: it expands their build prerequisites only,
        // never the siblings outside the native dependency cone.
        return EngineClient.buildWorkspace(
                EnginePaths.current(),
                run.request(
                        entryDir,
                        modules,
                        graal.onto(WorkspaceSpec.nativeImage(Set.of(), Map.of(), mainClass, extraArgs))),
                listener);
    }

    @Override
    public WorkspaceRunView.Tails tails(BuildRun run, WorkspaceRunView view, long startNanos) {
        return new WorkspaceRunView.Tails(
                (r, planned) ->
                        Theme.colorize("Native build successful", Theme.active().success())
                                + ", "
                                + workspaceSummary(
                                        (int) r.modules().stream()
                                                .filter(ModuleOutcome::success)
                                                .count(),
                                        nativeModules)
                                + " "
                                + BuildTails.elapsedSince(startNanos),
                r -> BuildTails.failureTail(WorkspaceRunView.failedSubject(r, "native"), startNanos));
    }

    /**
     * The native-eligible module dirs. Prefers modules that declare {@code [native]} so workspace
     * plugin harness mains do not each start a native-image run. When none declare the table, every
     * module with a unique main is eligible.
     */
    static Set<Path> nativeEligible(
            List<Path> moduleDirs, @Nullable String mainOverride, Function<Path, @Nullable ProjectInfo> infos)
            throws AmbiguousMainException {
        Set<Path> withTable = new LinkedHashSet<>();
        Set<Path> withMain = new LinkedHashSet<>();
        for (Path moduleDir : moduleDirs) {
            boolean hasNativeTable;
            boolean explicitlyDisabled;
            var info = infos.apply(moduleDir);
            if (info != null) {
                explicitlyDisabled = info.nativeExplicitlyDisabled();
                hasNativeTable = !"DISABLED".equals(info.nativeMode());
            } else {
                // No engine answer: bootstrap [native] scan, not a plugin-schema parse.
                var scan = TomlScan.scan(ManifestPaths.manifestIn(moduleDir), "native.enabled");
                explicitlyDisabled = scan.hasSection("native")
                        && EnvValues.parseBool(scan.get("native.enabled"))
                                .filter(on -> !on)
                                .isPresent();
                hasNativeTable = scan.hasSection("native") && !explicitlyDisabled;
            }
            // enabled = false keeps the table but opts the module out of native builds — it must
            // not re-enter through the unique-main fallback.
            if (explicitlyDisabled) continue;
            var main = NativePreflight.resolveMain(moduleDir, mainOverride);
            if (main instanceof NativePreflight.Main.None) continue;
            if (main instanceof NativePreflight.Main.Ambiguous) throw new AmbiguousMainException();
            withMain.add(moduleDir);
            if (hasNativeTable) withTable.add(moduleDir);
        }
        return withTable.isEmpty() ? withMain : withTable;
    }

    /** Checked-style signal for multiple discovered mains in one module. */
    static final class AmbiguousMainException extends Exception {
        AmbiguousMainException() {
            super(NativePreflight.MANY_MAINS);
        }
    }

    /** The success summary's counts: modules built and native artifacts. */
    static String workspaceSummary(int built, long nativeCount) {
        return built
                + " module"
                + (built == 1 ? "" : "s")
                + " built"
                + (nativeCount > 0 ? ", " + nativeCount + " native artifact" + (nativeCount == 1 ? "" : "s") : "");
    }
}
