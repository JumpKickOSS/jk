// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import cc.jumpkick.cli.api.BuildOptions;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.CliPaths;
import cc.jumpkick.cli.api.CommonOpts;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.engine.ProjectInfos;
import cc.jumpkick.cli.run.AggregateContext;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Coord;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.cli.tui.ModuleScopeHint;
import cc.jumpkick.command.CwdModuleScope;
import cc.jumpkick.command.ModuleSelectors;
import cc.jumpkick.command.VariantSelection;
import cc.jumpkick.config.EnvValues;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.TomlScan;
import cc.jumpkick.layout.NativePreflight;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Arity;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.model.command.Param;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.ProjectInfo;
import cc.jumpkick.wire.runtime.ModuleOutcome;
import cc.jumpkick.wire.runtime.ModulePlan;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk native} — GraalVM native-image for opted-in modules. Pre-fails when {@code
 * GRAALVM_HOME} is missing, {@code native-image} is not under it, or no eligible main is found.
 *
 * <p>Workspace eligibility: modules with {@code [native]} enabled ({@code true} or {@code
 * "always"}) and a unique main. When none declare {@code [native]}, fall back to unique-main
 * discovery. Cascade is the dependency closure of those targets only (prereqs package/test;
 * targets end at {@code native-image}) — siblings outside the cone are not built.
 */
public final class NativeCommand implements CliCommand {

    @Override
    public String name() {
        return "native";
    }

    @Override
    public String description() {
        return "Build a native binary with GraalVM";
    }

    @Override
    public List<Opt> options() {
        var opts = new ArrayList<Opt>();
        opts.add(Opt.value("<class>", "Main class (jk.toml image.main / main)", "--main"));
        opts.add(CommonOpts.cacheDirHidden());
        opts.add(CommonOpts.jdksDir());
        opts.add(CommonOpts.skipTests());
        opts.add(CommonOpts.guard());
        opts.addAll(CommonOpts.moduleSelection());
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Override
    public List<Param> parameters() {
        return List.of(Param.of(
                "native-image-args", Arity.ZERO_OR_MORE, "Extra arguments forwarded to\nnative-image (after --)."));
    }

    @Nullable
    String mainClass;

    @Nullable
    Path cacheDirOverride;

    @Nullable
    Path jdksDir;

    List<String> extra = new ArrayList<>();
    BuildOptions buildOpts;
    GlobalOptions global;

    @Nullable
    Path graalHome;
    /** Optional {@code -m}/{@code --affected}/{@code --affected-since} filter; null = whole workspace. */
    @Nullable
    String modulesSpec;

    @Nullable
    String affectedSince;

    boolean affectedWip;
    List<String> scopeHintNames = List.of();

    @Override
    public int run(Invocation in) throws Exception {
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride = in.value("cache-dir").map(CliPaths::abs).orElse(null);
        this.jdksDir = CommonOpts.jdksDirValue(in);
        this.extra = in.positionals();
        this.buildOpts = new BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);
        this.modulesSpec = CommonOpts.modulesSpec(in);
        this.affectedSince = in.value("affected-since").orElse(null);
        if (!TestCommand.installSelection(in, "Native")) return Exit.CONFIG;
        this.affectedWip = in.isSet("affected");
        if (ModuleSelectors.bothSelectors(affectedWip, affectedSince)) {
            CommandWedge.printFail("Native", ModuleSelectors.BOTH_MESSAGE);
            return Exit.CONFIG;
        }

        Path startDir = global.workingDir();
        VariantSelection.install(in, startDir);
        Path buildFile = ManifestPaths.manifestIn(startDir);
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();

        if (!Files.exists(buildFile)) {
            CommandWedge.printFail("Native", PathDisplay.styledRaw(buildFile) + " not found.");
            return Exit.NO_INPUT;
        }

        var graal = NativePreflight.graal(System.getenv("GRAALVM_HOME"));
        if (graal instanceof NativePreflight.Graal.Fail fail) {
            return failPreflight(fail.message());
        }
        this.graalHome = ((NativePreflight.Graal.Ok) graal).home();

        ProjectInfo peek = ProjectInfos.orNull(startDir);
        CwdModuleScope.Resolved cwdScope = CwdModuleScope.resolve(startDir, modulesSpec, peek);
        if (cwdScope.inferredFromCwd()) this.modulesSpec = cwdScope.modulesSpec();

        // Workspace root: cascade to all eligible modules.
        if (peek != null && peek.workspaceRoot()) {
            return runWorkspaceNative(startDir, cache);
        }

        // Workspace member: same as `jk native -m <this-module>` from the root.
        if (cwdScope.workspaceMember()) {
            return runWorkspaceNative(cwdScope.workspaceRoot(), cache);
        }

        // Single project: -m/--affected/--affected-since still validate.
        if (ModuleSelectors.anySelector(modulesSpec, affectedSince, affectedWip)) {
            var sel = ProjectInfos.orError(startDir, modulesSpec, affectedSince, affectedWip);
            if (sel.error() != null && !sel.error().isBlank()) {
                CommandWedge.printFail("Native", sel.error());
                return Exit.CONFIG;
            }
            if (sel.moduleDirs().isEmpty()) {
                CliOutput.out("(no modules matched selection)");
                return 0;
            }
        }
        return runSingleProject(startDir, buildFile, cache);
    }

