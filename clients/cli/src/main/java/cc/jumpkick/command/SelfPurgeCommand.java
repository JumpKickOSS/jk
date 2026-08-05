// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.PathDisplay;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.BoxTable;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.PathUtil;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code jk self purge} — wipe JumpKick product data while leaving the PATH install binaries,
 * managed JDKs, the <strong>active engine version</strong>, and <strong>latest plugin workers</strong>
 * ({@code store/lib/}) intact.
 *
 * <p>Targets (stackable; default {@code --all}):
 *
 * <ul>
 *   <li>{@code --cache} — cache tier (action index + cache CAS + format stamps)
 *   <li>{@code --store} — artifact store CAS, repo mirrors, store catalogs, shell completions, old
 *       {@code versions/*} (keeps active version + {@code store/lib})
 *   <li>{@code --state} — engine sockets, AOT, builds, scratch tmp
 *   <li>{@code --config} — user config
 *   <li>{@code --all} — every target above (default when none are named)
 * </ul>
 *
 * <p><strong>Never touches the bin directory</strong> ({@code ~/.local/bin} / {@code JK_BIN_DIR}),
 * and never removes forge/repo credentials — logging out is {@code jk repo logout}'s job.
 */
public final class SelfPurgeCommand implements CliCommand {

    /** Selectable purge scopes. */
    enum Target {
        CACHE("Cache tier"),
        STORE("Artifact store, old engines"),
        STATE("Engine sockets, AOT, builds"),
        CONFIG("User config");

        final String what;

        Target(String what) {
            this.what = what;
        }
    }

    /** One display/delete row for the confirm table. */
    record PurgeRow(Path path, String what, Target target) {}

    /**
     * Paths a purge must never remove — nor remove a parent of. Resolved once per plan from the
     * <em>same</em> {@link JkDirs} the rows come from, so synthetic test environments guard
     * consistently.
     */
    record Guards(Path bin, Path jdks, Path activeVersion, Path lib) {
        static Guards of(JkDirs dirs) {
            Path versions = abs(dirs.versionsDir());
            return new Guards(
                    abs(dirs.binDirectory()),
                    abs(dirs.jdksDir()),
                    versions == null ? null : versions.resolve(Jk.VERSION).normalize(),
                    abs(dirs.libDir()));
        }
    }

    @Override
    public String name() {
        return "purge";
    }

    @Override
    public String description() {
        return "Wipe data/state (keeps active engine, plugins, PATH, JDKs)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.flag("Print what would be removed; touch nothing.", "--dry-run"),
                Opt.flag("Purge every target (default when none named).", "--all"),
                Opt.flag("Purge the cache tier only (action outputs).", "--cache"),
                Opt.flag("Purge store + old engines (keeps plugins, logins).", "--store"),
                Opt.flag("Purge engine state, AOT caches, builds, and tmp.", "--state"),
                Opt.flag("Purge user config.", "--config"));
    }

    @Override
    public int run(Invocation in) {
        GlobalOptions.from(in);
        boolean dryRun = in.isSet("dry-run");
        Set<Target> selected = selectedTargets(in);
        JkDirs dirs = JkDirs.current();

        List<PurgeRow> rows = plan(dirs, selected);
        List<PurgeRow> existing =
                rows.stream().filter(r -> Files.exists(r.path(), LinkOption.NOFOLLOW_LINKS)).toList();
        if (existing.isEmpty()) {
            CommandWedge.printOk("Self", "Nothing to purge — selected JumpKick data not found.");
            return Exit.SUCCESS;
        }

        printPlan(existing, dirs, selected);
        // Dry run deletes nothing — never gate it behind the destructive prompt (which would
        // also abort with exit 1 on non-TTY stdin).
        if (!dryRun && !confirmPrompt()) {
            CommandWedge.printFail("Self", "Purge aborted.");
            return 1;
        }

        // Engines hold sockets under state and read/write the store mid-build — stop them
        // before deleting either tree, and say so when one refuses to die.
        if (!dryRun && (selected.contains(Target.STATE) || selected.contains(Target.STORE))) {
            try {
                for (EngineFleet.StopResult r : EngineFleet.stopAll(true)) {
                    if (r.outcome() == EngineFleet.Outcome.SURVIVED) {
                        Theme t = Theme.active();
                        CliOutput.err(Theme.colorize(Glyphs.BANG, t.warning())
                                + " Engine pid "
                                + r.member().pid()
                                + " did not stop; purging around it may leave it orphaned.");
                    }
                }
            } catch (RuntimeException ignored) {
                // best-effort
            }
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
            CommandWedge.printOk("Self", "Dry run: would purge " + removed + " path" + (removed == 1 ? "" : "s") + ".");
        } else {
            CommandWedge.printOk(
                    "Self",
                    "Purged "
                            + removed
                            + " path"
                            + (removed == 1 ? "" : "s")
                            + ". Kept: active engine "
                            + Jk.VERSION
                            + ", store/lib plugins, PATH, JDKs.");
        }
        return Exit.SUCCESS;
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
     * Build ordered purge rows for the selected targets. Never includes bin, JDKs, the active
     * {@code versions/<Jk.VERSION>/} tree, or {@code store/lib/} (latest plugin workers).
     */
    static List<PurgeRow> plan(JkDirs dirs, Set<Target> selected) {
        Guards guards = Guards.of(dirs);
        Map<Path, PurgeRow> byPath = new LinkedHashMap<>();

        if (selected.contains(Target.CACHE)) {
            addRow(byPath, abs(dirs.cacheDir()), "Cache tier", Target.CACHE, guards);
        }

        if (selected.contains(Target.STORE)) {
            planStore(dirs, byPath, guards);
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
     * Store purge is surgical: wipe CAS + mirrors + non-active engine versions; keep {@code
     * versions/<active>/} and {@code store/lib/} (latest workers).
     */
    private static void planStore(JkDirs dirs, Map<Path, PurgeRow> byPath, Guards guards) {
        String active = Jk.VERSION;
        Path versions = abs(dirs.versionsDir());
        if (versions != null && Files.isDirectory(versions)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(versions)) {
                for (Path p : stream) {
                    String name = p.getFileName().toString();
                    if (name.startsWith(".")) {
                        // Stale .0.9.0.lock etc. — safe to drop. The active version's lock file
                        // stays: VersionStore.materialize holds a flock on it, and unlinking a
                        // held lock lets a racing materializer lock a fresh inode (two winners).
                        if (name.equals("." + active + ".lock")) continue;
                        if (Files.isRegularFile(p)) {
                            addRow(byPath, p, "Version lock/marker", Target.STORE, guards);
                        }
                        continue;
                    }
                    if (!Files.isDirectory(p)) continue;
                    if (name.equals(active)) continue; // keep active engine + client materialization
                    addRow(byPath, p, "Old engine version " + name, Target.STORE, guards);
                }
            } catch (IOException ignored) {
            }
        }

        Path store = abs(dirs.storeDir());
        if (store != null && Files.isDirectory(store)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(store)) {
                for (Path p : stream) {
                    String name = p.getFileName().toString();
                    // Latest plugin/tool workers — do not remove (jk would need reinstallLocal).
                    if ("lib".equals(name)) continue;
                    // Feed catalogs re-download on idle warmup; drop them with store purge.
                    String what =
                            switch (name) {
                                case "sha256" -> "CAS blobs";
                                case "repos" -> "Repo mirrors";
                                case "jdks.json" -> "JDK catalog cache";
                                case "libs.global.toml" -> "Library registry cache";
                                default -> "Store: " + name;
                            };
                    addRow(byPath, p, what, Target.STORE, guards);
                }
            } catch (IOException ignored) {
            }
        }

        // Other data-dir siblings (e.g. completions), but never versions/store handled above, never
        // the data dir root itself (would wipe kept subtrees).
        Path data = abs(dirs.dataDir());
        if (data != null && Files.isDirectory(data)) {
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(data)) {
                for (Path p : stream) {
                    String name = p.getFileName().toString();
                    if ("versions".equals(name) || "store".equals(name)) continue;
                    if (name.startsWith(".")) continue;
                    // Auth outlives every purge target: removing logins is jk repo logout /
                    // jk forge logout territory, never implied by "CAS/repos and old engines".
                    if ("credentials".equals(name) || "repo-credentials".equals(name)) continue;
                    String what =
                            "completions".equals(name)
                                    ? "Shell completions (re-run jk activate)"
                                    : "Data: " + name;
                    addRow(byPath, p, what, Target.STORE, guards);
                }
            } catch (IOException ignored) {
            }
        }
    }

    /**
     * Config purge targets the dedicated platform config dir ({@code ~/.config/jk}) when there is
     * one. Under {@code JK_HOME} the "config dir" is the umbrella root shared with versions, store,
     * and state — deleting it would wipe every kept subtree — so only {@code config.toml} itself is
     * scheduled. Same when the config dir coincides with the product home/data root for any other
     * reason.
     */
    private static void planConfig(JkDirs dirs, Map<Path, PurgeRow> byPath, Guards guards) {
        Path configFile = abs(dirs.userConfigFilePath());
        Path configDir = abs(dirs.configDir());
        Path home = abs(dirs.homeDir());
        Path data = abs(dirs.dataDir());
        boolean dirIsUmbrella =
                configDir == null || configDir.equals(home) || configDir.equals(data);
        if (!dirIsUmbrella && addRow(byPath, configDir, Target.CONFIG.what, Target.CONFIG, guards)) {
            return;
        }
        addRow(byPath, configFile, Target.CONFIG.what, Target.CONFIG, guards);
    }

    /** Absolute paths selected for deletion (for tests). */
    static List<Path> wipeRoots(JkDirs dirs) {
        return plan(dirs, EnumSet.allOf(Target.class)).stream().map(PurgeRow::path).toList();
    }

    static List<Path> wipeRoots(JkDirs dirs, Set<Target> selected) {
        return plan(dirs, selected).stream().map(PurgeRow::path).toList();
    }

    /**
     * Schedule a row unless it would touch a guarded tree: equal to, inside, or an <em>ancestor</em>
     * of bin, jdks, the active version, or {@code store/lib}. Comparisons also run on real paths so
     * a guarded dir reached through a symlink (e.g. {@code ~/.local/bin -> <data>/bin}) stays safe.
     *
     * @return whether the row was scheduled
     */
    private static boolean addRow(
            Map<Path, PurgeRow> byPath, Path path, String what, Target target, Guards guards) {
        if (path == null) return false;
        Path norm = path.toAbsolutePath().normalize();
        if (conflicts(norm, guards.bin())
                || conflicts(norm, guards.jdks())
                || conflicts(norm, guards.activeVersion())
                || conflicts(norm, guards.lib())) {
            return false;
        }
        byPath.putIfAbsent(norm, new PurgeRow(norm, what, target));
        return true;
    }

    private static void printPlan(List<PurgeRow> rows, JkDirs dirs, Set<Target> selected) {
        List<String> headers = List.of("Path to Delete", "What");
        List<List<String>> tableRows = new ArrayList<>();
        for (PurgeRow r : rows) {
            tableRows.add(List.of(pathStyled(r.path()), r.what()));
        }
        CommandWedge.envelopeStart();
        for (String line : BoxTable.renderWarning("JumpKick Data Purge", headers, tableRows)) {
            CliOutput.out(line);
        }
        if (selected.contains(Target.STORE)) {
            Path active = dirs.versionsDir().resolve(Jk.VERSION);
            Path lib = dirs.libDir();
            CliOutput.out("  Kept:  "
                    + pathStyled(active)
                    + "  and  "
                    + pathStyled(lib)
                    + "  (latest plugins)");
            CliOutput.out("  Kept:  forge/repo credentials  (remove via jk repo logout)");
        }
        CliOutput.out("  Kept:  " + pathStyled(dirs.binDirectory()) + "  (PATH binaries)");
        CliOutput.out("  Kept:  " + pathStyled(dirs.jdksDir()) + "  (managed JDKs)");
        CliOutput.out();
    }

    private static boolean confirmPrompt() {
        Theme t = Theme.active();
        String bang = Theme.colorize(Glyphs.BANG, t.warning());
        return Confirm.of(bang + " Purge this JumpKick data?", false).ask();
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
