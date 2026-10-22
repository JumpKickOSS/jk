// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    private @Nullable Path graalHome;

    /** Native-eligible module → GraalVM home, decided before the workspace run starts. */
    private Map<Path, Path> graalHomes = Map.of();

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
    public int before(BuildRun run) {
        var graal = NativePreflight.graal(System.getenv("GRAALVM_HOME"));
        if (graal instanceof NativePreflight.Graal.Fail fail) {
            run.fail(fail.message());
            return Exit.CONFIG;
        }
        this.graalHome = ((NativePreflight.Graal.Ok) graal).home();
        return -1;
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
        try {
            this.graalHomes = graalHomesForModules(candidates, graalHome, mainClass, ProjectInfos::orNull);
        } catch (AmbiguousMainException e) {
            run.fail(NativePreflight.MANY_MAINS);
            return Exit.CONFIG;
        }
        if (graalHomes.isEmpty()) {
            run.fail(NativePreflight.NO_MAIN);
            return Exit.CONFIG;
        }
        return -1;
    }

    @Override
    public WorkspaceResult workspace(BuildRun run, Path entryDir, List<String> modules, WorkspaceBuildListener listener)
            throws IOException {
        // The native targets are the engine selection: it expands their build prerequisites only,
        // never the siblings outside the native dependency cone.
        return EngineClient.buildWorkspace(
                EnginePaths.current(),
                run.request(entryDir, modules, WorkspaceSpec.nativeImage(Set.of(), graalHomes, mainClass, extraArgs)),
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
                                        graalHomes.size())
                                + " "
                                + BuildTails.elapsedSince(startNanos),
                r -> BuildTails.failureTail(WorkspaceRunView.failedSubject(r, "native"), startNanos));
    }

    /**
     * Map each native-eligible module dir to {@code graalHome}. Prefers modules that declare
     * {@code [native]} so workspace plugin harness mains do not each start a native-image run.
     * When none declare the table, every module with a unique main is eligible.
     */
    static Map<Path, Path> graalHomesForModules(
            List<Path> moduleDirs,
            @Nullable Path graalHome,
            @Nullable String mainOverride,
            Function<Path, @Nullable ProjectInfo> infos)
            throws AmbiguousMainException {
        Map<Path, Path> withTable = new HashMap<>();
        Map<Path, Path> withMain = new HashMap<>();
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
            withMain.put(moduleDir, graalHome);
            if (hasNativeTable) withTable.put(moduleDir, graalHome);
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
