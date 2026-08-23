// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.engine;

import cc.jumpkick.engine.EnginePaths;
import cc.jumpkick.engine.protocol.ProtoJobs;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.runtime.WorkspaceBuildListener;
import cc.jumpkick.runtime.WorkspaceResult;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;

/** Hosted verb bodies that are more than a one-line adapter call. */
final class EngineHosted {
    private EngineHosted() {}

    /**
     * Run {@code jk audit}'s plan against the engine (the worker forks engine-side). Findings
     * stream to {@code findings} as plain structured strings — the command assembles/renders the
     * report and applies the severity threshold itself.
     */
    static cc.jumpkick.run.BuildPlanResult runAudit(
            EnginePaths.Paths paths,
            EngineRequests.AuditRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            cc.jumpkick.runtime.HostedEvents.FindingObserver findings)
            throws IOException {
        return EnginePluginAdapter.stream(
                        paths,
                        ProtoJobs.auditRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.severity(),
                                req.osvBatchUrl() != null ? req.osvBatchUrl().toString() : null,
                                req.osvVulnsUrl() != null ? req.osvVulnsUrl().toString() : null),
                        "audit",
                        listenerFactory,
                        (type, line) -> findings.onFinding(
                                Jsonl.str(line, "module"),
                                Jsonl.str(line, "version"),
                                Jsonl.str(line, "vulnId"),
                                Jsonl.str(line, "severity"),
                                Jsonl.str(line, "summary")))
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
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            cc.jumpkick.runtime.HostedEvents.FileObserver files)
            throws IOException {
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                ProtoJobs.formatRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.check(),
                        req.javaStyle(),
                        req.kotlinStyle(),
                        req.optimizeImports(),
                        req.importOrder(),
                        req.removeUnusedImports(),
                        req.rewriteConfig() != null ? req.rewriteConfig().toString() : null,
                        req.offline(),
                        req.verbose()),
                "format",
                listenerFactory,
                (type, line) -> files.onFile(
                        Jsonl.str(line, "path"),
                        Jsonl.str(line, "status"),
                        Jsonl.str(line, "message"),
                        Jsonl.intValue(line, "index", 0),
                        Jsonl.intValue(line, "total", 0)));
        return new EngineRequests.FormatOutcome(
                finish.result(),
                Jsonl.intValue(finish.finishLine(), "formatChanged", -1),
                Jsonl.intValue(finish.finishLine(), "formatClean", -1),
                Jsonl.intValue(finish.finishLine(), "formatErrors", -1),
                Jsonl.intValue(finish.finishLine(), "formatTotal", -1),
                Jsonl.intValue(finish.finishLine(), "formatWorkerExit", -1));
    }

    /** Run {@code jk publish}'s plan against the engine (the publisher worker forks engine-side). */
    static EngineRequests.PublishOutcome runPublish(
            EnginePaths.Paths paths,
            EngineRequests.PublishRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        String authType;
        String user = null;
        String pass = null;
        String token = null;
        if (req.credential() instanceof cc.jumpkick.credential.RepoCredential.Basic b) {
            authType = "basic";
            user = b.username();
            pass = b.password();
        } else if (req.credential() instanceof cc.jumpkick.credential.RepoCredential.Bearer b) {
            authType = "bearer";
            token = b.token();
        } else {
            authType = "anonymous";
        }
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                ProtoJobs.publishRequest(
                        req.entryDir().toString(),
                        req.cache().toString(),
                        req.repoUrl().toString(),
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
                        req.verbose()),
                "publish",
                listenerFactory,
                (type, line) -> {});
        return new EngineRequests.PublishOutcome(
                finish.result(), Jsonl.intValue(finish.finishLine(), "publishFiles", -1));
    }

    /**
     * Run {@code jk image}'s plan against the engine (full plan + image tail engine-side).
     * {@code summaryOut} (a single-slot holder) is populated from the terminal plan-finish
     * <em>before</em> it reaches {@code listenerFactory}'s listener — whose own {@code planFinish}
     * handler renders the success tail from those fields, exactly the {@code runTest} holder
     * pattern.
     */
    static cc.jumpkick.run.BuildPlanResult runImage(
            EnginePaths.Paths paths,
            EngineRequests.ImageRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            EngineRequests.ImageSummary[] summaryOut)
            throws IOException {
        return EnginePluginAdapter.stream(
                        paths,
                        ProtoJobs.imageRequest(
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
                                req.verbose()),
                        "image",
                        listenerFactory,
                        (type, line) -> {},
                        line -> {
                            long total = Jsonl.longValue(line, "testTotal", -1);
                            cc.jumpkick.run.TestSummary testResult = total < 0
                                    ? null
                                    : new cc.jumpkick.run.TestSummary(
                                            total,
                                            Jsonl.longValue(line, "testSucceeded", 0),
                                            Jsonl.longValue(line, "testFailed", 0),
                                            Jsonl.longValue(line, "testSkipped", 0),
                                            List.of());
                            summaryOut[0] = new EngineRequests.ImageSummary(
                                    testResult,
                                    Jsonl.str(line, "imageRef"),
                                    Jsonl.str(line, "imageTarball"),
                                    Jsonl.str(line, "imageName"),
                                    Jsonl.str(line, "imageVersion"),
                                    Jsonl.str(line, "imageDaemonExe"));
                        })
                .result();
    }

    /** Run {@code jk import}'s plan against the engine, streaming progress notes to {@code notes}. */
    static EngineRequests.ImportOutcome runImport(
            EnginePaths.Paths paths,
            EngineRequests.ImportRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            cc.jumpkick.runtime.HostedEvents.NoteObserver notes)
            throws IOException {
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                ProtoJobs.importRequest(
                        req.source().toString(),
                        req.out().toString(),
                        req.baseDir().toString(),
                        req.tmpDir().toString(),
                        req.force(),
                        req.report() != null ? req.report().toString() : null,
                        req.cache().toString()),
                "import",
                listenerFactory,
                (type, line) -> notes.onNote(Jsonl.str(line, "kind"), Jsonl.str(line, "text")));
        String line = finish.finishLine();
        return new EngineRequests.ImportOutcome(
                finish.result(),
                Jsonl.intValue(line, "importExit", 1),
                Jsonl.intValue(line, "importWarnings", 0),
                Jsonl.str(line, "importError"),
                Jsonl.str(line, "importDiag"));
    }

    /**
     * Provision a Maven/Gradle distribution via the engine ({@code jk mvn}/{@code jk gradle}) — a
     * one-shot request; the exec of the provisioned tool stays in this client process (it inherits
     * this terminal's stdio, which the engine deliberately never touches).
     */
    static cc.jumpkick.runtime.HostedEvents.Provision provision(
            EnginePaths.Paths paths, Path cache, Path projectDir, Path toolsRoot, boolean noDiscover, boolean gradle)
            throws IOException {
        return EnginePluginAdapter.provision(
                paths,
                ProtoJobs.provisionRequest(
                        cache.toString(), projectDir.toString(), toolsRoot.toString(), noDiscover, gradle));
    }

    /**
     * Run {@code jk compile}'s compile-only plan against the engine — {@code jk test}'s
     * listener-factory shape, plain terminal plan-finish.
     */
    static cc.jumpkick.run.BuildPlanResult runCompile(
            EnginePaths.Paths paths,
            EngineRequests.CompileRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        return EnginePluginAdapter.stream(
                        paths,
                        ProtoJobs.compileRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.profile(),
                                req.offline(),
                                req.force(),
                                req.verbose(),
                                req.modules()),
                        "compile",
                        listenerFactory,
                        (type, line) -> {})
                .result();
    }

    /** Run {@code jk train}: package then observe under the tracing agent. */
    static cc.jumpkick.run.BuildPlanResult runTrain(
            EnginePaths.Paths paths,
            EngineRequests.TrainRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        return EnginePluginAdapter.stream(
                        paths,
                        ProtoJobs.trainRequest(
                                req.entryDir().toString(),
                                req.cache().toString(),
                                req.jdksDir() != null ? req.jdksDir().toString() : null,
                                req.graalHome() != null ? req.graalHome().toString() : null,
                                req.profile(),
                                req.force(),
                                req.skipTests(),
                                req.offline(),
                                req.verbose()),
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
        return EngineBuildListenerAdapter.runNative(paths, req, listener);
    }

    /**
     * Run {@code jk install}'s build + cache-install plan against the engine — {@link EngineClient#runTest}'s
     * exact contract ({@code testResultOut} settles before the terminal plan-finish reaches the
     * listener). The launcher-writing "make install" half stays in the calling command.
     */
    static cc.jumpkick.run.BuildPlanResult runInstall(
            EnginePaths.Paths paths,
            EngineRequests.InstallRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            cc.jumpkick.run.TestSummary[] testResultOut)
            throws IOException {
        return EngineBuildListenerAdapter.runInstall(paths, req, listenerFactory, testResultOut);
    }

    /**
     * Materialize a git checkout via the engine ({@code jk install <git-url>}'s clone half; git
     * runs in-process in the engine). The checkout path + resolved sha ride the terminal
     * plan-finish and feed the follow-up {@link #runInstall}.
     */
    static EngineRequests.GitFetchOutcome runGitFetch(
            EnginePaths.Paths paths,
            EngineRequests.GitFetchRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                ProtoJobs.gitFetchRequest(
                        req.url(),
                        req.canonicalUrl(),
                        req.ref(),
                        req.cache().toString(),
                        req.refresh(),
                        req.requireJkToml()),
                "install-git-fetch",
                listenerFactory,
                (type, line) -> {});
        String checkout = Jsonl.str(finish.finishLine(), "gitCheckout");
        return new EngineRequests.GitFetchOutcome(
                finish.result(), checkout != null ? Path.of(checkout) : null, Jsonl.str(finish.finishLine(), "gitSha"));
    }

    /**
     * Resolve a Maven-published CLI tool against the engine (the POM walk + jar fetches run
     * engine-side; see {@code ToolPlans}). The launcher write / inheritIO exec stays in the calling
     * command — it owns the user's {@code ~/.local/bin} and terminal.
     */
    static EngineRequests.ToolResolveOutcome runToolResolve(
            EnginePaths.Paths paths,
            EngineRequests.ToolResolveRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                ProtoSession.toolResolveRequest(
                        req.coord(),
                        req.with(),
                        req.bin(),
                        req.mainClass(),
                        req.repoUrl() != null ? req.repoUrl().toString() : null,
                        req.cache().toString()),
                "tool-resolve",
                listenerFactory,
                (type, line) -> {});
        return new EngineRequests.ToolResolveOutcome(
                finish.result(),
                Jsonl.str(finish.finishLine(), "toolCoord"),
                Jsonl.str(finish.finishLine(), "toolMainClass"),
                Jsonl.strArray(finish.finishLine(), "toolClasspath").stream()
                        .map(Path::of)
                        .toList());
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
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory)
            throws IOException {
        EnginePluginAdapter.HostedFinish finish = EnginePluginAdapter.stream(
                paths,
                ProtoSession.scriptPrepareRequest(
                        req.mode(),
                        req.script().toString(),
                        req.cache().toString(),
                        req.stateDir() != null ? req.stateDir().toString() : null,
                        req.repoUrl() != null ? req.repoUrl().toString() : null,
                        req.forceRecompile(),
                        req.with()),
                "script-prepare",
                listenerFactory,
                (type, line) -> {});
        String line = finish.finishLine();
        String classesDir = Jsonl.str(line, "scriptClassesDir");
        String kotlincBin = Jsonl.str(line, "scriptKotlincBin");
        String stdlib = Jsonl.str(line, "scriptStdlib");
        return new EngineRequests.ScriptPrepareOutcome(
                finish.result(),
                Jsonl.str(line, "scriptMainClass"),
                Jsonl.strArray(line, "scriptClasspath").stream().map(Path::of).toList(),
                classesDir != null ? Path.of(classesDir) : null,
                kotlincBin != null ? Path.of(kotlincBin) : null,
                stdlib != null ? Path.of(stdlib) : null);
    }

    /**
     * Run a cache maintenance op against the engine, which executes it as an idle-boundary job: the
     * mutation waits until no plan is in flight (and blocks new ones while it runs), holding the
     * cross-process {@code.prune.lock} throughout. {@code onWait} fires when the engine reports the
     * job is queued — {@code plans} in-flight builds ({@code external=true}: another process's
     * prune) — so the command can explain the pause before the progress UI starts. {@code
     * summaryOut} (a single-slot holder) is populated from the terminal plan-finish <em>before</em>
     * it reaches {@code listenerFactory}'s listener, whose own {@code planFinish} handler renders
     * the summary line from those fields — the {@code runImage} holder pattern.
     */
    static cc.jumpkick.run.BuildPlanResult runCacheMaintenance(
            EnginePaths.Paths paths,
            EngineRequests.CacheMaintRequest req,
            Function<List<cc.jumpkick.run.Task>, cc.jumpkick.run.BuildPlanListener> listenerFactory,
            ObjIntConsumer<Boolean> onWait,
            EngineRequests.CacheMaintSummary[] summaryOut)
            throws IOException {
        String requestLine = "clear".equals(req.op())
                ? ProtoSession.cacheClearRequest(
                        req.cache().toString(), req.projectRoot().toString(), req.dryRun())
                : ProtoSession.cachePruneRequest(req.op(), req.cache().toString(), req.dryRun(), req.includeJkTmp());
        return EnginePluginAdapter.stream(
                        paths,
                        requestLine,
                        "cache-" + req.op(),
                        listenerFactory,
                        (type, line) ->
                                onWait.accept(Jsonl.bool(line, "external", false), Jsonl.intValue(line, "plans", 0)),
                        line -> summaryOut[0] = new EngineRequests.CacheMaintSummary(
                                Jsonl.longValue(line, "cacheFiles", -1), Jsonl.longValue(line, "cacheBytes", -1)))
                .result();
    }
}
