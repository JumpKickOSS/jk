// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.Table;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
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
import java.util.stream.Stream;

/**
 * {@code jk self nuke} — wipe JumpKick product data while leaving the PATH install binaries,
 * managed JDKs, and the <strong>live engine jar</strong> intact.
 *
 * <p>Targets (stackable; default {@code --all}):
 *
 * <ul>
 *   <li>{@code --cache} — same as {@code jk cache nuke}
 *   <li>{@code --data} (alias {@code --store}) — the product data root ({@code JK_DATA_DIR},
 *       default {@code ~/.local/share/jk}, {@code $JK_HOME/data} under the umbrella): the artifact
 *       store via {@code jk storage nuke}, plus every other child of that root
 *   <li>{@code --state} — engine sockets, AOT, builds, scratch tmp
 *   <li>{@code --config} — user config
 *   <li>{@code --all} — every target above (default when none are named)
 * </ul>
 *
 * <p><strong>Never touches the bin directory</strong> ({@code ~/.local/bin} / {@code JK_BIN_DIR}),
 * and never removes forge/repo credentials — logging out is {@code jk repo logout}'s job. Those two
 * live <em>inside</em> the data root and survive {@code --data} anyway; see {@link Guards}.
 */
public final class SelfNukeCommand implements CliCommand {

    /** Selectable nuke scopes. */
    enum Target {
        CACHE("Cache tier"),
        DATA("Product data"),
        STATE("Engine sockets, AOT, builds"),
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
     */
    record PurgeRow(Path path, String what, Target target, boolean delegated) {}

    /**
     * Paths a purge must never remove — nor remove a parent of. Resolved once per plan from the
     * <em>same</em> {@link JkDirs} the rows come from, so synthetic test environments guard
     * consistently.
     */
    record Guards(Path bin, Path jdks, Path productLib, Path lib, Path credentials, Path repoCredentials) {
        static Guards of(JkDirs dirs) {
            Path data = dirs.dataDir();
            return new Guards(
                    abs(dirs.binDirectory()),
                    abs(dirs.jdksDir()),
                    abs(dirs.productLibDir()),
                    abs(dirs.libDir()),
                    abs(data.resolve("credentials")),
                    abs(data.resolve("repo-credentials")));
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
        return "Nuke data/state (keeps live engine jar, PATH, JDKs)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Print what would be removed; touch nothing.", "--dry-run"),
                Opt.flag("Nuke every target (default when none named).", "--all"),
                Opt.flag("Nuke the cache tier only (action outputs).", "--cache"),
                Opt.flag("Nuke the product data root (artifact store, versions, ...).", "--data")
                        .alias("--store"), // pre-widening name
                Opt.flag("Nuke engine state, AOT caches, builds, and tmp.", "--state"),
                Opt.flag("Nuke user config.", "--config"));
    }

    @Override
    public int run(Invocation in) throws Exception {
        GlobalOptions global = GlobalOptions.from(in);
        boolean dryRun = in.isSet("dry-run");
        Set<Target> selected = selectedTargets(in);
        JkDirs dirs = JkDirs.current();

        // Single-target cache: identical code path + UX as `jk cache nuke`. There is no such
        // shortcut for --data — the store is only one child of the data root, so the plan table and
        // this command's own confirm have to cover the rest.
        if (selected.equals(EnumSet.of(Target.CACHE))) {
            return CacheCommand.runNuke(dirs.cacheDir(), dryRun, global, false);
        }

        List<PurgeRow> rows = plan(dirs, selected);
        // Cache tier and artifact store are wiped by the shared nukes — drop them from path rows.
        List<PurgeRow> pathRows = rows.stream().filter(r -> !r.delegated()).toList();
        boolean wantCache = selected.contains(Target.CACHE);
        boolean wantData = selected.contains(Target.DATA);
        List<PurgeRow> existing = pathRows.stream()
                .filter(r -> Files.exists(r.path(), LinkOption.NOFOLLOW_LINKS))
                .toList();
        boolean cacheExists = wantCache && Files.isDirectory(dirs.cacheDir());
        boolean storeExists = wantData && Files.isDirectory(dirs.storeDir());
        if (existing.isEmpty() && !cacheExists && !storeExists) {
            CommandWedge.printOk("Self", "Nothing to nuke — selected JumpKick data not found.");
            return Exit.SUCCESS;
        }

        printPlan(existing, dirs, selected, wantCache, wantData);
        if (!dryRun && !confirmPrompt()) {
            CommandWedge.printFail("Self", "Nuke aborted.");
            return 1;
        }

        if (!dryRun && (selected.contains(Target.STATE) || wantData)) {
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

        int exit = Exit.SUCCESS;
        // Settle order: Storage → Cache → Self, with a blank between back-to-back wedges so
        // adjacent chip backgrounds do not visually merge.
        if (wantData) {
            settleGap();
            int s = StorageCommand.runNuke(dryRun, true);
            if (s != 0) exit = s;
        }
        if (wantCache) {
            // Engines were stopped above for STATE/STORE — the hosted purge would boot a fresh
            // one only for the STATE rows below to delete its state dir out from under it.
            boolean enginesStopped = !dryRun && (selected.contains(Target.STATE) || wantData);
            settleGap();
            int c = CacheCommand.runNuke(dirs.cacheDir(), dryRun, global, true, enginesStopped);
            if (c != 0) exit = c;
        }

        long removed = 0;
        List<String> failures = new ArrayList<>();
        for (PurgeRow row : existing) {
            if (dryRun) {
                CliOutput.out("  would remove " + pathStyled(row.path()));
                removed++;
                continue;
            }
            try {
                PathUtil.deleteRecursivelyOrThrow(row.path());
                removed++;
            } catch (IOException e) {
                failures.add(pathStyled(row.path()) + " (" + e.getMessage() + ")");
            }
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
                            + (wantCache || wantData ? " plus cache/store targets" : "")
                            + ".");
        } else if (removed > 0 || wantCache || wantData) {
            settleGap();
            CommandWedge.printOk(
                    "Self",
                    "Nuked selected JumpKick data. Kept: active engine "
                            + Jk.VERSION
                            + ", PATH, JDKs"
                            + (wantData ? ", credentials" : ", store")
                            + ".");
        }
        return exit;
    }

