// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.theme.Theme;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * {@code jk self purge} — wipe all JumpKick product data (cache, store, state, versions, config)
 * while keeping the PATH binaries ({@code jk}/{@code jkx}) and managed JDKs.
 *
 * <p>Confirmation required unless the hidden global {@code -y}/{@code --yes} is set.
 */
public final class SelfPurgeCommand implements CliCommand {

    /** Filenames under the bin dir that survive purge (platform extensions included). */
    private static final Set<String> KEEP_BIN_NAMES = Set.of("jk", "jkx", "jk.exe", "jkx.exe", "jk.bat", "jkx.bat");

    @Override
    public String name() {
        return "purge";
    }

    @Override
    public String description() {
        return "Wipe all data/state (keeps jk/jkx and JDKs)";
    }

    @Override
    public List<Opt> options() {
        return List.of(Opt.flag("Print what would be removed; touch nothing.", "--dry-run"));
    }

    @Override
    public int run(Invocation in) {
        GlobalOptions.from(in); // install session + Confirm.assumeYes
        boolean dryRun = in.isSet("dry-run");
        JkDirs dirs = JkDirs.current();

        List<Path> wipeRoots = wipeRoots(dirs);
        List<Path> binExtras = binEntriesToRemove(dirs.binDirectory());

        if (wipeRoots.isEmpty() && binExtras.isEmpty()) {
            CommandWedge.printOk("Self", "Nothing to purge — no JumpKick data directories found.");
            return Exit.SUCCESS;
        }

        if (!confirm(dirs, wipeRoots, binExtras)) {
            CommandWedge.printFail("Self", "Purge aborted.");
            return 1;
        }

        // Engines hold sockets/files under state — stop them before deleting.
        if (!dryRun) {
            try {
                EngineFleet.stopAll(true);
            } catch (RuntimeException ignored) {
                // best-effort; delete still proceeds
            }
        }

        long removed = 0;
        List<String> failures = new ArrayList<>();
        for (Path root : wipeRoots) {
            if (!Files.exists(root)) continue;
            if (dryRun) {
                CliOutput.out("  would remove " + root);
                removed++;
                continue;
            }
            try {
                PathUtil.deleteRecursivelyOrThrow(root);
                removed++;
            } catch (IOException e) {
                failures.add(root + " (" + e.getMessage() + ")");
            }
        }
        for (Path p : binExtras) {
            if (!Files.exists(p)) continue;
            if (dryRun) {
                CliOutput.out("  would remove " + p);
                removed++;
                continue;
            }
            try {
                Files.deleteIfExists(p);
                removed++;
            } catch (IOException e) {
                failures.add(p + " (" + e.getMessage() + ")");
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
                    "Purged JumpKick data ("
                            + removed
                            + " path"
                            + (removed == 1 ? "" : "s")
                            + "). Binaries and JDKs kept.");
        }
        return Exit.SUCCESS;
    }

    private static boolean confirm(JkDirs dirs, List<Path> wipeRoots, List<Path> binExtras) {
        Theme t = Theme.active();
        String bang = Theme.colorize(Glyphs.BANG, t.warning());
        CliOutput.out();
        CliOutput.out(bang
                + " "
                + Theme.colorize(
                        "This permanently deletes all JumpKick product data on this machine.", t.errorLabel()));
        CliOutput.out("  Cache, store/CAS, state, versions, config, and non-jk tools under the bin dir.");
        CliOutput.out("  Kept:  " + dirs.binDirectory().resolve("jk") + " (+ jkx)");
        CliOutput.out("  Kept:  managed JDKs under " + dirs.jdksDir());
        CliOutput.out("  Paths:");
        for (Path p : wipeRoots) {
            if (Files.exists(p)) CliOutput.out("    " + p);
        }
        for (Path p : binExtras) {
            if (Files.exists(p)) CliOutput.out("    " + p);
        }
        return Confirm.of(bang + " Purge all JumpKick data?", false).ask();
    }

    /**
     * Product trees to delete entirely. Never includes the bin dir or the JDK install root.
     * Deduped and sorted longest-first so parents are not deleted before children we also list
     * (walk-delete handles children either way).
     */
    static List<Path> wipeRoots(JkDirs dirs) {
        Path bin = abs(dirs.binDirectory());
        Path jdks = abs(dirs.jdksDir());
        LinkedHashSet<Path> roots = new LinkedHashSet<>();
        addIfSafe(roots, abs(dirs.cacheDir()), bin, jdks);
        addIfSafe(roots, abs(dirs.storeDir()), bin, jdks);
        addIfSafe(roots, abs(dirs.stateDir()), bin, jdks);
        addIfSafe(roots, abs(dirs.dataDir()), bin, jdks);
        addIfSafe(roots, abs(dirs.versionsDir()), bin, jdks);
        addIfSafe(roots, abs(dirs.tmpDir()), bin, jdks);
        addIfSafe(roots, abs(dirs.buildsDir()), bin, jdks);
        // Config: whole platform config dir, or just config.toml when configDir == JK_HOME / data.
        Path configFile = abs(dirs.userConfigFilePath());
        Path configDir = abs(dirs.configDir());
        if (configDir.equals(bin) || configDir.equals(jdks) || isAncestor(configDir, bin) || isAncestor(configDir, jdks)) {
            // Never wipe a parent of bin/jdks — only the config file.
            if (Files.isRegularFile(configFile)) roots.add(configFile);
        } else if (Files.isDirectory(configDir)) {
            addIfSafe(roots, configDir, bin, jdks);
        } else if (Files.isRegularFile(configFile)) {
            roots.add(configFile);
        }
        // Drop roots that are strictly under another selected root (avoid double-count noise).
        List<Path> list = new ArrayList<>(roots);
        list.removeIf(p -> list.stream().anyMatch(o -> !o.equals(p) && isAncestor(o, p)));
        list.sort((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()));
        return list;
    }

    /** Extra tool launchers under bin (not jk/jkx) — removed individually so PATH binaries remain. */
    static List<Path> binEntriesToRemove(Path binDir) {
        List<Path> out = new ArrayList<>();
        if (binDir == null || !Files.isDirectory(binDir)) return out;
        try (var stream = Files.list(binDir)) {
            for (Path p : stream.toList()) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (KEEP_BIN_NAMES.contains(name)) continue;
                // Only remove entries that look like jk-installed tools (symlinks or plain files).
                if (Files.isRegularFile(p) || Files.isSymbolicLink(p)) out.add(p);
            }
        } catch (IOException ignored) {
        }
        return out;
    }

    private static void addIfSafe(Set<Path> roots, Path candidate, Path bin, Path jdks) {
        if (candidate == null) return;
        if (candidate.equals(bin) || candidate.equals(jdks)) return;
        if (isAncestor(candidate, bin) || isAncestor(candidate, jdks)) return;
        if (isAncestor(bin, candidate) || isAncestor(jdks, candidate)) return;
        roots.add(candidate);
    }

    /** True when {@code ancestor} is a proper path prefix of {@code child}. */
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
