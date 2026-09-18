// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.compat.ToolProvisioning;
import cc.jumpkick.config.Session;
import cc.jumpkick.engine.jobs.JobKind;
import cc.jumpkick.engine.jobs.JobOutcome;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.gradle.GradleBuildImport;
import cc.jumpkick.gradle.GradleModelQuery;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.mvn.PomImporter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.runtime.RepoGroupBuilder;
import cc.jumpkick.runtime.base.CompatPlans;
import cc.jumpkick.util.JkDirs;
import cc.jumpkick.wire.protocol.EngineProtocol;
import cc.jumpkick.wire.protocol.ImportRequest;
import cc.jumpkick.wire.protocol.ProtoEvents;
import java.io.BufferedWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

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

    /** The build files an import auto-detects, in the order tried — the same order the CLI uses. */
    public static final List<String> AUTO_DETECT_ORDER =
            List.of("build.gradle.kts", "build.gradle", "settings.gradle.kts", "settings.gradle", "pom.xml");

    /** Auto-detects the source build file. */
    @Override
    public String decodeJob(JobSpec spec) {
        Path dir = Path.of(spec.dir());
        Path source = null;
        for (String candidate : AUTO_DETECT_ORDER) {
            Path p = dir.resolve(candidate);
            if (Files.isRegularFile(p)) {
                source = p;
                break;
            }
        }
        if (source == null) {
            throw new IllegalArgumentException(
                    "no build file found in " + dir + " (looked for " + String.join(", ", AUTO_DETECT_ORDER) + ")");
        }
        return new ImportRequest(
                        source.toString(),
                        dir.resolve(ManifestPaths.MANIFEST).toString(),
                        spec.dir(),
                        JkDirs.tmp().toString(),
                        false,
                        null,
                        JkDirs.cache().toString())
                .encode();
    }

    /**
     * The job verdict for an import, from the plan's verdict and the importer's own exit code.
     *
     * <p>{@code CompatPlans} publishes that code as a plan result rather than failing the step, so a
     * conversion the importer refused still reaches the end of its plan. The code is carried through
     * rather than flattened: it is what {@code jk import} exits with, and a journal row that
     * disagreed with the command would be the same defect one layer down.
     */
    static JobOutcome verdict(JobOutcome planVerdict, int importerExit) {
        return PlanBurst.withToolExit(planVerdict, importerExit);
    }

    @Override
    public JobOutcome run(String requestLine, Session.CancelToken cancelToken, @Nullable BufferedWriter writer) {
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
                Cas cas = JkStores.storeCas();
                PomImporter poms = new PomImporter(RepoGroupBuilder.buildForImport(cas), cas);
                // Gradle's own evaluation runs in a fork on a provisioned distribution: the engine's
                // heap never hosts Gradle, and the wrapper's checksum vouches for the download.
                GradleBuildImport gradle = GradleBuildImport.withModel(GradleModelQuery.provisioning(
                        JkDirs.tools(), new Http(), ToolProvisioning.Policy.DEFAULT, Path.of(body.tmpDir())));
                BuildPlan plan = CompatPlans.importBuildPlan(
                        poms,
                        gradle,
                        Path.of(body.source()),
                        Path.of(body.out()),
                        baseDir,
                        Path.of(body.tmpDir()),
                        body.force(),
                        body.report() != null ? Path.of(body.report()) : null,
                        (kind, text) -> host.sendQuiet(writer, ProtoEvents.importNote(dir, kind, text)));
                // `result.success()` answers whether the conversion ran, not whether it worked:
                // CompatPlans publishes the importer's exit as a result rather than a step failure,
                // so the event carries both and the job verdict below folds them.
                JobOutcome planVerdict = host.streamSinglePlan(
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
                return verdict(planVerdict, plan.get(CompatPlans.EXIT).orElse(0));
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
