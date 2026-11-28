// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Errors;
import cc.jumpkick.host.SlsaProvenance;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.plugin.Plugin;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.PluginManifest;
import cc.jumpkick.plugin.ProjectFacts;
import cc.jumpkick.plugin.ProtocolWriter;
import cc.jumpkick.plugin.RuntimeEntry;
import cc.jumpkick.plugin.build.ImageContext;
import cc.jumpkick.plugin.build.ImageExtension;
import cc.jumpkick.plugin.build.ImageResult;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.PluginReply;
import cc.jumpkick.plugin.protocol.PluginSpec;
import cc.jumpkick.sigstore.Dsse;
import cc.jumpkick.sigstore.KeylessSigstoreSigner;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import org.jspecify.annotations.Nullable;

/**
 * The {@code jk-image-builder} plugin: the terminal {@link ImageExtension} goal for OCI images. Its
 * plugin entry ({@link #run}) reads the engine's spec, assembles an {@link ImageContext} from the
 * finished module (main jar + runtime closure + {@code [image]} settings), and hands it to {@link
 * #image}, which drives {@link ImageBuilder} (Jib) to a tarball, the local daemon, or a registry.
 *
 * <p>The spec is line-oriented ({@code MAIN_JAR /abs/app.jar}, {@code BASE …}, {@code TARBALL …},
 * {@code DEP_JAR …}, …); the reply is {@value #PREFIX}-prefixed JSONL, terminating in
 * {@code {"t":"result","ok":true,"ref":"…"}} (a push adds {@code "digest"}; a tarball sends {@code
 * "tarball"} instead), {@code {"t":"result",
 * "ok":false,"error":"…"}} on failure. Exit codes are {@link Exit}: 0 success, 1 build/push
 * error, {@link Exit#USAGE} bad command line, {@link Exit#NO_INPUT} unreadable spec.
 */
public final class OciImageBuilder implements Plugin, ImageExtension {

    private static final String PREFIX = "##JKIM:";

    /** The media type of a Sigstore bundle stored as an OCI artifact's layer. */
    static final String BUNDLE_TYPE = "application/vnd.dev.sigstore.bundle+json";

    /** Opens the keyless signer of a signed push; a test hands in one that needs no Sigstore. */
    private final Callable<KeylessSigstoreSigner> keyless;

    public OciImageBuilder() {
        this(KeylessSigstoreSigner::sigstorePublic);
    }

    OciImageBuilder(Callable<KeylessSigstoreSigner> keyless) {
        this.keyless = keyless;
    }

    @Override
    public PluginManifest manifest() {
        return new PluginManifest("jk-image-builder", PREFIX);
    }

    @Override
    public int run(List<String> args, ProtocolWriter out) {
        if (args.isEmpty()) {
            System.err.println("jk-image-runner: expected spec file path");
            return Exit.USAGE;
        }
        Path specFile = Path.of(args.get(0));
        if (!Files.isRegularFile(specFile)) {
            System.err.println("jk-image-builder: spec file not found: " + specFile);
            return Exit.NO_INPUT;
        }
        PluginSpec spec;
        try {
            spec = PluginSpec.read(specFile);
        } catch (IOException e) {
            System.err.println("jk-image-builder: could not read spec: " + e.getMessage());
            return Exit.NO_INPUT;
        }

        try {
            ImageResult result = image(new SpecImageContext(spec, out));
            Map<String, Object> fields = new LinkedHashMap<>();
            if (result.tarball() != null) {
                fields.put("tarball", result.tarball().toString());
            } else {
                fields.put("ref", result.reference());
                if (result.daemon()) fields.put("daemon", true);
                if (result.digest() != null) fields.put("digest", result.digest());
            }
            out.emit(PluginReply.result(fields));
            return 0;
        } catch (Exception e) {
            out.emit(PluginReply.error("image", String.valueOf(e.getMessage())));
            return 1;
        }
    }

