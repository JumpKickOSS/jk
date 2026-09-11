// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.cli.api.CliOutput;
import cc.jumpkick.cli.api.EnsureFreshLock;
import cc.jumpkick.cli.api.GlobalOptions;
import cc.jumpkick.cli.api.PathDisplay;
import cc.jumpkick.cli.engine.EngineClient;
import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.run.BuildPlanConsole;
import cc.jumpkick.cli.run.jsonl.JsonlEnvelope;
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.jsonl.JsonFields;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk audit} — scan the lockfile against OSV. Exits {@link Exit#FAILURE} when any finding at
 * or above {@code --severity} is not covered by an unexpired {@code [audit] ignore} entry, and
 * {@link Exit#SUCCESS} otherwise. The worker runs engine-side, which also judges each finding
 * against the manifest's ignore list; this command renders the findings and applies the gate.
 */
public final class AuditCommand implements CliCommand {

    @Override
    public String name() {
        return "audit";
    }

    @Override
    public String description() {
        return "Check the locked dependencies for known vulnerabilities";
    }

    @Override
    public List<Opt> options() {
        return List.of(
                Opt.value("<level>", "Severity: CRITICAL|HIGH|MEDIUM|LOW. Default: LOW.", "--severity"),
                Opt.value("<url>", "Override the OSV batch query URL (for tests).", "--osv-batch-url")
                        .hide(),
                Opt.value("<url>", "Override the OSV vulnerability lookup URL (for tests).", "--osv-vulns-url")
                        .hide());
    }

    @Nullable
    URI osvBatchUrl;

    @Nullable
    URI osvVulnsUrl;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        GlobalOptions global = GlobalOptions.from(in);
        this.osvBatchUrl = in.value("osv-batch-url").map(URI::create).orElse(null);
        this.osvVulnsUrl = in.value("osv-vulns-url").map(URI::create).orElse(null);
        String severity = in.value("severity").orElse("LOW");
        Path projectDir = global.workingDir();
        if (!Files.exists(projectDir.resolve(ManifestPaths.MANIFEST))) {
            CommandWedge.printFail("Audit", "no jk.toml in " + PathDisplay.styledRaw(projectDir));
            return Exit.CONFIG;
        }
        int lockCode = EnsureFreshLock.ensure(projectDir, JkDirs.cache(), global, "Audit");
        if (lockCode != 0) return lockCode;
        Path lockPath = LockPaths.lockFile(projectDir);
        if (!Files.exists(lockPath)) {
            CommandWedge.printFail(
                    "Audit",
                    "no jk-lock.toml in " + PathDisplay.styledRaw(projectDir) + " (lock refresh did not produce one).");
            return Exit.CONFIG;
        }
        if (global.offline) {
            CommandWedge.printFail("Audit", offlineRefusal(osvBatchUrl));
            return Exit.FAILURE;
        }
        Path cache = JkDirs.cache();
        AuditReport.Severity threshold = AuditReport.Severity.parse(severity);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        List<AuditReport.Finding> findings = new ArrayList<>();
        BuildPlanResult result;
        try {
            result = EngineClient.runAudit(
                    EnginePaths.current(),
                    new EngineRequests.AuditRequest(
                            projectDir, cache, threshold.toString(), osvBatchUrl, osvVulnsUrl, global.offline),
                    steps -> BuildPlanConsole.chooseConsoleListener("audit", steps, mode),
                    findings::add);
        } catch (IOException e) {
            CommandWedge.printFail("Audit", e.getMessage());
            return Exit.SOFTWARE;
        }

        if (!result.success()) return Exit.FAILURE;

        AuditReport report = new AuditReport(findings);
        if (global.outputIsJson()) {
            for (AuditReport.Finding f : report.findings()) {
                CliOutput.out(findingJson(Clock.SYSTEM.millis(), f));
            }
        } else {
            CliOutput.out(report.renderMarkdown());
        }

        int exit = exitFor(report, threshold);
        if (exit != Exit.SUCCESS) {
            CommandWedge.printFail("Audit", verdict(report, threshold));
        }
        return exit;
    }

    /**
     * The gate: {@link Exit#FAILURE} when a finding at or above {@code threshold} is not covered by
     * an unexpired ignore entry, else {@link Exit#SUCCESS}. Ignored findings are reported but never
     * counted; an expired entry counts again.
     */
    static int exitFor(AuditReport report, AuditReport.Severity threshold) {
        return report.blocking(threshold).isEmpty() ? Exit.SUCCESS : Exit.FAILURE;
    }

    /** The failing wedge's text: what blocked, and how many findings an ignore entry kept out of the count. */
    static String verdict(AuditReport report, AuditReport.Severity threshold) {
        int blocking = report.blocking(threshold).size();
        int ignored = report.ignored().size();
        StringBuilder sb = new StringBuilder()
                .append(blocking)
                .append(" finding")
                .append(blocking == 1 ? "" : "s")
                .append(" at or above ")
                .append(threshold);
        if (ignored > 0) sb.append(" (").append(ignored).append(" ignored)");
        return sb.append(" — failing.").toString();
    }

    /**
     * One {@code --output json} line per finding. {@code fixedIn} rides when OSV named a fixed
     * version; {@code reason} and {@code until} ride when an ignore entry names the advisory, and
     * {@code ignoreExpired} only when that entry has lapsed — in which case {@code ignored} is
     * {@code false} and the finding counts toward the exit status again.
     */
    static String findingJson(long ts, AuditReport.Finding f) {
        JsonFields json = JsonlEnvelope.open(ts, "audit-finding")
                .string("id", f.vulnId())
                .string("package", f.module())
                .string("version", f.version())
                .string("severity", f.severity().name())
                .string("summary", f.summary())
                .optionalString("fixedIn", f.fixedIn())
                .bool("ignored", f.ignored());
        AuditReport.Ignore ignore = f.ignore();
        if (ignore != null) {
            json.string("reason", ignore.reason())
                    .optionalString(
                            "until",
                            ignore.until() == null ? null : ignore.until().toString())
                    .optionalTrue("ignoreExpired", ignore.expired());
        }
        return json.finish();
    }

    /**
     * The refusal {@code jk audit --offline} prints before the engine round trip — the same
     * decision the auditor worker makes for web/MCP triggers, worded by the same owner
     * ({@link Errors#offlineRefusal}). The explicit {@code --osv-batch-url} is named when given;
     * the default endpoint's owner is the auditor worker, off this classpath, so the fallback is
     * a stable phrase rather than a second copy of the URL.
     */
    static String offlineRefusal(@Nullable URI osvBatchUrl) {
        return Errors.offlineRefusal(osvBatchUrl != null ? osvBatchUrl.toString() : "the OSV API");
    }
}
