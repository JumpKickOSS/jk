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
import cc.jumpkick.cli.tui.CommandWedge;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.runtime.HostedEvents;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk audit} — scan the lockfile against OSV. Exits non-zero when any finding meets the
 * severity threshold. Worker runs engine-side; this command renders findings and applies the gate.
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
            return 1;
        }
        Path cache = JkDirs.cache();
        AuditReport.Severity threshold = AuditReport.Severity.parse(severity);
        BuildPlanConsole.Mode mode = BuildPlanConsole.modeFor(global);

        // Findings accumulate here from either transport — raw worker fields in, typed report rows
        // out — so the report/threshold tail below is transport-agnostic.
        List<AuditReport.Finding> findings = new ArrayList<>();
        HostedEvents.FindingObserver observer = (module, version, vulnId, sev, summary) -> {
            if (module != null && version != null && vulnId != null) {
                findings.add(new AuditReport.Finding(
                        module, version, vulnId, summary != null ? summary : "", AuditReport.Severity.parse(sev)));
            }
        };

        BuildPlanResult result;
        try {
            result = EngineClient.runAudit(
                    EnginePaths.current(),
                    new EngineRequests.AuditRequest(
                            projectDir, cache, threshold.toString(), osvBatchUrl, osvVulnsUrl, global.offline),
                    steps -> BuildPlanConsole.chooseConsoleListener("audit", steps, mode),
                    observer);
        } catch (IOException e) {
            CommandWedge.printFail("Audit", e.getMessage());
            return Exit.SOFTWARE;
        }

        if (!result.success()) return 1;

        AuditReport report = new AuditReport(findings);
        if (!global.outputIsJson()) {
            CliOutput.out(report.renderMarkdown());
        }

        List<AuditReport.Finding> blocking = report.filterAtLeast(threshold);
        if (!blocking.isEmpty()) {
            CommandWedge.printFail("Audit", blocking.size() + " finding(s) at or above " + threshold + " — failing.");
            return 1;
        }
        return 0;
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