    @Override
    public ImageResult image(ImageContext ctx) throws Exception {
        PluginConfig c = ctx.config();
        String artifact = c.string("artifact");
        String version = c.string("version");
        List<Integer> ports = new ArrayList<>();
        for (String p : c.stringList("ports")) {
            try {
                ports.add(Integer.parseInt(p));
            } catch (NumberFormatException ignored) {
                // skip malformed port
            }
        }
        Map<String, String> env = splitPairs(c.stringList("env"));
        Map<String, String> labels = splitPairs(c.stringList("labels"));
        List<String> platforms = c.stringList("platforms");
        String base = c.stringOpt("base").orElse(null);
        String registry = c.stringOpt("registry").orElse(null);
        String tag = c.stringOpt("tag").orElse(null);
        String dockerExecutable = c.stringOpt("dockerExecutable").orElse(null);
        boolean aotCache = c.bool("aotCache").orElse(false);
        boolean referrers = c.bool("referrers").orElse(true);

        // Every mode — tarball, daemon load, push — starts by pulling a base image through Jib,
        // which has no notion of jk's --offline and revalidates a mutable tag whatever the local
        // cache holds. The engine's own digest pin already refuses offline (BaseImageDigest), so
        // refusing here keeps the two halves of an image build saying the same thing.
        if (ctx.offline()) {
            throw new IOException(Errors.offlineRefusal((base == null ? "the default base image" : base)
                    + (registry == null ? "" : " and the push to " + registry)));
        }

        // A native, node or static image has no main class: the engine names what it holds.
        if ("native".equals(c.stringOpt("kind").orElse(null))) {
            return nativeImage(ctx, c, artifact, version, base, registry, tag, keyless);
        }
        if (c.stringOpt("kind").isPresent()) return layered(ctx, c, artifact, version, base, registry, tag, keyless);
        String mainClass = c.string("mainClass");

        Path mainJar = ctx.mainArtifact().orElseThrow(() -> new IOException("image goal needs a built main artifact"));
        List<Path> depJars = new ArrayList<>();
        List<Path> snapshotJars = new ArrayList<>();
        Map<Path, String> jarNames = new LinkedHashMap<>();
        for (RuntimeEntry e : ctx.runtimeEntries()) {
            if (e.jar() == null) continue;
            (e.snapshot() ? snapshotJars : depJars).add(e.jar());
            // The entry knows its coordinate; the path is a CAS digest and says nothing.
            if (e.fileName() != null && !e.fileName().isBlank()) jarNames.put(e.jar(), e.fileName());
        }
        Path classesDir = ctx.classesDir().orElse(null);
        // jk's cache root. Required: an AOT build extracts the base image's JRE here, and the
        // engine's bound for CacheTree.BASE_JRE is the only thing that bounds that tree.
        Path cacheRoot = Path.of(c.string("jkCache"));
        String appDir = c.stringOpt("appDir").orElse(null);
        String appJar = c.stringOpt("appJar").orElse(null);

        ImageConfig config = new ImageConfig(
                base,
                c.stringOpt("name").orElse(null),
                c.stringOpt("user").orElse(null),
                ports,
                env,
                splitPairs(c.stringList("trainingEnv")),
                labels,
                registry,
                tag,
                platforms,
                mainClass,
                dockerExecutable,
                null,
                aotCache,
                referrers,
                c.bool("sigstore").orElse(false));
        ImageBuilder.Plan plan = new ImageBuilder.Plan(
                config,
                artifact,
                version,
                mainClass,
                mainJar,
                depJars,
                snapshotJars,
                classesDir,
                jarNames,
                appDir == null ? null : Path.of(appDir),
                appJar,
                c.stringOpt("libDir").orElse(null));

        String ref = config.targetReference(artifact, version);
        Optional<String> tarball = c.stringOpt("tarball");
        boolean pushing =
                tarball.isEmpty() && !"daemon".equals(c.stringOpt("mode").orElse(null));
        // Every mode pulls the base image, so every mode needs the pull credential; only a push
        // needs the second one. Handing `ref` over in tarball/daemon mode would claim a registry
        // is contacted when none is.
        RegistryAuth auth = RegistryAuth.of(
                        credential(ctx, "base"),
                        credential(ctx, "push"),
                        ImageBuilder.baseOf(config),
                        pushing ? ref : null)
                .withCacheRoot(c.stringOpt("jkCache").map(Path::of).orElse(null));

        if (tarball.isPresent()) {
            Path tarballPath = Path.of(tarball.get());
            ctx.label("building OCI tarball");
            if (tarballPath.getParent() != null) Files.createDirectories(tarballPath.getParent());
            noticeOf(ctx, ImageBuilder.writeToTarball(plan, tarballPath, cacheRoot, auth));
            return ImageResult.tarball(tarballPath);
        }
        if (!pushing) {
            String exe = dockerExecutable != null ? dockerExecutable : "docker";
            ctx.label("loading " + ref + " into " + exe);
            noticeOf(
                    ctx,
                    ImageBuilder.loadToLocalDaemon(
                            plan, dockerExecutable != null ? Path.of(dockerExecutable) : null, cacheRoot, auth));
            return ImageResult.loaded(ref);
        }
        ctx.label("pushing " + ref);
        ImageBuilder.Result pushed = ImageBuilder.pushToRegistry(plan, cacheRoot, auth);
        noticeOf(ctx, pushed);
        if (config.referrers()) attach(ctx, pushed, base, auth, keyless);
        return ImageResult.pushed(ref, pushed.digest());
    }

