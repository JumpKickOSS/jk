// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.engine.plugin.BuiltInPluginJars;
import cc.jumpkick.engine.plugin.PluginClient;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Errors;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.GitRefSpec;
import cc.jumpkick.model.GitSource;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanKey;
import cc.jumpkick.run.BuildStage;
import cc.jumpkick.run.Task;
import cc.jumpkick.run.TaskKind;
import cc.jumpkick.run.TaskNames;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * {@code jk publish} plan: validate, then assemble/sign/upload via {@code jk-publisher}.
 * Credentials and GPG passphrase arrive client-resolved in {@link Request} (engine env is not
 * this invocation's); secrets go through a 0600 spec file only.
 */
public final class PublishPlans {

    private PublishPlans() {}

    /**
     * Everything the publish plan needs beyond the project directory — the command's validated
     * flags plus the client-resolved credential/passphrase. {@code keyFile} is non-null only when
     * {@code --sign} was set (the command already enforced {@code --sign} ⇒ {@code --key-file}).
     */
    public record Request(
            URI repoUrl,
            @Nullable String region,
            @Nullable String endpoint,
            @Nullable Path jarPath,
            boolean allowSnapshot,
            boolean dryRun,
            @Nullable Path keyFile,
            @Nullable String gpgPassphrase,
            boolean sigstore,
            boolean slsa,
            boolean sbom,
            RepoCredential credential) {}

    public static final BuildPlanKey<JkBuild> PROJECT = BuildPlanKey.scalar("project", JkBuild.class);
    public static final BuildPlanKey<Path> JAR = BuildPlanKey.scalar("jar", Path.class);

    /** The plugin's uploaded-file count (0 for {@code --dry-run}), populated by the publish step. */
    public static final BuildPlanKey<Integer> FILES = BuildPlanKey.scalar("pub-files", Integer.class);

    /** Build the publish plan for {@code projectDir}. Locates the plugin jar eagerly (fail fast, with side-load hints). */
    public static BuildPlan publishBuildPlan(Path projectDir, Path cache, Request req) {
        Path workerJar = PluginJar.PUBLISHER.locate(JkStores.storeCas());
        Path jkBuildPath = projectDir.resolve(ManifestPaths.MANIFEST);

        Task parseBuild = Task.builder(TaskNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    ctx.label("parse jk.toml + validate version");
                    JkBuild project = JkBuildParser.parse(jkBuildPath);
                    if (project.project().version().endsWith("-SNAPSHOT") && !req.allowSnapshot()) {
                        ctx.error(
                                "snapshot",
                                "refusing to publish a SNAPSHOT version "
                                        + "(use --allow-snapshot, or rename to -dev.N / -rc.N "
                                        + "per publish policy).");
                        throw new RuntimeException("snapshot refused");
                    }
                    // A branch-tracked git dep is locked in jk-lock.toml, but its pin moves on the next
                    // `jk update --git`/`jk fetch` — not a stable reference for external consumers
                    // of the published artifact. Refuse until it's pinned to a tag/rev instead.
                    Dependency branchGit = firstBranchGitDep(project);
                    if (branchGit != null) {
                        ctx.error(
                                "branch-git-dep",
                                "refusing to publish: dependency `"
                                        + branchGit.module()
                                        + "` tracks a git branch, which is not a stable reference for"
                                        + " published consumers. Pin it to an immutable git tag/rev"
                                        + " (or a released `version = \"…\"`) before publishing.");
                        throw new RuntimeException("branch git dependency refused");
                    }
                    BuildLayout layout = BuildLayout.of(projectDir, project);
                    // A assembly project's self-contained artifact IS the fat jar (a build plugin,
                    // say, shades jk-plugin-sdk in) — publish that as the coordinate, not the thin
                    // main jar a consumer couldn't fork. An explicit --jar still wins.
                    Path jar = req.jarPath() != null
                            ? req.jarPath()
                            : project.assembly() ? layout.assemblyJar() : layout.mainJar();
                    if (!Files.exists(jar)) {
                        ctx.error("missing-jar", "jar not found at " + jar + " — run `jk build` first or pass --jar.");
                        throw new RuntimeException("missing jar");
                    }
                    ctx.put(PROJECT, project);
                    ctx.put(JAR, jar);
                    ctx.progress(1);
                })
                .build();

        Task publish = Task.builder("publish")
                .stage(BuildStage.PUBLISH)
                .kind(TaskKind.IO)
                .requires(TaskNames.PARSE_BUILD)
                .ticks(1)
                .execute(ctx -> {
                    Path jar = ctx.require(JAR);
                    ctx.label(req.dryRun() ? "dry-run — assembling publish bundle" : "publish to " + req.repoUrl());
                    try {
                        ctx.put(FILES, runWorker(workerJar, projectDir, jar, req));
                    } catch (RuntimeException e) {
                        ctx.error("publish", Errors.text(e));
                        throw e;
                    }
                    ctx.progress(1);
                })
                .build();

        return BuildPlan.builder("publish")
                .stateKeys(PROJECT, JAR, FILES)
                .addTask(parseBuild)
                .addTask(publish)
                .build();
    }

    /** Fork the {@code jk-publisher} plugin; returns the uploaded-file count. */
    private static int runWorker(Path workerJar, Path projectDir, Path jar, Request req) {
        try {
            Path spec = writeSpec(projectDir, jar, req);
            try {
                int[] files = {0};
                String[] error = {null};
                StringBuilder workerDiag = new StringBuilder();
                int exit = new PluginClient("##JKPU:")
                        .on(PluginProtocol.RESULT, json -> {
                            files[0] = Jsonl.intValue(json, "files", 0);
                            // The worker knows what it PUT (body lengths); the run's ledger shows it
                            // as remote-up on the dashboard.
                            SessionContext.current().io().remoteUp(Jsonl.longValue(json, "bytes", 0L));
                        })
                        .on(PluginProtocol.ERROR, json -> error[0] = Jsonl.str(json, PluginProtocol.MESSAGE))
                        .passthrough(line -> workerDiag.append(line).append('\n'))
                        .run(PluginLaunch.javaCommand(workerJar, spec));
                if (exit != 0) {
                    String diag =
                            workerDiag.length() > 0 ? workerDiag.toString().trim() : null;
                    throw new RuntimeException("publish worker failed"
                            + (error[0] != null
                                    ? ": " + error[0]
                                    : diag != null ? ": " + diag : " (exit " + exit + ")"));
                }
                return files[0];
            } finally {
                Files.deleteIfExists(spec);
            }
        } catch (IOException e) {
            throw new RuntimeException(e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("publish worker interrupted", e);
        }
    }

    private static Path writeSpec(Path projectDir, Path jar, Request req) throws IOException {
        SpecWriter sw = new SpecWriter()
                .op(PluginProtocol.OP_PUBLISH, null, "jk-publisher")
                .configString("repoUrl", req.repoUrl().toString())
                .configBool("dryRun", req.dryRun())
                .configBool("slsa", req.slsa())
                .configBool("sbom", req.sbom())
                .configBool("signSigstore", req.sigstore());

        // Credential (resolved client-side so neither the engine nor the plugin needs env/keychain access).
        if (req.credential() instanceof RepoCredential.Basic b) {
            sw.configString("repoAuthType", "basic")
                    .secret("repoUser", b.username())
                    .secret("repoPass", b.password());
        } else if (req.credential() instanceof RepoCredential.Bearer b) {
            sw.configString("repoAuthType", "bearer").secret("repoToken", b.token());
        } else {
            sw.configString("repoAuthType", "anonymous");
        }

        if (req.keyFile() != null) {
            sw.configBool("signGpg", true)
                    .configString("gpgKeyFile", req.keyFile().toAbsolutePath().toString());
            if (req.gpgPassphrase() != null) sw.secret("gpgPassphrase", req.gpgPassphrase());
        }
        if (req.region() != null && !req.region().isBlank()) sw.configString("objectStoreRegion", req.region());
        if (req.endpoint() != null && !req.endpoint().isBlank()) sw.configString("objectStoreEndpoint", req.endpoint());
        sw.artifact(jar);
        sw.layout(Map.of("moduleDir", projectDir));
        String pluginJars = Classpaths.join(BuiltInPluginJars.tablePluginJars());
        if (!pluginJars.isEmpty()) sw.configString("pluginJars", pluginJars);

        // Use a 0600 temp file so credentials aren't world-readable.
        Path spec;
        try {
            spec = Files.createTempFile(
                    "jk-publish-",
                    ".spec",
                    PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX filesystem (Windows): fall back to default permissions.
            spec = Files.createTempFile("jk-publish-", ".spec");
        }
        Files.write(spec, sw.lines(), StandardCharsets.UTF_8);
        return spec;
    }

    /**
     * The first branch-tracked git dependency declared in any scope, or {@code null} if none. Its
     * lockfile pin moves whenever the branch tip is re-resolved, so it cannot appear in a published
     * POM. A tag/rev git dep is materialized to a real, stable coordinate and is fine.
     */
    private static @Nullable Dependency firstBranchGitDep(JkBuild project) {
        for (List<Dependency> deps : project.dependencies().byScope().values()) {
            for (Dependency d : deps) {
                GitSource git = d.gitSource();
                if (git != null && git.ref() instanceof GitRefSpec.Branch) {
                    return d;
                }
            }
        }
        return null;
    }
}