    /** Blank line before a settle when this command prints wedges back-to-back. */
    private static void settleGap() {
        CliOutput.out();
    }

    /** Parse stackable target flags; default {@code --all} when none named. */
    static Set<Target> selectedTargets(Invocation in) {
        boolean cache = in.isSet("cache");
        boolean data = in.isSet("data"); // --store is a hidden alias on the same canonical key
        boolean state = in.isSet("state");
        boolean config = in.isSet("config");
        boolean all = in.isSet("all") || !(cache || data || state || config);
        if (all) return EnumSet.allOf(Target.class);
        EnumSet<Target> set = EnumSet.noneOf(Target.class);
        if (cache) set.add(Target.CACHE);
        if (data) set.add(Target.DATA);
        if (state) set.add(Target.STATE);
        if (config) set.add(Target.CONFIG);
        return set;
    }

    /**
     * Build ordered purge rows for the selected targets. Never includes bin, JDKs, the active
     * product-lib engine jar, {@code store/lib/} (installed tools), or the credential stores.
     */
    static List<PurgeRow> plan(JkDirs dirs, Set<Target> selected) {
        Guards guards = Guards.of(dirs);
        Map<Path, PurgeRow> byPath = new LinkedHashMap<>();

        if (selected.contains(Target.CACHE)) {
            addRow(byPath, abs(dirs.cacheDir()), "Cache tier", Target.CACHE, guards, true);
        }

        if (selected.contains(Target.DATA)) {
            planData(dirs, byPath, guards);
        }

        if (selected.contains(Target.STATE)) {
            addRow(byPath, abs(dirs.stateDir()), Target.STATE.what, Target.STATE, guards);
            Path state = abs(dirs.stateDir());
            Path builds = abs(dirs.buildsDir());
            Path tmp = abs(dirs.tmpDir());
            if (builds != null && state != null && !isAncestor(state, builds) && !builds.equals(state)) {
                addRow(byPath, builds, "Build history", Target.STATE, guards);
            }
            if (tmp != null && state != null && !isAncestor(state, tmp) && !tmp.equals(state)) {
                addRow(byPath, tmp, "Scratch tmp", Target.STATE, guards);
            }
        }

        if (selected.contains(Target.CONFIG)) {
            planConfig(dirs, byPath, guards);
        }

        return new ArrayList<>(byPath.values());
    }

    /**
     * Data-root nuke: the artifact store plus every <em>other</em> child of {@code <data>}
     * ({@code JK_DATA_DIR}; default {@code ~/.local/share/jk}, or {@code $JK_HOME/data}). The store
     * is normally a child of that root, but is scheduled explicitly because {@code JK_STORE_DIR}
     * can relocate it out of the data root, and because it is wiped whole-tree
     * — including {@code store/lib} — by the engine-hosted {@code jk storage nuke} path, which the
     * {@link #addRow} guards would otherwise refuse.
     *
     * <p>The remaining children do go through those guards, so the live engine jar
     * ({@code <data>/lib}) and the forge/repo credential stores survive.
     */
    private static void planData(JkDirs dirs, Map<Path, PurgeRow> byPath, Guards guards) {
        Path store = abs(dirs.storeDir());
        if (store != null) {
            byPath.putIfAbsent(store, new PurgeRow(store, "Artifact store", Target.DATA, true));
        }
        Path data = abs(dirs.dataDir());
        if (data == null) return;
        List<Path> children;
        try (Stream<Path> stream = Files.list(data)) {
            children = stream.map(SelfNukeCommand::abs).sorted().toList();
        } catch (IOException e) {
            return; // no data root, or unreadable — the store row above is the whole plan
        }
        for (Path child : children) {
            if (child.equals(store)) continue;
            addRow(byPath, child, Target.DATA.what, Target.DATA, guards);
        }
    }

