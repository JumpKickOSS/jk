// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.InstalledTool;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.discovery.SymlinkProvisioner;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.terminal.posix.PosixPasswd;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk doctor} — host health checklist. Prints a wedge header plus one row per subsystem
 * (engine, dirs, jdk, lock, shell, tools) and a summary. {@code --output json} emits machine output.
 */
public final class DoctorCommand implements CliCommand {

    @Override
    public String name() {
        return "doctor";
    }

    @Override
    public String description() {
        return "Check host health (engine, cache, JDKs, lock, shell)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tools install root. Default: $JK_STORE_DIR/tools.", "--tools-dir")
                        .hide(),
                // Kept under HelpWidthTest's 78-column budget: 20 columns go to the indent and the
                // flag itself, so this string has 58 to spend.
                Opt.flag("Fingerprint each linked install and report drift", "--verify-linked"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path toolsDir = in.value("tools-dir").map(Path::of).orElse(null);
        boolean verifyLinked = in.isSet("verify-linked");
        Path root = toolsDir != null ? toolsDir : JkDirs.tools();

        // Collect checks first so JSON and human share the same facts. Tools are scanned (and
        // repaired — broken links unlinked, fingerprints written) exactly once, here, so
        // `--output json` performs the same repair the human view does instead of only reporting it.
        Check engine = checkEngine();
        Check cache = checkDirs();
        Check jdk = checkJdk();
        Check lock = checkLock();
        Check shell = checkShell();
        List<ToolRow> toolRows;
        String toolsError = null;
        try {
            toolRows = scanTools(root, verifyLinked);
        } catch (IOException e) {
            toolRows = List.of();
            toolsError = e.getMessage();
        }
        int healthy = 0, pruned = 0, verified = 0, drifted = 0, firstSeen = 0, empty = 0;
        for (ToolRow row : toolRows) {
            switch (row.kind()) {
                case PRUNED -> pruned++;
                case VERIFIED -> {
                    healthy++;
                    verified++;
                }
                case DRIFTED -> {
                    healthy++;
                    drifted++;
                }
                case FIRST_SEEN -> {
                    healthy++;
                    firstSeen++;
                }
                case EMPTY -> {
                    healthy++;
                    empty++;
                }
                case LINKED, OK -> healthy++;
            }
        }

        boolean hasFail = engine.status == Status.FAIL
                || cache.status == Status.FAIL
                || jdk.status == Status.FAIL
                || lock.status == Status.FAIL; // tools alone (including a scan error) never fails

        if (global.outputIsJson()) {
            String json = "{"
                    + "\"engine\":" + checkJson(engine) + ","
                    + "\"cache\":" + checkJson(cache) + ","
                    + "\"jdk\":" + checkJson(jdk) + ","
                    + "\"lock\":" + checkJson(lock) + ","
                    + "\"shell\":" + checkJson(shell) + ","
                    + "\"tools\":{\"healthy\":" + healthy + ",\"pruned\":" + pruned + ",\"verified\":" + verified
                    + ",\"drifted\":" + drifted + ",\"firstSeen\":" + firstSeen + ",\"empty\":" + empty
                    + ",\"error\":" + Jsonl.quote(toolsError) + "}"
                    + "}";
            CliOutput.out(json);
            return hasFail ? 1 : 0;
        }

        Theme t = Theme.active();
        CommandWedge.envelopeStart();
        CliOutput.out(CommandWedge.menu("Doctor"));

        printCheck(engine, t);
        printCheck(cache, t);
        printCheck(jdk, t);
        printCheck(lock, t);
        printCheck(shell, t);

        if (toolsError != null) {
            CliOutput.out(Theme.colorize("warn:    ", t.warning()) + Theme.colorize("tools", t.cyan())
                    + " — probe failed: " + toolsError);
        } else if (toolRows.isEmpty()) {
            // If no tools at all, emit a row so the checklist looks complete.
            CliOutput.out(Theme.colorize("ok:      ", t.completedStep()) + " tools — no installs found");
        } else {
            for (ToolRow row : toolRows) {
                String toolName = Theme.colorize(row.tool().slug(), t.cyan());
                String label = toolName + " " + row.installed().version();
                switch (row.kind()) {
                    case PRUNED ->
                        CliOutput.out(Theme.colorize("pruned:  ", t.warning()) + " " + label + " (link target missing: "
                                + Theme.colorize(row.detail(), t.path()) + ")");
                    case VERIFIED ->
                        CliOutput.out(Theme.colorize("verified:", t.completedStep()) + " " + label + " (sha256-tree="
                                + short12(row.detail()) + "…, unchanged)");
                    case FIRST_SEEN ->
                        CliOutput.out(Theme.colorize("recorded:", t.completedStep()) + " " + label + " (sha256-tree="
                                + short12(row.detail()) + "…, first fingerprint)");
                    case DRIFTED ->
                        CliOutput.out(Theme.colorize("drifted: ", t.warning()) + " " + label + " — link target changed "
                                + Theme.colorize(row.detail(), t.path()) + " (baseline updated)");
                    case EMPTY ->
                        CliOutput.out(Theme.colorize("warn:    ", t.warning()) + " " + label
                                + " — link target holds no files; there is nothing to fingerprint ("
                                + Theme.colorize(row.detail(), t.path()) + ")");
                    case LINKED ->
                        CliOutput.out("linked:   " + label
                                + " " + Theme.colorize("→", t.darkGray()) + " "
                                + Theme.colorize(row.detail(), t.path()));
                    case OK -> CliOutput.out(Theme.colorize("ok:      ", t.completedStep()) + " " + label);
                }
            }
        }

        CliOutput.out(Theme.paint("---", t.darkGray()));
        String summary = Theme.colorize(String.valueOf(healthy), t.focused())
                + " healthy"
                + (pruned > 0 ? ", " + Theme.colorize(String.valueOf(pruned), t.focused()) + " pruned" : "")
                + (verified > 0 ? ", " + Theme.colorize(String.valueOf(verified), t.focused()) + " unchanged" : "")
                + (firstSeen > 0 ? ", " + Theme.colorize(String.valueOf(firstSeen), t.focused()) + " recorded" : "")
                + (drifted > 0 ? ", " + Theme.colorize(String.valueOf(drifted), t.focused()) + " drifted" : "")
                + (empty > 0 ? ", " + Theme.colorize(String.valueOf(empty), t.focused()) + " empty" : "");
        // Append subsystem warnings to summary when any check failed.
        if (hasFail) {
            summary += Theme.colorize(" — issues found", t.warning());
        }
        CliOutput.out(summary);
        return hasFail ? 1 : 0;
    }

