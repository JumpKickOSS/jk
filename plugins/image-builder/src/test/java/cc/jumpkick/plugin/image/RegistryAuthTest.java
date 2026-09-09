// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.ImageContext;
import cc.jumpkick.plugin.build.PackageIo;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.testing.RepoRoot;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Jib attaches no credential retriever to a {@code RegistryImage} unless it is asked to, and jk
 * never asked. Both legs were affected and both are covered here: the <em>base-image
 * pull</em>, which every mode performs — so a private base broke a plain {@code jk build}, not just
 * a push — and the push itself.
 *
 * <p>Each test faces a registry that answers {@code 401} to an unauthenticated request and the real
 * distribution API to an authenticated one, so the anonymous case genuinely cannot succeed.
 */
class RegistryAuthTest {

    private static final String USER = "robot";
    private static final String PASSWORD = "s3cr3t-not-in-any-log";

    /** Fresh names per test: Jib's base-image cache is keyed by reference, and it is user-global. */
    private static final AtomicInteger NAMES = new AtomicInteger();

    private FakeRegistry registry;
    private FakeRegistry openRegistry;
    private String baseRef;
    private String openBaseRef;
    private String repo;

    @BeforeEach
    void start() throws Exception {
        registry = FakeRegistry.requiring(USER, PASSWORD);
        openRegistry = FakeRegistry.open();
        repo = "base-" + NAMES.incrementAndGet() + "-" + System.nanoTime();
        baseRef = registry.publishImage(repo, "1");
        openBaseRef = openRegistry.publishImage(repo, "1");
    }

    @AfterEach
    void stop() {
        registry.close();
        openRegistry.close();
    }

    /** The bug: with no credential the base image cannot be read, in the simplest mode there is. */
    @Test
    void a_tarball_build_cannot_pull_a_private_base_image_anonymously(@TempDir Path tmp) throws Exception {
        assertThatThrownBy(() -> ImageBuilder.writeToTarball(
                        plan(tmp), tmp.resolve("out/image.tar"), tmp.resolve("cache"), anonymous()))
                .as("an anonymous pull of a private base has to fail — silently succeeding is the "
                        + "only outcome that would make the credential path untestable")
                .hasMessageContaining("Unauthorized");
        assertThat(registry.refused()).anyMatch(r -> r.contains("/manifests/1"));
        assertThat(registry.served()).isEmpty();
        assertThat(tmp.resolve("out/image.tar")).doesNotExist();
    }

    /** The fix, for the pull leg: the same build, the same registry, one credential. */
    @Test
    void a_tarball_build_pulls_a_private_base_image_with_the_credential(@TempDir Path tmp) throws Exception {
        Path tarball = tmp.resolve("out/image.tar");

        ImageBuilder.Result result = ImageBuilder.writeToTarball(plan(tmp), tarball, tmp.resolve("cache"), basic(null));

        assertThat(tarball).exists();
        assertThat(result.digest()).startsWith("sha256:");
        assertThat(registry.served())
                .as("the manifest is only served to a caller that authenticated")
                .anyMatch(r -> r.contains("/manifests/1"));
    }

    /**
     * Push mode, anonymous. The base comes from a registry that serves anyone, so the pull is not
     * what fails — the write to the private registry is.
     */
    @Test
    void a_push_cannot_write_to_a_registry_that_refuses_anonymous_requests(@TempDir Path tmp) throws Exception {
        RegistryAuth pullOnly = RegistryAuth.of(
                RepoCredential.ANONYMOUS, RepoCredential.ANONYMOUS, openBaseRef, registry.hostPort() + "/app:9");

        assertThatThrownBy(() -> ImageBuilder.pushToRegistry(pushPlan(tmp), tmp.resolve("cache"), pullOnly))
                .hasMessageContaining("Unauthorized");
        assertThat(openRegistry.served())
                .as("the base pull is not the leg under test here")
                .anyMatch(r -> r.contains("/manifests/1"));
        assertThat(registry.refused()).isNotEmpty();
        assertThat(registry.holdsManifest("app", "9")).isFalse();
    }

    /**
     * Push mode, authenticated on both legs: the base is pulled from the private registry and the
     * result is written back to it. This is the mode where the pull and the push are separate
     * {@code RegistryImage}s, so it is the one that would still fail if only one had been fixed.
     */
    @Test
    void a_push_authenticates_its_base_pull_and_its_write(@TempDir Path tmp) throws Exception {
        String target = registry.hostPort() + "/app:9";

        ImageBuilder.Result result = ImageBuilder.pushToRegistry(
                plan(tmp, config(baseRef, registry.hostPort())), tmp.resolve("cache"), basic(target));

        assertThat(result.imageReference()).isEqualTo(target);
        assertThat(registry.served())
                .as("the base pull in push mode is its own RegistryImage, and it authenticated")
                .anyMatch(r -> r.contains("/" + repo + "/manifests/1"));
        assertThat(registry.holdsManifest("app", "9"))
                .as("an authenticated push is the only way a manifest reaches this registry")
                .isTrue();
    }

    /**
     * The credential is a secret everywhere downstream of here: the worker's stderr becomes the
     * engine's diagnostic text, and the exception message becomes a journal record and an SSE
     * error event. This asserts the literal password string appears in neither, on the path most
     * likely to spill it — a build that fails while holding one.
     */
    @Test
    void a_failing_authenticated_build_prints_the_credential_nowhere(@TempDir Path tmp) throws Exception {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        PrintStream realErr = System.err;
        PrintStream realOut = System.out;
        String message;
        try {
            System.setErr(new PrintStream(captured, true, StandardCharsets.UTF_8));
            System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
            // A base image the registry does not have: authentication succeeds, the pull does not.
            ImageConfig config = config(registry.hostPort() + "/" + repo + ":missing", null);
            message = catchMessage(() -> ImageBuilder.writeToTarball(
                    plan(tmp, config), tmp.resolve("out/image.tar"), tmp.resolve("cache"), basic(null)));
        } finally {
            System.setErr(realErr);
            System.setOut(realOut);
        }

        assertThat(message).isNotNull().doesNotContain(PASSWORD);
        assertThat(captured.toString(StandardCharsets.UTF_8))
                .as("Jib logs which credential source answered, never what it answered with")
                .doesNotContain(PASSWORD);
    }

