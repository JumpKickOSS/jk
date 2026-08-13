// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.plugin.protocol.Jsonl;
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
    public void run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                String mode = String.valueOf(Jsonl.str(requestLine, "mode"));
                Path script = Path.of(Jsonl.str(requestLine, "script"));
                Path cache = Path.of(Jsonl.str(requestLine, "cache"));
                String stateDirStr = Jsonl.str(requestLine, "stateDir");
                Path stateDir = stateDirStr != null ? Path.of(stateDirStr) : cc.jumpkick.util.JkDirs.state();
                URI repoUrl = LockVerb.repoUrlOf(requestLine);
                boolean forceRecompile = Jsonl.bool(requestLine, "forceRecompile", false);
                Files.createDirectories(cache);
                Session session = Session.defaults()
                        .withWorkingDir(script.toAbsolutePath().getParent())
                        .withCacheDir(cache)
                        .withCancel(cancelToken);
                List<cc.jumpkick.model.Dependency> extraDeps = Jsonl.strArray(requestLine, "with").stream()
                        .map(cc.jumpkick.script.ScriptHeaderParser::parseDependency)
                        .toList();
                cc.jumpkick.run.BuildPlan plan =
                        switch (mode) {
                            case "java" ->
                                cc.jumpkick.runtime.ScriptPlans.javaScriptBuildPlan(
                                        script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                            case "kt" ->
                                cc.jumpkick.runtime.ScriptPlans.kotlinScriptBuildPlan(
                                        script, cache, stateDir, repoUrl, forceRecompile, extraDeps);
                            case "kts" ->
                                cc.jumpkick.runtime.ScriptPlans.ktsScriptBuildPlan(script, cache, repoUrl, extraDeps);
                            case "jar" -> cc.jumpkick.runtime.ScriptPlans.jarBuildPlan(script, cache, repoUrl);
                            default -> throw new IllegalArgumentException("unknown script mode: " + mode);
                        };
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                host.streamSinglePlan(plan, session, writer, result -> {
                    Path classesDir = plan.get(cc.jumpkick.runtime.ScriptPlans.CLASSES_DIR)
                            .orElse(null);
                    Path kotlincBin = plan.get(cc.jumpkick.runtime.ScriptPlans.KOTLINC_BIN)
                            .orElse(null);
                    Path stdlib =
                            plan.get(cc.jumpkick.runtime.ScriptPlans.KT_STDLIB).orElse(null);
                    return ProtoSession.planFinishScript(
                            dir,
                            result.success(),
                            plan.get(cc.jumpkick.runtime.ScriptPlans.MAIN_CLASS).orElse(null),
                            cc.jumpkick.runtime.ScriptPlans.classpathOf(plan).stream()
                                    .map(Path::toString)
                                    .toList(),
                            classesDir != null ? classesDir.toString() : null,
                            kotlincBin != null ? kotlincBin.toString() : null,
                            stdlib != null ? stdlib.toString() : null);
                });
            } catch (Exception e) {
                host.sendQuiet(writer, host.requestFailedLine(null, e));
            }

        } catch (Exception e) {
            host.sendQuiet(writer, host.requestFailedLine(null, e));
        }
    }
}
