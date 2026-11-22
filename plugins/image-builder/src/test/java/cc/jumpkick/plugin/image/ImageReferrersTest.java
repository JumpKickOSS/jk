// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.ProjectFacts;
import cc.jumpkick.plugin.RuntimeEntry;
import cc.jumpkick.plugin.build.ImageContext;
import cc.jumpkick.sigstore.Dsse;
import cc.jumpkick.sigstore.KeylessSigstoreSigner;
import cc.jumpkick.sigstore.StubSigner;
import cc.jumpkick.testing.RegistryStub;
import dev.sigstore.bundle.Bundle;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A pushed image carries its SBOM and provenance as OCI 1.1 referrers, against a local registry:
 * one that keeps no referrers API (the referrers tag schema), one that does, and one that wants a
 * credential.
 */
class ImageReferrersTest {

    private static final String USER = "robot";
    private static final String PASSWORD = "s3cr3t-not-in-any-log";

    /** Fresh names per test: Jib's base-image cache is keyed by reference, and it is user-global. */
    private static final AtomicInteger NAMES = new AtomicInteger();

    private static final byte[] SBOM = """
            {"bomFormat":"CycloneDX","components":[
              {"purl":"pkg:maven/com.acme/lib@1.0","hashes":[{"alg":"SHA-256","content":"%s"}]},
              {"purl":"pkg:maven/com.acme/unhashed@2.0"}]}
            """.formatted("c".repeat(64)).getBytes(StandardCharsets.UTF_8);

    /** The signer source of an unsigned push, which never opens one. */
    private static final Callable<KeylessSigstoreSigner> UNSIGNED = () -> {
        throw new AssertionError("an unsigned push opens no signer");
    };

    private RegistryStub registry;
    private String baseRef;

    @BeforeEach
    void start() throws Exception {
        registry = RegistryStub.open();
        baseRef = registry.publishImage("base-" + NAMES.incrementAndGet() + "-" + System.nanoTime(), "1");
    }

    @AfterEach
    void stop() {
        registry.close();
    }

    /** No referrers API, the registry:2 shape: the index lives at the subject's referrers tag. */
    @Test
    void a_pushed_image_has_its_sbom_and_provenance_as_referrers(@TempDir Path tmp) throws Exception {
        ImageBuilder.Result pushed = push(tmp, anonymous());
        Context ctx = context(tmp, true);

        OciImageBuilder.attach(ctx, pushed, baseRef, anonymous(), UNSIGNED);

        assertThat(ctx.notices).isEmpty();
        Map<?, ?> index = json(registry.manifest("app", Referrers.tagSchema(pushed.digest())));
        assertThat(index.get("mediaType")).isEqualTo(Referrers.INDEX_TYPE);
        List<?> listed = MiniJson.list(index, "manifests");
        assertThat(listed)
                .extracting(m -> MiniJson.str(m, "artifactType"))
                .containsExactly(Referrers.SBOM_TYPE, Referrers.PROVENANCE_TYPE);

        Map<?, ?> sbomManifest = json(registry.manifest("app", str(listed.get(0), "digest")));
        assertThat(MiniJson.str(MiniJson.get(sbomManifest, "subject"), "digest"))
                .as("the artifact names the pushed image as its subject")
                .isEqualTo(pushed.digest());
        assertThat(MiniJson.str(sbomManifest, "artifactType")).isEqualTo(Referrers.SBOM_TYPE);
        String layer = str(MiniJson.list(sbomManifest, "layers").get(0), "digest");
        assertThat(registry.blob(layer))
                .as("the layer is the module's SBOM, byte for byte")
                .isEqualTo(SBOM);

        Map<?, ?> provenanceManifest = json(registry.manifest("app", str(listed.get(1), "digest")));
        String statement = new String(
                Objects.requireNonNull(registry.blob(
                        str(MiniJson.list(provenanceManifest, "layers").get(0), "digest"))),
                StandardCharsets.UTF_8);
        assertThat(statement)
                .contains("\"sha256\":\"" + pushed.digest().substring("sha256:".length()) + "\"")
                .contains("pkg:maven/com.acme/lib@1.0");
    }

