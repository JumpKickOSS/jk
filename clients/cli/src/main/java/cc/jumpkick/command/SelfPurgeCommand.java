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
import java.util.Set;

/**
 * {@code jk self purge} — wipe JumpKick-owned product data (cache, store, state, versions, config)
 * while leaving PATH install binaries and managed JDKs untouched.
 *
 * <p><strong>Never touches the bin directory</strong> ({@code ~/.local/bin} / {@code JK_BIN_DIR}):
 * neither {@code jk}/{@code jkx} nor any other executables. Only product data roots that JumpKick
 * owns are deleted.
 *
 * <p>Confirmation required unless the hidden global {@code -y}/{@code --yes} is set.
 */
public final class SelfPurgeCommand implements CliCommand {

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
        if (wipeRoots.isEmpty()) {
            CommandWedge.printOk("Self", "Nothing to purge — no JumpKick data directories found.");
            return Exit.SUCCESS;
        }

        if (!confirm(dirs, wipeRoots)) {
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
                            + "). PATH binaries and JDKs untouched.");
        }
        return Exit.SUCCESS;
    }

    private static boolean confirm(JkDirs dirs, List<Path> wipeRoots) {
        Theme t = Theme.active();
        String bang = Theme.colorize(Glyphs.BANG, t.warning());
        CliOutput.out();
        CliOutput.out(bang
                + " "
                + Theme.colorize(
                        "This permanently deletes all JumpKick product data on this machine.", t.errorLabel()));
        CliOutput.out("  Cache, store/CAS, state, versions, and config only.");
        CliOutput.out("  Untouched: PATH install dir (" + dirs.binDirectory() + ")");
        CliOutput.out("  Untouched: managed JDKs under " + dirs.jdksDir());
        CliOutput.out("  Paths:");
        for (Path p : wipeRoots) {
            if (Files.exists(p)) CliOutput.out("    " + p);
        }
        return Confirm.of(bang + " Purge all JumpKick data?", false).ask();
    }

    /**
     * Product trees JumpKick owns and may delete. Never includes the PATH bin directory or the JDK
     * install root (or anything under them). Deduped so nested paths under a selected root are not
     * listed twice.
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
        // Config: whole platform config dir (~/.config/jk), or just config.toml when configDir is a
        // parent of bin/jdks (JK_HOME umbrella) — never wipe that parent wholesale.
        Path configFile = abs(dirs.userConfigFilePath());
        Path configDir = abs(dirs.configDir());
        if (configDir != null
                && (configDir.equals(bin)
                        || configDir.equals(jdks)
                        || isAncestor(configDir, bin)
                        || isAncestor(configDir, jdks))) {
            if (Files.isRegularFile(configFile)) roots.add(configFile);
        } else if (configDir != null && Files.isDirectory(configDir)) {
            addIfSafe(roots, configDir, bin, jdks);
        } else if (configFile != null && Files.isRegularFile(configFile)) {
            roots.add(configFile);
        }
        // Drop roots that are strictly under another selected root.
        List<Path> list = new ArrayList<>(roots);
        list.removeIf(p -> list.stream().anyMatch(o -> !o.equals(p) && isAncestor(o, p)));
        list.sort((a, b) -> Integer.compare(b.getNameCount(), a.getNameCount()));
        return list;
    }

    private static void addIfSafe(Set<Path> roots, Path candidate, Path bin, Path jdks) {
        if (candidate == null) return;
        // Never the bin/jdks roots themselves.
        if (candidate.equals(bin) || candidate.equals(jdks)) return;
        // Never a parent of bin/jdks (would wipe shared trees).
        if (isAncestor(candidate, bin) || isAncestor(candidate, jdks)) return;
        // Never anything under bin/jdks (PATH install dir is off-limits entirely).
        if (isAncestor(bin, candidate) || isAncestor(jdks, candidate) || candidate.equals(bin) || candidate.equals(jdks))
            return;
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
