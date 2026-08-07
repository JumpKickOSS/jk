// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.compat.BuildTool;
import cc.jumpkick.compat.InstalledTool;
import cc.jumpkick.config.JkCacheConfig;
import cc.jumpkick.discovery.SymlinkProvisioner;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.plugin.protocol.Jsonl;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.util.TreeFingerprint;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * {@code jk doctor} — host health checklist. Prints a wedge header plus one row per subsystem
 * (engine, dirs, jdk, lock, tools) and a summary. {@code --output json} emits machine output.
 */
public final class DoctorCommand implements CliCommand {

    @Override
    public String name() {
        return "doctor";
    }

    @Override
    public String description() {
        return "Check host health (engine, cache, JDKs, lock)";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<dir>", "Override the tools install root. Default: $JK_CACHE_DIR/tools.", "--tools-dir")
                        .hide(),
                Opt.flag("Fingerprint each linked install (SHA-256 every file)", "--verify-linked"));
    }

    @Override
    public int run(Invocation in) throws IOException {
        GlobalOptions global = GlobalOptions.from(in);
        Path toolsDir = in.value("tools-dir").map(Path::of).orElse(null);
        boolean verifyLinked = in.isSet("verify-linked");
        Path root = toolsDir != null ? toolsDir : JkDirs.cache().resolve("tools");

        // Collect checks first so JSON and human share the same facts. Tools are scanned (and
        // repaired — broken links unlinked, fingerprints written) exactly once, here, so
        // `--output json` performs the same repair the human view does instead of only reporting it.
        Check engine = checkEngine();
        Check cache = checkDirs();
        Check jdk = checkJdk();
        Check lock = checkLock();
        List<ToolRow> toolRows;
        String toolsError = null;
        try {
            toolRows = scanTools(root, verifyLinked);
        } catch (IOException e) {
            toolRows = List.of();
            toolsError = e.getMessage();
        }
        int healthy = 0, pruned = 0, verified = 0;
        for (ToolRow row : toolRows) {
            switch (row.kind()) {
                case PRUNED -> pruned++;
                case VERIFIED -> {
                    healthy++;
                    verified++;
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
                    + "\"tools\":{\"healthy\":" + healthy + ",\"pruned\":" + pruned + ",\"verified\":" + verified
                    + ",\"error\":" + Jsonl.quote(toolsError) + "}"
                    + "}";
            CliOutput.out(json);
            return hasFail ? 1 : 0;
        }

        Theme t = Theme.active();
        cc.jumpkick.cli.tui.CommandWedge.envelopeStart();
        CliOutput.out(cc.jumpkick.cli.tui.CommandWedge.menu("Doctor"));

        printCheck(engine, t);
        printCheck(cache, t);
        printCheck(jdk, t);
        printCheck(lock, t);

        if (toolsError != null) {
            CliOutput.out(Theme.colorize("warn:    ", t.warning()) + Theme.colorize("tools", t.cyan()) + " — probe failed: "
                    + toolsError);
        } else if (toolRows.isEmpty()) {
            // If no tools at all, emit a row so the checklist looks complete.
            CliOutput.out(Theme.colorize("ok:      ", t.completedStep()) + " tools — no installs found");
        } else {
            for (ToolRow row : toolRows) {
                String toolName = Theme.colorize(row.tool().slug(), t.cyan());
                String label = toolName + " " + row.installed().version();
                switch (row.kind()) {
                    case PRUNED ->
                        CliOutput.out(Theme.colorize("pruned:  ", t.warning()) + " " + label
                                + " (link target missing: " + Theme.colorize(row.detail(), t.path()) + ")");
                    case VERIFIED ->
                        CliOutput.out(Theme.colorize("verified:", t.completedStep()) + " " + label
                                + " (sha256-tree=" + row.detail().substring(0, 12) + "…)");
                    case LINKED ->
                        CliOutput.out("linked:   " + label
                                + " " + Theme.colorize("→", t.darkGray()) + " "
                                + Theme.colorize(row.detail(), t.path()));
                    case OK -> CliOutput.out(Theme.colorize("ok:      ", t.completedStep()) + " " + label);
                }
            }
        }

        CliOutput.out(Theme.colorize("---", t.darkGray()));
        String summary = Theme.colorize(String.valueOf(healthy), t.focused())
                + " healthy"
                + (pruned > 0 ? ", " + Theme.colorize(String.valueOf(pruned), t.focused()) + " pruned" : "")
                + (verified > 0 ? ", " + Theme.colorize(String.valueOf(verified), t.focused()) + " fingerprinted" : "");
        // Append subsystem warnings to summary when any check failed.
        if (hasFail) {
            summary += Theme.colorize(" — issues found", t.warning());
        }
        CliOutput.out(summary);
        return hasFail ? 1 : 0;
    }

    // ---- checks ----

    private enum Status {
        OK,
        WARN,
        FAIL
    }

    private record Check(Status status, String label, String detail) {}

    private enum ToolRowKind {
        PRUNED,
        VERIFIED,
        LINKED,
        OK
    }

    /** One installed/linked tool, after {@link #scanTools} has already applied any repair. */
    private record ToolRow(BuildTool tool, InstalledTool installed, ToolRowKind kind, String detail) {}

    private static Check checkEngine() {
        EnginePaths.Paths paths = EnginePaths.current();
        Path socket = EnginePaths.activeSocket(paths);
        Optional<EngineClient.Status> st = EngineClient.status(socket);
        if (st.isEmpty()) {
            // A socket that accepts connections but never answers status is a wedged engine, not
            // an absent one — `jk build` would hang on it instead of lazy-starting a fresh one.
            if (EngineClient.reachable(socket)) {
                return new Check(
                        Status.FAIL,
                        "engine",
                        "listening but not answering status — likely wedged; try `jk engine stop`");
            }
            return new Check(Status.WARN, "engine", "not running (lazy start on next build)");
        }
        EngineClient.Status s = st.get();
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
        if (!Files.isDirectory(store.getParent())) problems.add("store parent missing");
        if (!Files.isDirectory(state.getParent())) problems.add("state parent missing");
        if (!problems.isEmpty()) return new Check(Status.FAIL, "dirs", String.join("; ", problems));
        String detail = "cache " + cache + " · store " + store + " · " + cfg.maxCacheSizeMb() + "M cache / "
                + cfg.maxStoreSizeMb() + "M store (display)";
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

    private static Check checkLock() {
        try {
            Path cwd = Path.of(System.getProperty("user.dir", "."));
            Path lock = cc.jumpkick.lock.LockPaths.lockFile(cwd);
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
                    String fingerprint = TreeFingerprint.compute(home);
                    Path marker = root.resolve(tool.slug()).resolve(installed.version() + ".fingerprint");
                    Files.writeString(marker, fingerprint);
                    rows.add(new ToolRow(tool, installed, ToolRowKind.VERIFIED, fingerprint));
                } else if (Files.isSymbolicLink(home)) {
                    rows.add(new ToolRow(tool, installed, ToolRowKind.LINKED, readlinkSafe(home)));
                } else {
                    rows.add(new ToolRow(tool, installed, ToolRowKind.OK, null));
                }
            }
        }
        return rows;
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
        return "{\"status\":" + Jsonl.quote(c.status.name().toLowerCase()) + ",\"detail\":" + Jsonl.quote(c.detail)
                + "}";
    }

    private static String formatUptime(long secs) {
        if (secs < 60) return secs + "s";
        if (secs < 3600) return (secs / 60) + "m";
        return (secs / 3600) + "h" + ((secs % 3600) / 60) + "m";
    }

    private static List<InstalledTool> listIncludingBrokenLinks(Path root, BuildTool tool) throws IOException {
        Path slugDir = root.resolve(tool.slug());
        if (!Files.exists(slugDir)) return List.of();
        List<InstalledTool> result = new ArrayList<>();
        try (Stream<Path> stream = Files.list(slugDir)) {
            stream.forEach(path -> {
                if (Files.isDirectory(path) || Files.isSymbolicLink(path)) {
                    result.add(new InstalledTool(tool, path.getFileName().toString(), path));
                }
            });
        }
        return result;
    }

    private static String readlinkSafe(Path link) {
        try {
            return Files.readSymbolicLink(link).toString();
        } catch (IOException e) {
            return "?";
        }
    }
}
