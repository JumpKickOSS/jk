// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.http.mcp;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.engine.jobs.JobOrigin;
import cc.jumpkick.engine.jobs.JobRequests;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.host.ChildEnv;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.host.ProcessEnv;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.DevReady;
import cc.jumpkick.runtime.workspace.ExecPlans;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;

/**
 * What the MCP {@code run} tool does: build the project and start its application with its output in
 * {@code target/jk-app.log}, wait until it answers, read that log, stop it. The verdict's first word
 * is {@code READY}, {@code UP} (running, but no port to wait for), or {@code FAIL}, as {@code build}'s
 * is {@code OK} or {@code FAIL}.
 */
public final class McpAppRuns {

    /** The log of the app last started in a project, under its directory. */
    public static final String LOG = "target/jk-app.log";

    /** The failure verdict's quote of the log: its last lines, where a startup error is. */
    static final int FAILURE_LINES = 40;

    private McpAppRuns() {}

    /**
     * Build, then start the app and wait for it. An app this tool already runs in the directory is
     * stopped first, so a start after an edit is a restart.
     */
    public static Map<String, Object> start(McpCall in) {
        String dir = dirOf(in);
        McpApps apps = in.ctx().apps();
        synchronized (apps.lockFor(dir)) {
            apps.stop(dir);
            String built = McpJobRuns.verdict(in, buildSpec(in, dir));
            if (!built.startsWith("OK")) return in.text(built);
            Path project = Path.of(dir);
            String name = nameOf(project);
            ExecPlan plan = ExecPlans.execPlan(project, JkDirs.cache(), "run", null, null);
            if (plan.error() != null) return in.text(fail(name, plan.error()));
            if (!plan.deployCommand().isEmpty()) {
                return in.text(fail(name, "it deploys to a device; jk run deploys it"));
            }
            ExecPlan.Probe probe = probeOf(project);
            int port = portOf(in, probe, plan);
            if (port > 0 && McpAppReady.answers(port)) {
                return in.text(fail(name, "port " + port + " already answers; stop what listens there"));
            }
            McpApp app;
            try {
                app = launch(in, dir, name, plan);
            } catch (IOException e) {
                return in.text(fail(name, String.valueOf(e.getMessage())));
            }
            apps.add(app);
            long timeout =
                    probe.readyTimeoutMillis() > 0 ? probe.readyTimeoutMillis() : DevReady.DEFAULT_TIMEOUT_MILLIS;
            McpAppReady.Result r = in.ctx()
                    .admissionYield()
                    .yielding(() -> new McpAppReady(app, probe, port, timeout, Clock.SYSTEM).await());
            return in.text(verdict(app, r, timeout));
        }
    }

    /**
     * The budgeted tail of the app's stdout and stderr, or of one request's lines in it, then the same
     * of the file the call's {@code log} names, else {@code [run] log}; two sources share the budget.
     */
    public static Map<String, Object> logs(McpCall in) {
        String dir = dirOf(in);
        Path project = Path.of(dir);
        String name = nameOf(project);
        String request = in.str("request");
        if (request != null && !request.isBlank()) {
            try {
                McpAppLog.regexOf(request);
            } catch (PatternSyntaxException e) {
                return in.error("request " + request.strip() + " is not a Java regex: " + e.getDescription() + "\n");
            }
        }
        String extra = in.str("log");
        if (extra == null || extra.isBlank()) extra = runLogOf(project);
        Path log = project.resolve(LOG);
        if (!Files.isRegularFile(log) && extra == null) {
            return in.error("no app log in " + name + "; run(action=start) starts the app\n");
        }
        int max = in.count("lines", McpAppLog.DEFAULT_LINES, 1, McpAppLog.MAX_LINES);
        int budget = extra == null ? McpAppLog.BUDGET_CHARS : McpAppLog.BUDGET_CHARS / 2;
        McpApp app = in.ctx().apps().in(dir);
        StringBuilder out = new StringBuilder("LOG app ").append(name).append(" · ");
        out.append(app == null ? "not started by this engine" : app.state() + pid(app));
        out.append('\n');
        try {
            section(out, "stdout and stderr", log, request, max, budget);
            if (extra != null) section(out, extra, project.resolve(extra), request, max, budget);
        } catch (IOException e) {
            return in.error("cannot read a log of " + name + ": " + e.getMessage() + "\n");
        }
        return in.text(out.toString());
    }

