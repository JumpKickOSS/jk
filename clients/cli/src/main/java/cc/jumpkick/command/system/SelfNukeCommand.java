// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.tool.LauncherName;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk self nuke} — wipe JumpKick product data while leaving the PATH install binaries,
 * managed JDKs, credentials, and the <strong>live engine jar</strong> intact.
 *
 * <p>One target per root, stackable; default {@code --all}:
 *
 * <ul>
 *   <li>{@code --cache} — {@code <home>/cache}; same as {@code jk cache nuke}
 *   <li>{@code --store} — {@code <home>/store} whole-tree, via {@code jk storage nuke}
 *   <li>{@code --state} — {@code <home>/state}: engine sockets, AOT, builds, scratch tmp, and
 *       the installed tool envs — the launchers {@code jk install} wrote into {@code bin} for
 *       them go too, see {@link #toolLaunchers}
 *   <li>{@code --config} — {@code <home>/config.toml} and the per-app {@code <home>/config} tree
 *   <li>{@code --all} — every target above (default when none are named)
 * </ul>
 *
 * <p><strong>What survives is decided by the layout, not by a carve-out.</strong> {@code bin},
 * {@code lib} and {@code creds} are roots of their own, so no target's delete reaches them:
 * {@code --store} cannot log the user out because the tokens are not in the tree it deletes.
 * {@link Guards} exists for the remaining way to aim a delete somewhere it does not belong — a
 * {@code JK_STORE_DIR} / {@code JK_CACHE_DIR} / {@code JK_STATE_DIR} pointed at a protected tree.
 */
public final class SelfNukeCommand implements CliCommand {

    /** Selectable nuke scopes. */
    enum Target {
        CACHE("Cache tier"),
        STORE("Artifact store"),
        STATE("Engine sockets, AOT, builds, installed tools"),
        CONFIG("User config");

        final String what;

        Target(String what) {
            this.what = what;
        }
    }

    /**
     * One display/delete row for the confirm table. {@code delegated} rows are wiped by the shared
     * {@code jk cache nuke} / {@code jk storage nuke} code paths rather than by this command's own
     * recursive delete, so {@link #run} skips them when walking the path rows.
     *
     * <p>Delegated or not, every row prints under <strong>Path to Delete</strong> and every row's
     * path is <em>gone as a directory</em> when the command finishes. The two shared nukes each
     * spent a release emptying their root instead of removing it (for the cache
     * for the store), which is the same table saying "delete" and meaning "empty".
     */
    record PurgeRow(Path path, String what, Target target, boolean delegated) {}

    /**
     * Paths a purge must never remove — nor remove a parent of. Resolved once per plan from the
     * <em>same</em> {@link JkDirs} the rows come from, so synthetic test environments guard
     * consistently.
     */
    record Guards(
            @Nullable Path bin,
            @Nullable Path jdks,
            @Nullable Path productLib,
            @Nullable Path creds) {
        static Guards of(JkDirs dirs) {
            return new Guards(
                    abs(dirs.binDirectory()), abs(dirs.jdksDir()), abs(dirs.productLibDir()), abs(dirs.credsDir()));
        }
    }

    @Override
    public String name() {
        return "nuke";
    }

    @Override
    public List<String> aliases() {
        return List.of("purge"); // pre-rename
    }

    @Override
    public String description() {
        return "Nuke store/cache/state/config (keeps bin, lib, creds, JDKs)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Print what would be removed; touch nothing.", "--dry-run"),
                Opt.flag("Nuke every target (default when none named).", "--all"),
                Opt.flag("Nuke the cache tier only (action outputs).", "--cache"),
                Opt.flag("Nuke the artifact store (repos, tools, templates, completions).", "--store"),
                Opt.flag("Nuke engine state, AOT caches, builds, tmp, and installed tools.", "--state"),
                Opt.flag("Nuke user config.", "--config"));
    }

    /**
     * The two nukes that run engine-side. They sit behind a seam because the contract that matters
     * — a failure here must not stop the local deletes below it — cannot be provoked otherwise:
     * whether a real engine starts depends on the machine, so a test that corrupts an engine jar
     * asserts nothing on a box that finds a working one anyway.
     */
    interface Hosted {
        /** Never called on a dry run — even the preview would spawn a daemon. */
        int storage() throws IOException;

        /** Called on dry runs too: the shared nuke's dry-run leg is a purely local walk. */
        int cache(Path cacheDir, boolean dryRun, GlobalOptions global, boolean enginesStopped) throws IOException;

        Hosted DEFAULT = new Hosted() {
            @Override
            public int storage() throws IOException {
                return StorageCommand.runNuke(false, true);
            }

            @Override
            public int cache(Path cacheDir, boolean dryRun, GlobalOptions global, boolean enginesStopped)
                    throws IOException {
                return CacheCommand.runNuke(cacheDir, dryRun, global, true, enginesStopped);
            }
        };
    }

    @Override
    public int run(Invocation in) throws Exception {
        return run(in, Hosted.DEFAULT);
    }

    int run(Invocation in, Hosted hosted) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        boolean dryRun = in.isSet("dry-run");
        Set<Target> selected = selectedTargets(in);
        JkDirs dirs = JkDirs.current();

        // Single-target cache: identical code path + UX as `jk cache nuke`. The shortcut still
        // answers to the guards — a refused cache row means the plan is empty, and the shared nuke
        // deletes whatever root it is handed.
        if (selected.equals(EnumSet.of(Target.CACHE))) {
            if (plan(dirs, selected).isEmpty()) {
                refuse("cache tier", dirs.cacheDir());
                return Exit.SOFTWARE;
            }
            return CacheCommand.runNuke(dirs.cacheDir(), dryRun, global, false);
        }

        List<PurgeRow> rows = plan(dirs, selected);
        // Cache tier and artifact store are wiped by the shared nukes — drop them from path rows.
        List<PurgeRow> pathRows =
                new ArrayList<>(rows.stream().filter(r -> !r.delegated()).toList());
        List<PurgeRow> launchers = toolLaunchers(dirs, rows);
        pathRows.addAll(launchers);
        boolean wantCache = selected.contains(Target.CACHE);
        boolean wantStoreTarget = selected.contains(Target.STORE);
        // The delegated wipes delete whatever root the request names — the store engine-side, the
        // cache via the shared nuke — with no guard of their own. Each runs only when its row
        // cleared the addRow guards: a JK_STORE_DIR / JK_CACHE_DIR mis-pointed at a protected tree
        // ($HOME, bin, lib, creds) is refused here rather than handed over whole-tree.
        boolean wantStore = wantStoreTarget && hasDelegated(rows, Target.STORE);
        boolean wantCacheWipe = wantCache && hasDelegated(rows, Target.CACHE);
        if (wantStoreTarget && !wantStore) refuse("artifact store", dirs.storeDir());
        if (wantCache && !wantCacheWipe) refuse("cache tier", dirs.cacheDir());
        List<PurgeRow> existing = pathRows.stream()
                .filter(r -> Files.exists(r.path(), LinkOption.NOFOLLOW_LINKS))
                .toList();
        boolean cacheExists = wantCacheWipe && Files.isDirectory(dirs.cacheDir());
        boolean storeExists = wantStore && Files.isDirectory(dirs.storeDir());
        if (existing.isEmpty() && !cacheExists && !storeExists) {
            CommandWedge.printOk("Self", "Nothing to nuke — selected JumpKick data not found.");
            return Exit.SUCCESS;
        }

        printPlan(existing, dirs, wantCacheWipe, wantStore, !launchers.isEmpty());
        if (!dryRun && !confirmPrompt()) {
            CommandWedge.printFail("Self", "Nuke aborted.");
            return 1;
        }

        boolean enginesStopped = !dryRun && (selected.contains(Target.STATE) || wantStoreTarget);
        if (enginesStopped) stopFleet();

        int exit = Exit.SUCCESS;
        // What the delegated (engine-hosted) nukes could not do. They are attempted FIRST but must
        // never end the command: the rows below — state and config — are deleted by this process
        // and need no engine at all. Letting an engine failure unwind
        // `run` meant a user who had just approved a table of five paths got an error about the
        // engine and five surviving paths, plus the files the spawn attempt had just written.
        List<String> delegatedFailures = new ArrayList<>();
        // A dry run never previews the store: its walk runs engine-side, and even the preview
        // spawns a daemon — which writes engine logs, an AOT index and a JDK registry into the
        // very state directory it is pretending to delete. The cache preview is a purely local
        // walk (the shared nuke's dry-run leg returns before any engine contact), so it stays;
        // only the store row goes un-itemised.
        if (dryRun && wantStore) {
            CliOutput.out("  (store contents not itemised — a dry run does not start an engine)");
        }
        // Settle order: Storage → Cache → Self, with a blank between back-to-back wedges so
        // adjacent chip backgrounds do not visually merge.
        if (wantStore && !dryRun) {
            settleGap();
            try {
                int s = hosted.storage();
                if (s != 0) {
                    delegatedFailures.add("artifact store (jk storage nuke) exited " + s);
                }
            } catch (IOException | RuntimeException e) {
                delegatedFailures.add("artifact store (jk storage nuke): " + reason(e));
            }
        }
        // `jk storage nuke` performs its delete engine-side: the wipe-store request calls
        // ensureRunning and the engine it boots is still there when it returns. Everything below
        // this line assumes a stopped fleet — the cache wipe skips the hosted purge on that
        // assumption, and the STATE rows are about to delete the sockets and AOT cache that
        // engine holds open, which it would then write straight back. Take it down again.
        if (enginesStopped && wantStore) stopFleet();
        if (wantCacheWipe) {
            // Engines were stopped above for STATE/STORE — the hosted purge would boot a fresh
            // one only for the STATE rows below to delete its state dir out from under it.
            settleGap();
            try {
                int c = hosted.cache(dirs.cacheDir(), dryRun, global, enginesStopped);
                if (c != 0) {
                    delegatedFailures.add("cache tier (jk cache nuke) exited " + c);
                }
            } catch (IOException | RuntimeException e) {
                delegatedFailures.add("cache tier (jk cache nuke): " + reason(e));
            }
        }

        List<String> failures = new ArrayList<>();
        Removal gone = remove(existing, launchers, dryRun, failures);
        long removed = gone.paths();
        long launchersRemoved = gone.launchers();

        // Say what did NOT happen, always. The whole failure mode this replaced was a command
        // that reported an engine problem and left the user to infer — wrongly — that the paths
        // they had just approved were gone.
        if (!delegatedFailures.isEmpty()) {
            Theme t = Theme.active();
            CliOutput.err(Theme.colorize(Glyphs.BANG, t.warning())
                    + (dryRun ? " Could not preview:" : " NOT removed — these targets need a running engine:"));
            for (String f : delegatedFailures) CliOutput.err("  " + f);
            if (!dryRun) exit = Exit.SOFTWARE;
        }
        if (!failures.isEmpty()) {
            Theme t = Theme.active();
            CliOutput.err(Theme.colorize(Glyphs.BANG, t.warning()) + " Some paths could not be removed:");
            for (String f : failures) CliOutput.err("  " + f);
            return Exit.SOFTWARE;
        }
        if (dryRun) {
            settleGap();
            CommandWedge.printOk(
                    "Self",
                    "Dry run: would nuke "
                            + removed
                            + " path"
                            + (removed == 1 ? "" : "s")
                            + (wantStore ? " plus the store target" : "")
                            + ".");
        } else if (!delegatedFailures.isEmpty()) {
            // Partial by construction: the local rows went, the delegated ones did not. Naming the
            // count is what tells the user the approval was not honoured in full.
            settleGap();
            CommandWedge.printFail(
                    "Self",
                    "Nuked " + removed + " path" + (removed == 1 ? "" : "s") + "; " + delegatedFailures.size()
                            + " target" + (delegatedFailures.size() == 1 ? "" : "s") + " left in place.");
        } else if (removed > 0 || wantCacheWipe || wantStoreTarget) {
            // Name the launchers that went: the user sees `widget` vanish from bin and needs to
            // know it was this command, and that `jk install` brings it back.
            String tools = launchersRemoved == 0
                    ? ""
                    : " and " + launchersRemoved + " installed tool launcher" + (launchersRemoved == 1 ? "" : "s")
                            + " (jk install restores them)";
            settleGap();
            CommandWedge.printOk(
                    "Self",
                    "Nuked selected JumpKick data" + tools + ". Kept: active engine "
                            + JkVersion.VERSION
                            + ", PATH, JDKs, credentials"
                            + (wantStoreTarget ? "" : ", store")
                            + ".");
        }
        return exit;
    }

    /** What {@link #remove} took off disk: every row, and how many of those were tool launchers. */
    record Removal(long paths, long launchers) {}

    /**
     * Delete each row this process owns — or, on a dry run, print it as "would remove" and count
     * it. A row that will not go is added to {@code failures} and never stops its siblings.
     */
    private static Removal remove(
            List<PurgeRow> rows, List<PurgeRow> launchers, boolean dryRun, List<String> failures) {
        long paths = 0;
        long launcherCount = 0;
        for (PurgeRow row : rows) {
            if (dryRun) {
                CliOutput.out("  would remove " + pathStyled(row.path()));
                paths++;
                continue;
            }
            try {
                PathUtil.deleteRecursivelyOrThrow(row.path());
                paths++;
                if (launchers.contains(row)) launcherCount++;
            } catch (IOException e) {
                failures.add(pathStyled(row.path()) + " (" + e.getMessage() + ")");
            }
        }
        return new Removal(paths, launcherCount);
    }

    /** Blank line before a settle when this command prints wedges back-to-back. */
    private static void settleGap() {
        CliOutput.out();
    }

    /**
     * Stop every engine of this home, naming any that refuses. Best-effort — a nuke does not fail
     * because process enumeration did — but it is called at <em>every</em> point where a running
     * engine would undo work this command is about to report as done, not only once up front.
     */
    private static void stopFleet() {
        try {
            for (EngineFleet.StopResult r : EngineFleet.stopAll(true)) {
                if (r.outcome() == EngineFleet.Outcome.SURVIVED) {
                    Theme t = Theme.active();
                    CliOutput.err(Theme.colorize(Glyphs.BANG, t.warning())
                            + " Engine pid "
                            + r.member().pid()
                            + " did not stop; nuking around it may leave it orphaned.");
                }
            }
        } catch (RuntimeException ignored) {
            // best-effort
        }
    }

    /** Parse stackable target flags; default {@code --all} when none named. */
    static Set<Target> selectedTargets(Invocation in) {
        boolean cache = in.isSet("cache");
        boolean store = in.isSet("store");
        boolean state = in.isSet("state");
        boolean config = in.isSet("config");
        boolean all = in.isSet("all") || !(cache || store || state || config);
        if (all) return EnumSet.allOf(Target.class);
        EnumSet<Target> set = EnumSet.noneOf(Target.class);
        if (cache) set.add(Target.CACHE);
        if (store) set.add(Target.STORE);
        if (state) set.add(Target.STATE);
        if (config) set.add(Target.CONFIG);
        return set;
    }

    /**
     * Build ordered purge rows for the selected targets — one row per root, plus the two config
     * paths. Never includes bin, JDKs, the product lib, or {@code creds}: those are roots of their
     * own, so no target names them and {@link #addRow} refuses anything that would reach them.
     *
     * <p>The store row is delegated whole-tree to {@code jk storage nuke}, which deletes the store
     * root outright — {@code repos}, {@code templates}, {@code tools}, {@code completions} and all.
     * That is intended: every byte under it is re-fetchable.
     */
    static List<PurgeRow> plan(JkDirs dirs, Set<Target> selected) {
        Guards guards = Guards.of(dirs);
        Map<Path, PurgeRow> byPath = new LinkedHashMap<>();

        if (selected.contains(Target.CACHE)) {
            addRow(byPath, abs(dirs.cacheDir()), "Cache tier", Target.CACHE, guards, true);
        }
        if (selected.contains(Target.STORE)) {
            addRow(byPath, abs(dirs.storeDir()), Target.STORE.what, Target.STORE, guards, true);
        }
        if (selected.contains(Target.STATE)) {
            // builds and tmp are inside state by construction, so the one row takes them with it.
            addRow(byPath, abs(dirs.stateDir()), Target.STATE.what, Target.STATE, guards);
        }
        if (selected.contains(Target.CONFIG)) {
            addRow(byPath, abs(dirs.userConfigFilePath()), Target.CONFIG.what, Target.CONFIG, guards);
            addRow(byPath, abs(dirs.configDir()), "Per-app config", Target.CONFIG, guards);
        }
        return new ArrayList<>(byPath.values());
    }

    /** Absolute paths selected for deletion (for tests). */
    static List<Path> wipeRoots(JkDirs dirs) {
        return plan(dirs, EnumSet.allOf(Target.class)).stream()
                .map(PurgeRow::path)
                .toList();
    }

    static List<Path> wipeRoots(JkDirs dirs, Set<Target> selected) {
        return plan(dirs, selected).stream().map(PurgeRow::path).toList();
    }

    /**
     * One row per launcher in {@code <home>/bin} that belongs to a tool env under {@code
     * <state>/tools/envs} — both the POSIX and the {@code .cmd} spelling. A launcher execs the
     * absolute classpath its env records, so once the state root is gone it fails with "could not
     * find or load main class"; it goes with its env and the table says so, rather than the
     * settle line claiming installed tools survive. {@code bin} itself stays a guarded root: the
     * rows are single files named by env directories that pass {@link LauncherName}, so jk's own
     * client under a reserved stem is never one of them. Empty unless the state row is scheduled
     * — a refused state root deletes no envs, so it orphans no launcher.
     */
    static List<PurgeRow> toolLaunchers(JkDirs dirs, List<PurgeRow> rows) {
        boolean stateGoes = rows.stream().anyMatch(r -> r.target() == Target.STATE && !r.delegated());
        if (!stateGoes) return List.of();
        Path envs = dirs.stateDir().resolve("tools").resolve("envs");
        if (!Files.isDirectory(envs)) return List.of();
        Path bin = dirs.binDirectory();
        List<String> names = new ArrayList<>();
        try {
            PathUtil.forEachChild(envs, (env, attrs) -> {
                String name = env.getFileName().toString();
                if (attrs.isDirectory() && LauncherName.validationError(name).isEmpty()) names.add(name);
                return true;
            });
        } catch (IOException unreadable) {
            // The state row's own delete reports an unreadable envs directory; nothing to add here.
        }
        names.sort(null);
        List<PurgeRow> out = new ArrayList<>();
        for (String name : names) {
            for (String leaf : List.of(name, name + ".cmd")) {
                Path launcher = LauncherName.resolveChild(bin, leaf);
                if (Files.exists(launcher, LinkOption.NOFOLLOW_LINKS)) {
                    out.add(new PurgeRow(
                            launcher.toAbsolutePath().normalize(), "Launcher of tool " + name, Target.STATE, false));
                }
            }
        }
        return out;
    }

    /**
     * Schedule a row unless it would touch a guarded tree: equal to, inside, or an <em>ancestor</em>
     * of bin, jdks, the product lib, or creds. Comparisons also run on real paths, so a root a user
     * has symlinked onto another volume — the reason {@code JK_STORE_DIR} exists — stays safe.
     *
     * @return whether the row was scheduled
     */
    private static boolean addRow(
            Map<Path, PurgeRow> byPath, @Nullable Path path, String what, Target target, Guards guards) {
        return addRow(byPath, path, what, target, guards, false);
    }

    /** {@link #addRow} for a row the shared cache/storage nukes wipe; see {@link PurgeRow}. */
    private static boolean addRow(
            Map<Path, PurgeRow> byPath,
            @Nullable Path path,
            String what,
            Target target,
            Guards guards,
            boolean delegated) {
        if (path == null) return false;
        Path norm = path.toAbsolutePath().normalize();
        if (conflicts(norm, guards.bin())
                || conflicts(norm, guards.jdks())
                || conflicts(norm, guards.productLib())
                || conflicts(norm, guards.creds())) {
            return false;
        }
        byPath.putIfAbsent(norm, new PurgeRow(norm, what, target, delegated));
        return true;
    }

    private static boolean hasDelegated(List<PurgeRow> rows, Target target) {
        return rows.stream().anyMatch(r -> r.delegated() && r.target() == target);
    }

    private static void refuse(String what, Path root) {
        CliOutput.err("jk: refusing to nuke the " + what + " at " + root
                + " — it overlaps a protected root (bin, JDKs, the live engine lib, or creds)");
    }

    /** Failure-row reason: the message when there is one, else the exception itself. */
    private static String reason(Throwable e) {
        return e.getMessage() != null ? e.getMessage() : e.toString();
    }

    /**
     * {@code wantStore} — the store row cleared the guards and will be wiped engine-side; {@code
     * launchersGo} — the rows include tool launchers under bin, so the bin line says what survives.
     */
    private static void printPlan(
            List<PurgeRow> rows, JkDirs dirs, boolean wantCache, boolean wantStore, boolean launchersGo) {
        List<String> headers = List.of("Path to Delete", "What");
        List<List<String>> tableRows = new ArrayList<>();
        for (PurgeRow r : rows) {
            tableRows.add(List.of(pathStyled(r.path()), r.what()));
        }
        if (wantCache) {
            tableRows.add(List.of(pathStyled(dirs.cacheDir()), "Cache tier (jk cache nuke)"));
        }
        if (wantStore) {
            tableRows.add(List.of(pathStyled(dirs.storeDir()), "Artifact store (jk storage nuke)"));
        }
        CommandWedge.envelopeStart();
        for (String line : Table.renderWarning("JumpKick Data Nuke", headers, tableRows)) {
            CliOutput.out(line);
        }
        // Every root that is NOT a target, named every time — the survivors do not vary by
        // selection now that each is a root rather than a child of one.
        CliOutput.out("  Kept:  " + pathStyled(dirs.credsDir()) + "  (credentials; remove via jk repo logout)");
        CliOutput.out("  Kept:  " + pathStyled(dirs.productLibDir()) + "  (live engine + installed app jars)");
        CliOutput.out("  Kept:  " + pathStyled(dirs.binDirectory())
                + (launchersGo
                        ? "  (PATH binaries; the tool launchers listed above go with their envs)"
                        : "  (PATH binaries)"));
        CliOutput.out("  Kept:  " + pathStyled(dirs.jdksDir()) + "  (managed JDKs)");
        CliOutput.out();
    }

    private static boolean confirmPrompt() {
        Theme t = Theme.active();
        String bang = Theme.colorize(Glyphs.BANG, t.warning());
        return Confirm.of(bang + " Nuke this JumpKick data?", false).ask();
    }

    /** Home-relative display form, painted with the theme path color. */
    static @Nullable String pathStyled(Path path) {
        return PathDisplay.styledRaw(displayPath(path));
    }

    /** Prefer {@code ~/…} when under the user home directory. */
    static String displayPath(Path path) {
        if (path == null) return "";
        String abs = path.toAbsolutePath().normalize().toString();
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            String h = Path.of(home).toAbsolutePath().normalize().toString();
            if (abs.equals(h)) return "~";
            if (abs.startsWith(h + "/")) return "~" + abs.substring(h.length());
            if (abs.regionMatches(true, 0, h, 0, h.length())
                    && abs.length() > h.length()
                    && (abs.charAt(h.length()) == '\\' || abs.charAt(h.length()) == '/')) {
                return "~" + abs.substring(h.length()).replace('\\', '/');
            }
        }
        return abs;
    }

    /**
     * Whether deleting {@code candidate} could touch {@code guarded}: the two are equal or one
     * contains the other, comparing both the textual (normalized) and real (symlink-resolved)
     * forms of each side.
     */
    private static boolean conflicts(Path candidate, @Nullable Path guarded) {
        if (candidate == null || guarded == null) return false;
        Path realCandidate = real(candidate);
        Path realGuarded = real(guarded);
        return overlaps(candidate, guarded)
                || overlaps(realCandidate, guarded)
                || overlaps(candidate, realGuarded)
                || overlaps(realCandidate, realGuarded);
    }

    private static boolean overlaps(Path a, Path b) {
        return a.equals(b) || isAncestor(a, b) || isAncestor(b, a);
    }

    /** Symlink-resolved form when the path (or its nearest existing ancestor) resolves; else normalized. */
    private static Path real(Path p) {
        try {
            return p.toRealPath();
        } catch (IOException e) {
            Path parent = p.getParent();
            if (parent != null) {
                try {
                    return parent.toRealPath().resolve(p.getFileName());
                } catch (IOException ignored) {
                    // fall through
                }
            }
            return p.toAbsolutePath().normalize();
        }
    }

    private static boolean isAncestor(Path ancestor, Path child) {
        if (ancestor == null || child == null) return false;
        Path a = ancestor.normalize();
        Path c = child.normalize();
        return c.startsWith(a) && !c.equals(a);
    }

    private static @Nullable Path abs(Path p) {
        return p == null ? null : p.toAbsolutePath().normalize();
    }
}