    static int failPreflight(String message) {
        CommandWedge.printFail("Native", message);
        return Exit.CONFIG;
    }

    /** The engine request for {@code entryDir}, with the client-resolved GraalVM homes attached. */
    private EngineRequests.NativeRequest hostedRequest(
            Path entryDir, Path cache, Map<Path, Path> graalHomes, @Nullable List<Path> selectedModuleDirs) {
        var session = SessionContext.current();
        return new EngineRequests.NativeRequest(
                entryDir,
                cache,
                jdksDir,
                mainClass,
                buildOpts.skipTests,
                session.offline(),
                session.force(),
                global.verbose,
                extra,
                graalHomes,
                selectedModuleDirs);
    }

    // --- workspace cascade ---------------------------------------------------

    private int runWorkspaceNative(Path wsRoot, Path cache) throws Exception {
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);
        long buildStart = System.nanoTime();

        // Thin client: per-module native-mode + graal spec ride ProjectInfo summaries; the
        // engine owns ordering/scheduling. The GraalVM pre-resolve stays HERE — a prompt or
        // install owns this terminal and must never run inside the engine.
        var rootInfo = ProjectInfos.orNull(wsRoot);
        if (rootInfo == null) {
            CommandWedge.printFail("Native", "could not read the workspace summary at " + wsRoot);
            return Exit.CONFIG;
        }
        if (rootInfo.moduleDirs().isEmpty()) {
            CliOutput.out("(workspace declares no modules)");
            return 0;
        }

        // -m / --affected / --affected-since: engine ModuleSelection via projectInfo.
        List<Path> selectedDirs = null;
        if (ModuleSelectors.anySelector(modulesSpec, affectedSince, affectedWip)) {
            var sel = ProjectInfos.orError(wsRoot, modulesSpec, affectedSince, affectedWip);
            if (sel.error() != null && !sel.error().isBlank()) {
                CommandWedge.printFail("Native", sel.error());
                return Exit.CONFIG;
            }
            if (sel.moduleDirs().isEmpty()) {
                CliOutput.out("(no modules matched selection)");
                return 0;
            }
            selectedDirs = sel.moduleDirs().stream()
                    .map(d -> Path.of(d).toAbsolutePath().normalize())
                    .toList();
        }