    /** One source's headline and tail: {@code == stdout and stderr · last 60 of 812 lines}. */
    private static void section(
            StringBuilder out, String label, Path file, @Nullable String request, int max, int budget)
            throws IOException {
        out.append("== ").append(label).append(" · ");
        if (!Files.isRegularFile(file)) {
            out.append("no file yet\n");
            return;
        }
        McpAppLog.Tail tail =
                McpAppLog.tail(McpAppLog.read(file), request, max, budget, McpAppLog.REGEX_MILLIS, Clock.SYSTEM);
        if (request != null && !request.isBlank()) {
            out.append(tail.of())
                    .append(tail.of() == 1 ? " line for " : " lines for ")
                    .append(request.strip());
            if (tail.lines().size() < tail.of())
                out.append(", last ").append(tail.lines().size());
            if (tail.cut())
                out.append("; the regex ran out of ")
                        .append(seconds(McpAppLog.REGEX_MILLIS))
                        .append(", so these are the matches before that");
        } else {
            out.append("last ")
                    .append(tail.lines().size())
                    .append(" of ")
                    .append(tail.of())
                    .append(" lines");
        }
        out.append('\n');
        for (String line : tail.lines()) out.append(line).append('\n');
    }

    /** Stop the app this tool runs in the call's project. */
    public static Map<String, Object> stop(McpCall in) {
        String dir = dirOf(in);
        String name = nameOf(Path.of(dir));
        McpApp app = in.ctx().apps().stop(dir);
        if (app == null) return in.text("no app running in " + name + "\n");
        return in.text("STOPPED app " + name + pid(app) + " · ran " + wall(app.ranMillis()) + "\n");
    }

    /** The build {@code jk run} runs first: the project, tests skipped, as this session's job. */
    private static JobSpec buildSpec(McpCall in, String dir) {
        try {
            return JobRequests.decode(
                    Map.of("kind", "build", "skip_tests", true),
                    JobOrigin.mcp(in.sessionLabel()),
                    "build",
                    dir,
                    McpJobRuns.RUN_FIELDS);
        } catch (IllegalArgumentException e) {
            throw new McpError(-32602, e.getMessage());
        }
    }