    /**
     * Attach the module's SBOM, when the engine sent one, and a provenance statement to the image
     * just pushed, as OCI referrers. The image is in the registry whatever happens here, so a
     * registry that refuses an artifact manifest costs a notice, not the build.
     *
     * <p>A signed push ({@code sigstore}) attaches the statement in a keyless DSSE envelope, beside
     * the Sigstore bundle that certifies its signature, in one artifact. It was asked for a signed
     * image, so a signature or an attach that fails fails the build, naming the image it left
     * unsigned.
     */
    static void attach(
            ImageContext ctx,
            ImageBuilder.Result pushed,
            @Nullable String base,
            RegistryAuth auth,
            Callable<KeylessSigstoreSigner> keyless)
            throws IOException {
        String ref = pushed.imageReference();
        boolean sign = ctx.config().bool("sigstore").orElse(false);
        try {
            List<Referrers.Document> documents = new ArrayList<>();
            Optional<String> sbomFile = ctx.config().stringOpt("sbom");
            byte[] sbom = sbomFile.isPresent() ? Files.readAllBytes(Path.of(sbomFile.get())) : null;
            if (sbom != null) documents.add(Referrers.Document.of(Referrers.SBOM_TYPE, sbom));
            byte[] statement = provenance(
                    ref,
                    pushed.digest(),
                    base,
                    ctx.project(),
                    sbom,
                    ctx.config().stringOpt("sourceCommit").orElse(null));
            documents.add(
                    sign
                            ? signed(ctx, ref, statement, keyless)
                            : Referrers.Document.of(Referrers.PROVENANCE_TYPE, statement));
            String what = (sign ? "signed " : "") + "provenance";
            ctx.label("attaching " + (sbom == null ? what : "SBOM and " + what) + " to " + ref);
            Referrers.attach(ref, pushed.digest(), documents, auth);
        } catch (IOException e) {
            if (sign)
                throw new IOException("[image] no signed provenance attached to " + ref + ": " + Errors.text(e), e);
            ctx.notice("[image] no SBOM or provenance attached to " + ref + ": " + Errors.text(e));
        }
    }

    /**
     * {@code statement} signed with Sigstore keyless: one artifact whose layers are the DSSE
     * envelope and the bundle of its signature (the short-lived certificate and the transparency-log
     * entry), as {@code jk publish} uploads them beside a jar.
     */
    private static Referrers.Document signed(
            ImageContext ctx, String ref, byte[] statement, Callable<KeylessSigstoreSigner> keyless)
            throws IOException {
        ctx.label("signing the provenance of " + ref + " with Sigstore");
        KeylessSigstoreSigner.Attestation attestation;
        try (KeylessSigstoreSigner signer = open(keyless)) {
            attestation = signer.attest(statement);
        }
        return new Referrers.Document(
                Dsse.ENVELOPE_TYPE,
                List.of(
                        new Referrers.Layer(Dsse.ENVELOPE_TYPE, attestation.envelope()),
                        new Referrers.Layer(BUNDLE_TYPE, attestation.bundle())));
    }