    // ---- checks ----

    enum Status {
        OK,
        WARN,
        FAIL
    }

    record Check(Status status, String label, String detail) {}

    /**
     * The verdicts a tool install can carry. The four fingerprint verdicts are reachable only under
     * {@code --verify-linked}: {@link #VERIFIED} matches the stored baseline, {@link #DRIFTED}
     * does not, {@link #FIRST_SEEN} had no baseline to compare against, and {@link #EMPTY} means the
     * digest was {@link JdkFingerprint#EMPTY_TREE} — nothing was hashed, which is a finding, not a
     * fingerprint.
     */
    private enum ToolRowKind {
        PRUNED,
        VERIFIED,
        DRIFTED,
        FIRST_SEEN,
        EMPTY,
        LINKED,
        OK
    }

    /** One installed/linked tool, after {@link #scanTools} has already applied any repair. */
    private record ToolRow(
            BuildTool tool,
            InstalledTool installed,
            ToolRowKind kind,
            @Nullable String detail) {}

    private static Check checkEngine() {
        EnginePaths.Paths paths = EnginePaths.current();
        Path socket = EnginePaths.activeSocket(paths);
        Optional<EngineProbe.Status> st = EngineProbe.status(socket);
        if (st.isEmpty()) {
            // A socket that accepts connections but never answers status is a wedged engine, not
            // an absent one — `jk build` would hang on it instead of lazy-starting a fresh one.
            if (EngineProbe.reachable(socket)) {
                return new Check(
                        Status.FAIL,
                        "engine",
                        "listening but not answering status — likely wedged; try `jk engine stop`");
            }
            return new Check(Status.WARN, "engine", "not running (lazy start on next build)");
        }
        EngineProbe.Status s = st.get();
        String detail = "running pid " + s.pid() + " · " + s.version() + " · up "
                + formatUptime((System.currentTimeMillis() - s.startedAtMillis()) / 1000);
        if (s.httpError() != null && !s.httpError().isBlank()) {
            return new Check(Status.WARN, "engine", detail + " · http: " + s.httpError());
        }
        return new Check(Status.OK, "engine", detail);
    }

    private static Check checkDirs() {
        Path cache = JkDirs.cache();
        Path store = JkDirs.store();
        Path state = JkDirs.state();
        JkCacheConfig cfg = JkCacheConfig.resolve();
        List<String> problems = new ArrayList<>();
        if (!Files.isDirectory(cache)) problems.add("cache missing: " + cache);
        Path storeParent = store.getParent();
        Path stateParent = state.getParent();
        if (storeParent == null || !Files.isDirectory(storeParent)) problems.add("store parent missing");
        if (stateParent == null || !Files.isDirectory(stateParent)) problems.add("state parent missing");
        if (!problems.isEmpty()) return new Check(Status.FAIL, "dirs", String.join("; ", problems));
        String detail = "cache " + cache + " · store " + store + " · " + JkCacheConfig.formatGb(cfg.maxCacheSizeGb())
                + "G cache budget · store unbudgeted";
        return new Check(Status.OK, "dirs", detail);
    }

