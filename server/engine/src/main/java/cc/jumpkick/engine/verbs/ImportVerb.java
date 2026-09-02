// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.protocol.EngineProtocol;
import cc.jumpkick.engine.protocol.ImportRequest;
import cc.jumpkick.engine.protocol.ProtoEvents;
import cc.jumpkick.engine.protocol.ProtoSession;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.CompatPlans;
import cc.jumpkick.util.JkDirs;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public final class ImportVerb implements HostedVerb {

    private final VerbHost host;

    public ImportVerb(VerbHost host) {
        this.host = host;
    }

    @Override
    public String wireType() {
        return EngineProtocol.IMPORT_REQUEST;
    }

    @Override
    public JobKind jobKind() {
        return JobKind.plan("import");
    }

    @Override
    public VerbShape shape() {
        return new VerbShape.AsyncPlan();
    }

    @Override
    public String threadPrefix() {
        return "jk-engine-import-";
    }

    @Override
    public List<String> jobKinds() {
        return List.of("import");
    }

    /** Auto-detects the source build file — the same order the CLI uses. */
    @Override
    public String decodeJob(JobSpec spec) {
        Path dir = Path.of(spec.dir());
        Path source = null;
        for (String candidate : List.of("build.gradle.kts", "build.gradle", "pom.xml")) {
            Path p = dir.resolve(candidate);
            if (Files.isRegularFile(p)) {
                source = p;
                break;
            }
        }
        if (source == null) {
            throw new IllegalArgumentException(
                    "no build file found in " + dir + " (looked for build.gradle.kts, build.gradle, pom.xml)");
        }
        return ProtoSession.withTrigger(
                new ImportRequest(
                                source.toString(),
                                dir.resolve(ManifestPaths.MANIFEST).toString(),
                                spec.dir(),
                                JkDirs.tmp().toString(),
                                false,
                                null,
                                JkDirs.cache().toString())
                        .encode(),
                "web");
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, BufferedWriter writer) {
        try {
            try {
                ImportRequest body = ImportRequest.decode(requestLine);
                Path baseDir = Path.of(body.baseDir());
                Path cache = Path.of(body.cache());
                Session session = Session.defaults()
                        .withWorkingDir(baseDir)
                        .withCacheDir(cache)
                        .withCancel(cancelToken);
                String dir = EngineProtocol.SINGLE_PLAN_DIR;
                BuildPlan plan = CompatPlans.importBuildPlan(
                        Path.of(body.source()),
                        Path.of(body.out()),
                        baseDir,
                        Path.of(body.tmpDir()),
                        body.force(),
                        body.report() != null ? Path.of(body.report()) : null,
                        (kind, text) -> host.sendQuiet(writer, ProtoEvents.importNote(dir, kind, text)));
                return host.streamSinglePlan(
                        plan,
                        session,
                        writer,
                        result -> ProtoEvents.planFinishImport(
                                dir,
                                result.success(),
                                plan.get(CompatPlans.EXIT).orElse(1),
                                plan.get(CompatPlans.WARNINGS).orElse(0),
                                plan.get(CompatPlans.ERROR).orElse(null),
                                plan.get(CompatPlans.DIAG).orElse(null)));
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
