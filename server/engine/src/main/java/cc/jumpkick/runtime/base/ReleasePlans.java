// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.compile.CycloneDxSbom;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ReleaseDeliverables;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.lock.MemberRows;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.StepForecast;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.wire.runtime.ReleaseOutcome;
import cc.jumpkick.wire.runtime.TailArgs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The release lane's module step: upload what this module puts in the release. A stage run
 * uploads this host's platform-specific files; the final run the platform-neutral ones, this
 * host's platform-specific ones when no stage run put them there, and the module's SBOM. A dry run
 * lists them with their sizes and digests and sends nothing.
 */
public final class ReleasePlans {

    private ReleasePlans() {}

    /** What the module's upload did with each of its files. */
    public static final BuildPlanKey<List<ReleaseOutcome.File>> FILES =
            BuildPlanKey.list("release-files", ReleaseOutcome.File.class);

    /** The module's files another platform's stage run uploads: {@code module/platform/name} per file. */
    public static final BuildPlanKey<List<String>> EXPECTED = BuildPlanKey.list("release-expected", String.class);

    /**
     * The upload of {@code dir}'s release files {@code deliverables}, as {@code
     * ModuleJar.releaseFiles} names them; it runs after PACKAGE left them.
     */
    public static Task uploadStep(Path dir, TailArgs.Publish args, List<ReleaseDeliverables.Deliverable> deliverables) {
        return Task.builder(TaskNames.RELEASE_UPLOAD)
                .forecast(StepForecast.always("an upload is a side effect"))
                .stage(BuildStage.PUBLISH)
                .kind(TaskKind.IO)
                .ticks(1)
                .execute(ctx -> {
                    ReleaseRun run = ReleaseRun.of(dir, args);
                    JkBuild build = JkBuildParser.parse(ManifestPaths.manifestIn(dir));
                    BuildLayout layout = BuildLayout.of(dir, build);
                    ctx.label(run.dryRun() ? "release plan" : "upload to " + run.destination());
                    List<ReleaseOutcome.File> files = new ArrayList<>();
                    @Nullable ReleaseBucket bucket = run.dryRun() ? null : ReleaseBucket.open(run);
                    try {
                        for (ReleaseDeliverables.Deliverable d : deliverables) {
                            boolean wanted = d.neutral() ? !run.stage() : run.hostListed();
                            if (!wanted) continue;
                            if (!Files.isRegularFile(d.file())) {
                                ctx.error("missing-release-file", "release file missing: " + d.file());
                                throw new IllegalStateException("missing release file");
                            }
                            // The final run takes a stage run's file of this platform as it found it:
                            // a second build of a native executable need not have the same bytes.
                            ReleaseBucket.Held staged = bucket != null && !run.stage() && !d.neutral()
                                    ? bucket.held(d.name()).orElse(null)
                                    : null;
                            if (staged != null) {
                                files.add(new ReleaseOutcome.File(
                                        d.name(),
                                        d.module(),
                                        d.platform(),
                                        staged.size(),
                                        staged.sha256(),
                                        ReleaseOutcome.STAGING,
                                        ReleaseOutcome.STAGED));
                                continue;
                            }
                            files.add(send(bucket, d.name(), d.module(), d.platform(), d.file()));
                        }
                        if (!run.stage() && build.pomMetadata().sbom()) {
                            Path sbom = sbom(dir, build, layout);
                            files.add(send(bucket, String.valueOf(sbom.getFileName()), layout.artifact(), null, sbom));
                        }
                    } catch (IOException e) {
                        ctx.error("upload", Errors.text(e));
                        throw e;
                    }
                    List<String> expected = new ArrayList<>();
                    if (!run.stage()) {
                        for (String platform : run.others()) {
                            for (String name : ReleaseDeliverables.platformNames(layout, build, platform)) {
                                expected.add(layout.artifact() + "/" + platform + "/" + name);
                            }
                        }
                    }
                    ctx.put(FILES, files);
                    ctx.put(EXPECTED, expected);
                    ctx.progress(1);
                })
                .build();
    }

    private static ReleaseOutcome.File send(
            @Nullable ReleaseBucket bucket, String name, String module, @Nullable String platform, Path file)
            throws IOException, InterruptedException {
        String role = platform == null ? ReleaseOutcome.FINAL : ReleaseOutcome.STAGING;
        String status;
        if (bucket == null) {
            status = ReleaseOutcome.PLANNED;
        } else {
            status = bucket.put(name, file) == ReleaseBucket.Put.UPLOADED
                    ? ReleaseOutcome.UPLOADED
                    : ReleaseOutcome.EXISTING;
        }
        return new ReleaseOutcome.File(name, module, platform, Files.size(file), Hashing.sha256Hex(file), role, status);
    }

    /**
     * The module's CycloneDX SBOM, {@code target/sbom/<name>-<version>.cdx.json}: the document the
     * library lane uploads beside the jar, over the runtime closure the lock records.
     */
    static Path sbom(Path dir, JkBuild build, BuildLayout layout) throws IOException {
        Path lockPath = LockPaths.lockFile(dir);
        CycloneDxSbom.Closure closure = CycloneDxSbom.Closure.NONE;
        if (Files.exists(lockPath)) {
            Lockfile lock = MemberRows.view(LockfileReader.read(lockPath), lockPath, dir);
            closure = CycloneDxSbom.closure(lock, build, dir);
        }
        byte[] cdx = CycloneDxSbom.write(
                build.project().group(), build.project().name(), build.project().version(), closure);
        Path out = Files.createDirectories(layout.sbomDir()).resolve(layout.archiveRoot() + ".cdx.json");
        Files.write(out, cdx);
        return out;
    }
}