    private static Check checkJdk() {
        try {
            Path jdksDir = JkDirs.jdks();
            long count = Files.isDirectory(jdksDir) ? Files.list(jdksDir).count() : 0;
            String javaHome = System.getenv("JAVA_HOME");
            String detail = count + " installs under " + jdksDir + (javaHome != null ? " · JAVA_HOME=" + javaHome : "");
            return new Check(Status.OK, "jdk", detail);
        } catch (IOException e) {
            return new Check(Status.WARN, "jdk", "probe failed: " + e.getMessage());
        }
    }

    static Check checkShell() {
        Path home = Path.of(System.getProperty("user.home", ""));
        return checkShell(
                home,
                Shell.live(System.getenv("SHELL"), PosixPasswd.loginShell().orElse(null), parentShellCommand()));
    }

    /**
     * Login and current shells only — not every rc greedy-activate would touch. Missing block is a
     * WARN; doctor never writes profiles.
     */
    static Check checkShell(Path home, List<Shell> live) {
        if (live.isEmpty()) {
            return new Check(Status.OK, "shell", "no login or current shell to check");
        }
        List<String> hooked = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (Shell shell : live) {
            String display = shell.rcFileDisplay();
            if (hasInstallerBlock(shell.rcFile(home))) {
                hooked.add(display);
            } else {
                missing.add(display);
            }
        }
        if (missing.isEmpty()) {
            return new Check(Status.OK, "shell", "hooks in " + String.join(", ", hooked));
        }
        String verb = missing.size() == 1 ? " has no jk block" : " have no jk block";
        String detail = String.join(", ", missing) + verb + " — run `jk activate`";
        if (!hooked.isEmpty()) {
            detail = "hooks in " + String.join(", ", hooked) + "; " + detail;
        }
        return new Check(Status.WARN, "shell", detail);
    }