        List<Path> candidates = new ArrayList<>();
        int considered = 0;
        for (String rel : rootInfo.moduleDirs()) {
            Path moduleDir = Path.of(rel).isAbsolute()
                    ? Path.of(rel).normalize()
                    : wsRoot.resolve(rel).toAbsolutePath().normalize();
            if (selectedDirs != null && !selectedDirs.contains(moduleDir)) continue;
            considered++;
            candidates.add(moduleDir);
        }
        if (selectedDirs != null && considered == 0) {
            CliOutput.out("(no modules matched selection)");
            return 0;
        }
        Map<Path, Path> graalHomes;
        try {
            graalHomes = graalHomesForModules(candidates, graalHome, mainClass);
        } catch (AmbiguousMainException e) {
            return failPreflight(NativePreflight.MANY_MAINS);
        }
        if (graalHomes.isEmpty()) return failPreflight(NativePreflight.NO_MAIN);
        // Always pass native targets as the engine selection: expands transitive build prereqs only.
        // Never cascade the whole workspace (sibling modules outside the native dependency cone).
        List<Path> cascadeRoots = List.copyOf(graalHomes.keySet());
        if (selectedDirs != null) {
            var sel = ProjectInfos.orError(wsRoot, modulesSpec, affectedSince, affectedWip);
            this.scopeHintNames = ModuleScopeHint.namesFrom(sel);
            ModuleScopeHint.print("building", scopeHintNames, mode == BuildPlanConsole.Mode.JSON);
        }
        return runWorkspaceHosted(
                wsRoot, cache, graalHomes, cascadeRoots, mode, buildStart, cascadeRoots.size(), graalHomes.size());
    }

    /**
     * Map each native-eligible module dir to {@code graalHome}. Prefers modules that declare
     * {@code [native]} so workspace plugin harness mains do not each start a native-image run.
     * When none declare the table, every module with a unique main is eligible.
     */
    static Map<Path, Path> graalHomesForModules(
            List<Path> moduleDirs, @Nullable Path graalHome, @Nullable String mainOverride)
            throws AmbiguousMainException {
        Map<Path, Path> withTable = new HashMap<>();
        Map<Path, Path> withMain = new HashMap<>();
        for (Path moduleDir : moduleDirs) {
            boolean hasNativeTable = false;
            boolean explicitlyDisabled = false;
            var info = ProjectInfos.orNull(moduleDir);
            if (info != null) {
                explicitlyDisabled = info.nativeExplicitlyDisabled();
                hasNativeTable = !"DISABLED".equals(info.nativeMode());
            } else {
                // Unit tests / engine-down: bootstrap [native] scan, not a plugin-schema parse.
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
            if (main instanceof NativePreflight.Main.Ambiguous) {
                throw new AmbiguousMainException();
            }
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

    /**
     * Engine-hosted workspace cascade: the engine assembles and runs each module's plan (the
     * {@code native-image} child forks engine-side) and streams the workspace event vocabulary
     * back; this method only renders. Exit codes arrive engine-computed.
     */
    private int runWorkspaceHosted(
            Path wsRoot,
            Path cache,
            Map<Path, Path> graalHomes,
            List<Path> selectedModuleDirs,
            BuildPlanConsole.Mode mode,
            long buildStart,
            int totalModules,
            long nativeCount) {
        var req = hostedRequest(wsRoot, cache, graalHomes, selectedModuleDirs);
        var paths = EnginePaths.current();

        // JSON / verbose: the shared append-only renderer, exactly as `jk build` drives it — the
        // `workspace-progress` vocabulary has one owner, WorkspaceRunView, and no copy here.
        if (mode != BuildPlanConsole.Mode.AUTO && mode != BuildPlanConsole.Mode.QUIET) {
            boolean json = mode == BuildPlanConsole.Mode.JSON;
            var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Build", false), wsRoot, null, json);
            // Client-side pre-count; the engine corrects it upward when -m pulls in prereqs.
            run.seedPlanned(totalModules);
            long headlessStart = System.nanoTime();
            WorkspaceResult result;
            try {
                result = EngineClient.runNative(paths, req, run.headless());
            } catch (IOException e) {
                run.finishEvent(false, BuildTails.elapsedMsSince(headlessStart));
                if (!json) CommandWedge.printFail("Native", e.getMessage());
                return Exit.SOFTWARE;
            }
            long headlessElapsed = BuildTails.elapsedMsSince(headlessStart);
            if (result.cancelled()) {
                run.finishEvent(false, headlessElapsed);
                if (!json) CommandWedge.printFail("Native", "Native job was cancelled");
                return 1;
            }
            run.finishEvent(result.success(), headlessElapsed);
            if (!json) {
                for (String err : result.errors()) CommandWedge.printFail("Native", err);
            }
            return result.exitCode();
        }

        // AUTO / QUIET: one shared aggregate view, calibrated to the whole cascade up front
        // (the plan burst carries every module plan's estimated weight).
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        JkManager view = JkManager.plan(CliOutput.stdout(), "Build", animate);
        view.setPlanCoord(BuildCommand.projectGaLabel(wsRoot));
        ModuleScopeHint.apply(view, "building", scopeHintNames);
        AggregateContext agg = new AggregateContext(view);
        int[] built = {0};
        // Not buffered: the live region owns every line, so nothing is written above it.
        var run = new WorkspaceRunView(new WorkspaceRunView.Chrome("Build", false), wsRoot, null, false);
        // Client-side pre-count; the engine corrects it upward when -m pulls in transitive prereqs.
        run.seedPlanned(totalModules);
        WorkspaceResult result;
        try {
            result = EngineClient.runNative(paths, req, run.live(view, agg, o -> {
                if (o.success()) built[0]++;
            }));
        } catch (IOException e) {
            run.finishEvent(false, BuildTails.elapsedMsSince(buildStart));
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (result.cancelled()) {
            run.finishEvent(false, BuildTails.elapsedMsSince(buildStart));
            view.finishBuildPlanCancelled(List.of());
            return 1;
        }
        if (!result.errors().isEmpty()) {
            run.finishEvent(false, BuildTails.elapsedMsSince(buildStart));
            view.finishBuildPlanFailure("dependency resolution failed");
            for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            return result.exitCode();
        }
        if (!result.success()) {
            String failedCoord = result.modules().stream()
                    .filter(m -> !m.success())
                    .map(ModuleOutcome::coord)
                    .findFirst()
                    .orElse("build");
            view.finishBuildPlanFailure(Coord.module(failedCoord) + " " + BuildTails.elapsedSince(buildStart));
            List<String> rendered = new ArrayList<>();
            ConsoleSpec.appendErrors(rendered, agg.unstreamedErrors());
            for (String line : rendered) CliOutput.err(line);
            return result.exitCode();
        }
        view.finishBuildPlanSuccess(
                Theme.colorize("Native build successful", Theme.active().success())
                        + ", "
                        + workspaceSummary(built[0], nativeCount)
                        + " "
                        + BuildTails.elapsedSince(buildStart));
        return 0;
    }

    /** Success-summary tail shared by the hosted and in-process workspace paths. */
    static String workspaceSummary(int built, long nativeCount) {
        return built
                + " module"
                + (built == 1 ? "" : "s")
                + " built"
                + (nativeCount > 0 ? ", " + nativeCount + " native artifact" + (nativeCount == 1 ? "" : "s") : "");
    }

    // --- single-project ------------------------------------------------------

    private int runSingleProject(Path projectDir, Path buildFile, Path cache) throws IOException, InterruptedException {
        var main = NativePreflight.resolveMain(projectDir, mainClass);
        if (!(main instanceof NativePreflight.Main.Unique)) {
            return failPreflight(NativePreflight.failMessage(main));
        }
        ProjectInfo build = ProjectInfos.orNull(projectDir);
        if (build == null) {
            CommandWedge.printFail("Native", "could not read the project.");
            return Exit.CONFIG;
        }

        String coord = ProjectInfos.buildTarget(buildFile, projectDir);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        // Engine-hosted (a cascade of one): the success tail names the built artifact from
        // the engine summary's candidate paths (thin client — no local layout derivation).
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> Theme.colorize("Native build successful", Theme.active().success())
                        + BuildTails.builtArtifact(projectDir, build),
                r -> Coord.module(coord).renderLine(),
                true);
        var listener = new WorkspaceBuildListener() {
            @Override
            public BuildPlanListener onModuleStart(ModulePlan m) {
                return BuildPlanConsole.chooseConsoleListener(m.plan().steps(), mode, spec, coord);
            }
        };
        WorkspaceResult result;
        try {
            result = EngineClient.runNative(
                    EnginePaths.current(),
                    hostedRequest(projectDir, cache, Map.of(projectDir, graalHome), null),
                    listener);
        } catch (IOException e) {
            CommandWedge.printFail("Native", e.getMessage());
            return Exit.SOFTWARE;
        }
        for (String err : result.errors()) CommandWedge.printFail("Native", err);
        return result.exitCode();
    }
}