    /** A second attach of the same image lists each artifact once, not twice. */
    @Test
    void attaching_again_does_not_list_an_artifact_twice(@TempDir Path tmp) throws Exception {
        ImageBuilder.Result pushed = push(tmp, anonymous());

        OciImageBuilder.attach(context(tmp, true), pushed, baseRef, anonymous(), UNSIGNED);
        OciImageBuilder.attach(context(tmp, true), pushed, baseRef, anonymous(), UNSIGNED);

        assertThat(MiniJson.list(json(registry.manifest("app", Referrers.tagSchema(pushed.digest()))), "manifests"))
                .hasSize(2);
    }

    /** A registry with the referrers API indexes the subject itself: no tag is written for it. */
    @Test
    void a_registry_with_the_referrers_api_gets_no_referrers_tag(@TempDir Path tmp) throws Exception {
        registry.withReferrersApi();
        ImageBuilder.Result pushed = push(tmp, anonymous());
        Context ctx = context(tmp, true);

        OciImageBuilder.attach(ctx, pushed, baseRef, anonymous(), UNSIGNED);

        assertThat(ctx.notices).isEmpty();
        assertThat(registry.tags("app")).containsExactly("9");
    }

    /** No SBOM from the engine (a module with no lock): the provenance alone is attached. */
    @Test
    void without_an_sbom_the_provenance_is_attached_alone(@TempDir Path tmp) throws Exception {
        ImageBuilder.Result pushed = push(tmp, anonymous());

        OciImageBuilder.attach(context(tmp, false), pushed, baseRef, anonymous(), UNSIGNED);

        assertThat(MiniJson.list(json(registry.manifest("app", Referrers.tagSchema(pushed.digest()))), "manifests"))
                .extracting(m -> MiniJson.str(m, "artifactType"))
                .containsExactly(Referrers.PROVENANCE_TYPE);
    }

    /** The attach authenticates with the push credential, and only with it does it succeed. */
    @Test
    void the_attach_carries_the_push_credential(@TempDir Path tmp) throws Exception {
        RegistryStub open = registry;
        registry = RegistryStub.requiring(USER, PASSWORD);
        try {
            RepoCredential credential = new RepoCredential.Basic(USER, PASSWORD);
            String target = registry.hostPort() + "/app:9";
            RegistryAuth auth = RegistryAuth.of(RepoCredential.ANONYMOUS, credential, baseRef, target);
            ImageBuilder.Result pushed = push(tmp, auth);

            Context refused = context(tmp, true);
            OciImageBuilder.attach(
                    refused,
                    pushed,
                    baseRef,
                    RegistryAuth.of(RepoCredential.ANONYMOUS, RepoCredential.ANONYMOUS, baseRef, target),
                    UNSIGNED);
            assertThat(refused.notices)
                    .as("the image is pushed either way; a refused attach says so and does not fail the build")
                    .singleElement()
                    .satisfies(n -> assertThat(n).startsWith("[image] no SBOM or provenance attached to " + target))
                    .satisfies(n -> assertThat(n).doesNotContain(PASSWORD));
            assertThat(registry.holdsManifest("app", Referrers.tagSchema(pushed.digest())))
                    .isFalse();

            Context ctx = context(tmp, true);
            OciImageBuilder.attach(ctx, pushed, baseRef, auth, UNSIGNED);
            assertThat(ctx.notices).isEmpty();
            assertThat(registry.holdsManifest("app", Referrers.tagSchema(pushed.digest())))
                    .isTrue();
        } finally {
            registry.close();
            registry = open;
        }
    }

    /** A node module's push attaches its provenance; jk renders no SBOM of its npm packages. */
    @Test
    void a_node_push_attaches_its_provenance(@TempDir Path tmp) throws Exception {
        Context ctx = new Context(nodePush(tmp, true));

        new OciImageBuilder().image(ctx);

        assertThat(ctx.notices).isEmpty();
        assertThat(MiniJson.list(json(registry.manifest("app", Referrers.tagSchema(pushedDigest()))), "manifests"))
                .extracting(m -> MiniJson.str(m, "artifactType"))
                .containsExactly(Referrers.PROVENANCE_TYPE);
    }