    /**
     * Daemon mode pulls the same base image and cannot be driven without a running daemon, and the
     * next call site anyone adds will arrive with no test at all. So the acceptance criterion is
     * enforced against the source rather than sampled: naming a registry image happens in exactly
     * one file, and that file is the one that attaches credentials.
     */
    @Test
    void nothing_else_in_the_plugin_names_a_registry_image() throws Exception {
        Path main = pluginSources();

        List<String> elsewhere;
        try (var walk = Files.walk(main)) {
            elsewhere = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.getFileName().toString().equals("RegistryAuth.java"))
                    .filter(RegistryAuthTest::namesARegistryImage)
                    .map(p -> main.relativize(p).toString())
                    .sorted()
                    .toList();
        }

        assertThat(elsewhere)
                .as("a RegistryImage built anywhere else is an anonymous pull or push again")
                .isEmpty();
        assertThat(namesARegistryImage(main.resolve("cc/jumpkick/plugin/image/RegistryAuth.java")))
                .as("...and this test is worthless if the construction moved or was renamed")
                .isTrue();
    }

    /** A spec secret is what the worker turns into the credential Jib is handed. */
    @Test
    void a_spec_secret_becomes_the_registry_credential() {
        SpecContext basic =
                new SpecContext(Map.of("baseAuthType", "basic"), Map.of("baseUser", "robot", "basePass", PASSWORD));

        assertThat(OciImageBuilder.credential(basic, "base")).isEqualTo(new RepoCredential.Basic("robot", PASSWORD));
        assertThat(OciImageBuilder.credential(
                        new SpecContext(Map.of("baseAuthType", "bearer"), Map.of("baseToken", PASSWORD)), "base"))
                .isEqualTo(new RepoCredential.Bearer(PASSWORD));
        assertThat(OciImageBuilder.credential(basic, "push"))
                .as("tarball and daemon mode ship no push credential, and an empty Basic is not one")
                .isEqualTo(RepoCredential.ANONYMOUS);
    }

    private static boolean namesARegistryImage(Path source) {
        try {
            return Files.readString(source).contains("RegistryImage.named(");
        } catch (Exception e) {
            throw new IllegalStateException(source.toString(), e);
        }
    }

    private static Path pluginSources() throws Exception {
        return RepoRoot.dir(RegistryAuthTest.class, "plugins/image-builder/src/main/java");
    }

    /** An {@link ImageContext} carrying only what a credential is read out of. */
    private record SpecContext(Map<String, Object> values, Map<String, String> secrets) implements ImageContext {
        @Override
        public PluginConfig config() {
            return new PluginConfig("image", values);
        }

        @Override
        public Optional<String> secret(String key) {
            return Optional.ofNullable(secrets.get(key));
        }

        @Override
        public boolean offline() {
            return false;
        }

        @Override
        public ProjectFacts project() {
            return null;
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
    }

    /** A loopback reference is the only one jk will speak plain HTTP to. */
    @Test
    void only_loopback_registries_are_read_over_plain_http() {
        assertThat(RegistryAuth.loopback("127.0.0.1:5000/app:1")).isTrue();
        assertThat(RegistryAuth.loopback("localhost:5000/app:1")).isTrue();
        assertThat(RegistryAuth.loopback("ghcr.io/acme/app:1")).isFalse();
        assertThat(RegistryAuth.loopback("eclipse-temurin:25-jre"))
                .as("a bare name is Docker Hub, not something on this machine")
                .isFalse();
        assertThat(RegistryAuth.loopback("not a reference")).isFalse();
    }

    private static String catchMessage(ThrowingRun body) {
        try {
            body.run();
            return null;
        } catch (Exception e) {
            return String.valueOf(e.getMessage()) + " / " + String.valueOf(e.getCause());
        }
    }

    private interface ThrowingRun {
        void run() throws Exception;
    }

    private RegistryAuth anonymous() {
        return RegistryAuth.of(RepoCredential.ANONYMOUS, RepoCredential.ANONYMOUS, baseRef, null);
    }

    /** jk's resolved credential for the base pull, and for {@code pushRef} when there is one. */
    private RegistryAuth basic(String pushRef) {
        RepoCredential credential = new RepoCredential.Basic(USER, PASSWORD);
        return RegistryAuth.of(credential, credential, baseRef, pushRef);
    }

    private ImageConfig config(String base, String targetRegistry) {
        return new ImageConfig(
                base,
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                targetRegistry,
                "9",
                List.of("linux/amd64"),
                null,
                null,
                null,
                false);
    }

    private ImageBuilder.Plan plan(Path tmp) throws Exception {
        return plan(tmp, config(baseRef, null));
    }

    private ImageBuilder.Plan pushPlan(Path tmp) throws Exception {
        return plan(tmp, config(openBaseRef, registry.hostPort()));
    }

    private ImageBuilder.Plan plan(Path tmp, ImageConfig config) throws Exception {
        Path jar = tmp.resolve("app.jar");
        Files.writeString(jar, "not really a jar, but it is bytes in a layer");
        return new ImageBuilder.Plan(config, "app", "0.1.0", "com.example.Main", jar, List.of());
    }
}
