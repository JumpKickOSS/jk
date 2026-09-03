// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.ScriptPlans;
import cc.jumpkick.script.ScriptHeaderParser;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ProtoSession;
import java.io.BufferedWriter;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ScriptPrepareVerb implements HostedVerb {

    private final VerbHost host;

    public ScriptPrepareVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.SCRIPT_PREPARE_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("script");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-script-";
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                String mode = String.valueOf(Jsonl.str(requestLine, "mode"));
                Path script = Path.of(Jsonl.str(requestLine, "script"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                String stateDirStr = Jsonl.str(requestLine, "stateDir");
                Path stateDir = stateDirStr != null ? Path.of(stateDirStr) : JkDirs.state();
                URI repoUrl = LockVerb.repoUrlOf(requestLine);
                boolean forceRecompile = Jsonl.bool(requestLine, "forceRecompile", false);
                Files.createDirectories(cache);
                Session session = Session.defaults()
                        .withWorkingDir(script.toAbsolutePath().getParent())
                        .withCacheDir(cache)
                        .withCancel(cancelToken);
                List<Dependency> extraDeps = Jsonl.strArray(requestLine, "with").stream()
                        .map(ScriptHeaderParser::parseDependency)
                        .toList();
                BuildPlan plan =
                        switch (mode) {
                            case "java" ->
                                ScriptPlans.javaScriptBuildPlan(
                                        script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                            case "kt" ->
                                ScriptPlans.kotlinScriptBuildPlan(
                                        script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                            case "kts" -> ScriptPlans.ktsScriptBuildPlan(script, cache, repoUrl, extraDeps);
                            case "jar" -> ScriptPlans.jarBuildPlan(script, cache, repoUrl);
                            default -> throw new IllegalArgumentException("unknown script mode: " + mode);
                        };
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                return host.streamSinglePlan(plan, session, writer, result -> {
                    Path classesDir = plan.get(ScriptPlans.CLASSES_DIR).orElse(null);
                    Path kotlincBin = plan.get(ScriptPlans.KOTLINC_BIN).orElse(null);
                    Path stdlib = plan.get(ScriptPlans.KT_STDLIB).orElse(null);
                    return ProtoSession.planFinishScript(
                            dir,
                            result.success(),
                            plan.get(ScriptPlans.MAIN_CLASS).orElse(null),
                            ScriptPlans.classpathOf(plan).stream()
                                    .map(Path::toString)
                                    .toList(),
                            classesDir != null ? classesDir.toString() : null,
                            kotlincBin != null ? kotlincBin.toString() : null,
                            stdlib != null ? stdlib.toString() : null);
                });
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
                return JobOutcome.failed(Exit.FAILURE);
            }
        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
            return JobOutcome.failed(Exit.FAILURE);
        }
    }
}