    /** {@code [image] referrers = false}: the image is pushed and nothing is attached to it. */
    @Test
    void a_push_with_referrers_off_attaches_nothing(@TempDir Path tmp) throws Exception {
        new OciImageBuilder().image(new Context(nodePush(tmp, false)));

        Path jar = Files.writeString(tmp.resolve("app.jar"), "bytes in a layer");
        Map<String, Object> jvm = new HashMap<>(Map.of(
                "artifact",
                "app",
                "version",
                "0.1.0",
                "mainClass",
                "com.example.Main",
                "base",
                baseRef,
                "registry",
                registry.hostPort(),
                "tag",
                "10",
                "jkCache",
                tmp.resolve("cache").toString(),
                "referrers",
                false));
        jvm.put("sbom", Files.write(tmp.resolve("image.cdx.json"), SBOM).toString());
        new OciImageBuilder().image(new Context(jvm, jar));

        assertThat(registry.tags("app"))
                .as("the images are pushed, and no referrers tag beside them")
                .containsExactlyInAnyOrder("9", "10");
    }

    /** The provenance resolves the pinned base and every SBOM component, hashed where the lock pins one. */
    @Test
    void the_provenance_names_the_base_and_the_sbom_components() {
        String digest = "sha256:" + "d".repeat(64);
        String base = "ghcr.io/acme/jre@sha256:" + "e".repeat(64);

        String statement = new String(
                OciImageBuilder.provenance("127.0.0.1:5000/app:9", digest, base, facts(), SBOM, "f".repeat(40)),
                StandardCharsets.UTF_8);

        Map<?, ?> json = json(statement);
        assertThat(MiniJson.str(MiniJson.list(json, "subject").get(0), "name")).isEqualTo("127.0.0.1:5000/app");
        List<?> resolved =
                MiniJson.list(MiniJson.get(MiniJson.get(json, "predicate"), "buildDefinition"), "resolvedDependencies");
        assertThat(resolved)
                .extracting(d -> MiniJson.str(d, "uri"))
                .containsExactly(
                        "pkg:docker/ghcr.io/acme/jre", "pkg:maven/com.acme/lib@1.0", "pkg:maven/com.acme/unhashed@2.0");
        assertThat(MiniJson.str(MiniJson.get(resolved.get(0), "digest"), "sha256"))
                .isEqualTo("e".repeat(64));
        assertThat(MiniJson.get(resolved.get(2), "digest"))
                .as("an unhashed component claims no digest")
                .isNull();
        Object definition = MiniJson.get(MiniJson.get(json, "predicate"), "buildDefinition");
        assertThat(MiniJson.str(MiniJson.get(definition, "externalParameters"), "command"))
                .isEqualTo("jk image");
        assertThat(MiniJson.str(MiniJson.get(definition, "externalParameters"), "sourceCommit"))
                .isEqualTo("f".repeat(40));
        assertThat(MiniJson.str(MiniJson.get(definition, "internalParameters"), "jkVersion"))
                .isEqualTo(JkVersion.VERSION);
        assertThat(OciImageBuilder.provenance("127.0.0.1:5000/app:9", digest, base, facts(), SBOM, "f".repeat(40)))
                .as("no clock and no random id: a re-push attaches the same statement")
                .isEqualTo(statement.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * A signed push attaches the statement in a DSSE envelope beside the Sigstore bundle of its
     * signature, in one artifact, and the envelope verifies against the bundle's certificate.
     */
    @Test
    void a_signed_push_attaches_an_envelope_its_bundle_verifies(@TempDir Path tmp) throws Exception {
        ImageBuilder.Result pushed = push(tmp, anonymous());
        Path sbom = Files.write(tmp.resolve("image.cdx.json"), SBOM);
        Context ctx = new Context(Map.of("sbom", sbom.toString(), "sigstore", true, "sourceCommit", "a".repeat(40)));
        StubSigner stub = new StubSigner();

        OciImageBuilder.attach(ctx, pushed, baseRef, anonymous(), stub::signer);

        assertThat(ctx.notices).isEmpty();
        List<?> listed =
                MiniJson.list(json(registry.manifest("app", Referrers.tagSchema(pushed.digest()))), "manifests");
        assertThat(listed)
                .extracting(m -> MiniJson.str(m, "artifactType"))
                .as("the SBOM, and the provenance only in its signed envelope")
                .containsExactly(Referrers.SBOM_TYPE, Dsse.ENVELOPE_TYPE);
        verifySigned(pushed.digest(), str(listed.get(1), "digest"), stub);
    }

    /** A node module's signed push signs its provenance the same way. */
    @Test
    void a_signed_node_push_attaches_an_envelope_its_bundle_verifies(@TempDir Path tmp) throws Exception {
        Map<String, Object> spec = nodePush(tmp, true);
        spec.put("sigstore", true);
        StubSigner stub = new StubSigner();

        new OciImageBuilder(stub::signer).image(new Context(spec));

        List<?> listed =
                MiniJson.list(json(registry.manifest("app", Referrers.tagSchema(pushedDigest()))), "manifests");
        assertThat(listed).extracting(m -> MiniJson.str(m, "artifactType")).containsExactly(Dsse.ENVELOPE_TYPE);
        verifySigned(pushedDigest(), str(listed.get(0), "digest"), stub);
    }

    /** It was asked for a signed image: a signature that fails fails the build, naming the image. */
    @Test
    void a_signature_that_fails_fails_the_push(@TempDir Path tmp) throws Exception {
        ImageBuilder.Result pushed = push(tmp, anonymous());
        Context ctx = new Context(Map.of("sigstore", true));

        assertThatThrownBy(() -> OciImageBuilder.attach(ctx, pushed, baseRef, anonymous(), () -> {
                    throw new IOException("no OIDC token");
                }))
                .isInstanceOf(IOException.class)
                .hasMessage("[image] no signed provenance attached to " + pushed.imageReference() + ": no OIDC token");
        assertThat(registry.holdsManifest("app", Referrers.tagSchema(pushed.digest())))
                .as("no unsigned provenance stands in for the signed one")
                .isFalse();
    }

    /**
     * The artifact {@code manifestDigest} holds the provenance of {@code subject} in a DSSE envelope
     * whose one keyless signature the Sigstore bundle beside it certifies, over the envelope's
     * pre-authentication encoding, as {@code stub} was asked to sign it.
     */
    private void verifySigned(String subject, String manifestDigest, StubSigner stub) throws Exception {
        Map<?, ?> manifest = json(registry.manifest("app", manifestDigest));
        assertThat(MiniJson.str(MiniJson.get(manifest, "subject"), "digest")).isEqualTo(subject);
        List<?> layers = MiniJson.list(manifest, "layers");
        assertThat(layers)
                .extracting(l -> MiniJson.str(l, "mediaType"))
                .containsExactly(Dsse.ENVELOPE_TYPE, OciImageBuilder.BUNDLE_TYPE);
        String envelope =
                new String(Objects.requireNonNull(registry.blob(str(layers.get(0), "digest"))), StandardCharsets.UTF_8);
        assertThat(Jsonl.str(envelope, "payloadType")).isEqualTo(Dsse.IN_TOTO);
        byte[] statement = Base64.getDecoder().decode(Jsonl.requiredStr(envelope, "payload"));
        assertThat(new String(statement, StandardCharsets.UTF_8))
                .contains("\"predicateType\":\"https://slsa.dev/provenance/v1\"")
                .contains("\"sha256\":\"" + subject.substring("sha256:".length()) + "\"");
        byte[] sig = Base64.getDecoder()
                .decode(Jsonl.requiredStr(
                        Jsonl.objectArray(envelope, "signatures").getFirst(), "sig"));

        byte[] pae = Dsse.pae(Dsse.IN_TOTO, statement);
        assertThat(stub.handed).containsExactly(Hashing.sha256(pae));
        Bundle bundle = Bundle.from(new StringReader(new String(
                Objects.requireNonNull(registry.blob(str(layers.get(1), "digest"))), StandardCharsets.UTF_8)));
        assertThat(bundle.getMessageSignature().orElseThrow().getSignature()).isEqualTo(sig);
        Signature verify = Signature.getInstance("SHA256withECDSA");
        verify.initVerify(bundle.getCertPath().getCertificates().getFirst().getPublicKey());
        verify.update(pae);
        assertThat(verify.verify(sig)).as("the envelope's signature verifies").isTrue();
    }

    private ImageBuilder.Result push(Path tmp, RegistryAuth auth) throws Exception {
        Path jar = tmp.resolve("app.jar");
        Files.writeString(jar, "not really a jar, but it is bytes in a layer");
        ImageConfig config = new ImageConfig(
                baseRef,
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                registry.hostPort(),
                "9",
                List.of("linux/amd64"),
                null,
                null,
                null,
                false,
                true,
                false);
        return ImageBuilder.pushToRegistry(
                new ImageBuilder.Plan(config, "app", "0.1.0", "com.example.Main", jar, List.of()),
                tmp.resolve("cache"),
                auth);
    }

    /** The spec of a node module's push to the stub as {@code app:9}. */
    private Map<String, Object> nodePush(Path tmp, boolean referrers) throws Exception {
        Path app = Files.createDirectories(tmp.resolve("web"));
        Files.writeString(app.resolve("server.js"), "console.log('up');");
        Map<String, Object> config = new HashMap<>(Map.of(
                "kind",
                "node",
                "artifact",
                "app",
                "version",
                "0.1.0",
                "base",
                baseRef,
                "registry",
                registry.hostPort(),
                "tag",
                "9",
                "jkCache",
                tmp.resolve("cache").toString(),
                "layers",
                List.of("app\t" + app.toAbsolutePath() + "\t/app"),
                "entrypoint",
                List.of("node", "server.js")));
        if (!referrers) config.put("referrers", false);
        return config;
    }

    /** The digest of the manifest the stub holds at {@code app:9}. */
    private String pushedDigest() {
        return "sha256:"
                + Hashing.sha256Hex(
                        Objects.requireNonNull(registry.manifest("app", "9")).getBytes(StandardCharsets.UTF_8));
    }

    private RegistryAuth anonymous() {
        return RegistryAuth.of(
                RepoCredential.ANONYMOUS, RepoCredential.ANONYMOUS, baseRef, registry.hostPort() + "/app:9");
    }

    private static Context context(Path tmp, boolean withSbom) throws Exception {
        if (!withSbom) return new Context(Map.of());
        Path sbom = Files.write(tmp.resolve("image.cdx.json"), SBOM);
        return new Context(Map.of("sbom", sbom.toString()));
    }

    private static ProjectFacts facts() {
        return new ProjectFacts("com.example", "app", "0.1.0", 25, null, false, false, Map.of());
    }

    private static Map<?, ?> json(@Nullable String text) {
        return (Map<?, ?>) Objects.requireNonNull(MiniJson.parse(Objects.requireNonNull(text, "no manifest")));
    }

    private static String str(Object node, String key) {
        return Objects.requireNonNull(MiniJson.str(node, key), key);
    }

    /** The worker's context as far as an attach reads it, keeping every notice. */
    private static final class Context implements ImageContext {
        final List<String> notices = new ArrayList<>();
        private final Map<String, Object> values;
        private final @Nullable Path mainJar;

        Context(Map<String, Object> values) {
            this(values, null);
        }

        Context(Map<String, Object> values, @Nullable Path mainJar) {
            this.values = values;
            this.mainJar = mainJar;
        }

        @Override
        public PluginConfig config() {
            return new PluginConfig("image", values);
        }

        @Override
        public Optional<String> secret(String key) {
            return Optional.empty();
        }

        @Override
        public boolean offline() {
            return false;
        }

        @Override
        public ProjectFacts project() {
            return facts();
        }

        @Override
        public Path moduleDir() {
            return Path.of(".");
        }

        @Override
        public Optional<Path> mainArtifact() {
            return Optional.ofNullable(mainJar);
        }

        @Override
        public List<RuntimeEntry> runtimeEntries() {
            return List.of();
        }

        @Override
        public Path javaHome() {
            return Path.of(System.getProperty("java.home"));
        }

        @Override
        public Optional<Path> classesDir() {
            return Optional.empty();
        }

        @Override
        public void label(String text) {}

        @Override
        public void notice(String message) {
            notices.add(message);
        }
    }
}
