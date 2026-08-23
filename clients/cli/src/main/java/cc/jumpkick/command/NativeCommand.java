// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Coord;
import cc.jumpkick.cli.tui.JkManager;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        opts.add(cc.jumpkick.cli.CommonOpts.cacheDirHidden());
        opts.add(Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                .hide());
        opts.add(cc.jumpkick.cli.CommonOpts.skipTests());
        opts.addAll(cc.jumpkick.cli.CommonOpts.moduleSelection());
        opts.addAll(VariantSelection.options());
        return opts;
    }

    @Override
    public List<cc.jumpkick.model.command.Param> parameters() {
        return List.of(cc.jumpkick.model.command.Param.of(
                "native-image-args",
                cc.jumpkick.model.command.Arity.ZERO_OR_MORE,
                "Extra arguments forwarded to\nnative-image (after --)."));
    }

    String mainClass;
    Path cacheDirOverride;
    Path jdksDir;
    List<String> extra = new ArrayList<>();
    cc.jumpkick.cli.BuildOptions buildOpts;
    GlobalOptions global;
    Path graalHome;
    /** Optional {@code -m}/{@code --affected-since} filter; null = whole workspace. */
    String modulesSpec;

    String affectedSince;
    List<String> scopeHintNames = List.of();

    @Override
    public int run(Invocation in) throws Exception {
        this.mainClass = in.value("main").orElse(null);
        this.cacheDirOverride =
                in.value("cache-dir").map(cc.jumpkick.cli.CliPaths::abs).orElse(null);
        this.jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        this.extra = in.positionals();
        this.buildOpts = new cc.jumpkick.cli.BuildOptions();
        this.buildOpts.skipTests = in.isSet("skip-tests");
        this.global = GlobalOptions.from(in);
        this.modulesSpec = in.value("modules").orElse(null);
        this.affectedSince = in.value("affected-since").orElse(null);

        Path startDir = global.workingDir();
        VariantSelection.install(in, startDir);
        Path buildFile = startDir.resolve("jk.toml");
        Path cache = cacheDirOverride != null ? cacheDirOverride : JkDirs.cache();

        if (!Files.exists(buildFile)) {
            cc.jumpkick.cli.tui.CommandWedge.printFail(
                    "Native", cc.jumpkick.cli.PathDisplay.styledRaw(buildFile) + " not found.");
            return Exit.NO_INPUT;
        }

        var graal = cc.jumpkick.layout.NativePreflight.graal(System.getenv("GRAALVM_HOME"));
        if (graal instanceof cc.jumpkick.layout.NativePreflight.Graal.Fail fail) {
            return failPreflight(fail.message());
        }
        this.graalHome = ((cc.jumpkick.layout.NativePreflight.Graal.Ok) graal).home();

        cc.jumpkick.engine.protocol.ProjectInfo peek = BuildCommand.projectInfoOrNull(startDir);
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

        // Single project: -m/--affected-since still validate.
        if ((modulesSpec != null && !modulesSpec.isBlank()) || (affectedSince != null && !affectedSince.isBlank())) {
            var sel = BuildCommand.projectInfoOrError(startDir, modulesSpec, affectedSince);
            if (sel.error() != null && !sel.error().isBlank()) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Native", sel.error());
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
        cc.jumpkick.cli.tui.CommandWedge.printFail("Native", message);
        return Exit.CONFIG;
    }

    /** The engine request for {@code entryDir}, with the client-resolved GraalVM homes attached. */
    private EngineRequests.NativeRequest hostedRequest(
            Path entryDir, Path cache, Map<Path, Path> graalHomes, List<Path> selectedModuleDirs) {
        var session = cc.jumpkick.config.SessionContext.current();
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
        var rootInfo = BuildCommand.projectInfoOrNull(wsRoot);
        if (rootInfo == null) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Native", "could not read the workspace summary at " + wsRoot);
            return Exit.CONFIG;
        }
        if (rootInfo.moduleDirs().isEmpty()) {
            CliOutput.out("(workspace declares no modules)");
            return 0;
        }

        // -m / --affected-since: engine ModuleSelection via projectInfo.
        List<Path> selectedDirs = null;
        if ((modulesSpec != null && !modulesSpec.isBlank()) || (affectedSince != null && !affectedSince.isBlank())) {
            var sel = BuildCommand.projectInfoOrError(wsRoot, modulesSpec, affectedSince);
            if (sel.error() != null && !sel.error().isBlank()) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Native", sel.error());
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
            return failPreflight(cc.jumpkick.layout.NativePreflight.MANY_MAINS);
        }
        if (graalHomes.isEmpty()) return failPreflight(cc.jumpkick.layout.NativePreflight.NO_MAIN);
        // Always pass native targets as the engine selection: expands transitive build prereqs only.
        // Never cascade the whole workspace (sibling modules outside the native dependency cone).
        List<Path> cascadeRoots = List.copyOf(graalHomes.keySet());
        if (selectedDirs != null) {
            var sel = BuildCommand.projectInfoOrError(wsRoot, modulesSpec, affectedSince);
            this.scopeHintNames = cc.jumpkick.cli.tui.ModuleScopeHint.namesFrom(sel);
            cc.jumpkick.cli.tui.ModuleScopeHint.print("building", scopeHintNames, mode == BuildPlanConsole.Mode.JSON);
        }
        return runWorkspaceHosted(
                wsRoot, cache, graalHomes, cascadeRoots, mode, buildStart, cascadeRoots.size(), graalHomes.size());
    }

    /**
     * Map each native-eligible module dir to {@code graalHome}. Prefers modules that declare
     * {@code [native]} so workspace plugin harness mains do not each start a native-image run.
     * When none declare the table, every module with a unique main is eligible.
     */
    static Map<Path, Path> graalHomesForModules(List<Path> moduleDirs, Path graalHome, String mainOverride)
            throws AmbiguousMainException {
        Map<Path, Path> withTable = new HashMap<>();
        Map<Path, Path> withMain = new HashMap<>();
        for (Path moduleDir : moduleDirs) {
            boolean hasNativeTable = false;
            boolean explicitlyDisabled = false;
            var info = BuildCommand.projectInfoOrNull(moduleDir);
            if (info != null) {
                explicitlyDisabled = info.nativeExplicitlyDisabled();
                hasNativeTable = !"DISABLED".equals(info.nativeMode());
            } else {
                // Unit tests / engine-down: bootstrap [native] scan, not a plugin-schema parse.
                var scan = cc.jumpkick.config.TomlScan.scan(moduleDir.resolve("jk.toml"), "native.enabled");
                explicitlyDisabled = scan.hasSection("native") && "false".equalsIgnoreCase(scan.get("native.enabled"));
                hasNativeTable = scan.hasSection("native") && !explicitlyDisabled;
            }
            // enabled = false keeps the table but opts the module out of native builds — it must
            // not re-enter through the unique-main fallback (JK-2089).
            if (explicitlyDisabled) continue;
            var main = cc.jumpkick.layout.NativePreflight.resolveMain(moduleDir, mainOverride);
            if (main instanceof cc.jumpkick.layout.NativePreflight.Main.None) continue;
            if (main instanceof cc.jumpkick.layout.NativePreflight.Main.Ambiguous) {
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
            super(cc.jumpkick.layout.NativePreflight.MANY_MAINS);
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

        // JSON / verbose: append-only per-module listeners. JSON must not print human banners and
        // must not let module-local num/den clobber the engine aggregate rider.
        if (mode != BuildPlanConsole.Mode.AUTO && mode != BuildPlanConsole.Mode.QUIET) {
            int[] idx = {0};
            // Engine-corrected denominator: with -m the engine adds transitive prereqs the client
            // never counted, so the plan's modulesTotal wins over the client-side guess.
            int[] total = {totalModules};
            boolean json = mode == BuildPlanConsole.Mode.JSON;
            var listener = new cc.jumpkick.runtime.WorkspaceBuildListener() {
                @Override
                public void onWorkspaceProgress(cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot snap) {
                    if (snap.modulesTotal() > 0) total[0] = snap.modulesTotal();
                    if (!json) return;
                    cc.jumpkick.cli.run.LiveProgress.get().apply(snap);
                    cc.jumpkick.cli.run.JsonlShape.emitJsonl(
                            cc.jumpkick.cli.run.JsonlShape.workspaceProgress(
                                    wsRoot.toString(),
                                    snap.numerator(),
                                    snap.denominator(),
                                    snap.phase(),
                                    snap.modulesComplete(),
                                    snap.modulesTotal()),
                            true);
                }

                @Override
                public cc.jumpkick.run.BuildPlanListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                    if (!json) {
                        CliOutput.out();
                        CliOutput.out("══ " + wsRoot.relativize(m.dir()) + " (" + (++idx[0]) + "/"
                                + Math.max(total[0], idx[0]) + ") ══");
                    }
                    // JSON: workspace member listener (no aggregate-rider writes). Verbose: full console.
                    var console = json
                            ? new cc.jumpkick.cli.run.JsonlListener(System.out, false)
                            : BuildPlanConsole.chooseConsoleListener(
                                    m.plan().name(), m.plan().steps(), mode);
                    return console;
                }

                @Override
                public void onModuleFinish(cc.jumpkick.runtime.ModuleOutcome o) {
                    if (!o.success() && !json) {
                        cc.jumpkick.cli.tui.CommandWedge.printFail(
                                "Native", wsRoot.relativize(o.dir()) + " failed (exit " + o.exitCode() + ")");
                    }
                }
            };
            cc.jumpkick.runtime.WorkspaceResult result;
            try {
                result = EngineClient.runNative(paths, req, listener);
            } catch (IOException e) {
                cc.jumpkick.cli.tui.CommandWedge.printFail("Native", e.getMessage());
                return Exit.SOFTWARE;
            }
            for (String err : result.errors()) cc.jumpkick.cli.tui.CommandWedge.printFail("Native", err);
            return result.exitCode();
        }

        // AUTO / QUIET: one shared aggregate view, calibrated to the whole cascade up front
        // (the plan burst carries every module plan's estimated weight).
        boolean animate = mode == BuildPlanConsole.Mode.AUTO && BuildPlanConsole.isInteractiveTerminal();
        JkManager view = JkManager.plan(CliOutput.stdout(), "Build", animate);
        view.setPlanCoord(BuildCommand.projectGaLabel(wsRoot));
        cc.jumpkick.cli.tui.ModuleScopeHint.apply(view, "building", scopeHintNames);
        cc.jumpkick.cli.run.AggregateContext agg = new cc.jumpkick.cli.run.AggregateContext(view);
        int[] built = {0};
        int[] finished = {0};
        var listener = new cc.jumpkick.runtime.WorkspaceBuildListener() {
            @Override
            public void onWorkspaceProgress(cc.jumpkick.runtime.WorkspaceProgressTracker.Snapshot snap) {
                agg.applySnapshot(snap);
            }

            @Override
            public cc.jumpkick.run.BuildPlanListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                return new cc.jumpkick.cli.run.AggregateModuleListener(
                        agg, m.coord(), m.plan().steps(), m.weight());
            }

            @Override
            public void onModuleFinish(cc.jumpkick.runtime.ModuleOutcome o) {
                if (o.success()) built[0]++;
                int n = ++finished[0];
                String completion =
                        BuildCommand.completionLine(o.success(), n, Math.max(totalModules, n), o.coord(), o.millis());
                if (view.animating()) {
                    view.addCompletion(completion);
                }
            }
        };
        cc.jumpkick.runtime.WorkspaceResult result;
        try {
            result = EngineClient.runNative(paths, req, listener);
        } catch (IOException e) {
            view.finishBuildPlanFailure(String.valueOf(e.getMessage()));
            return Exit.SOFTWARE;
        }
        if (!result.errors().isEmpty()) {
            view.finishBuildPlanFailure("dependency resolution failed");
            for (String err : result.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            return result.exitCode();
        }
        if (!result.success()) {
            String failedCoord = result.modules().stream()
                    .filter(m -> !m.success())
                    .map(cc.jumpkick.runtime.ModuleOutcome::coord)
                    .findFirst()
                    .orElse("build");
            view.finishBuildPlanFailure(Coord.module(failedCoord) + " " + BuildCommand.elapsedSince(buildStart));
            List<String> rendered = new ArrayList<>();
            ConsoleSpec.appendErrors(rendered, agg.lastErrors());
            for (String line : rendered) CliOutput.err(line);
            return result.exitCode();
        }
        view.finishBuildPlanSuccess(
                Theme.colorize("Native build successful", Theme.active().success())
                        + ", "
                        + workspaceSummary(built[0], nativeCount)
                        + " "
                        + BuildCommand.elapsedSince(buildStart));
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
        var main = cc.jumpkick.layout.NativePreflight.resolveMain(projectDir, mainClass);
        if (!(main instanceof cc.jumpkick.layout.NativePreflight.Main.Unique)) {
            return failPreflight(cc.jumpkick.layout.NativePreflight.failMessage(main));
        }
        cc.jumpkick.engine.protocol.ProjectInfo build = BuildCommand.projectInfoOrNull(projectDir);
        if (build == null) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Native", "could not read the project.");
            return Exit.CONFIG;
        }

        String coord = BuildCommand.buildTarget(buildFile, projectDir);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        // Engine-hosted (a cascade of one): the success tail names the built artifact from
        // the engine summary's candidate paths (thin client — no local layout derivation).
        ConsoleSpec spec = new ConsoleSpec(
                "Build",
                r -> Theme.colorize("Native build successful", Theme.active().success())
                        + BuildCommand.builtArtifact(projectDir, build),
                r -> Coord.module(coord).renderLine(),
                true);
        var listener = new cc.jumpkick.runtime.WorkspaceBuildListener() {
            @Override
            public cc.jumpkick.run.BuildPlanListener onModuleStart(cc.jumpkick.runtime.ModulePlan m) {
                return BuildPlanConsole.chooseConsoleListener(m.plan().steps(), mode, spec, coord);
            }
        };
        cc.jumpkick.runtime.WorkspaceResult result;
        try {
            result = EngineClient.runNative(
                    EnginePaths.current(),
                    hostedRequest(projectDir, cache, Map.of(projectDir, graalHome), null),
                    listener);
        } catch (IOException e) {
            cc.jumpkick.cli.tui.CommandWedge.printFail("Native", e.getMessage());
            return Exit.SOFTWARE;
        }
        for (String err : result.errors()) cc.jumpkick.cli.tui.CommandWedge.printFail("Native", err);
        return result.exitCode();
    }
}