    private static boolean hasInstallerBlock(Path rcFile) {
        try {
            return Files.isRegularFile(rcFile) && ShellInstallerBlock.present(Files.readString(rcFile));
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * First ancestor that is not jk/java. {@code sh}/{@code dash} are filtered later in
     * {@link Shell#live} so a script wrapper is not treated as an interactive bash.
     */
    static @Nullable String parentShellCommand() {
        try {
            Optional<ProcessHandle> p = ProcessHandle.current().parent();
            int hops = 0;
            while (p.isPresent() && hops++ < 8) {
                ProcessHandle handle = p.get();
                String cmd = handle.info().command().orElse("");
                if (cmd.isBlank() || processWrapper(cmd)) {
                    p = handle.parent();
                    continue;
                }
                return cmd;
            }
        } catch (RuntimeException ignored) {
            // ProcessHandle is best-effort on some hosts
        }
        return null;
    }

    private static final Set<String> PROCESS_WRAPPERS =
            Set.of("java", "javaw", "jk", "jkx", "jk.cmd", "jk.bat", "sudo", "env", "nice", "nohup", "timeout", "time");

    private static boolean processWrapper(String command) {
        String base = command.replace('\\', '/');
        int slash = base.lastIndexOf('/');
        String name = (slash >= 0 ? base.substring(slash + 1) : base).toLowerCase(Locale.ROOT);
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - 4);
        }
        return PROCESS_WRAPPERS.contains(name);
    }

    private static Check checkLock() {
        try {
            Path cwd = Path.of(System.getProperty("user.dir", "."));
            Path lock = LockPaths.lockFile(cwd);
            Path proj = lock.getParent();
            if (!Files.isRegularFile(lock))
                return new Check(Status.WARN, "lock", "no jk-lock.toml at " + proj + " (run jk lock)");
            String text = Files.readString(lock);
            if (!text.contains("version = 1")) return new Check(Status.WARN, "lock", "unexpected lock version");
            long artifacts = text.lines()
                    .filter(l -> l.trim().startsWith("[[artifact]]"))
                    .count();
            return new Check(Status.OK, "lock", artifacts + " artifacts · " + lock);
        } catch (IOException e) {
            return new Check(Status.WARN, "lock", "check failed: " + e.getMessage());
        }
    }

    /**
     * Scan every installed/linked tool, applying repair as it goes (unlink a broken symlink,
     * fingerprint a verified one) — the single pass both JSON and human output render from, so
     * {@code --output json} performs the same repair the human view reports.
     *
     * <p>Only a symlinked home is fingerprinted, because a link is the only install whose contents
     * jk does not control: {@code ToolProvisioning} links a discovered host install rather than
     * downloading one, and that tree can change underneath jk at any time. A directory jk installed
     * itself is {@code OK} without a digest. On Windows that means {@code --verify-linked}
     * fingerprints nothing, and deliberately so — {@code SymlinkProvisioner.canSymlink()} is false
     * there, so jk never creates a linked tool home in the first place and there is no drift to
     * detect. (A junction, which {@code Files.isSymbolicLink} also reports false for, is not
     * something jk creates either.)
     */
    private static List<ToolRow> scanTools(Path root, boolean verifyLinked) throws IOException {
        List<ToolRow> rows = new ArrayList<>();
        for (BuildTool tool : BuildTool.values()) {
            for (InstalledTool installed : listIncludingBrokenLinks(root, tool)) {
                Path home = installed.home();
                if (SymlinkProvisioner.isBrokenLink(home)) {
                    String wasPointingAt = readlinkSafe(home);
                    SymlinkProvisioner.unlink(home);
                    rows.add(new ToolRow(tool, installed, ToolRowKind.PRUNED, wasPointingAt));
                    continue;
                }
                if (Files.isSymbolicLink(home) && verifyLinked) {
                    rows.add(verifyLinkedTool(root, tool, installed, home));
                } else if (Files.isSymbolicLink(home)) {
                    rows.add(new ToolRow(tool, installed, ToolRowKind.LINKED, readlinkSafe(home)));
                } else {
                    rows.add(new ToolRow(tool, installed, ToolRowKind.OK, null));
                }
            }
        }
        return rows;
    }

    /**
     * Fingerprint one symlinked tool home and compare it against the digest the last run stored at
     * {@code <slug>/<version>.fingerprint}. Before that marker was written and never read by
     * anything in the tree, so {@code --verify-linked} verified nothing; the read is what makes the
     * write mean something.
     *
     * <p>The baseline is re-written on every verdict, drift included, so a change is reported once
     * and the next run is quiet. That matches what {@code scanTools} already is — a repair pass, not
     * a monitor — and doctor's exit code is deliberately unaffected either way.
     */
    private static ToolRow verifyLinkedTool(Path root, BuildTool tool, InstalledTool installed, Path home)
            throws IOException {
        String fingerprint = JdkFingerprint.compute(home);
        // Nothing was hashed. Report it and do NOT store it: a digest of no files is not a baseline
        // a later run should be able to match.
        if (JdkFingerprint.EMPTY_TREE.equals(fingerprint)) {
            return new ToolRow(tool, installed, ToolRowKind.EMPTY, readlinkSafe(home));
        }
        Path marker = root.resolve(tool.slug()).resolve(installed.version() + ".fingerprint");
        String baseline = Files.isRegularFile(marker) ? Files.readString(marker).strip() : null;
        Files.writeString(marker, fingerprint);
        if (baseline == null) return new ToolRow(tool, installed, ToolRowKind.FIRST_SEEN, fingerprint);
        if (baseline.equals(fingerprint)) return new ToolRow(tool, installed, ToolRowKind.VERIFIED, fingerprint);
        return new ToolRow(
                tool, installed, ToolRowKind.DRIFTED, short12(baseline) + "… → " + short12(fingerprint) + "…");
    }

    /** The leading 12 hex characters a digest is quoted by, tolerant of a short/absent value. */
    private static String short12(@Nullable String digest) {
        if (digest == null) return "?";
        return digest.length() <= 12 ? digest : digest.substring(0, 12);
    }

    private static void printCheck(Check c, Theme t) {
        String prefix;
        switch (c.status) {
            case OK -> prefix = Theme.colorize("ok:      ", t.completedStep());
            case WARN -> prefix = Theme.colorize("warn:    ", t.warning());
            case FAIL -> prefix = Theme.colorize("fail:    ", t.error());
            default -> prefix = "";
        }
        CliOutput.out(prefix + Theme.colorize(c.label, t.cyan()) + " — " + c.detail);
    }

    private static String checkJson(Check c) {
        return "{\"status\":" + Jsonl.quote(c.status.name().toLowerCase(Locale.ROOT)) + ",\"detail\":"
                + Jsonl.quote(c.detail) + "}";
    }

    private static String formatUptime(long secs) {
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        return (secs / 3600) + "h" + ((secs % 3600) / 60) + "m";
    }

    private static List<InstalledTool> listIncludingBrokenLinks(Path root, BuildTool tool) throws IOException {
        return new ToolRegistry(root).list(tool, /* includeBrokenLinks */ true);
    }

    private static String readlinkSafe(Path link) {
        try {
            return Files.readSymbolicLink(link).toString();
        } catch (IOException e) {
            return "?";
        }
    }
}
