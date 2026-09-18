// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.run.BuildPlanListener;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TestSummary;
import cc.jumpkick.wire.EnginePaths;
import cc.jumpkick.wire.protocol.AuditFindingEvent;
import cc.jumpkick.wire.protocol.AuditRequest;
import cc.jumpkick.wire.protocol.CachePruneRequest;
import cc.jumpkick.wire.protocol.CompileRequest;
import cc.jumpkick.wire.protocol.FormatFileEvent;
import cc.jumpkick.wire.protocol.FormatRequest;
import cc.jumpkick.wire.protocol.GitFetchRequest;
import cc.jumpkick.wire.protocol.ImageRequest;
import cc.jumpkick.wire.protocol.ImportNoteEvent;
import cc.jumpkick.wire.protocol.ImportRequest;
import cc.jumpkick.wire.protocol.MvnResultsRequest;
import cc.jumpkick.wire.protocol.PlanFinishCacheEvent;
import cc.jumpkick.wire.protocol.PlanFinishFormatEvent;
import cc.jumpkick.wire.protocol.PlanFinishGitFetchEvent;
import cc.jumpkick.wire.protocol.PlanFinishImageEvent;
import cc.jumpkick.wire.protocol.PlanFinishImportEvent;
import cc.jumpkick.wire.protocol.PlanFinishPublishEvent;
import cc.jumpkick.wire.protocol.PlanFinishScriptEvent;
import cc.jumpkick.wire.protocol.PlanFinishToolEvent;
import cc.jumpkick.wire.protocol.ProvisionRequest;
import cc.jumpkick.wire.protocol.PruneWaitEvent;
import cc.jumpkick.wire.protocol.PublishRequest;
import cc.jumpkick.wire.protocol.ScriptPrepareRequest;
import cc.jumpkick.wire.protocol.ToolResolveRequest;
import cc.jumpkick.wire.protocol.TrainRequest;
import cc.jumpkick.wire.runtime.HostedEvents;
import cc.jumpkick.wire.runtime.WorkspaceBuildListener;
import cc.jumpkick.wire.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;

/** Hosted verb bodies that are more than a one-line adapter call. */
final class EngineHosted {
    private EngineHosted() {}

    /**
     * Run {@code jk audit}'s plan against the engine (the worker forks engine-side, and the engine
     * applies the manifest's ignore list). Findings stream to {@code findings}; the command
     * assembles/renders the report and applies the severity threshold itself.
     */
    static BuildPlanResult runAudit(
            EnginePaths.Paths paths,
            EngineRequests.AuditRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            HostedEvents.FindingObserver findings)
            throws IOException {
        return EnginePluginAdapter.stream(
                        paths,
                        new AuditRequest(
                                        req.entryDir().toString(),
                                        req.cache().toString(),
                                        req.severity(),
                                        req.osvBatchUrl() != null
                                                ? req.osvBatchUrl().toString()
                                                : null,
                                        req.osvVulnsUrl() != null
                                                ? req.osvVulnsUrl().toString()
                                                : null,
                                        req.offline())
                                .encode(),
                        "audit",
                        listenerFactory,
                        (type, line) -> findings.onFinding(
                                AuditFindingEvent.decode(line).toFinding()))
                .result();
    }

