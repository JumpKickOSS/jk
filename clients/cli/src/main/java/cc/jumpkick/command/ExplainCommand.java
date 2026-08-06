// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.EnsureFreshLock;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.ProjectContext;
import cc.jumpkick.cli.run.ConsoleSpec;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Spinner;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.ModuleDotGraph;
import cc.jumpkick.config.ModuleSelection;
import cc.jumpkick.config.WorkspaceLoader;
import cc.jumpkick.lock.LockFreshness;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.runtime.TaskForecast;
import cc.jumpkick.runtime.ExplainPlan;
import cc.jumpkick.util.HostCalibrationStatus;
import cc.jumpkick.util.JkDirs;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code jk explain} — forecast of what a build would run (cache hit/miss per module/step). Prefer
 * this over Gradle build scans for "why will this rebuild?" questions. Hidden aliases: {@code plan},
 * {@code why-rebuilt}. Refreshes a stale/missing lock first (same as {@code jk build}) so the ETA
 * matches the build countdown. {@code --verbose} expands all; {@code --run} executes the plan.
 */
public final class ExplainCommand implements CliCommand {

    @Override
    public String name() {
        return "explain";
    }

    // Hidden aliases plan / why-rebuilt live in Jk.VERB_ALIASES (exact-token rewrite before
    // dispatch) — never here: dispatcher aliases join the unique-prefix candidate set and would
    // make `jk pl` ambiguous with plugin and `jk wh` with why (JK-1364).

    @Override
    public String description() {
        return "Forecast rebuilds (cache hit/miss per step)";
    }

