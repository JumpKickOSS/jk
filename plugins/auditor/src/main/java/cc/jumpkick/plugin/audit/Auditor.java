// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.audit;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.host.Errors;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * The {@code jk-auditor} plugin (op {@code command}/{@code audit}): queries the OSV vulnerability
 * API in an isolated child JVM so Jackson and the OSV HTTP client never load in jk's own process.
 * Speaks the unified plugin wire — the spec is JSONL ({@code config}: {@code lockfile},
 * {@code batchUrl}?, {@code vulnsUrl}?) and the reply is {@code finding} lines + a terminal
 * {@code done}. Exit codes are {@link Exit}: 0 success, 1 network/parse error, {@link Exit#USAGE}
 * bad command line, {@link Exit#NO_INPUT} unreadable spec, {@link Exit#DATA_ERR} spec missing a key.
 */
public final class Auditor implements Plugin {

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-auditor", "##JKAU:");
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        if (args.isEmpty()) {
            System.err.println("jk-auditor: expected spec file path as first argument");
            return Exit.USAGE;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("jk-auditor: spec file not found: " + specFile);
            return Exit.NO_INPUT;
        }
        PluginSpec spec;
        try {
            spec = PluginSpec.read(specFile);
        } catch (IOException e) {
            System.err.println("jk-auditor: could not read spec file: " + e.getMessage());
            return Exit.NO_INPUT;
        }
        PluginConfig config = spec.config();
        Optional<String> lockfile = config.stringOpt("lockfile");
        if (lockfile.isEmpty()) {
            System.err.println("jk-auditor: spec missing `lockfile` config");
            return Exit.DATA_ERR;
        }

        URI batchUrl = config.stringOpt("batchUrl").map(URI::create).orElse(null);
        URI vulnsUrl = config.stringOpt("vulnsUrl").map(URI::create).orElse(null);

        // An audit IS a network query — there is no cached answer to fall back on, and a "clean"
        // report produced without asking OSV would be a lie about safety. Refuse, naming the
        // endpoint. `jk audit --offline` is also refused client-side; this covers the web/MCP
        // trigger, which never passes through that check.
        if (spec.offline()) {
            URI endpoint = batchUrl != null ? batchUrl : OsvClient.DEFAULT_BATCH;
            out.emit(PluginReply.error("offline", Errors.offlineRefusal(endpoint.toString())));
            return 1;
        }

        Lockfile lock;
        try {
            lock = LockfileReader.read(Path.of(lockfile.get()));
        } catch (IOException e) {
            out.emit(PluginReply.error("lockfile", e.getMessage()));
            return 1;
        }

        OsvClient client = (batchUrl != null || vulnsUrl != null)
                ? new OsvClient(
                        batchUrl != null ? batchUrl : OsvClient.DEFAULT_BATCH,
                        vulnsUrl != null ? vulnsUrl : OsvClient.DEFAULT_VULNS)
                : new OsvClient();

        AuditReport report;
        try {
            report = new OsvAuditor(client).audit(lock);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            out.emit(PluginReply.error("osv", e.getMessage()));
            return 1;
        }

        for (AuditReport.Finding f : report.findings()) {
            out.emit(PluginReply.finding(
                    f.module(), f.version(), f.vulnId(), f.severity().name(), f.summary()));
        }
        return 0;
    }
}
