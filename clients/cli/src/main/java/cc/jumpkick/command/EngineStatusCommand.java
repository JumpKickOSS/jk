// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.cli.Ansi;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.theme.Theme;
import cc.jumpkick.cli.tui.Glyphs;
import cc.jumpkick.cli.tui.PipelineWedge;
import cc.jumpkick.config.GlobalConfig;
import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.plugin.protocol.Jsonl;
import java.util.List;
import java.util.Optional;

/**
 * {@code jk engine status} — pings first (the same liveness authority every engine-aware command
 * uses, never a bare pidfile read) and reports pid/version/uptime/active requests and
 * best-effort memory usage (heap used/committed/max, plus OS RSS where readable), or "not running"
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
        Optional<EngineClient.Status> status = EngineClient.status(cc.jumpkick.engine.EnginePaths.activeSocket(paths));
        if (status.isEmpty()) {
            // "not running" is only true of THIS directory's engine. Saying it flatly while others are
            // alive is how eighteen engines once went unnoticed (JK-1293), so name them.
            java.util.List<cc.jumpkick.cli.engine.EngineFleet.Member> others =
                    cc.jumpkick.cli.engine.EngineFleet.list();
            if (global.outputIsJson()) {
                CliOutput.out("{\"running\":false,\"engines\":" + enginesJson(others) + "}");
            } else {
                CliOutput.out(PipelineWedge.chipLine(
                        Glyphs.STOP,
                        "Engine",
                        GlobalConfig.nerdfont(),
                        others.isEmpty()
                                ? "Engine is not running"
                                : "No engine for this directory (" + others.size()
                                        + (others.size() == 1 ? " other is" : " others are") + " running)"));
                printFleet(others);
            }
            return Exit.FAILURE;
        }
        EngineClient.Status s = status.get();
        long uptimeSeconds = Math.max(0, (System.currentTimeMillis() - s.startedAtMillis()) / 1000);
        if (global.outputIsJson()) {
            CliOutput.out("{\"running\":true"
                    + ",\"pid\":" + s.pid()
                    + ",\"version\":" + Jsonl.quote(s.version())
                    + ",\"startedAt\":" + s.startedAtMillis()
                    + ",\"uptimeSeconds\":" + uptimeSeconds
                    + ",\"activeRequests\":" + s.activeRequests()
                    + ",\"heapUsedBytes\":" + s.heapUsedBytes()
                    + ",\"heapCommittedBytes\":" + s.heapCommittedBytes()
                    + ",\"heapMaxBytes\":" + s.heapMaxBytes()
                    + ",\"rssBytes\":" + s.rssBytes()
                    + ",\"aotTrainingPid\":" + s.aotTrainingPid()
                    + ",\"httpUrl\":" + (s.httpUrl() != null ? Jsonl.quote(s.httpUrl()) : "null")
                    + ",\"httpError\":" + (s.httpError() != null ? Jsonl.quote(s.httpError()) : "null")
                    + ",\"mcpUrl\":" + (s.mcpUrl() != null ? Jsonl.quote(s.mcpUrl()) : "null")
                    + ",\"engines\":" + enginesJson(cc.jumpkick.cli.engine.EngineFleet.list())
                    + "}");
            return Exit.SUCCESS;
        }
        CliOutput.out(PipelineWedge.chipLine(
                Glyphs.PLAY, "Engine", GlobalConfig.nerdfont(), "Engine is running (pid " + pidStyled(s.pid()) + ")"));
        detail("Version", s.version());
        detail("Uptime", formatUptime(uptimeSeconds));
        detail("Jobs", String.valueOf(s.activePipelines()));
        // Transient by design: the sidecar trainer lives ~15s after a fresh install/upgrade, then
        // this line disappears — steady state stays exactly four/five bullets.
        if (s.aotTrainingPid() > 0) {
            detail("AOT", "training in progress (pid " + pidStyled(s.aotTrainingPid()) + ")");
        }
        String memory = formatMemory(s);
        if (memory != null) {
            detail("Memory", memory);
            String bar = memoryBar(s);
            if (bar != null) CliOutput.out(" ".repeat(VALUE_COL) + bar);
        }
        String http = describeHttp(s, paths);
        // OSC-8 hyperlink with the URL in the theme's path color so it reads AND behaves as a link.
        String webUi = s.httpUrl() != null
                ? Ansi.hyperlink(http, Theme.colorize(http, Theme.active().path()))
                : http;
        detail("Web UI", webUi);
        if (s.mcpUrl() != null) {
            String mcp = Theme.colorize(s.mcpUrl(), Theme.active().path());
            detail("MCP", mcp + "  (POST JSON-RPC; Bearer token)");
        }
        java.util.List<cc.jumpkick.cli.engine.EngineFleet.Member> fleet =
                cc.jumpkick.cli.engine.EngineFleet.list();
        if (fleet.size() > 1) printFleet(fleet);
        return Exit.SUCCESS;
    }

    /**
     * The whole fleet, one line each. Only printed when there is more than one engine, so the common
     * single-engine case reads exactly as before.
     *
     * <p>Each line leads with the identity {@code id} because that is the handle a user can act on —
     * alongside the pid, which is what {@code stop --pid} takes. Without this the only way to discover a
     * second engine was {@code ps}.
     */
    private static void printFleet(java.util.List<cc.jumpkick.cli.engine.EngineFleet.Member> fleet) {
        if (fleet.isEmpty()) return;
        CliOutput.out("");
        CliOutput.out(" Engines (" + fleet.size() + "):");
        for (cc.jumpkick.cli.engine.EngineFleet.Member m : fleet) {
            String marker = m.current() ? "*" : " ";
            StringBuilder line = new StringBuilder(" " + marker + " " + m.id() + "  pid " + pidStyled(m.pid()));
            if (m.responsive()) {
                long up = Math.max(0, (System.currentTimeMillis() - m.status().startedAtMillis()) / 1000);
                line.append("  up ").append(formatUptime(up)).append("  jobs ").append(m.status().activePipelines());
            } else {
                // Alive but not answering. Worth saying plainly: it still holds memory and its port, and it
                // is the case a user is most likely to need to stop.
                line.append("  unresponsive (alive, not answering)");
            }
            if (m.current()) line.append("   (this directory)");
            CliOutput.out(line.toString());
        }
        CliOutput.out("");
        CliOutput.out(" Stop one with `jk engine stop --pid <pid>`, or all with `jk engine stop --all`.");
    }

    private static String enginesJson(java.util.List<cc.jumpkick.cli.engine.EngineFleet.Member> fleet) {
        StringBuilder b = new StringBuilder("[");
        for (int i = 0; i < fleet.size(); i++) {
            cc.jumpkick.cli.engine.EngineFleet.Member m = fleet.get(i);
            if (i > 0) b.append(",");
            b.append("{\"id\":").append(Jsonl.quote(m.id()))
                    .append(",\"pid\":").append(m.pid())
                    .append(",\"current\":").append(m.current())
                    .append(",\"responsive\":").append(m.responsive());
            if (m.responsive()) {
                b.append(",\"startedAt\":").append(m.status().startedAtMillis())
                        .append(",\"activePipelines\":").append(m.status().activePipelines())
                        .append(",\"version\":").append(Jsonl.quote(m.status().version()));
            }
            b.append("}");
        }
        return b.append("]").toString();
    }

    /** Widest {@code "label:"} ({@code "Version:"}); values line up one space past it. */
    private static final int LABEL_FIELD = 8;

    /** Column where values (and the memory bar) begin: {@code " • "} + label field + one space. */
    private static final int VALUE_COL = 3 + LABEL_FIELD + 1;

    /** One detail line under the header: {@code  • Label:  value} (label left-aligned, values aligned). */
    private static void detail(String label, String value) {
        CliOutput.out(" " + Theme.colorize(Glyphs.BULLET, Theme.active().dim()) + " "
                + String.format("%-" + LABEL_FIELD + "s", label + ":") + " " + value);
    }

    /** The engine pid in yellow on an ANSI terminal (matching the start/stop wedges). */
    private static String pidStyled(long pid) {
        String s = Long.toString(pid);
        return Theme.active().isAnsi() ? Theme.colorize(s, Theme.active().warning()) : s;
    }

    /**
     * The embedded HTTP server's state — serving URL, bind error, or disabled ({@code docs/http.md}).
     * The serving URL carries the bearer token as a fragment ({@code #t=…}) when the engine's
     * owner-only token file is readable: fragments never leave the browser, and this line is how
     * the dashboard SPA bootstraps its token — click/open the printed URL and it's authenticated.
     */
    private static String describeHttp(EngineClient.Status s, EnginePaths.Paths paths) {
        if (s.httpUrl() != null) {
            try {
                String token = java.nio.file.Files.readString(paths.httpToken()).trim();
                if (!token.isEmpty()) return s.httpUrl() + "#t=" + token;
            } catch (java.io.IOException e) {
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
    private static String formatMemory(EngineClient.Status s) {
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
     * A stacked heap bar aligned under the memory value: {@code used} in bright-cyan, {@code
     * committed}-beyond-used in cyan, and the rest (up to {@code max}) in bright-black. {@code null}
     * when the heap max isn't observable (nothing to scale against).
     */
    private static String memoryBar(EngineClient.Status s) {
        long max = s.heapMaxBytes();
        if (max <= 0 || s.heapUsedBytes() < 0 || s.heapCommittedBytes() < 0) return null;
        int width = 50;
        int used = clamp((int) Math.round((double) s.heapUsedBytes() / max * width), 0, width);
        int committed = clamp((int) Math.round((double) s.heapCommittedBytes() / max * width), used, width);
        Theme t = Theme.active();
        return Theme.colorize("▰".repeat(used), t.brightCyan())
                + Theme.colorize("▰".repeat(committed - used), t.blue())
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