    @Override
    public List<Opt> options() {
        var opts = new java.util.ArrayList<Opt>();
        opts.add(Opt.flag("Build the plan instead of printing it", "--run"));
        opts.addAll(cc.jumpkick.cli.ParallelTestsOpts.options());
        // The plan-affecting options `jk build` accepts — forecasting `jk build <flags>`
        // means feeding the same inputs to the shared estimate (and, with --run, to build).
        // Module concurrency: global -j/--jobs.
        opts.add(Opt.value("<name>", "Forecast with a build profile", "--profile"));
        opts.add(Opt.value("<N>", "Test JVMs per module (0=auto)", "-w", "--workers"));
        opts.add(cc.jumpkick.cli.CommonOpts.skipTests());
        // -r/--redo is a global flag (same as `jk build --redo`); see GlobalOptions.
        opts.add(Opt.value("<dir>", "Override the JDK install root.", "--jdks-dir")
                .hide());
        opts.add(cc.jumpkick.cli.CommonOpts.cacheDir());
        opts.addAll(cc.jumpkick.cli.CommonOpts.moduleSelection());
        opts.add(Opt.value("<fmt>", "Emit module DAG as dot or mermaid", "--graph"));
        opts.add(Opt.value("<file>", "Write --graph output to this file", "--graph-out"));
        return opts;
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        Path cacheDir = in.value("cache-dir").map(Path::of).orElse(null);
        Path startDir = global.workingDir();
        var proj = ProjectContext.require(startDir, "explain").orElse(null);
        if (proj == null) return Exit.CONFIG;
        Path buildFile = proj.buildFile();
        Path cache = cacheDir != null ? cacheDir : JkDirs.cache();

        String graphFmt = in.value("graph").orElse(null);
        boolean hasGraph = graphFmt != null && !graphFmt.isBlank();
        if (in.isSet("run") && hasGraph) {
            CliOutput.err(
                    cc.jumpkick.cli.tui.CommandWedge.fail("Explain", "cannot combine --run with --graph (pick one)"));
            return Exit.USAGE;
        }
        if (in.isSet("run")) {
            return new BuildCommand().run(in); // forwards --cache-dir; build options default
        }

        // Module DAG export is offline (no engine / lock). Honor --modules / --affected-since.
        // On single-project layouts, selectors only validate; the graph is one node.
        if (hasGraph) {
            return emitModuleGraph(
                    startDir,
                    buildFile,
                    graphFmt,
                    in.value("modules").orElse(null),
                    in.value("affected-since").orElse(null),
                    in.value("graph-out").orElse(null));
        }

        // HARD INVARIANT: bare `jk explain` uses the exact same defaults as bare `jk build`
        // (-w 0 = auto, -j from jobsEffective, parallel-tests default on). The estimate must
        // match the live countdown bit-for-bit — docs/perf/progress-contract.md.
        boolean parallelTests = cc.jumpkick.cli.ParallelTestsOpts.enabled(in);
        int jobs = global.jobsEffective();
        boolean serial = jobs == 1;
        // 0 = auto within-module test JVMs — same as BuildCommand when -w is omitted.
        int workers = in.value("workers").map(Integer::parseInt).orElse(0);
        boolean skipTests = in.isSet("skip-tests");
        // Global --redo / --force: forecast full work + rebuild ETA priors.
        boolean rebuild = global.rebuild || global.force;
        String profile = in.value("profile").orElse(null);
        Path jdksDir = in.value("jdks-dir").map(Path::of).orElse(null);
        String affectedSince = in.value("affected-since").orElse(null);
        String modulesSpec = in.value("modules").orElse(null);

        // Client-side module filter listing (before engine forecast) when selectors are set.
        if ((affectedSince != null && !affectedSince.isBlank()) || (modulesSpec != null && !modulesSpec.isBlank())) {
            try {
                var entry = cc.jumpkick.config.JkBuildParser.parse(buildFile);
                var selected =
                        cc.jumpkick.config.ModuleSelection.resolveOptional(startDir, entry, modulesSpec, affectedSince);
                if (selected != null && !selected.ok()) {
                    CliOutput.err(CommandWedge.fail("Explain", selected.errorMessage()));
                    return Exit.CONFIG;
                }
                if (selected != null) {
                    CliOutput.out("Selected modules (" + selected.moduleDirs().size() + "):");
                    for (Path m : selected.moduleDirs()) {
                        Path rel;
                        try {
                            rel = startDir.toAbsolutePath().normalize().relativize(m);
                        } catch (IllegalArgumentException e) {
                            rel = m;
                        }
                        CliOutput.out("  " + (rel.toString().isEmpty() ? "." : rel));
                    }
                    if (selected.moduleDirs().isEmpty()) {
                        return 0;
                    }
                }
            } catch (Exception e) {
                CliOutput.err(CommandWedge.fail("Explain", "module selection failed: " + e.getMessage()));
                return Exit.CONFIG;
            }
        }

        // Live prep wedge: Locking versions… → Calculating build plan… (or Calibrating host…),
        // then clear and print the settled Build Plan tree.
        boolean livePrep = EnsureFreshLock.isInteractiveAuto(global) && !global.outputIsJson();
        boolean needsLock = LockFreshness.needsRefresh(startDir);
        boolean needsCalibrate = HostCalibrationStatus.needsBootstrapProbe();
        String prepMsg =
                needsLock ? "Locking versions…" : needsCalibrate ? "Calibrating host…" : "Calculating build plan…";

        ExplainPlan plan;
        long etaMillis;
        long[] etaOut = new long[1];
        try (Spinner prep = livePrep ? CommandWedge.analyzing(CliOutput.stdout(), "Explain", prepMsg) : null) {
            // Same starting lock as `jk build` so the dirty plan and ETA match the countdown.
            // Pass the prep spinner so a freshen failure settles it before writing stderr.
            if (needsLock) {
                if (prep != null) prep.update("Locking versions…");
                int lockCode = EnsureFreshLock.ensure(startDir, cache, global, "Explain", prep, false);
                if (lockCode != 0) return lockCode;
            }
            if (prep != null) {
                prep.update(needsCalibrate ? "Calibrating host…" : "Calculating build plan…");
            }
            // Forecast via engine: graph + per-step plan + schedule-aware ETA.
            plan = cc.jumpkick.cli.engine.EngineClient.explain(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineClient.ExplainRequest(
                            startDir,
                            cache,
                            workers,
                            skipTests,
                            profile,
                            jdksDir,
                            serial,
                            parallelTests,
                            global.verbose,
                            rebuild,
                            jobs),
                    etaOut);
        }
        etaMillis = etaOut[0];

        if (plan.hasErrors()) {
            for (String err : plan.errors()) CliOutput.err(ConsoleSpec.errorLine("composite", err));
            return Exit.CONFIG;
        }

        Theme t = Theme.active();
        boolean ansi = t.isAnsi();
        boolean nerdfont = cc.jumpkick.config.GlobalConfig.nerdfont();
        // On a TTY, wrap the cached-module list to the terminal width; piped output gets
        // the full list on one line (MAX_VALUE → never wraps).
        int width = cc.jumpkick.cli.run.BuildPlanConsole.isInteractiveTerminal()
                ? cc.jumpkick.cli.tui.CommandManager.detectColumns()
                : Integer.MAX_VALUE;

        // Forecast every module's full step plan (compile → test → package),
        // truthfully — see TaskForecaster.
        List<TaskForecast.Module> modules = plan.modules();
        boolean all = in.isSet("verbose");

        // Header: plan chip (nerd powerline / ansi two-space trail / plain " = Build Plan >")
        // then the build-time estimate.
        String header =
                cc.jumpkick.cli.tui.BuildPlanWedge.planChip(cc.jumpkick.cli.tui.Glyphs.MENU, "Build Plan", nerdfont);
        // Fully-cached plans report eta 0 from the engine ("no work") — that is not unknown;
        // a pure cache verify is sub-second. Only show "unknown" when there is real
        // work but no learned timings yet.
        boolean fullyCached = !modules.isEmpty() && modules.stream().noneMatch(TaskForecast.Module::dirty);
        String estimate = buildTimeEstimate(etaMillis, fullyCached, t);
        // Leading blank once per command (prep lock wedge may already have opened it).
        CommandWedge.envelopeStart();
        CliOutput.out(header + " " + estimate);
        // Root node: ● bullet, then the entry project's group:artifact in bold.
        String rootBullet = ansi ? Theme.colorize("●", t.darkGray()) : "*";
        String coord = BuildCommand.buildTarget(buildFile, startDir);
        CliOutput.out(" " + rootBullet + " " + (ansi ? boldCoord(coord, t) : coord));

        // Workspace-wide stats directly under the root bullet.
        int totalModules = modules.size();
        int totalSources =
                modules.stream().mapToInt(TaskForecast.Module::sourceCount).sum();
        int totalTests = modules.stream().mapToInt(TaskForecast.Module::testCount).sum();
        int totalJars =
                (int) modules.stream().filter(TaskForecast.Module::producesJar).count();
        int totalImages =
                (int) modules.stream().filter(TaskForecast.Module::producesImage).count();
        String rootPfx = ansi ? " " + Theme.colorize("│", t.darkGray()) + " · " : " | - ";
        if (totalModules > 1) CliOutput.out(rootPfx + "Modules: " + String.format("%,d", totalModules));
        CliOutput.out(rootPfx + "Sources: " + fmtCount(totalSources, "file", "files"));
        CliOutput.out(rootPfx + "Tests: " + fmtCount(totalTests, "test", "tests"));
        if (totalJars > 0) CliOutput.out(rootPfx + "Packages: " + fmtCount(totalJars, "jar", "jars"));
        if (totalImages > 0) CliOutput.out(rootPfx + "Containers: " + fmtCount(totalImages, "image", "images"));

        // Partition the topo order by cache status: every fully-cached module (wherever
        // it sits in the order) collapses into the "Fully Cached" section (names only);
        // only the modules that actually rebuild appear in the detailed "Rebuild" section,
        // each keeping its topo index. --verbose expands every step (cached ones too).
        boolean verbose = all;
        List<Integer> cachedIdx = new ArrayList<>();
        List<Integer> dirtyIdx = new ArrayList<>();
        for (int i = 0; i < modules.size(); i++) {
            (modules.get(i).dirty() ? dirtyIdx : cachedIdx).add(i);
        }

        if (!cachedIdx.isEmpty()) {
            boolean lastSection = dirtyIdx.isEmpty();
            String sectionConnector = lastSection ? "╰─" : "├─";
            // Plain: no space between connector and pill (`-[Fully Cached]`) — matches jk tree.
            String sectionBadge = ansi ? cc.jumpkick.cli.tui.Badge.pill("Fully Cached", nerdfont) : "[Fully Cached]";
            CliOutput.out(" "
                    + (ansi ? Theme.colorize(sectionConnector, t.darkGray()) : (lastSection ? "`-" : "+-"))
                    + sectionBadge);
            String childPrefix = ansi
                    ? " " + Theme.colorize(lastSection ? "   " : "│  ", t.darkGray())
                    : " " + (lastSection ? "   " : "|  ");
            if (verbose) {
                for (int j = 0; j < cachedIdx.size(); j++) {
                    int i = cachedIdx.get(j);
                    renderModuleRow(
                            modules.get(i), i + 1, j == cachedIdx.size() - 1, childPrefix, nerdfont, true, t, ansi);
                }
            } else {
                List<String> names = new ArrayList<>();
                for (int i : cachedIdx) names.add(":" + shortName(modules.get(i).coord()));
                // Wrap the full list across lines (no truncation): the first line hangs off
                // a "╰─ " connector; continuations align under the first name.
                List<String> lines = wrapNames(names, Math.max(20, width - 7));
                String cont = childPrefix + "   "; // align past "╰─ " / "`- "
                String firstConnector = ansi ? Theme.colorize("╰─ ", t.darkGray()) : "`- ";
                for (int li = 0; li < lines.size(); li++) {
                    CliOutput.out(
                            (li == 0 ? childPrefix + firstConnector : cont) + renderCachedNames(lines.get(li), t));
                }
            }
        }
        if (!dirtyIdx.isEmpty()) {
            String rebuildBadge = ansi ? cc.jumpkick.cli.tui.Badge.pill("Rebuild", nerdfont) : "[Rebuild]";
            CliOutput.out(" " + (ansi ? Theme.colorize("╰─", t.darkGray()) : "`-") + rebuildBadge);
            String secPfx = ansi ? "    " + Theme.colorize("│", t.darkGray()) + " · " : "    | - ";
            int dirtyModules = dirtyIdx.size();
            int dirtySources = modules.stream()
                    .filter(TaskForecast.Module::dirty)
                    .mapToInt(TaskForecast.Module::sourceCount)
                    .sum();
            int dirtyTests = modules.stream()
                    .filter(TaskForecast.Module::dirty)
                    .mapToInt(TaskForecast.Module::testCount)
                    .sum();
            int dirtyJars = (int)
                    modules.stream().filter(m -> m.dirty() && m.producesJar()).count();
            int dirtyImages = (int)
                    modules.stream().filter(m -> m.dirty() && m.producesImage()).count();
            if (totalModules > 1)
                CliOutput.out(
                        secPfx + "Modules: " + String.format("%,d", dirtyModules) + pct(dirtyModules, totalModules));
            CliOutput.out(
                    secPfx + "Sources: " + fmtCount(dirtySources, "file", "files") + pct(dirtySources, totalSources));
            CliOutput.out(secPfx + "Tests: " + fmtCount(dirtyTests, "test", "tests") + pct(dirtyTests, totalTests));
            if (totalJars > 0)
                CliOutput.out(secPfx + "Packages: " + fmtCount(dirtyJars, "jar", "jars") + pct(dirtyJars, totalJars));
            if (totalImages > 0)
                CliOutput.out(secPfx
                        + "Containers: "
                        + fmtCount(dirtyImages, "image", "images")
                        + pct(dirtyImages, totalImages));
            for (int j = 0; j < dirtyIdx.size(); j++) {
                int i = dirtyIdx.get(j);
                renderModuleRow(modules.get(i), i + 1, j == dirtyIdx.size() - 1, "    ", nerdfont, verbose, t, ansi);
            }
        }
        return 0;
    }