    private static KeylessSigstoreSigner open(Callable<KeylessSigstoreSigner> keyless) throws IOException {
        try {
            return keyless.call();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Sigstore signer: " + Errors.text(e), e);
        }
    }

    /**
     * The SLSA provenance of the image {@code reference} pushed as {@code digest}: pushed by {@code
     * jk publish} at this JumpKick's version, from {@code sourceCommit} when the module is in a git
     * checkout, on {@code base}, from the components {@code sbom} lists. A function of its inputs,
     * no clock and no random id, so a re-push of the same image attaches the same statement.
     */
    static byte[] provenance(
            String reference,
            String digest,
            @Nullable String base,
            ProjectFacts project,
            byte @Nullable [] sbom,
            @Nullable String sourceCommit) {
        List<SlsaProvenance.Dependency> dependencies = new ArrayList<>();
        if (base != null) dependencies.add(baseImage(base));
        if (sbom != null) {
            for (Object component :
                    MiniJson.list(MiniJson.parseRelaxed(new String(sbom, StandardCharsets.UTF_8)), "components")) {
                String purl = MiniJson.str(component, "purl");
                if (purl == null) continue;
                String sha256 = null;
                for (Object hash : MiniJson.list(component, "hashes")) {
                    if ("SHA-256".equals(MiniJson.str(hash, "alg"))) sha256 = MiniJson.str(hash, "content");
                }
                dependencies.add(new SlsaProvenance.Dependency(purl, sha256));
            }
        }
        SlsaProvenance.BuildContext build = new SlsaProvenance.BuildContext(
                SlsaProvenance.BUILDER_ID,
                SlsaProvenance.BUILD_TYPE,
                UUID.nameUUIDFromBytes(("slsa\0" + digest).getBytes(StandardCharsets.UTF_8))
                        .toString(),
                null,
                null,
                externalParameters(reference, sourceCommit),
                Map.of(
                        "group",
                        project.group(),
                        "artifact",
                        project.name(),
                        "version",
                        project.version(),
                        "jkVersion",
                        JkVersion.VERSION),
                dependencies);
        return SlsaProvenance.generate(
                List.of(new SlsaProvenance.Subject(repository(reference), digest.substring(digest.indexOf(':') + 1))),
                build);
    }

