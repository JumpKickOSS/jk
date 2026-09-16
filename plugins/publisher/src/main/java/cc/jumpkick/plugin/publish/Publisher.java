// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import cc.jumpkick.cache.SourcesJar;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.config.WorkspaceResolve;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.http.Http;
import cc.jumpkick.http.OfflineException;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.SourceLayout;
import cc.jumpkick.lock.LockPaths;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.ObjectStoreConfig;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.build.PublishContext;
import cc.jumpkick.plugin.build.PublishExtension;
import cc.jumpkick.plugin.build.PublishResult;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import cc.jumpkick.publish.PublishablePom;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code jk-publisher} plugin: the terminal {@link PublishExtension} goal for Maven publishing.
 * Its plugin entry ({@link #run}) reads the engine's spec and assembles a {@link PublishContext}
 * (main jar + repo/signing config + resolved secrets) which {@link #publish} consumes to assemble,
 * sign, and upload the Maven artifacts.
 *
 * <p>The spec is line-oriented ({@code PROJECT_DIR …}, {@code JAR …}, {@code REPO_URL …},
 * {@code SIGN_GPG …}, …); the reply is {@value #PREFIX}-prefixed JSONL terminating in
 * {@code {"t":"result","ok":true,"files":N,"bytes":N}} (or {@code "ok":false,"error":…}) — {@code
 * bytes} is the uploaded payload total the engine folds into the run's I/O ledger. Exit 0 success,
 * 1 publish error, 2 bad arguments.
 */
public final class Publisher implements Plugin, PublishExtension {

    private static final String PREFIX = "##JKPU:";

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-publisher", PREFIX);
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        if (args.isEmpty()) {
            System.err.println("jk-publish-runner: expected spec file path as first argument");
            return Exit.USAGE;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("jk-publisher: spec file not found: " + specFile);
            return Exit.NO_INPUT;
        }
        PluginSpec spec;
        try {
            spec = PluginSpec.read(specFile);
        } catch (IOException e) {
            System.err.println("jk-publisher: could not read spec: " + e.getMessage());
            return Exit.NO_INPUT;
        }

        // Make the transport's offline guard real inside this worker JVM. Http.checkOffline reads
        // the ambient session, and a forked worker starts on Session.defaults() — so without this
        // the guard MavenPublisher relies on is permanently disarmed and an `--offline` publish
        // uploads. The value comes off the spec, never off this process's environment: a worker
        // inherits the engine daemon's env, not the job's.
        SessionContext.installConfig(JkConfig.empty().withOffline(spec.offline()));

        try {
            PublishResult result = publish(new SpecPublishContext(spec, out));
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("files", result.files());
            if (result.bytes() > 0) fields.put("bytes", result.bytes());
            if (result.dryRun()) fields.put("dry_run", true);
            if (!result.written().isEmpty())
                fields.put(
                        "written", result.written().stream().map(Path::toString).toList());
            if (!result.bundle().isEmpty()) fields.put("bundle", result.bundle());
            PublishResult.Deployment deployment = result.deployment();
            if (deployment != null) {
                fields.put("deploymentId", deployment.id());
                fields.put("deploymentState", deployment.state());
                if (!deployment.errors().isEmpty()) fields.put("deploymentErrors", deployment.errors());
            }
            // The facts go out before the verdict: a rejected deployment is a failure whose id and
            // every validation error the caller still has to write down.
            out.emit(PluginReply.result(fields));
            if (deployment != null && "FAILED".equals(deployment.state())) {
                out.emit(PluginReply.error("publish", rejected(deployment)));
                return 1;
            }
            return 0;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            out.emit(PluginReply.error("publish", String.valueOf(e.getMessage())));
            return 1;
        } catch (Exception e) {
            out.emit(PluginReply.error("publish", String.valueOf(e.getMessage())));
            return 1;
        }
    }

    @Override
    public PublishResult publish(PublishContext ctx) throws Exception {
        PluginConfig c = ctx.config();
        Path projectDir = ctx.moduleDir();
        Path jar = ctx.mainArtifact().orElseThrow(() -> new IOException("publish goal needs a built main artifact"));

        c.stringOpt("pluginJars")
                .ifPresent(joined -> Classpaths.split(joined).forEach(PluginTableRegistry::installFromJar));

        // Resolve workspace-sibling placeholders before rendering anything: a single-file parse
        // leaves `workspace:<name>`/`LATEST`, which would land in the POM and make the published
        // artifact unconsumable.
        JkBuild project = WorkspaceResolve.applyWorkspace(
                projectDir, JkBuildParser.parse(projectDir.resolve(ManifestPaths.MANIFEST)));

        // Assemble artifacts.
        List<MavenPublisher.Artifact> artifacts = new ArrayList<>();
        byte[] jarBytes = Files.readAllBytes(jar);
        artifacts.add(new MavenPublisher.Artifact(".jar", jarBytes));
        ctx.label("artifact " + jar.getFileName() + " (" + jarBytes.length + " bytes)");

        PublishablePom.Pom pom = PublishablePom.render(project, null, WorkspaceResolve.siblingCoordinates(projectDir));
        byte[] pomBytes = pom.xml().getBytes(StandardCharsets.UTF_8);
        artifacts.add(new MavenPublisher.Artifact(".pom", pomBytes));
        ctx.label(
                "artifact " + project.project().name() + "-" + project.project().version() + ".pom");

        BuildLayout layout = BuildLayout.of(projectDir, project);
        boolean central = c.bool("centralPortal", false);
        libraryArtifacts(ctx, project, projectDir, layout, central, artifacts);

        if (c.bool("slsa", false)) {
            String jarFilename = jar.getFileName().toString();
            SlsaProvenance.BuildContext buildCtx = new SlsaProvenance.BuildContext(
                    "https://github.com/buildjk/jk",
                    "https://buildjk.dev/jk-build/v1",
                    UUID.randomUUID().toString(),
                    Instant.now(),
                    Instant.now(),
                    Map.of("configRef", ManifestPaths.MANIFEST),
                    Map.of(
                            "group", project.project().group(),
                            "artifact", project.project().name(),
                            "version", project.project().version(),
                            "jdk",
                                    project.project().jdk() == null
                                            ? ""
                                            : project.project().jdk()));
            byte[] provenance = SlsaProvenance.generate(
                    List.of(new SlsaProvenance.Subject(jarFilename, Checksums.sha256Hex(jarBytes))), buildCtx);
            artifacts.add(new MavenPublisher.Artifact(".intoto.json", provenance));
            ctx.label("artifact " + project.project().name() + "-"
                    + project.project().version() + ".intoto.json");
        }

        List<Path> written = new ArrayList<>();
        if (c.bool("sbom", false)) sbom(ctx, projectDir, project, artifacts, written);

        if (central) return publishCentral(ctx, c, project, layout, artifacts, written);

        if (c.bool("dryRun", false)) {
            return PublishResult.dryRun(artifacts.size()).withWritten(written);
        }

        // Everything past here talks to someone else's server — the PUTs, and Sigstore's Fulcio/
        // Rekor round trip when keyless signing is on. Refuse before any of it, naming the target,
        // rather than discovering it one layer down: `--offline` that uploads anyway is worse than
        // no `--offline` at all.
        URI repoUrl = URI.create(c.string("repoUrl"));
        if (ctx.offline()) {
            throw new OfflineException(repoUrl);
        }

        // Load signing.
        SigningOptions signing;
        GpgSigner gpg = c.bool("signGpg", false)
                ? GpgSigner.fromKeyFile(
                        Path.of(c.string("gpgKeyFile")),
                        ctx.secret("gpgPassphrase").map(String::toCharArray).orElse(new char[0]))
                : null;
        SigstoreSigner sigstoreSigner = c.bool("signSigstore", false) ? KeylessSigstoreSigner.sigstorePublic() : null;
        signing = new SigningOptions(gpg, sigstoreSigner);

        // Upload.
        try {
            RepoCredential cred =
                    switch (c.string("repoAuthType").toLowerCase(Locale.ROOT)) {
                        case "basic" ->
                            new RepoCredential.Basic(
                                    ctx.secret("repoUser").orElse(""),
                                    ctx.secret("repoPass").orElse(""));
                        case "bearer" ->
                            new RepoCredential.Bearer(ctx.secret("repoToken").orElse(""));
                        default -> new RepoCredential.Anonymous();
                    };
            ObjectStoreConfig objectStore = new ObjectStoreConfig(
                    c.stringOpt("objectStoreRegion").filter(s -> !s.isBlank()).orElse(null),
                    c.stringOpt("objectStoreEndpoint").filter(s -> !s.isBlank()).orElse(null),
                    null,
                    null,
                    null);
            MavenPublisher publisher = MavenPublisher.withObjectStore(repoUrl, cred, objectStore);

            MavenPublisher.Result result = publisher.publish(project.project(), artifacts, signing);
            for (Map.Entry<String, Integer> e : result.statusByPath().entrySet()) {
                ctx.label("upload " + e.getKey() + " → " + e.getValue());
            }
            // No partial-failure check: publish() throws on the first non-2xx PUT, so a Result here
            // is a fully successful upload.
            return PublishResult.uploaded(result.statusByPath().size(), result.bytes())
                    .withWritten(written);
        } finally {
            if (signing.sigstore() instanceof AutoCloseable closeable) {
                try {
                    closeable.close();
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    /**
     * The library artefacts {@code jk package} wrote ride along whenever they are on disk; a
     * project that asked for a sources jar it did not build gets one assembled here. Central needs
     * both jars and the {@code [publish]} metadata, and is refused with the fix before anything is
     * signed.
     */
    private static void libraryArtifacts(
            PublishContext ctx,
            JkBuild project,
            Path projectDir,
            BuildLayout layout,
            boolean central,
            List<MavenPublisher.Artifact> artifacts)
            throws IOException {
        Path sourcesOnDisk = layout.sourcesJar();
        if (Files.isRegularFile(sourcesOnDisk)) {
            artifacts.add(new MavenPublisher.Artifact("-sources.jar", Files.readAllBytes(sourcesOnDisk)));
            ctx.label("artifact " + sourcesOnDisk.getFileName());
        } else if (project.project().sourcesMode().publishSources() && !central) {
            boolean compact = SourceLayout.isSimpleLayout(project.project(), projectDir);
            List<Path> sourceRoots = compact
                    ? List.of(projectDir.resolve("src"))
                    : List.of(projectDir.resolve("src/main/java"), projectDir.resolve("src/main/kotlin"));
            artifacts.add(new MavenPublisher.Artifact("-sources.jar", SourcesJar.build(sourceRoots)));
            ctx.label("artifact " + project.project().name() + "-"
                    + project.project().version() + "-sources.jar");
        }
        Path javadocOnDisk = layout.javadocJar();
        if (Files.isRegularFile(javadocOnDisk)) {
            artifacts.add(new MavenPublisher.Artifact("-javadoc.jar", Files.readAllBytes(javadocOnDisk)));
            ctx.label("artifact " + javadocOnDisk.getFileName());
        }
        if (!central) return;
        List<String> missing = new ArrayList<>();
        if (!Files.isRegularFile(sourcesOnDisk)) missing.add(sourcesOnDisk.toString());
        if (!Files.isRegularFile(javadocOnDisk)) missing.add(javadocOnDisk.toString());
        if (!missing.isEmpty()) {
            throw new IOException("Maven Central requires the sources and javadoc jars beside the jar, and the build"
                    + " has not written them: " + String.join(", ", missing)
                    + " — run `jk build` (a library packages both; an application needs `sources = \"always\"`).");
        }
        List<String> gaps = project.pomMetadata().centralGaps(project.project().description());
        if (!gaps.isEmpty()) {
            throw new IOException("Maven Central requires POM metadata this manifest lacks: " + String.join(", ", gaps)
                    + ". Add a [publish] table to jk.toml (or the workspace root):\n" + PUBLISH_TABLE_EXAMPLE);
        }
    }

    /** The fix a refusal prints when the manifest carries no Central-grade metadata. */
    static final String PUBLISH_TABLE_EXAMPLE = """
            [publish]
            url = "https://example.com/widget"
            licenses = [{ name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0" }]
            developers = [{ id = "ada", name = "Ada Lovelace", email = "ada@example.com" }]
            scm = { url = "https://github.com/example/widget", connection = "scm:git:https://github.com/example/widget.git", developer-connection = "scm:git:ssh://git@github.com/example/widget.git" }
            """;

    /** The fix a refusal prints when Central is asked for without a signing key. */
    public static final String SIGNING_REQUIRED =
            "Maven Central requires a GPG signature on every file: pass --sign --key-file <secret-key.asc>"
                    + " (the passphrase via --key-passphrase or JK_GPG_PASSPHRASE).";

    /**
     * The Central Portal flow: sign every artifact into one {@link CentralBundle}, upload it, and
     * poll the deployment to its verdict. A dry run writes the bundle under the module's {@code
     * target/publish/} and lists its entries instead.
     */
    private static PublishResult publishCentral(
            PublishContext ctx,
            PluginConfig c,
            JkBuild project,
            BuildLayout layout,
            List<MavenPublisher.Artifact> artifacts,
            List<Path> written)
            throws IOException, InterruptedException {
        if (!c.bool("signGpg", false)) throw new IOException(SIGNING_REQUIRED);
        GpgSigner gpg = GpgSigner.fromKeyFile(
                Path.of(c.string("gpgKeyFile")),
                ctx.secret("gpgPassphrase").map(String::toCharArray).orElse(new char[0]));
        CentralBundle.Bundle bundle = CentralBundle.build(project.project(), artifacts, gpg);
        for (String entry : bundle.entries()) ctx.label("bundle " + entry);

        if (c.bool("dryRun", false)) {
            Path zip = Files.createDirectories(layout.moduleTargetDir().resolve("publish"))
                    .resolve("central-bundle.zip");
            Files.write(zip, bundle.zip());
            written.add(zip);
            ctx.label("wrote " + zip);
            return PublishResult.dryRun(artifacts.size()).withWritten(written).withBundle(bundle.entries());
        }

        URI portalUrl = URI.create(c.stringOpt("repoUrl").orElse(CentralPortal.DEFAULT_URL.toString()));
        if (ctx.offline()) throw new OfflineException(portalUrl);
        CentralPortal.PublishingType type =
                CentralPortal.PublishingType.parse(c.stringOpt("publishingType").orElse(null));
        RepoCredential cred =
                switch (c.string("repoAuthType").toLowerCase(Locale.ROOT)) {
                    case "basic" ->
                        new RepoCredential.Basic(
                                ctx.secret("repoUser").orElse(""),
                                ctx.secret("repoPass").orElse(""));
                    case "bearer" ->
                        new RepoCredential.Bearer(ctx.secret("repoToken").orElse(""));
                    default -> new RepoCredential.Anonymous();
                };
        CentralPortal portal = new CentralPortal(portalUrl, cred, new Http());
        String name = project.project().name() + "-" + project.project().version();
        String id = portal.upload(bundle.zip(), name, type);
        ctx.label("upload " + name + ".zip (" + bundle.zip().length + " bytes) → deployment " + id);
        CentralPortal.Status status =
                portal.awaitTerminal(id, type, CentralPortal.DEFAULT_TIMEOUT, Clock.SYSTEM, Thread::sleep);
        ctx.label("deployment " + id + " " + status.state());
        for (String error : status.errors()) ctx.label("  " + error);
        return PublishResult.uploaded(bundle.entries().size(), bundle.zip().length)
                .withWritten(written)
                .withBundle(bundle.entries())
                .withDeployment(new PublishResult.Deployment(id, status.state(), status.errors()));
    }

    /** The one-line verdict for a deployment the Portal refused, every error on its own line. */
    static String rejected(PublishResult.Deployment deployment) {
        StringBuilder sb = new StringBuilder("Central Portal rejected deployment ")
                .append(deployment.id())
                .append(" (")
                .append(deployment.state())
                .append(")");
        if (deployment.errors().isEmpty())
            return sb.append(": the Portal listed no errors").toString();
        sb.append(":");
        for (String error : deployment.errors()) sb.append("\n  - ").append(error);
        return sb.toString();
    }

    /**
     * The SBOM sidecars: uploaded beside the artifact, and the same documents left under the
     * module's target/ so a release takes the bill of materials from disk — with or without an
     * upload — instead of unzipping a jar.
     */
    private static void sbom(
            PublishContext ctx,
            Path projectDir,
            JkBuild project,
            List<MavenPublisher.Artifact> artifacts,
            List<Path> written)
            throws IOException {
        Path lockPath = LockPaths.lockFile(projectDir);
        Lockfile lock = Files.exists(lockPath) ? LockfileReader.read(lockPath) : null;
        byte[] cdx = Sbom.cyclonedx(project, lock);
        byte[] spdxBytes = Sbom.spdx(project, lock);
        artifacts.add(new MavenPublisher.Artifact("-cyclonedx.json", cdx));
        artifacts.add(new MavenPublisher.Artifact("-spdx.json", spdxBytes));
        ctx.label("artifact cyclonedx+spdx (" + (cdx.length + spdxBytes.length) + " bytes)");
        Path sbomDir =
                Files.createDirectories(BuildLayout.of(projectDir, project).sbomDir());
        String stem = project.project().name() + "-" + project.project().version();
        written.add(Files.write(sbomDir.resolve(stem + ".cdx.json"), cdx));
        written.add(Files.write(sbomDir.resolve(stem + ".spdx.json"), spdxBytes));
        for (Path p : written) ctx.label("wrote " + projectDir.relativize(p));
    }

    /** The generic terminal context assembled from the plugin spec. */
    private record SpecPublishContext(PluginSpec spec, ProtocolWriter out) implements PublishContext {
        @Override
        public PluginConfig config() {
            return spec.config();
        }

        @Override
        public ProjectFacts project() {
            return spec.project();
        }

        @Override
        public Path moduleDir() {
            return Objects.requireNonNull(spec.moduleDir(), "spec missing layout.moduleDir");
        }

        @Override
        public Optional<Path> mainArtifact() {
            return Optional.ofNullable(spec.artifactPath());
        }

        @Override
        public List<PackageIo.RuntimeEntry> runtimeEntries() {
            return spec.entries();
        }

        @Override
        public Path javaHome() {
            return Objects.requireNonNull(spec.javaHome(), "spec missing java-home.path");
        }

        @Override
        public boolean offline() {
            return spec.offline();
        }

        @Override
        public Optional<String> secret(String key) {
            return spec.secret(key);
        }

        @Override
        public void label(String text) {
            out.emit(PluginReply.label(text));
        }
    }
}