    /**
     * Header estimate fragment: {@code Build time estimate ~8s}, {@code Build time estimate <1s}
     * (fully cached / sub-second), or {@code Build time not yet measured} when dirty work has no
     * host/project timings yet.
     */
    static String buildTimeEstimate(long etaMillis, boolean fullyCached, Theme t) {
        if (fullyCached || (etaMillis > 0 && etaMillis < 1000)) {
            return "Build time estimate " + Theme.colorize("<1s", t.warning());
        }
        if (etaMillis <= 0) {
            return "Build time " + Theme.colorize("not yet measured", t.warning());
        }
        return "Build time estimate " + Theme.colorize("~" + fmtDuration(etaMillis), t.warning());
    }

    /** "1m 20s" / "8s" / "<1s" — coarse predicted-duration formatting for the plan summary. */
    private static String fmtDuration(long millis) {
        if (millis <= 0) return "<1s";
        long s = millis / 1000; // floor: don't over-state
        if (s == 0) return "<1s"; // a sub-second cache-verify pass
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    /**
     * Extra bright-black dots after every step name (including the longest) so the {@code □}/{@code
     * ✓} column never butts up against the name.
     */
    private static final int STEP_NAME_DOT_GAP = 2;

    /**
     * Render one module row under a section: {@code prefix} + connector + index badge + coordinate,
     * then its step sub-tree. The verdict is implied by the enclosing section (Fully Cached /
     * Rebuild) and the origin / dependency edges are omitted as noise. Steps render when the module
     * rebuilds, or always under {@code verbose}.
     */
    private static void renderModuleRow(
            TaskForecast.Module m,
            int idx,
            boolean last,
            String prefix,
            boolean nerdfont,
            boolean verbose,
            Theme t,
            boolean ansi) {
        String moduleConnector = ansi ? Theme.colorize((last ? "╰" : "├") + "─", t.darkGray()) : (last ? "`-" : "+-");
        String moduleBadge = ansi
                ? cc.jumpkick.cli.tui.Badge.pill(String.format("%02d", idx), nerdfont)
                : " [" + String.format("%02d", idx) + "]";
        CliOutput.out(prefix + moduleConnector + moduleBadge + ' ' + (ansi ? coloredCoord(m.coord(), t) : m.coord()));

        if (m.dirty() || verbose) {
            String spine = ansi
                    ? prefix + (last ? "   " : Theme.colorize("│", t.darkGray()) + "  ")
                    : prefix + (last ? "   " : "|  ");
            List<TaskForecast.Task> ph = m.steps();
            // Pad each step name to the widest in this module with bright-black dots so the
            // □ / ✓ column lines up (package-assembly is longer than compile-main, etc.).
            int nameCol = 0;
            for (TaskForecast.Task p : ph) {
                nameCol = Math.max(nameCol, p.name().length());
            }
            nameCol += STEP_NAME_DOT_GAP;
            // Pad each □ step's command to the widest in this module so the · column lines up.
            int commandCol = 0;
            for (TaskForecast.Task p : ph) {
                if (!p.cached()) commandCol = Math.max(commandCol, commandWidth(p.text()));
            }
            for (int k = 0; k < ph.size(); k++) {
                boolean lp = k == ph.size() - 1;
                String stepConnector = ansi ? Theme.colorize(lp ? "╰─ " : "├─ ", t.darkGray()) : (lp ? "`- " : "+- ");
                String stepName = formatStepName(ph.get(k).name(), nameCol, t, ansi);
                CliOutput.out(spine + stepConnector + stepName + renderStatus(ph.get(k), commandCol, t, ansi));
            }
        }
    }

    /**
     * Step name in bright white, right-padded with bright-black {@code .} to {@code width} so the
     * status glyph lines up across steps. Non-ANSI: plain dots, no color.
     */
    static String formatStepName(String name, int width, Theme t, boolean ansi) {
        int pad = Math.max(0, width - name.length());
        String dots = pad == 0 ? "" : ".".repeat(pad);
        if (!ansi) return name + dots;
        return Theme.colorize(name, t.brightWhite()) + Theme.colorize(dots, t.darkGray());
    }

    /** Visible width of a step command — the text before {@code " · "} (or the whole text). */
    private static int commandWidth(String text) {
        int sep = text.indexOf(" · ");
        return (sep < 0 ? text : text.substring(0, sep)).length();
    }

    /** The entry project's {@code group:artifact} in bold, each segment in its coord color. */
    private static String boldCoord(String coord, Theme t) {
        int colon = coord.indexOf(':');
        if (colon < 0) return Theme.colorize(coord, t.coordName().bold());
        return Theme.colorize(coord.substring(0, colon), t.coordGroup().bold())
                + ":"
                + Theme.colorize(coord.substring(colon + 1), t.coordName().bold());
    }

    /** The artifact half of a {@code group:artifact} coordinate. */
    private static String shortName(String coord) {
        int c = coord.indexOf(':');
        return c < 0 ? coord : coord.substring(c + 1);
    }

    /** Color a comma-list of {@code :name} cached-module refs (the {@code …+N more…} marker dim). */
    private static String renderCachedNames(String elided, Theme t) {
        StringBuilder sb = new StringBuilder();
        String[] pieces = elided.split(", ");
        for (int i = 0; i < pieces.length; i++) {
            if (i > 0) sb.append(Theme.colorize(", ", t.darkGray()));
            String p = pieces[i];
            if (p.matches("…\\+\\d+ more…")) {
                sb.append(Theme.colorize(p, t.darkGray()));
            } else if (p.startsWith(":")) {
                sb.append(":").append(Theme.colorize(p.substring(1), t.coordName()));
            } else {
                sb.append(Theme.colorize(p, t.coordName()));
            }
        }
        return sb.toString();
    }

    /**
     * A step's status: {@code ✓ cached <key> · detail} (green) when cached, otherwise {@code □
     * <command> · <detail>} with the command in yellow (padded to {@code commandCol} so the {@code ·} lines up
     * across the module's steps), the {@code ·} bright-black, and the trailing detail in italic.
     * When {@code !ansi}: {@code (cached)} / {@code [run]} ASCII equivalents, no color.
     */
    private static String renderStatus(TaskForecast.Task p, int commandCol, Theme t, boolean ansi) {
        if (p.cached()) {
            if (!ansi) {
                StringBuilder s = new StringBuilder("(cached)");
                if (p.key() != null) s.append(' ').append(p.key());
                if (p.text() != null && !p.text().isEmpty()) s.append(' ').append(p.text());
                return s.toString();
            }
            StringBuilder s = new StringBuilder(Theme.colorize("✓ cached", t.success()));
            if (p.key() != null) s.append(' ').append(Theme.colorize(p.key(), t.path()));
            if (p.text() != null && !p.text().isEmpty())
                s.append(' ').append(Theme.colorize(p.text(), t.darkGray().italic()));
            return s.toString();
        }
        String text = p.text();
        int sep = text.indexOf(" · ");
        String command = sep < 0 ? text : text.substring(0, sep);
        String detail = sep < 0 ? null : text.substring(sep + 3);
        if (!ansi) {
            StringBuilder s = new StringBuilder("[run] ").append(padRight(command, commandCol));
            if (detail != null) s.append(" - ").append(detail);
            return s.toString();
        }
        StringBuilder s = new StringBuilder(Theme.colorize("□ ", t.brightWhite()))
                .append(Theme.colorize(padRight(command, commandCol), t.warning()));
        if (detail != null) {
            s.append(' ')
                    .append(Theme.colorize("·", t.darkGray()))
                    .append(' ')
                    .append(Theme.colorize(detail, t.brightWhite().italic()));
        }
        return s.toString();
    }

    private static String fmtCount(int n, String singular, String plural) {
        return String.format("%,d", n) + " " + (n == 1 ? singular : plural);
    }

    private static String pct(int part, int whole) {
        if (whole <= 0) return "";
        return " - " + (part * 100 / whole) + "%";
    }

    private static String padRight(String s, int width) {
        return s.length() >= width ? s : s + " ".repeat(width - s.length());
    }

    /** {@code group:name} with the group and name in their coordinate colors. */
    private static String coloredCoord(String coord, Theme t) {
        int colon = coord.indexOf(':');
        if (colon < 0) return Theme.colorize(coord, t.coordName());
        return Theme.colorize(coord.substring(0, colon), t.coordGroup())
                + ":"
                + Theme.colorize(coord.substring(colon + 1), t.coordName());
    }

    /**
     * Join {@code units} with {@code ", "} to fit {@code available} visible columns. When the full
     * list is too wide, show as many leading units as fit followed by a {@code …+N more…} marker,
     * where {@code N} is the count of remaining units that didn't fit. {@code available} is
     * effectively unbounded on a non-TTY, so the full list is shown there.
     */
    static String elideDeps(List<String> units, int available) {
        String full = String.join(", ", units);
        if (available <= 0 || units.size() <= 1 || full.length() <= available) return full;
        String best = "…+" + units.size() + " more…"; // marker-only, if even one unit won't fit
        for (int k = 1; k < units.size(); k++) {
            String candidate = String.join(", ", units.subList(0, k)) + ", …+" + (units.size() - k) + " more…";
            if (candidate.length() > available) break; // front grows monotonically
            best = candidate;
        }
        return best;
    }

    /**
     * Greedily pack {@code tokens} into {@code ", "}-joined lines, each at most {@code avail} visible
     * columns wide (the wrap point drops the separator rather than leaving a trailing comma). On a
     * non-TTY {@code avail} is effectively unbounded, so the whole list lands on one line.
     */
    static List<String> wrapNames(List<String> tokens, int avail) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String tok : tokens) {
            if (cur.length() == 0) {
                cur.append(tok);
            } else if (cur.length() + 2 + tok.length() <= avail) {
                cur.append(", ").append(tok);
            } else {
                lines.add(cur.toString());
                cur = new StringBuilder(tok);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    /**
     * {@code jk explain --graph dot|mermaid} — module dependency DAG (no engine).
     */
    private static int emitModuleGraph(
            Path startDir, Path buildFile, String format, String modulesSpec, String affectedSince, String outputPath)
            throws Exception {
        String fmt = format.trim().toLowerCase(Locale.ROOT);
        if (!ModuleDotGraph.isSupportedFormat(fmt)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Explain",
                    "unsupported --graph format '" + format + "' (supported: "
                            + String.join(" | ", ModuleDotGraph.FORMATS) + ")"));
            return Exit.CONFIG;
        }
        JkBuild entry = JkBuildParser.parse(buildFile);
        Path root = startDir.toAbsolutePath().normalize();
        String graph;
        if (entry.isWorkspaceRoot()) {
            Map<Path, JkBuild> modules = WorkspaceLoader.loadModules(root, entry);
            ModuleSelection.Result selected =
                    ModuleSelection.resolveOptional(startDir, entry, modulesSpec, affectedSince);
            if (selected != null && !selected.ok()) {
                CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Explain", selected.errorMessage()));
                return Exit.CONFIG;
            }
            Set<Path> only = selected != null ? selected.moduleDirs() : null;
            if (only != null && only.isEmpty()) {
                // Nothing selected — still valid empty digraph
                graph = ModuleDotGraph.render(fmt, root, Map.of(), null);
            } else {
                // Workspace root may not be in modules map; graph is modules only (Mill-like module DAG).
                Map<Path, JkBuild> forGraph = new LinkedHashMap<>(modules);
                graph = ModuleDotGraph.render(fmt, root, forGraph, only);
            }
        } else {
            // Single project: trivial one-node graph (selectors ignored / no-op).
            if ((modulesSpec != null && !modulesSpec.isBlank())
                    || (affectedSince != null && !affectedSince.isBlank())) {
                ModuleSelection.Result selected =
                        ModuleSelection.resolveOptional(startDir, entry, modulesSpec, affectedSince);
                if (selected != null && !selected.ok()) {
                    CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Explain", selected.errorMessage()));
                    return Exit.CONFIG;
                }
            }
            graph = ModuleDotGraph.singleModule(entry, root, fmt);
        }
        if (outputPath != null && !outputPath.isBlank()) {
            Path out = Path.of(outputPath);
            if (!out.isAbsolute()) out = root.resolve(out);
            Path parent = out.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.writeString(out, graph);
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Explain", "wrote " + out.toAbsolutePath().normalize()));
        } else {
            CliOutput.out(graph.endsWith("\n") ? graph.substring(0, graph.length() - 1) : graph);
        }
        return 0;
    }
}
