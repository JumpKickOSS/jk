// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli;

import cc.jumpkick.cli.run.TimelineOpts;
import cc.jumpkick.cli.tui.Confirm;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.JkEngineConfig;
import cc.jumpkick.config.Jobs;
import cc.jumpkick.config.PluginTuning;
import cc.jumpkick.config.PluginTunings;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Global flags that apply to every {@code jk} subcommand. Populated from a parsed {@link
 * cc.jumpkick.model.command.Invocation} via {@link #from(cc.jumpkick.model.command.Invocation)}.
 *
 * <p>Precedence for resolving each setting: explicit flag &gt; env var &gt; project {@code jk.toml}
 * {@code [config]} &gt; user-global {@code ~/.config/jk/config.toml} {@code [config]}. There is no {@code
 * /etc/jk} system layer and jk never reads {@code ~/.config} — see {@link
 * cc.jumpkick.config.ConfigSources}.
 */
public final class GlobalOptions {
    public boolean quiet;
    public boolean verbose;
    public String color;
    public boolean offline;

    /**
     * Hidden global {@code -y}/{@code --yes}: skip y/n confirmation prompts for this invocation.
     * Not shown in help; accepted on every command.
     */
    public boolean yes;

    /** {@code -F}/{@code --force}: bypass all of jk's caching for this invocation. */
    public boolean force;

    /**
     * {@code -r}/{@code --redo} (hidden alias {@code --rebuild}) — recompile/repackage/re-run tests
     * (skip freshness stamps and the action cache, both directions) while still serving locked deps
     * from the CAS: offline-safe cache distrust. {@code --force} implies it and additionally
     * re-fetches.
     */
    public boolean rebuild;

    public boolean noProgress;

    /** {@code --no-ansi} / {@code config.no-ansi} — strip ANSI; also implies no progress. */
    public boolean noAnsi;

    /**
     * {@code --no-osc} / {@code config.no-osc} — disable OSC capabilities (window title, taskbar
     * progress, desktop notifications). Independent of {@link #noAnsi}.
     */
    public boolean noOsc;

    /**
     * Resolved desktop-notification policy ({@code config.notify} / {@code JK_NOTIFY} /
     * {@code --notify}/{@code --no-notify}). Default {@link JkConfig.NotifyChoice#AUTO}.
     */
    public JkConfig.NotifyChoice notify = JkConfig.NotifyChoice.AUTO;

    /** {@code --no-timeline} — skip engine chrome-trace write under {@code target/}. */
    public boolean noTimeline;

    /** {@code --jdk <spec>} / {@code --graal <spec>}: the top JDK / GraalVM resolution tier. */
    public String jdk;

    public String graal;

    public String output;

    /**
     * True when the user asked for machine-readable <strong>live JSONL</strong> on stdout: {@code
     * -O}/{@code --output json}, {@code jsonl}, or env {@code JK_OUTPUT=json|jsonl}. Both format
     * names mean the same stream (one JSON object per line, flushed live) — see {@code
     * docs/machine-output.md}. Commands should suppress human wedge/summary lines so the stream
     * stays parseable.
     */
    public boolean outputIsJson() {
        return outputIsJson(output);
    }

    /**
     * True when this invocation asked for machine-readable JSON/JSONL on stdout — used by dispatch
     * to enter script mode before {@code run}, alongside a command's own
     * {@code CliCommand.scriptMode(Invocation)}.
     */
    public static boolean outputIsJson(Invocation in) {
        return outputIsJson(in.value("output").orElse(null));
    }

    static boolean outputIsJson(String output) {
        String resolved = output;
        if (resolved == null || resolved.isBlank()) {
            resolved = System.getenv("JK_OUTPUT");
        }
        if (resolved == null || resolved.isBlank()) return false;
        String r = resolved.trim();
        return r.equalsIgnoreCase("json") || r.equalsIgnoreCase("jsonl");
    }

    public Path configFile;
    public boolean noConfig;
    public Path directory;

    /**
     * Resolve the working directory: explicit {@code -C}/{@code --dir} if set (either on this mixin
     * or via {@link cc.jumpkick.config.SessionContext}, which captures {@code -C} placed before the
     * subcommand), otherwise the current working directory. Always returns an absolute normalised
     * path so callers can pass it into IO without worrying about whether {@code -C} was supplied.
     */
    public Path workingDir() {
        Path raw = directory;
        if (raw == null) {
            raw = SessionContext.current().config().directory().orElse(Path.of(""));
        }
        // Canonicalize symlinks (macOS /tmp → /private/tmp, symlinked checkouts): action-cache
        // task pointers hash this path's TEXT, and BuildCommand already realpaths its dir
        // a command that didn't (explain) queried a different identity than the build stored,
        // reporting a full rebuild right after a green build.
        Path abs = raw.toAbsolutePath().normalize();
        try {
            return abs.toRealPath();
        } catch (IOException e) {
            return abs; // not on disk yet (jk new target) — textual identity is all there is
        }
    }

    public boolean help;
    public boolean version;

    /** {@code --ram-percent}: per-JVM heap cap for jk's worker JVMs, or null. */
    public Double maxRamPercent;

    /**
     * {@code -j}/{@code --jobs}: concurrent module/worker budget. {@code null} = use
     * env/TOML/default; {@code 0} = all cores; {@code 1} = serial; {@code N} = cap. Resolved via
     * {@link #jobsEffective}.
     */
    public Integer jobs;

    /** {@code --jvm-arg}: extra raw flags for jk's worker JVMs (repeatable). */
    public List<String> jvmArgs = List.of();

    /**
     * The CLI-supplied JVM tuning as the highest-precedence {@link cc.jumpkick.config.PluginTuning}
     * layer. {@code gc} / {@code string-dedup} are left unset here — those come from env / {@code
     * jk.toml}; the CLI exposes only the two most common knobs.
     */
    public PluginTuning jvmCli() {
        return new PluginTuning(maxRamPercent, null, null, jvmArgs);
    }

    /**
     * Populate a {@code GlobalOptions} from a parsed {@link Invocation} — the picocli-free
     * counterpart to the {@code @Mixin}. A ported command's {@code run(Invocation)} replaces its
     * {@code @Mixin GlobalOptions global} field with {@code GlobalOptions.from(in)}; the rest of the
     * body ({@code global.workingDir}, {@code global.offline}, …) is unchanged.
     */
    public static GlobalOptions from(Invocation in) {
        // Session already holds file+env layers (and any early CLI overlays from Jk.applyCliOverrides).
        JkConfig cfg = SessionContext.current().config();

        GlobalOptions g = new GlobalOptions();
        // Boolean flags: CLI set wins; otherwise inherit true from config/env when present.
        g.quiet = in.isSet("quiet") || cfg.quietOr(false);
        g.verbose = in.isSet("verbose") || cfg.verboseOr(false);
        g.color = in.value("color").orElse(null);
        g.offline = in.isSet("offline") || cfg.offlineOr(false);
        g.yes = in.isSet("yes");
        // Confirm prompts read this for the rest of the command.
        Confirm.setAssumeYes(g.yes);
        g.force = in.isSet("force") || cfg.forceOr(false);
        // rebuild is CLI --redo only (not implied here from force; force is a separate flag).
        g.rebuild = in.isSet("redo") || cfg.rebuild().orElse(false);
        g.noAnsi = in.isSet("no-ansi") || cfg.noAnsiOr(false);
        // Progress is independent of --no-ansi: plain multi-line chrome still runs
        // unless --no-progress / quiet / json mute it.
        g.noProgress = in.isSet("no-progress") || cfg.noProgressOr(false);
        g.noOsc = in.isSet("no-osc") || cfg.noOscOr(false);
        // Notify: --no-notify > --notify > config/env (default AUTO).
        if (in.isSet("no-notify")) {
            g.notify = JkConfig.NotifyChoice.NEVER;
        } else if (in.isSet("notify")) {
            g.notify = JkConfig.NotifyChoice.ALWAYS;
        } else {
            g.notify = cfg.notifyOr(JkConfig.NotifyChoice.AUTO);
        }
        g.noTimeline = in.isSet("no-timeline");
        // Re-fold CLI/config OSC+notify into the session so mid-run readers see the same policy.
        // (applyCliOverrides already merged early argv; this covers flags after the subcommand.)
        JkConfig cliOverlay = new JkConfig(
                Optional.empty(),
                // offline + rebuild ride the overlay too: the engine reads them off the
                // session wire, and Jk.applyCliOverrides only catches exact tokens — a bundled
                // `-rq` or abbreviated `--red` / `--offl` lands here, in the parsed Invocation.
                g.offline ? Optional.of(true) : Optional.empty(),
                g.rebuild ? Optional.of(true) : Optional.empty(),
                g.noProgress ? Optional.of(true) : Optional.empty(),
                g.quiet ? Optional.of(true) : Optional.empty(),
                g.verbose ? Optional.of(true) : Optional.empty(),
                Optional.empty(),
                g.force ? Optional.of(true) : Optional.empty(),
                g.noAnsi ? Optional.of(true) : Optional.empty(),
                g.noOsc ? Optional.of(true) : Optional.empty(),
                Optional.of(g.notify),
                Optional.empty()); // build-output: config/env only (no CLI flag)
        SessionContext.installConfig(cfg.mergedWith(cliOverlay));
        // Engine-owned chrome profile; CLI only forwards the preference on the wire.
        TimelineOpts.setNoTimeline(g.noTimeline);
        g.output = in.value("output").orElse(null);
        g.configFile = in.value("config-file").map(Path::of).orElse(null);
        g.noConfig = in.isSet("no-config");
        g.directory = in.value("dir").map(Path::of).orElse(null);
        g.maxRamPercent = in.value("ram-percent")
                .map(s -> {
                    try {
                        return Double.valueOf(s.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
        g.jvmArgs = in.values("jvm-arg");
        g.jobs = in.value("jobs")
                .map(s -> {
                    try {
                        return Integer.valueOf(s.trim());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                })
                .orElse(null);
        g.jdk = in.value("jdk").orElse(null);
        g.graal = in.value("graal").orElse(null);
        // Carry the top-tier JDK/GraalVM selection and the client's JVM-tuning layers on the
        // request-scoped Session, so the toolchain resolvers (JdkResolution / GraalResolver) and
        // every worker fork read them from the request instead of process-global system properties
        // / static channels. Only the flag/env layers resolve here — the jk.toml [jvm] table is
        // engine-read at worker-fork time (thin client; keeps tomlj off the client). The working
        // dir rides along so the in-process seam overlays the same project's table.
        SessionContext.install(SessionContext.current()
                .withToolchainSpecs(g.jdk, g.graal)
                .withWorkingDir(g.workingDir())
                .withJvm(PluginTunings.resolveClient(g.jvmCli())));
        return g;
    }

    /**
     * The global options as {@link cc.jumpkick.model.command.Opt} data. The dispatcher merges these
     * into every command's option set so global flags are accepted everywhere and shown in the
     * "Global options" help section. Order here is the help-screen order.
     */
    public static List<Opt> globalOpts() {
        return List.of(
                Opt.flag("Redo work and re-fetch deps (bypass caches)", "-F", "--force"),
                Opt.flag("Redo work without re-fetching deps", "-r", "--redo").alias("--rebuild"),
                Opt.value("<FORMAT>", "Output format: text (default), or jsonl", "-O", "--output"),
                Opt.flag("Suppress informational output", "-q", "--quiet"),
                Opt.flag("Print additional diagnostic output", "-v", "--verbose"),
                Opt.flag("Disable all progress bars and spinners", "--no-progress"),
                Opt.flag("Skip writing target/jk-profile.json", "--no-timeline"),
                Opt.flag("Disable all ANSI/color/Unicode; ASCII-only output", "--no-ansi"),
                Opt.flag("Disable OSC (title, taskbar, notifications)", "--no-osc"),
                Opt.flag("Always notify when a build finishes", "--notify"),
                Opt.flag("Never send desktop notifications for this run", "--no-notify"),
                Opt.value("<WHEN>", "When to colorize output: auto, always, never", "--color"),
                Opt.value("<FILE>", "Use this jk.toml for configuration", "--config-file"),
                Opt.flag("Skip jk.toml discovery; use defaults", "--no-config"),
                Opt.value("<DIR>", "Change to this directory before running", "-C", "--dir")
                        .alias("--directory"),
                Opt.value("<N>", "Module/worker concurrency (0=max, 1=serial)", "-j", "--jobs"),
                Opt.value("<PCT>", "Worker-JVM max heap as % of RAM", "--ram-percent")
                        .alias("--max-ram-percent"),
                Opt.value("<spec>", "JDK for this run; overrides project pins", "--jdk"),
                Opt.value("<spec>", "GraalVM for jk native / GRAALVM_HOME", "--graal"),
                Opt.value("<ARG>", "Extra worker-JVM flag (repeatable)", "--jvm-arg")
                        .repeat(),
                Opt.flag("Disable network access for this run", "--offline"),
                // Hidden: assume-yes for every Confirm prompt (also covers former per-command -y/--yes).
                Opt.flag("Assume yes for all confirmation prompts", "-y", "--yes")
                        .hide(),
                Opt.flag("Print version information and exit", "-V", "--version"),
                Opt.flag("Show this help message and exit", "-h", "--help"));
    }

    /**
     * Resolved concurrent-work budget: CLI {@code -j} &gt; {@code JK_JOBS} / {@code [engine] jobs} &gt;
     * cores. Always ≥ 1.
     */
    public int jobsEffective() {
        return Jobs.resolve(Optional.ofNullable(jobs), JkEngineConfig.resolve(), System::getenv);
    }
}
