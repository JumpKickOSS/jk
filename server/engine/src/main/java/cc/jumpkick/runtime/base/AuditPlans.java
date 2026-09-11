// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.audit.AuditReport;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk audit} plan: scan {@code jk-lock.toml} against OSV via {@code jk-auditor}. Findings
 * stream to the caller as the worker reports them; the ignore list, threshold and exit code are
 * judged outside the plan.
 */
public final class AuditPlans {

    private AuditPlans() {}

    /**
     * Build the audit plan for {@code lockPath}. Locates the plugin jar eagerly, so a missing plugin
     * fails here (with {@link cc.jumpkick.wire.PluginJarNotFoundException}'s side-load
     * instructions) rather than mid-plan. {@code thresholdLabel} only feeds the evaluate step's
     * label; {@code osvBatchUrl}/{@code osvVulnsUrl} are the hidden test overrides ({@code null} =
     * the real OSV endpoints).
     */
    public static BuildPlan auditBuildPlan(
            Path lockPath,
            Path cache,
            @Nullable String thresholdLabel,
            @Nullable URI osvBatchUrl,
            @Nullable URI osvVulnsUrl,
            Consumer<AuditReport.Finding> observer) {
        Path workerJar = PluginJar.AUDITOR.locate(JkStores.storeCas());

        Task readLock = Task.builder(TaskNames.READ_LOCK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("read jk-lock.toml");
                    // Validates the lockfile is readable; the plugin re-reads it.
                    LockfileReader.read(lockPath);
                    ctx.progress(1);
                })
                .build();

        Task queryOsv = Task.builder(TaskNames.QUERY_OSV)
                .kind(TaskKind.IO)
                .requires(TaskNames.READ_LOCK)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("query OSV via audit worker");
                    try {
                        runWorker(workerJar, lockPath, osvBatchUrl, osvVulnsUrl, observer, ctx::output);
                    } catch (RuntimeException e) {
                        ctx.error("osv", Errors.text(e));
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        Task evaluate = Task.builder("evaluate")
                .requires(TaskNames.QUERY_OSV)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("apply " + thresholdLabel + " threshold");
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder("audit")
                .addTask(readLock)
                .addTask(queryOsv)
                .addTask(evaluate)
                .build();
    }

    /**
     * Fork the {@code jk-auditor} plugin and stream its JSONL findings to {@code observer}.
     *
     * <p>{@code onOutput} receives the worker's non-protocol lines. {@link PluginProcess} merges the
     * child's stderr into its stdout, so those lines are the worker's own diagnostics — a stack
     * trace, a missing class, anything it printed on its way to producing nothing. Dropping them
     * (the default) is what made a worker that ran and found nothing indistinguishable from a
     * worker that died: the plan reported a step failure with no cause attached, and three verbose
     * runs produced no worker output at all.
     */
    private static void runWorker(
            Path workerJar,
            Path lockPath,
            @Nullable URI osvBatchUrl,
            @Nullable URI osvVulnsUrl,
            Consumer<AuditReport.Finding> observer,
            Consumer<String> onOutput) {
        try {
            Path spec = writeSpec(lockPath, osvBatchUrl, osvVulnsUrl);
            try {
                @Nullable String[] error = {null};
                // Bounded: a worker that fails by printing megabytes must not be the reason the
                // engine runs out of heap reporting it.
                Deque<String> tail = new ArrayDeque<>();
                int exit = new PluginClient("##JKAU:")
                        .on(PluginProtocol.FINDING, json -> {
                            AuditReport.Finding finding = finding(json);
                            if (finding != null) observer.accept(finding);
                        })
                        .on(PluginProtocol.ERROR, json -> error[0] = Jsonl.str(json, PluginProtocol.MESSAGE))
                        .passthrough(line -> {
                            onOutput.accept(line);
                            tail.addLast(line);
                            if (tail.size() > OUTPUT_TAIL_LINES) tail.removeFirst();
                        })
                        .run(PluginLaunch.javaCommand(workerJar, spec));
                if (error[0] != null) {
                    throw new RuntimeException("audit worker: " + error[0] + withTail(tail));
                }
                if (exit != 0) {
                    throw new RuntimeException("audit worker exited with code " + exit + withTail(tail));
                }
            } finally {
                Files.deleteIfExists(spec);
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("audit worker interrupted", e);
        }
    }

    /** The worker's {@code finding} line as a typed finding; {@code null} when a required field is missing. */
    private static AuditReport.@Nullable Finding finding(String json) {
        String module = Jsonl.str(json, "module");
        String version = Jsonl.str(json, "version");
        String id = Jsonl.str(json, "id");
        if (module == null || version == null || id == null) return null;
        return new AuditReport.Finding(
                module,
                version,
                id,
                Jsonl.requiredStr(json, "summary"),
                AuditReport.Severity.parse(Jsonl.str(json, "severity")),
                Jsonl.str(json, "fixedIn"));
    }

    /** How much of a failed worker's own output rides its exception. */
    private static final int OUTPUT_TAIL_LINES = 20;

    /** The worker's last words, appended to the failure that reports it — empty when it said nothing. */
    private static String withTail(Deque<String> tail) {
        if (tail.isEmpty()) return "";
        return System.lineSeparator() + String.join(System.lineSeparator(), tail);
    }

    private static Path writeSpec(Path lockPath, @Nullable URI osvBatchUrl, @Nullable URI osvVulnsUrl)
            throws IOException {
        SpecWriter spec = new SpecWriter()
                .op(PluginProtocol.OP_COMMAND, "audit", "jk-auditor")
                .configString("lockfile", lockPath.toAbsolutePath().toString());
        if (osvBatchUrl != null) spec.configString("batchUrl", osvBatchUrl.toString());
        if (osvVulnsUrl != null) spec.configString("vulnsUrl", osvVulnsUrl.toString());
        Path file = Files.createTempFile("jk-audit-", ".spec");
        Files.write(file, spec.lines(), StandardCharsets.UTF_8);
        return file;
    }
}
