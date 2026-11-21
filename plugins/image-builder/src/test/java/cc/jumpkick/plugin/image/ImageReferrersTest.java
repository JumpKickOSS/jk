// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.ImageContext;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.testing.RegistryStub;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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

        OciImageBuilder.attach(ctx, pushed, baseRef, anonymous());

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

        OciImageBuilder.attach(context(tmp, true), pushed, baseRef, anonymous());
        OciImageBuilder.attach(context(tmp, true), pushed, baseRef, anonymous());

        assertThat(MiniJson.list(json(registry.manifest("app", Referrers.tagSchema(pushed.digest()))), "manifests"))
                .hasSize(2);
    }

    /** A registry with the referrers API indexes the subject itself: no tag is written for it. */
    @Test
    void a_registry_with_the_referrers_api_gets_no_referrers_tag(@TempDir Path tmp) throws Exception {
        registry.withReferrersApi();
        ImageBuilder.Result pushed = push(tmp, anonymous());
        Context ctx = context(tmp, true);

        OciImageBuilder.attach(ctx, pushed, baseRef, anonymous());

        assertThat(ctx.notices).isEmpty();
        assertThat(registry.tags("app")).containsExactly("9");
    }

    /** No SBOM from the engine (a module with no lock): the provenance alone is attached. */
    @Test
    void without_an_sbom_the_provenance_is_attached_alone(@TempDir Path tmp) throws Exception {
        ImageBuilder.Result pushed = push(tmp, anonymous());

        OciImageBuilder.attach(context(tmp, false), pushed, baseRef, anonymous());

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
                    RegistryAuth.of(RepoCredential.ANONYMOUS, RepoCredential.ANONYMOUS, baseRef, target));
            assertThat(refused.notices)
                    .as("the image is pushed either way; a refused attach says so and does not fail the build")
                    .singleElement()
                    .satisfies(n -> assertThat(n).startsWith("[image] no SBOM or provenance attached to " + target))
                    .satisfies(n -> assertThat(n).doesNotContain(PASSWORD));
            assertThat(registry.holdsManifest("app", Referrers.tagSchema(pushed.digest())))
                    .isFalse();

            Context ctx = context(tmp, true);
            OciImageBuilder.attach(ctx, pushed, baseRef, auth);
            assertThat(ctx.notices).isEmpty();
            assertThat(registry.holdsManifest("app", Referrers.tagSchema(pushed.digest())))
                    .isTrue();
        } finally {
            registry.close();
            registry = open;
        }
    }

    /** The provenance resolves the pinned base and every SBOM component, hashed where the lock pins one. */
    @Test
    void the_provenance_names_the_base_and_the_sbom_components() {
        String digest = "sha256:" + "d".repeat(64);
        String base = "ghcr.io/acme/jre@sha256:" + "e".repeat(64);

        String statement = new String(
                OciImageBuilder.provenance("127.0.0.1:5000/app:9", digest, base, facts(), SBOM),
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
        assertThat(OciImageBuilder.provenance("127.0.0.1:5000/app:9", digest, base, facts(), SBOM))
                .as("no clock and no random id: a re-push attaches the same statement")
                .isEqualTo(statement.getBytes(StandardCharsets.UTF_8));
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
                false);
        return ImageBuilder.pushToRegistry(
                new ImageBuilder.Plan(config, "app", "0.1.0", "com.example.Main", jar, List.of()),
                tmp.resolve("cache"),
                auth);
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

        Context(Map<String, Object> values) {
            this.values = values;
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
            return Optional.empty();
        }

        @Override
        public List<PackageIo.RuntimeEntry> runtimeEntries() {
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
