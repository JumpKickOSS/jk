// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.cli.CliOutput;
import cc.jumpkick.cli.GlobalOptions;
import cc.jumpkick.cli.run.PipelineConsole;
import cc.jumpkick.model.command.CliCommand;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.model.command.Invocation;
import cc.jumpkick.model.command.Opt;
import cc.jumpkick.run.PipelineResult;
import cc.jumpkick.runtime.HostedEvents;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
    public java.util.List<Opt> options() {
        return java.util.List.of(
                Opt.value("<level>", "Severity: CRITICAL|HIGH|MEDIUM|LOW. Default: LOW.", "--severity"),
                Opt.value("<url>", "Override the OSV batch query URL (for tests).", "--osv-batch-url")
                        .hide(),
                Opt.value("<url>", "Override the OSV vulnerability lookup URL (for tests).", "--osv-vulns-url")
                        .hide());
    }

    URI osvBatchUrl;
    URI osvVulnsUrl;

    @Override
    public int run(Invocation in) throws IOException, InterruptedException {
        GlobalOptions global = GlobalOptions.from(in);
        this.osvBatchUrl = in.value("osv-batch-url").map(java.net.URI::create).orElse(null);
        this.osvVulnsUrl = in.value("osv-vulns-url").map(java.net.URI::create).orElse(null);
        String severity = in.value("severity").orElse("LOW");
        Path projectDir = global.workingDir();
        if (!Files.exists(projectDir.resolve("jk.toml"))) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Audit", "no jk.toml in " + cc.jumpkick.cli.PathDisplay.styledRaw(projectDir)));
            return Exit.CONFIG;
        }
        int lockCode = cc.jumpkick.cli.EnsureFreshLock.ensure(projectDir, JkDirs.cache(), global, "Audit");
        if (lockCode != 0) return lockCode;
        Path lockPath = cc.jumpkick.lock.LockPaths.lockFile(projectDir);
        if (!Files.exists(lockPath)) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Audit",
                    "no jk-lock.toml in " + cc.jumpkick.cli.PathDisplay.styledRaw(projectDir)
                            + " (lock refresh did not produce one)."));
            return Exit.CONFIG;
        }
        if (global.offline) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Audit", "--offline is set; OSV queries require network access."));
            return 1;
        }
        Path cache = JkDirs.cache();
        AuditReport.Severity threshold = AuditReport.Severity.parse(severity);
        PipelineConsole.Mode mode = PipelineConsole.modeFor(global);

        // Findings accumulate here from either transport — raw worker fields in, typed report rows
        // out — so the report/threshold tail below is transport-agnostic.
        List<AuditReport.Finding> findings = new ArrayList<>();
        HostedEvents.FindingObserver observer = (module, version, vulnId, sev, summary) -> {
            if (module != null && version != null && vulnId != null) {
                findings.add(new AuditReport.Finding(
                        module, version, vulnId, summary != null ? summary : "", AuditReport.Severity.parse(sev)));
            }
        };

        PipelineResult result;
        try {
            result = cc.jumpkick.cli.engine.EngineClient.runAudit(
                    cc.jumpkick.engine.EnginePaths.current(),
                    new cc.jumpkick.cli.engine.EngineClient.AuditRequest(
                            projectDir, cache, threshold.toString(), osvBatchUrl, osvVulnsUrl),
                    steps -> PipelineConsole.chooseConsoleListener("audit", steps, mode),
                    observer);
        } catch (IOException e) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail("Audit", e.getMessage()));
            return Exit.SOFTWARE;
        }

        if (!result.success()) return 1;

        AuditReport report = new AuditReport(findings);
        if (!global.outputIsJson()) {
            CliOutput.out(report.renderMarkdown());
        }

        List<AuditReport.Finding> blocking = report.filterAtLeast(threshold);
        if (!blocking.isEmpty()) {
            CliOutput.err(cc.jumpkick.cli.tui.CommandWedge.fail(
                    "Audit", blocking.size() + " finding(s) at or above " + threshold + " — failing."));
            return 1;
        }
        return 0;
    }
}