    /**
     * The app's process: the plan's command and the call's {@code args}, the module's {@code .env}
     * filling in what the engine's environment does not set and the plan's values over both, stdout and stderr both into
     * {@link #LOG}, nothing on stdin.
     */
    private static McpApp launch(McpCall in, String dir, String name, ExecPlan plan) throws IOException {
        Path project = Path.of(dir);
        Path log = project.resolve(LOG);
        Files.createDirectories(log.getParent());
        List<String> command = new ArrayList<>(plan.argv());
        command.addAll(in.strings("args"));
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory((plan.workingDir().isEmpty() ? project : Path.of(plan.workingDir())).toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        Map<String, String> unset = new LinkedHashMap<>();
        plan.appDotEnv().forEach((k, v) -> {
            if (ProcessEnv.get(k) == null) unset.put(k, v);
        });
        ChildEnv.putAll(pb, unset);
        ChildEnv.putAll(pb, plan.appEnv());
        long startedAt = Clock.SYSTEM.millis();
        Process process = ChildEnv.start(pb);
        process.getOutputStream().close();
        McpConnection connection = in.connection();
        return new McpApp(
                dir,
                name,
                process,
                log,
                startedAt,
                connection == null ? null : connection.id(),
                connection == null ? "HTTP" : connection.label());
    }

    /** {@code [dev] ready} / {@code ready-pattern} / {@code ready-timeout} of the project's own {@code jk.toml}. */
    private static ExecPlan.Probe probeOf(Path project) {
        try {
            return ExecPlan.Probe.of(JkBuildParser.parse(ManifestPaths.manifestIn(project))
                    .build()
                    .devReady());
        } catch (IOException e) {
            return ExecPlan.Probe.NONE;
        }
    }

    /** {@code [run] log} of the project's own {@code jk.toml}; null when unset or not there. */
    private static @Nullable String runLogOf(Path project) {
        Path manifest = ManifestPaths.manifestIn(project);
        if (!Files.isRegularFile(manifest)) return null;
        try {
            return JkBuildParser.parse(manifest).build().runLog();
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * The port to wait for: the call's {@code port}, else {@code [dev] ready}'s, else a node
     * server's {@code PORT}; {@code 0} to learn it from the app's output.
     */
    static int portOf(McpCall in, ExecPlan.Probe probe, ExecPlan plan) {
        int asked = in.count("port", 0, 0, 65535);
        if (asked > 0) return asked;
        int ready = McpAppReady.portOf(probe.ready());
        if (ready > 0) return ready;
        String env = plan.appEnv().get("PORT");
        try {
            return env == null ? 0 : Integer.parseInt(env.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** The headline and, for a failure, the log's last lines; a failed or timed-out app is stopped. */
    static String verdict(McpApp app, McpAppReady.Result r, long timeoutMillis) {
        String head = " app " + app.name() + pid(app) + " · ";
        String took = " · " + wall(app.ranMillis());
        String what = r.port() > 0 ? "port " + r.port() : "the app";
        return switch (r.outcome()) {
            case READY -> {
                app.url(r.url());
                yield "READY" + head + (r.url().isEmpty() ? "started" : r.url()) + took + "\n";
            }
            case NO_PORT ->
                "UP" + head + "its output named no port in " + seconds(timeoutMillis) + "; port=N waits for one\n";
            case EXITED -> {
                String exit = "exited with " + app.process().exitValue() + " before " + what + " answered";
                yield "FAIL" + head + exit + took + "\n" + quoteLog(app);
            }
            case TIMED_OUT -> {
                app.stop();
                yield "FAIL" + head + what + " did not answer in " + seconds(timeoutMillis) + "; stopped it\n"
                        + quoteLog(app);
            }
        };
    }

    /** The log's last {@link #FAILURE_LINES} lines, then where the whole log is. */
    private static String quoteLog(McpApp app) {
        StringBuilder out = new StringBuilder();
        try {
            for (String line : McpAppLog.tail(McpAppLog.read(app.log()), null, FAILURE_LINES)
                    .lines()) {
                out.append(line).append('\n');
            }
        } catch (IOException e) {
            // the log is gone; the headline still says what happened
        }
        return out.append("log: ").append(LOG).append('\n').toString();
    }

    private static String fail(String name, @Nullable String why) {
        return "FAIL app " + name + " · " + why + "\n";
    }

    private static String pid(McpApp app) {
        return " pid " + app.process().pid();
    }

    /** A timeout as written: {@code 60s}, {@code 1.5s}. */
    static String seconds(long millis) {
        return millis % 1000 == 0 ? millis / 1000 + "s" : wall(millis);
    }

    /** {@code 840ms}, {@code 7.3s}, {@code 3m 12s}. */
    static String wall(long millis) {
        if (millis < 1_000) return millis + "ms";
        if (millis < 60_000) return String.format(Locale.ROOT, "%.1fs", millis / 1000.0);
        long s = millis / 1000;
        return s / 60 + "m " + s % 60 + "s";
    }

    private static String dirOf(McpCall in) {
        try {
            return McpHistoryViews.dirKey(in.requiredDir());
        } catch (McpError e) {
            throw e;
        } catch (RuntimeException e) {
            throw new McpError(-32602, "invalid dir: " + e.getMessage());
        }
    }

    private static String nameOf(Path project) {
        Path file = project.getFileName();
        return file == null ? project.toString() : file.toString();
    }
}
