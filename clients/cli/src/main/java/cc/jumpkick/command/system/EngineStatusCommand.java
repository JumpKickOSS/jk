// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.engine.EngineFleet;
import cc.jumpkick.cli.engine.EngineHeapDump;
import cc.jumpkick.cli.engine.EngineProbe;
import cc.jumpkick.cli.engine.EngineProcessControl;
import cc.jumpkick.cli.engine.SilentPeer;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.JkWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.terminal.Ansi;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk engine status} — pings first (the same liveness authority every engine-aware command
 * uses, never a bare pidfile read) and reports pid/version/source checkout/uptime/active requests
 * and best-effort memory usage (heap used/committed/max, plus OS RSS where readable), or "not running"
 * with a non-zero exit so scripts can branch on it. {@code --output json} emits one flat object
 * instead, for scripts/CI that want to parse the result rather than scrape text ({@code -1} =
 * that number couldn't be observed).
 */
public final class EngineStatusCommand implements CliCommand {

    @Override
    public String name() {
        return "status";
    }

    @Override
    public String description() {
        return "Show the build engine's status";
    }

    @Override
    public List<Opt> options() {
        return List.of();
    }

    @Override
    public int run(Invocation in) {
        GlobalOptions global = GlobalOptions.from(in);
        EnginePaths.Paths paths = EnginePaths.current();
        Optional<EngineProbe.Status> status = EngineProbe.status(EnginePaths.activeSocket(paths));
        if (status.isEmpty()) {
            // "not running" is only true of THIS directory's engine. Saying it flatly while others are
            // alive is how eighteen engines once went unnoticed, so name them. And a silent probe is
            // not proof of absence either: a wedged process can hold this directory's election state
            // without ever answering — every fresh spawn then loses to it, so the one thing status
            // must not do is call that "not running".
            long stray = EngineProcessControl.unresponsiveHolderPid(EnginePaths.activeSocket(paths));
            List<EngineFleet.Member> others = EngineFleet.list();
            if (global.outputIsJson()) {
                CliOutput.out(notRunningJson(stray, others));
            } else {
                CommandWedge.envelopeStart();
                String headline;
                if (stray > 0) {
                    headline = strayHeadline(stray);
                } else if (others.isEmpty()) {
                    headline = "Engine is not running";
                } else {
                    headline = "No engine for this directory (" + others.size()
                            + (others.size() == 1 ? " other is" : " others are") + " running)";
                }
                CliOutput.out(JkWedge.chipLine(Glyphs.STOP, "Engine", GlobalConfig.nerdFont(), headline));
                heapDumpRow(paths);
                printFleet(others);
            }
            return Exit.FAILURE;
        }
        EngineProbe.Status s = status.get();
        long now = System.currentTimeMillis();
        long uptimeSeconds = Math.max(0, (now - s.startedAtMillis()) / 1000);
        if (global.outputIsJson()) {
            CliOutput.out(runningJson(s, uptimeSeconds, EngineFleet.list()));
            return Exit.SUCCESS;
        }
        CommandWedge.envelopeStart();
        CliOutput.out(JkWedge.chipLine(
                Glyphs.PLAY, "Engine", GlobalConfig.nerdFont(), "Engine is running (pid " + pidStyled(s.pid()) + ")"));
        detail("Version", s.version());
        if (s.installSource() != null && !s.installSource().isEmpty()) detail("Source", s.installSource());
        detail("Uptime", formatUptime(uptimeSeconds));
        detail("Live Jobs", String.valueOf(s.activeBuildPlans()));
        for (EngineProbe.Job job : s.jobs()) if (job.live()) jobRow(job, now);
        if (s.queuedBuildPlans() > 0) {
            detail("Queued", s.queuedBuildPlans() + " (waiting for engine memory)");
            for (EngineProbe.Job job : s.jobs()) if (!job.live()) jobRow(job, now);
        }
        if (s.idleDropped() >= 0) {
            detail("Dropped", s.idleDropped() + (s.idleDropped() == 1 ? " idle connection" : " idle connections"));
        }
        if (s.logBytes() >= 0) detail("Log", describeLog(s, now));
        if (s.ignoredSignals() != null && !s.ignoredSignals().isEmpty()) {
            detail("Signals", describeIgnoredSignals(s.ignoredSignals()));
        }
        String containment = describeContainment(s.containment(), s.containmentReason(), s.workerMemoryMax());
        if (containment != null) detail("Containment", containment);
        heapDumpRow(paths);
        String memory = formatMemory(s);
        if (memory != null) {
            detail("Memory", memory);
            String bar = memoryBar(s);
            // Align under the value column (same indent as status dotted-label values).
            if (bar != null) CliOutput.out(" ".repeat(VALUE_COL) + bar);
        }
        String http = describeHttp(s, paths);
        // OSC-8 hyperlink; visible text is white to match other status values.
        String webUi = s.httpUrl() != null
                ? Ansi.hyperlink(http, Theme.paint(http, Theme.active().brightWhite()))
                : http;
        detail("Web UI", webUi);
        if (s.mcpUrl() != null) {
            detail("MCP", s.mcpUrl() + "  (POST JSON-RPC; Bearer token)");
        }
        List<EngineFleet.Member> fleet = EngineFleet.list();
        if (fleet.size() > 1) printFleet(fleet);
        return Exit.SUCCESS;
    }

    /** One job under the {@code Live Jobs} or {@code Queued} row, indented to the value column. */
    private static void jobRow(EngineProbe.Job job, long nowMillis) {
        CliOutput.out(" ".repeat(VALUE_COL)
                + Theme.colorize(jobLine(job, nowMillis), Theme.active().settled()));
    }

    /**
     * {@code #739 test /home/me/app · since 22:36 (2h 05m) · 1 worker · last event 3m ago} for a live
     * job; {@code #741 build /home/me/lib · behind 0 · waiting 12m} for a queued one.
     */
    static String jobLine(EngineProbe.Job job, long nowMillis) {
        StringBuilder line = new StringBuilder("#" + job.jid() + " " + job.kind() + " " + job.dir());
        if (job.live()) {
            line.append(" · since ")
                    .append(wallClock(job.sinceMillis()))
                    .append(" (")
                    .append(formatAge(nowMillis - job.sinceMillis()))
                    .append(")");
            if (job.workers() >= 0)
                line.append(" · ").append(job.workers()).append(job.workers() == 1 ? " worker" : " workers");
            if (job.lastEventAt() > 0) {
                line.append(" · last event ")
                        .append(formatAge(nowMillis - job.lastEventAt()))
                        .append(" ago");
            }
        } else {
            line.append(" · behind ").append(Math.max(0, job.ahead()));
            line.append(" · waiting ").append(formatAge(nowMillis - job.sinceMillis()));
        }
        return line.toString();
    }

    /** {@code 2h 05m} / {@code 12m} / {@code 40s}: how long ago, for a job row. */
    static String formatAge(long millis) {
        long s = Math.max(0, millis / 1000);
        long h = s / 3600;
        long m = (s % 3600) / 60;
        if (h > 0) return h + "h " + String.format("%02d", m) + "m";
        if (m > 0) return m + "m";
        return s + "s";
    }

    /** Epoch millis as the local wall clock, {@code HH:mm}. */
    static String wallClock(long epochMillis) {
        return WALL_CLOCK.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()));
    }

    private static final DateTimeFormatter WALL_CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** The heap dump a previous engine of this identity left when it exited on OutOfMemoryError, if any. */
    private static void heapDumpRow(EnginePaths.Paths paths) {
        EngineHeapDump.find(paths).ifPresent(dump -> detail("Heap Dump", EngineHeapDump.finding(dump)));
    }

    /**
     * The whole fleet, one line each. Only printed when there is more than one engine, so the common
     * single-engine case reads exactly as before.
     *
     * <p>Each line leads with the identity {@code id} because that is the handle a user can act on
     * alongside the pid, which is what {@code stop --pid} takes. Without this the only way to discover a
     * second engine was {@code ps}.
     */
    /**
     * The holder that does not answer its socket: alive and busy — young, or with worker processes
     * — is what a client waits for rather than displaces, and the line says so; anything else is
     * the wedge {@code stop --now} clears.
     */
    private static String strayHeadline(long stray) {
        var life = SilentPeer.Life.of(stray, Clock.SYSTEM);
        if (life.isPresent()
                && (life.get().workers() > 0 || life.get().youngerThan(SilentPeer.Grace.DEFAULT.startup()))) {
            return "Engine pid " + stray + " holds this directory's engine state and is alive and busy ("
                    + life.get().describe() + ") but does not answer its socket yet — clients wait for it rather"
                    + " than displace it; `jk engine stop --now` stops it regardless";
        }
        return "Engine pid " + stray + " holds this directory's engine state but does not"
                + " answer — `jk engine stop --now` clears it";
    }

    private static void printFleet(List<EngineFleet.Member> fleet) {
        if (fleet.isEmpty()) return;
        CliOutput.out("");
        CliOutput.out(" Engines (" + fleet.size() + "):");
        for (EngineFleet.Member m : fleet) {
            String marker = m.current() ? "*" : " ";
            StringBuilder line = new StringBuilder(" " + marker + " " + m.id() + "  pid " + pidStyled(m.pid()));
            if (m.responsive()) {
                var status = Objects.requireNonNull(m.status(), "status");
                long up = Math.max(0, (System.currentTimeMillis() - status.startedAtMillis()) / 1000);
                line.append("  up ").append(formatUptime(up)).append("  jobs ").append(status.activeBuildPlans());
                if (m.status().draining()) line.append("  draining");
            } else {
                // Alive but not answering: yielded listeners, rebound socket, or wedged. Still
                // holds memory; stop --pid is how it goes away.
                line.append("  unresponsive (alive, not answering)");
            }
            if (m.current()) line.append("   (this directory)");
            CliOutput.out(line.toString());
        }
        CliOutput.out("");
        CliOutput.out(" Stop this home with `jk engine stop --all`, or one engine with `jk engine stop --pid <pid>`.");
    }

    /** The `--output json` line for a directory with no engine of its own; the fleet says what else is alive. */
    public static String notRunningJson(long unresponsivePid, List<EngineFleet.Member> fleet) {
        return JsonFields.object()
                .bool("running", false)
                .optionalNumber("unresponsivePid", unresponsivePid, 0)
                .token("engines", enginesJson(fleet))
                .finish();
    }

    /** The `--output json` line for a running engine. */
    public static String runningJson(EngineProbe.Status s, long uptimeSeconds, List<EngineFleet.Member> fleet) {
        JsonFields json = JsonFields.object()
                .bool("running", true)
                .number("pid", s.pid())
                .string("version", s.version())
                .number("startedAt", s.startedAtMillis())
                .number("uptimeSeconds", uptimeSeconds)
                .number("activeRequests", s.activeRequests())
                .number("idleDropped", s.idleDropped())
                .number("queuedBuildPlans", s.queuedBuildPlans())
                .token("jobs", jobsJson(s.jobs()))
                .number("heapUsedBytes", s.heapUsedBytes())
                .number("heapCommittedBytes", s.heapCommittedBytes())
                .number("heapMaxBytes", s.heapMaxBytes())
                .number("rssBytes", s.rssBytes())
                .number("cores", s.cores())
                .number("totalMemoryBytes", s.totalMemoryBytes())
                .number("availableMemoryBytes", s.availableMemoryBytes())
                .token("systemCpuLoad", Double.toString(s.systemCpuLoad()))
                .token("systemLoadAverage", Double.toString(s.systemLoadAverage()))
                .string("engineEpoch", s.engineEpoch())
                .number("logBytes", s.logBytes())
                .number("logRolledAt", s.logRolledAt())
                .string("ignoredSignals", s.ignoredSignals())
                .string("installSource", s.installSource())
                .string("containment", s.containment())
                .string("containmentReason", s.containmentReason())
                .number("workerMemoryMax", s.workerMemoryMax())
                .string("httpUrl", s.httpUrl())
                .string("httpError", s.httpError())
                .string("mcpUrl", s.mcpUrl());
        if (s.vfsJson() != null) json.token("vfs", s.vfsJson());
        return json.token("engines", enginesJson(fleet)).finish();
    }

    /** The engine's job rows as one JSON array, the engine's own field names. */
    static String jobsJson(List<EngineProbe.Job> jobs) {
        List<String> rows = new ArrayList<>();
        for (EngineProbe.Job j : jobs) rows.add(j.toJson());
        return "[" + String.join(",", rows) + "]";
    }

    public static String enginesJson(List<EngineFleet.Member> fleet) {
        List<String> members = new ArrayList<>();
        for (EngineFleet.Member m : fleet) {
            JsonFields member = JsonFields.object()
                    .string("id", m.id())
                    .number("pid", m.pid())
                    .bool("current", m.current())
                    .bool("responsive", m.responsive());
            var st = m.status();
            if (m.responsive() && st != null) {
                member.number("startedAt", st.startedAtMillis())
                        .number("activeBuildPlans", st.activeBuildPlans())
                        .bool("draining", st.draining())
                        .string("version", st.version());
            }
            members.add(member.finish());
        }
        return "[" + String.join(",", members) + "]";
    }

    /**
     * Label field width including the trailing colon (widest is {@code Containment:}). Labels are
     * left-aligned and padded with dim dots — same shape as {@code jk status}.
     */
    private static final int LABEL_W = 12;

    /** Column where values (and the memory bar) begin: leading space + label field + one space. */
    private static final int VALUE_COL = 1 + LABEL_W + 1;

    /**
     * One detail row under the header chip:
     *
     * <pre>
     *  Version..: 0.13.0
     *  Live Jobs: 0
     * </pre>
     *
     * {@link Theme#settled()} label (body foreground), bright-black ({@link Theme#darkGray()})
     * dotted leader + colon, bright-white value. Leading space matches the historical engine-status
     * indent.
     *
     * <p>When {@code value} already contains ANSI (e.g. an OSC-8 hyperlink), it is emitted as-is
     * so nested styling is not double-wrapped.
     */
    private static void detail(String label, @Nullable String value) {
        Theme t = Theme.active();
        String field = StatusCommand.dottedLabel(label, LABEL_W);
        String name = field.substring(0, label.length());
        String leader = field.substring(label.length()); // dots + ':'
        String val = value == null ? "—" : value;
        // Pre-styled values (hyperlinks) keep their own sequences; plain text is bright white.
        String styledVal = val.indexOf('\u001B') >= 0 ? val : Theme.colorize(val, t.brightWhite());
        CliOutput.out(" " + Theme.colorize(name, t.settled()) + Theme.colorize(leader, t.darkGray()) + " " + styledVal);
    }

    /**
     * {@code none}, {@code score-only (<reason>)}, or {@code cgroup (max X GiB)}. Null when the
     * engine did not report a mode.
     */
    static @Nullable String describeContainment(@Nullable String mode, @Nullable String reason, long maxBytes) {
        if (mode == null || mode.isEmpty()) return null;
        if ("cgroup".equals(mode) && maxBytes > 0) {
            return "cgroup (max " + gib(maxBytes) + ")";
        }
        if ("score-only".equals(mode)) {
            return reason == null || reason.isEmpty() ? "score-only" : "score-only (" + reason + ")";
        }
        return mode;
    }

    /** One decimal gibibyte, for a cgroup limit. */
    private static String gib(long bytes) {
        return String.format(Locale.ROOT, "%.1f GiB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** The engine pid in yellow on an ANSI terminal (matching the start/stop wedges). */
    private static @Nullable String pidStyled(long pid) {
        String s = Long.toString(pid);
        return Theme.active().isAnsi() ? Theme.colorize(s, Theme.active().warning()) : s;
    }

    /**
     * The embedded HTTP server's state — serving URL, bind error, or disabled ({@code docs/http.md}).
     * The serving URL carries the bearer token as a fragment ({@code #t=…}) when the engine's
     * owner-only token file is readable: fragments never leave the browser, and this line is how
     * the dashboard SPA bootstraps its token — click/open the printed URL and it's authenticated.
     */
    private static String describeHttp(EngineProbe.Status s, EnginePaths.Paths paths) {
        if (s.httpUrl() != null) {
            try {
                String token = Files.readString(paths.httpToken()).trim();
                if (!token.isEmpty()) return s.httpUrl() + "#t=" + token;
            } catch (IOException e) {
                // token file unreadable/missing — the plain URL still serves static + loopback reads
            }
            return s.httpUrl();
        }
        if (s.httpError() != null) return "failed to start (" + s.httpError() + ")";
        return "disabled";
    }

    /**
     * One human line, e.g. {@code heap 18 MiB used / 42 MiB committed (max 256 MiB); rss 63 MiB}.
     * Unobservable parts are dropped; {@code null} when nothing at all was observed (an engine
     * predating the memory fields).
     */
    private static @Nullable String formatMemory(EngineProbe.Status s) {
        StringBuilder out = new StringBuilder();
        if (s.heapUsedBytes() >= 0 && s.heapCommittedBytes() >= 0) {
            out.append("Heap ")
                    .append(mib(s.heapUsedBytes()))
                    .append(" used / ")
                    .append(mib(s.heapCommittedBytes()))
                    .append(" committed");
            if (s.heapMaxBytes() >= 0) {
                out.append(" (max ").append(mib(s.heapMaxBytes())).append(")");
            }
        }
        if (s.rssBytes() >= 0) {
            if (out.length() > 0) out.append("; ");
            out.append("RSS ").append(mib(s.rssBytes()));
        }
        return out.length() > 0 ? out.toString() : null;
    }

    private static String mib(long bytes) {
        return (bytes + (1 << 19)) / (1 << 20) + "M"; // round to nearest MiB
    }

    /**
     * Only printed when the engine still ignores a terminal signal: the spawning shell had it
     * ignored and the startup reset did not take, so every JVM the engine forks ignores it too and
     * the Ctrl-C contract of {@code jk dev} and the test runner is void until a respawn.
     */
    static String describeIgnoredSignals(String ignored) {
        return "ignoring " + ignored + " (inherited from the shell that started the engine; forked workers"
                + " inherit it — `jk engine stop`, then start it from a foreground shell)";
    }

    /** The engine log's size and when this engine last rolled it, e.g. {@code 3M, rolled 2h 5m 0s ago}. */
    static String describeLog(EngineProbe.Status s, long nowMillis) {
        String size = s.logBytes() < (1 << 20) ? (s.logBytes() + 512) / 1024 + "K" : mib(s.logBytes());
        if (s.logRolledAt() < 0) return size + ", never rolled";
        long ago = Math.max(0, (nowMillis - s.logRolledAt()) / 1000);
        return size + ", rolled " + formatUptime(ago) + " ago";
    }

    /**
     * A stacked heap bar aligned under the memory value: {@code used} in bright-cyan, {@code
     * committed}-beyond-used in indigo, and the rest (up to {@code max}) in bright-black. {@code
     * null} when the heap max isn't observable (nothing to scale against).
     */
    private static @Nullable String memoryBar(EngineProbe.Status s) {
        long max = s.heapMaxBytes();
        if (max <= 0 || s.heapUsedBytes() < 0 || s.heapCommittedBytes() < 0) return null;
        int width = 50;
        int used = clamp((int) Math.round((double) s.heapUsedBytes() / max * width), 0, width);
        int committed = clamp((int) Math.round((double) s.heapCommittedBytes() / max * width), used, width);
        Theme t = Theme.active();
        return Theme.colorize("▰".repeat(used), t.brightCyan())
                + Theme.colorize("▰".repeat(committed - used), t.indigo())
                + Theme.colorize("▱".repeat(width - committed), t.darkGray());
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String formatUptime(long totalSeconds) {
        long h = totalSeconds / 3600;
        long m = (totalSeconds % 3600) / 60;
        long s = totalSeconds % 60;
        return h + "h " + m + "m " + s + "s";
    }
}