    /** What the build was asked: the command, the image it pushed, and the commit it built from when known. */
    private static Map<String, String> externalParameters(String reference, @Nullable String sourceCommit) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("command", "jk publish");
        parameters.put("image", reference);
        if (sourceCommit != null) parameters.put("sourceCommit", sourceCommit);
        return parameters;
    }

    /**
     * The base image as a resolved dependency: {@code pkg:docker/<name>}, with its digest when the
     * engine pinned one and its tag otherwise.
     */
    private static SlsaProvenance.Dependency baseImage(String base) {
        int at = base.indexOf("@sha256:");
        if (at >= 0) {
            return new SlsaProvenance.Dependency(
                    "pkg:docker/" + repository(base.substring(0, at)), base.substring(at + "@sha256:".length()));
        }
        String name = repository(base);
        String tag = base.length() > name.length() ? base.substring(name.length() + 1) : "latest";
        return new SlsaProvenance.Dependency("pkg:docker/" + name + "@" + tag, null);
    }

    /** {@code reference} without its tag. */
    private static String repository(String reference) {
        int colon = reference.lastIndexOf(':');
        return colon > reference.lastIndexOf('/') ? reference.substring(0, colon) : reference;
    }

    /** Pass on what the build owes the user: an AOT cache it was asked for and could not train. */
    private static void noticeOf(ImageContext ctx, ImageBuilder.Result built) {
        if (built.aotNotice() != null) ctx.notice("[image] aot-cache: " + built.aotNotice());
    }

    /**
     * A node build's image ({@code kind} {@code node} or {@code static}): the engine names every
     * layer as {@code name<TAB>source<TAB>image path}, plus the entrypoint and working directory.
     */
    private static ImageResult layered(
            ImageContext ctx,
            PluginConfig c,
            String artifact,
            String version,
            @Nullable String base,
            @Nullable String registry,
            @Nullable String tag,
            Callable<KeylessSigstoreSigner> keyless)
            throws IOException, InterruptedException {
        List<LayeredImage.Layer> layers = new ArrayList<>();
        for (String line : c.stringList("layers")) {
            String[] f = line.split("\t", 3);
            if (f.length != 3) throw new IOException("image spec: malformed layer `" + line + "`");
            layers.add(new LayeredImage.Layer(f[0], Path.of(f[1]), f[2]));
        }
        List<Integer> ports = new ArrayList<>();
        for (String p : c.stringList("ports")) ports.add(Integer.parseInt(p));
        String dockerExecutable = c.stringOpt("dockerExecutable").orElse(null);
        ImageConfig config = new ImageConfig(
                base,
                c.stringOpt("name").orElse(null),
                c.stringOpt("user").orElse(null),
                ports,
                splitPairs(c.stringList("env")),
                Map.of(),
                splitPairs(c.stringList("labels")),
                registry,
                tag,
                c.stringList("platforms"),
                null,
                dockerExecutable,
                null,
                false,
                c.bool("referrers").orElse(true),
                c.bool("sigstore").orElse(false));
        LayeredImage.Plan plan = new LayeredImage.Plan(
                config,
                artifact,
                version,
                layers,
                c.stringList("entrypoint"),
                c.stringOpt("workdir").orElse(null));
        String ref = config.targetReference(artifact, version);
        Optional<String> tarball = c.stringOpt("tarball");
        boolean pushing =
                tarball.isEmpty() && !"daemon".equals(c.stringOpt("mode").orElse(null));
        Path cacheRoot = c.stringOpt("jkCache").map(Path::of).orElse(null);
        RegistryAuth auth = RegistryAuth.of(
                        credential(ctx, "base"),
                        credential(ctx, "push"),
                        ImageBuilder.baseOf(config),
                        pushing ? ref : null)
                .withCacheRoot(cacheRoot);
        if (tarball.isPresent()) {
            Path tarballPath = Path.of(tarball.get());
            ctx.label("building OCI tarball");
            if (tarballPath.getParent() != null) Files.createDirectories(tarballPath.getParent());
            LayeredImage.writeToTarball(plan, tarballPath, cacheRoot, auth);
            return ImageResult.tarball(tarballPath);
        }
        if (!pushing) {
            ctx.label("loading " + ref + " into " + (dockerExecutable != null ? dockerExecutable : "docker"));
            LayeredImage.loadToLocalDaemon(
                    plan, dockerExecutable != null ? Path.of(dockerExecutable) : null, cacheRoot, auth);
            return ImageResult.loaded(ref);
        }
        ctx.label("pushing " + ref);
        ImageBuilder.Result pushed = LayeredImage.pushToRegistry(plan, cacheRoot, auth);
        if (config.referrers()) attach(ctx, pushed, base, auth, keyless);
        return ImageResult.pushed(ref, pushed.digest());
    }

    /**
     * A native executable's image ({@code kind} {@code native}): the engine names each platform's
     * executable as {@code os/arch<TAB>file} and the path it lies at in the image.
     */
    private static ImageResult nativeImage(
            ImageContext ctx,
            PluginConfig c,
            String artifact,
            String version,
            @Nullable String base,
            @Nullable String registry,
            @Nullable String tag,
            Callable<KeylessSigstoreSigner> keyless)
            throws IOException, InterruptedException {
        Map<String, Path> executables = new LinkedHashMap<>();
        for (String line : c.stringList("executables")) {
            String[] f = line.split("\t", 2);
            if (f.length != 2) throw new IOException("image spec: malformed executable `" + line + "`");
            executables.put(f[0], Path.of(f[1]));
        }
        List<Integer> ports = new ArrayList<>();
        for (String p : c.stringList("ports")) ports.add(Integer.parseInt(p));
        String dockerExecutable = c.stringOpt("dockerExecutable").orElse(null);
        ImageConfig config = new ImageConfig(
                base,
                c.stringOpt("name").orElse(null),
                c.stringOpt("user").orElse(null),
                ports,
                splitPairs(c.stringList("env")),
                Map.of(),
                splitPairs(c.stringList("labels")),
                registry,
                tag,
                List.copyOf(executables.keySet()),
                null,
                dockerExecutable,
                null,
                false,
                c.bool("referrers").orElse(true),
                c.bool("sigstore").orElse(false));
        NativeImage.Plan plan =
                new NativeImage.Plan(config, artifact, version, executables, c.string("executablePath"));
        String ref = config.targetReference(artifact, version);
        Optional<String> tarball = c.stringOpt("tarball");
        boolean pushing =
                tarball.isEmpty() && !"daemon".equals(c.stringOpt("mode").orElse(null));
        RegistryAuth auth = RegistryAuth.of(
                        credential(ctx, "base"),
                        credential(ctx, "push"),
                        ImageBuilder.baseOf(config),
                        pushing ? ref : null)
                .withCacheRoot(c.stringOpt("jkCache").map(Path::of).orElse(null));
        String platforms = String.join(", ", executables.keySet());
        if (tarball.isPresent()) {
            Path tarballPath = Path.of(tarball.get());
            ctx.label("building OCI tarball for " + platforms);
            if (tarballPath.getParent() != null) Files.createDirectories(tarballPath.getParent());
            NativeImage.writeToTarball(plan, tarballPath, auth);
            return ImageResult.tarball(tarballPath);
        }
        if (!pushing) {
            ctx.label("loading " + ref + " into " + (dockerExecutable != null ? dockerExecutable : "docker"));
            NativeImage.loadToLocalDaemon(plan, dockerExecutable != null ? Path.of(dockerExecutable) : null, auth);
            return ImageResult.loaded(ref);
        }
        ctx.label("pushing " + ref + " for " + platforms);
        ImageBuilder.Result pushed = NativeImage.pushToRegistry(plan, auth);
        if (config.referrers()) attach(ctx, pushed, base, auth, keyless);
        return ImageResult.pushed(ref, pushed.digest());
    }

    /**
     * The credential the engine resolved for one registry, rebuilt from the spec's {@code secret}
     * lines under {@code prefix} ({@code base} for the pull, {@code push} for the target). Same
     * shape {@code jk-publisher} reads its repository credential in — one credential vocabulary,
     * one resolution order, and the value never appears on this worker's command line.
     */
    static RepoCredential credential(ImageContext ctx, String prefix) {
        String kind = ctx.config().stringOpt(prefix + "AuthType").orElse("anonymous");
        return switch (kind) {
            case "basic" ->
                new RepoCredential.Basic(
                        ctx.secret(prefix + "User").orElse(""),
                        ctx.secret(prefix + "Pass").orElse(""));
            case "bearer" ->
                ctx.secret(prefix + "Token")
                        .filter(t -> !t.isBlank())
                        .<RepoCredential>map(RepoCredential.Bearer::new)
                        .orElse(RepoCredential.ANONYMOUS);
            default -> RepoCredential.ANONYMOUS;
        };
    }

    private static Map<String, String> splitPairs(List<String> pairs) {
        Map<String, String> out = new HashMap<>();
        for (String pair : pairs) {
            int eq = pair.indexOf('=');
            if (eq > 0) out.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        return out;
    }

    /** The generic terminal context, backed directly by the plugin spec. */
    private record SpecImageContext(PluginSpec spec, ProtocolWriter out) implements ImageContext {
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
        public List<RuntimeEntry> runtimeEntries() {
            return spec.entries();
        }

        @Override
        public boolean offline() {
            return spec.offline();
        }

        @Override
        public Path javaHome() {
            return Objects.requireNonNull(spec.javaHome(), "spec missing java-home.path");
        }

        @Override
        public Optional<Path> classesDir() {
            return Optional.ofNullable(spec.classesDir());
        }

        @Override
        public Optional<String> secret(String key) {
            return spec.secret(key);
        }

        @Override
        public void label(String text) {
            out.emit(PluginReply.label(text));
        }

        @Override
        public void notice(String message) {
            out.emit(PluginReply.diagnostic(PluginProtocol.SEVERITY_NOTICE, null, 0, 0, message));
        }
    }
}