    /**
     * Run {@code jk format}'s plan against the engine (source collection, formatter-jar resolution,
     * and the worker fork all engine-side). Per-file results stream to {@code files}; the counts
     * (and the worker's check-mode exit code) ride the returned outcome.
     */
    static EngineRequests.FormatOutcome runFormat(
            EnginePaths.Paths paths,
            EngineRequests.FormatRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            HostedEvents.FileObserver files)
            throws IOException {
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                new FormatRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.check(),
                                req.javaStyle(),
                                req.kotlinStyle(),
                                req.optimizeImports(),
                                req.importOrder(),
                                req.removeUnusedImports(),
                                req.offline(),
                                req.verbose())
                        .encode(),
                "format",
                listenerFactory,
                (type, line) -> {
                    FormatFileEvent e = FormatFileEvent.decode(line);
                    files.onFile(e.path(), e.status(), e.message(), e.index(), e.total());
                });
        // Per-file tallies (changed/clean/errors) do not ride the wire: the CLI counts
        // them from the per-file format-file stream above. An absent count is unknown (-1)
        // here, where the record reads 0.
        String finishLine = finish.finishLine();
        PlanFinishFormatEvent e = PlanFinishFormatEvent.decode(finishLine);
        return new EngineRequests.FormatOutcome(
                finish.result(),
                Jsonl.has(finishLine, "formatTotal") ? e.total() : -1,
                Jsonl.has(finishLine, "formatWorkerExit") ? e.workerExit() : -1);
    }

    /** Run {@code jk publish}'s plan against the engine (the publisher worker forks engine-side). */
    static EngineRequests.PublishOutcome runPublish(
            EnginePaths.Paths paths,
            EngineRequests.PublishRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        String authType;
        String user = null;
        String pass = null;
        String token = null;
        if (req.credential() instanceof RepoCredential.Basic b) {
            authType = "basic";
            user = b.username();
            pass = b.password();
        } else if (req.credential() instanceof RepoCredential.Bearer b) {
            authType = "bearer";
            token = b.token();
        } else {
            authType = "anonymous";
        }
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                new PublishRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                Objects.toString(req.repoUrl(), null),
                                req.region(),
                                req.endpoint(),
                                req.jarPath() != null ? req.jarPath().toString() : null,
                                req.allowSnapshot(),
                                req.dryRun(),
                                req.keyFile() != null ? req.keyFile().toString() : null,
                                req.gpgPassphrase(),
                                req.sigstore(),
                                req.slsa(),
                                req.sbom(),
                                authType,
                                user,
                                pass,
                                token,
                                req.offline(),
                                req.verbose(),
                                req.central(),
                                req.publishingType())
                        .encode(),
                "publish",
                listenerFactory,
                (type, line) -> {});
        // An absent file count is unknown (-1) here, where the record reads 0.
        if (!Jsonl.has(finish.finishLine(), "publishFiles"))
            return new EngineRequests.PublishOutcome(finish.result(), -1, List.of(), List.of(), null, null, List.of());
        PlanFinishPublishEvent event = PlanFinishPublishEvent.decode(finish.finishLine());
        return new EngineRequests.PublishOutcome(
                finish.result(),
                event.files(),
                event.written(),
                event.bundle(),
                event.deploymentId(),
                event.deploymentState(),
                event.deploymentErrors());
    }

    /**
     * Run {@code jk image}'s plan against the engine (full plan + image tail engine-side).
     * {@code summaryOut} (a single-slot holder) is populated from the terminal plan-finish
     * <em>before</em> it reaches {@code listenerFactory}'s listener — whose own {@code planFinish}
     * handler renders the success tail from those fields, exactly the {@code runTest} holder
     * pattern.
     */
    static BuildPlanResult runImage(
            EnginePaths.Paths paths,
            EngineRequests.ImageRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            EngineRequests.ImageSummary[] summaryOut)
            throws IOException {
        return EnginePluginAdapter.stream(
                        paths,
                        new ImageRequest(
                                        req.entryDir().toString(),
                                        req.cache().toString(),
                                        req.jdksDir() != null ? req.jdksDir().toString() : null,
                                        req.mainClass(),
                                        req.registry(),
                                        req.tag(),
                                        req.tarballArg(),
                                        req.dockerExecutable(),
                                        req.skipTests(),
                                        req.offline(),
                                        req.force(),
                                        req.verbose())
                                .encode(),
                        "image",
                        listenerFactory,
                        (type, line) -> {},
                        line -> {
                            PlanFinishImageEvent e = PlanFinishImageEvent.decode(line);
                            TestSummary counts = e.total() < 0
                                    ? null
                                    : new TestSummary(e.total(), e.succeeded(), e.failed(), e.skipped(), List.of());
                            summaryOut[0] = new EngineRequests.ImageSummary(
                                    counts, e.ref(), e.tarball(), e.name(), e.version(), e.daemonExe());
                        })
                .result();
    }

    /** Run {@code jk import}'s plan against the engine, streaming progress notes to {@code notes}. */
    static EngineRequests.ImportOutcome runImport(
            EnginePaths.Paths paths,
            EngineRequests.ImportRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            HostedEvents.NoteObserver notes)
            throws IOException {
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                new ImportRequest(
                                req.source().toString(),
                                req.out().toString(),
                                Objects.toString(req.baseDir(), null),
                                req.tmpDir().toString(),
                                req.force(),
                                req.report() != null ? req.report().toString() : null,
                                req.cache().toString())
                        .encode(),
                "import",
                listenerFactory,
                (type, line) -> {
                    ImportNoteEvent e = ImportNoteEvent.decode(line);
                    notes.onNote(e.kind(), e.text());
                });
        String line = finish.finishLine();
        PlanFinishImportEvent e = PlanFinishImportEvent.decode(line);
        // An absent worker exit code is a failure here, where the record reads 0.
        int exitCode = Jsonl.has(line, "importExit") ? e.exitCode() : 1;
        return new EngineRequests.ImportOutcome(finish.result(), exitCode, e.warnings(), e.error());
    }

    /**
     * Provision a Maven/Gradle distribution via the engine ({@code jk mvn}/{@code jk gradle}) — a
     * one-shot request; the exec of the provisioned tool stays in this client process (it inherits
     * this terminal's stdio, which the engine deliberately never touches).
     */
    static HostedEvents.Provision provision(
            EnginePaths.Paths paths,
            Path projectDir,
            Path toolsRoot,
            boolean noDiscover,
            boolean acceptUnverified,
            boolean gradle)
            throws IOException {
        return EnginePluginAdapter.provision(
                paths,
                new ProvisionRequest(
                                projectDir.toString(),
                                toolsRoot.toString(),
                                noDiscover,
                                acceptUnverified,
                                gradle,
                                null,
                                null)
                        .encode());
    }

    /**
     * Journal a finished {@code jk mvn} run from the spy's {@code events} file so {@code jk
     * results} and MCP see it as they see a jk build.
     */
    static HostedEvents.MvnResults mvnResults(
            EnginePaths.Paths paths, Path projectDir, Path events, int exit, long millis, String goals)
            throws IOException {
        return EnginePluginAdapter.mvnResults(
                paths, new MvnResultsRequest(projectDir.toString(), events.toString(), exit, millis, goals).encode());
    }

    /**
     * Provision {@code tool} at {@code version} into {@code toolsRoot} — {@code jk tool install
     * kotlin:latest}. {@code dir} is still sent (the engine's request shape has always carried it)
     * but is unread on this path: the distribution comes from the name, not from a wrapper file.
     */
    static HostedEvents.Provision provisionTool(
            EnginePaths.Paths paths, String tool, String version, Path toolsRoot, boolean noDiscover)
            throws IOException {
        return EnginePluginAdapter.provision(
                paths,
                new ProvisionRequest(
                                toolsRoot.toString(), toolsRoot.toString(), noDiscover, false, false, tool, version)
                        .encode());
    }

    /**
     * Run {@code jk compile}'s compile-only plan against the engine — {@code jk test}'s
     * listener-factory shape, plain terminal plan-finish.
     */
    static BuildPlanResult runCompile(
            EnginePaths.Paths paths,
            EngineRequests.CompileRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        return EnginePluginAdapter.stream(
                        paths,
                        new CompileRequest(
                                        req.entryDir().toString(),
                                        req.cache().toString(),
                                        req.profile(),
                                        req.offline(),
                                        req.force(),
                                        req.verbose(),
                                        req.modules())
                                .encode(),
                        "compile",
                        listenerFactory,
                        (type, line) -> {})
                .result();
    }

    /** Run {@code jk train}: package then observe under the tracing agent. */
    static BuildPlanResult runTrain(
            EnginePaths.Paths paths,
            EngineRequests.TrainRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        return EnginePluginAdapter.stream(
                        paths,
                        new TrainRequest(
                                        req.entryDir().toString(),
                                        req.cache().toString(),
                                        req.jdksDir() != null ? req.jdksDir().toString() : null,
                                        req.graalHome() != null
                                                ? req.graalHome().toString()
                                                : null,
                                        req.profile(),
                                        req.force(),
                                        req.skipTests(),
                                        req.offline(),
                                        req.verbose())
                                .encode(),
                        "train",
                        listenerFactory,
                        (type, line) -> {})
                .result();
    }

    /**
     * Run {@code jk native}'s hosted module cascade against the engine, driving {@code listener}
     * exactly as {@link EngineClient#buildWorkspace} does (the cascade speaks the workspace event vocabulary; a
     * single project is a cascade of one). The returned result's {@code exitCode} is authoritative
     * — computed engine-side with {@code jk native}'s 64/4/1 mapping.
     */
    static WorkspaceResult runNative(
            EnginePaths.Paths paths, EngineRequests.NativeRequest req, WorkspaceBuildListener listener)
            throws IOException {
        return EngineJobs.runNative(paths, req, listener);
    }

    /**
     * Run {@code jk install}'s build + cache-install plan against the engine — {@link EngineClient#runTest}'s
     * exact contract ({@code testResultOut} settles before the terminal plan-finish reaches the
     * listener). The launcher-writing "make install" half stays in the calling command.
     */
    static BuildPlanResult runInstall(
            EnginePaths.Paths paths,
            EngineRequests.InstallRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            TestSummary[] testResultOut)
            throws IOException {
        return EngineJobs.runInstall(paths, req, listenerFactory, testResultOut);
    }

    /**
     * Materialize a git checkout via the engine ({@code jk install <git-url>}'s clone half; git
     * runs in-process in the engine). The checkout path + resolved sha ride the terminal
     * plan-finish and feed the follow-up {@link #runInstall}.
     */
    static EngineRequests.GitFetchOutcome runGitFetch(
            EnginePaths.Paths paths,
            EngineRequests.GitFetchRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                new GitFetchRequest(
                                req.url(),
                                req.canonicalUrl(),
                                req.ref(),
                                req.cache().toString(),
                                req.refresh(),
                                req.requireJkToml())
                        .encode(),
                "install-git-fetch",
                listenerFactory,
                (type, line) -> {});
        PlanFinishGitFetchEvent e = PlanFinishGitFetchEvent.decode(finish.finishLine());
        String checkout = e.checkout();
        return new EngineRequests.GitFetchOutcome(
                finish.result(), checkout != null ? Path.of(checkout) : null, e.sha());
    }

    /**
     * Resolve a Maven-published CLI tool against the engine (the POM walk + jar fetches run
     * engine-side; see {@code ToolPlans}). The launcher write / inheritIO exec stays in the calling
     * command — it owns the user's {@code ~/.jk/bin} and terminal.
     */
    static EngineRequests.ToolResolveOutcome runToolResolve(
            EnginePaths.Paths paths,
            EngineRequests.ToolResolveRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                new ToolResolveRequest(
                                req.coord(),
                                req.with(),
                                req.bin(),
                                req.mainClass(),
                                req.repoUrl() != null ? req.repoUrl().toString() : null,
                                req.cache().toString())
                        .encode(),
                "tool-resolve",
                listenerFactory,
                (type, line) -> {});
        PlanFinishToolEvent e = PlanFinishToolEvent.decode(finish.finishLine());
        return new EngineRequests.ToolResolveOutcome(
                finish.result(),
                e.toolCoord(),
                e.toolMainClass(),
                e.toolClasspath().stream().map(Path::of).toList());
    }

    /**
     * Prepare a loose script/jar against the engine ({@code jk tool run <file>}: header parse, dep
     * resolution, compile / kotlinc provision / manifest inspection all engine-side — see {@code
     * ScriptPlans}). The exec of the prepared program stays in the calling command — it owns this
     * terminal.
     */
    static EngineRequests.ScriptPrepareOutcome runScriptPrepare(
            EnginePaths.Paths paths,
            EngineRequests.ScriptPrepareRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory)
            throws IOException {
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                new ScriptPrepareRequest(
                                req.mode(),
                                req.script().toString(),
                                req.cache().toString(),
                                req.stateDir() != null ? req.stateDir().toString() : null,
                                req.repoUrl() != null ? req.repoUrl().toString() : null,
                                req.forceRecompile(),
                                req.with())
                        .encode(),
                "script-prepare",
                listenerFactory,
                (type, line) -> {});
        PlanFinishScriptEvent e = PlanFinishScriptEvent.decode(finish.finishLine());
        String classesDir = e.scriptClassesDir();
        String kotlincBin = e.scriptKotlincBin();
        String stdlib = e.scriptStdlib();
        return new EngineRequests.ScriptPrepareOutcome(
                finish.result(),
                e.scriptMainClass(),
                e.scriptClasspath().stream().map(Path::of).toList(),
                classesDir != null ? Path.of(classesDir) : null,
                kotlincBin != null ? Path.of(kotlincBin) : null,
                stdlib != null ? Path.of(stdlib) : null);
    }

    /**
     * Run a cache maintenance op against the engine, which executes it as an idle-boundary job: the
     * mutation waits until no plan is in flight (and blocks new ones while it runs), holding the
     * cross-process {@code .prune.lock} throughout. {@code onWait} fires when the engine reports the
     * job is queued — {@code plans} in-flight builds ({@code external=true}: another process's
     * prune) — so the command can explain the pause before the progress UI starts. {@code
     * summaryOut} (a single-slot holder) is populated from the terminal plan-finish <em>before</em>
     * it reaches {@code listenerFactory}'s listener, whose own {@code planFinish} handler renders
     * the summary line from those fields — the {@code runImage} holder pattern.
     */
    static BuildPlanResult runCacheMaintenance(
            EnginePaths.Paths paths,
            EngineRequests.CacheMaintRequest req,
            Function<List<Task>, BuildPlanListener> listenerFactory,
            ObjIntConsumer<Boolean> onWait,
            EngineRequests.CacheMaintSummary[] summaryOut)
            throws IOException {
        String requestLine = new CachePruneRequest(
                        req.op(),
                        req.cache().toString(),
                        "clear".equals(req.op()) ? Objects.toString(req.projectRoot(), null) : null,
                        req.dryRun(),
                        req.includeJkTmp())
                .encode();
        return EnginePluginAdapter.stream(
                        paths,
                        requestLine,
                        "cache-" + req.op(),
                        listenerFactory,
                        (type, line) -> {
                            PruneWaitEvent e = PruneWaitEvent.decode(line);
                            onWait.accept(e.external(), e.plans());
                        },
                        line -> {
                            // Absent sizes are unknown (-1) here, where the record reads 0.
                            PlanFinishCacheEvent e = PlanFinishCacheEvent.decode(line);
                            summaryOut[0] = new EngineRequests.CacheMaintSummary(
                                    Jsonl.has(line, "cacheFiles") ? e.cacheFiles() : -1,
                                    Jsonl.has(line, "cacheBytes") ? e.cacheBytes() : -1);
                        })
                .result();
    }
}