    /**
     * Config nuke targets the config root ({@code ~/.config/jk}, {@code $JK_HOME/config}, or
     * {@code JK_CONFIG_DIR}) — including per-app {@code <bin>/config.toml} trees. If that root
     * somehow coincides with the data root, only the global {@code config.toml} file is scheduled
     * so store/lib are not wiped.
     */
    private static void planConfig(JkDirs dirs, Map<Path, PurgeRow> byPath, Guards guards) {
        Path configFile = abs(dirs.userConfigFilePath());
        Path configDir = abs(dirs.configDir());
        Path data = abs(dirs.dataDir());
        boolean dirIsUmbrella = configDir == null || configDir.equals(data);
        if (!dirIsUmbrella && addRow(byPath, configDir, Target.CONFIG.what, Target.CONFIG, guards)) {
            return;
        }
        addRow(byPath, configFile, Target.CONFIG.what, Target.CONFIG, guards);
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
     * Schedule a row unless it would touch a guarded tree: equal to, inside, or an <em>ancestor</em>
     * of bin, jdks, the product-lib engine, {@code store/lib} (installed tools), or the forge/repo
     * credential stores. Comparisons also run on real paths so
     * a guarded dir reached through a symlink (e.g. {@code ~/.local/bin -> <data>/bin}) stays safe.
     *
     * @return whether the row was scheduled
     */
    private static boolean addRow(Map<Path, PurgeRow> byPath, Path path, String what, Target target, Guards guards) {
        return addRow(byPath, path, what, target, guards, false);
    }

    /** {@link #addRow} for a row the shared cache/storage nukes wipe; see {@link PurgeRow}. */
    private static boolean addRow(
            Map<Path, PurgeRow> byPath, Path path, String what, Target target, Guards guards, boolean delegated) {
        if (path == null) return false;
        Path norm = path.toAbsolutePath().normalize();
        if (conflicts(norm, guards.bin())
                || conflicts(norm, guards.jdks())
                || conflicts(norm, guards.productLib())
                || conflicts(norm, guards.lib())
                || conflicts(norm, guards.credentials())
                || conflicts(norm, guards.repoCredentials())) {
            return false;
        }
        byPath.putIfAbsent(norm, new PurgeRow(norm, what, target, delegated));
        return true;
    }

    private static void printPlan(
            List<PurgeRow> rows, JkDirs dirs, Set<Target> selected, boolean wantCache, boolean wantData) {
        List<String> headers = List.of("Path to Delete", "What");
        List<List<String>> tableRows = new ArrayList<>();
        for (PurgeRow r : rows) {
            tableRows.add(List.of(pathStyled(r.path()), r.what()));
        }
        if (wantCache) {
            tableRows.add(List.of(pathStyled(dirs.cacheDir()), "Cache tier (jk cache nuke)"));
        }
        if (wantData) {
            tableRows.add(List.of(pathStyled(dirs.storeDir()), "Artifact store (jk storage nuke)"));
        }
        CommandWedge.envelopeStart();
        for (String line : Table.renderWarning("JumpKick Data Nuke", headers, tableRows)) {
            CliOutput.out(line);
        }
        if (wantData) {
            CliOutput.out("  Kept:  forge/repo credentials  (remove via jk repo logout)");
            CliOutput.out("  Kept:  " + pathStyled(dirs.productLibDir().resolve("jk-engine")) + "  (live engine)");
        }
        CliOutput.out("  Kept:  " + pathStyled(dirs.binDirectory()) + "  (PATH binaries)");
        CliOutput.out("  Kept:  " + pathStyled(dirs.jdksDir()) + "  (managed JDKs)");
        CliOutput.out();
    }

    private static boolean confirmPrompt() {
        Theme t = Theme.active();
        String bang = Theme.colorize(Glyphs.BANG, t.warning());
        return Confirm.of(bang + " Nuke this JumpKick data?", false).ask();
    }

    /** Home-relative display form, painted with the theme path color. */
    static String pathStyled(Path path) {
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
    private static boolean conflicts(Path candidate, Path guarded) {
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

    private static Path abs(Path p) {
        return p == null ? null : p.toAbsolutePath().normalize();
    }
}
