// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The engine half of: an image worker that pulls (and maybe pushes) a private registry has
 * to be handed a credential, and the credential has to travel where a secret is allowed to travel.
 *
 * <p>"Allowed" means three things, each asserted below rather than asserted about: it rides a
 * {@code secret} spec line and never a {@code config} one; the spec file it lands in is owner-only;
 * and it is absent from the packaging cache's action key, which is written to disk under a name
 * anyone can list.
 *
 * <p>The credential is supplied through a module {@code .env}, which is a real resolution source
 * ({@code JK_REPO_<ID>_*}) rather than a test hook — so this also pins that the lookup id is the
 * registry host.
 */
class ImageCredentialsTest {

    private static final String REGISTRY = "registry.example:5000";
    private static final String BASE = REGISTRY + "/acme/jre:21";
    private static final String PASSWORD = "pa55word-must-not-leak";

    /** The one value of a {@code config}/{@code secret} spec line for {@code key}, or null. */
    private static String line(List<String> spec, String kind, String key) {
        for (String raw : spec) {
            if (MiniJson.parse(raw) instanceof Map<?, ?> m && kind.equals(m.get("t")) && key.equals(m.get("key"))) {
                return String.valueOf(m.get("value"));
            }
        }
        return null;
    }

    /** The pull credential is written, and it is written as a secret. */
    @Test
    void the_base_pull_credential_rides_a_secret_line(@TempDir Path tmp) throws Exception {
        Path module = module(
                tmp,
                "JK_REPO_REGISTRY_EXAMPLE_5000_USERNAME=robot\n" + "JK_REPO_REGISTRY_EXAMPLE_5000_PASSWORD=" + PASSWORD
                        + "\n");

        List<String> spec = spec(module, tmp.resolve("cache"), null);

        assertThat(line(spec, "config", "baseAuthType")).isEqualTo("basic");
        assertThat(line(spec, "secret", "baseUser")).isEqualTo("robot");
        assertThat(line(spec, "secret", "basePass")).isEqualTo(PASSWORD);
        assertThat(spec.stream().filter(l -> l.contains("\"t\":\"config\"")).toList())
                .as("a config value is echoed in describe payloads and plugin logs; a secret is not")
                .noneMatch(l -> l.contains(PASSWORD));
    }

    /**
     * Tarball mode contacts exactly one registry. Resolving the push target as well would file a
     * credential — with the redactor, and in the spec — for a registry this build never opens.
     */
    @Test
    void tarball_mode_asks_for_no_push_credential(@TempDir Path tmp) throws Exception {
        Path module = module(tmp, "JK_REPO_REGISTRY_EXAMPLE_5000_TOKEN=" + PASSWORD + "\n");

        List<String> spec = spec(module, tmp.resolve("cache"), null);

        assertThat(line(spec, "config", "baseAuthType")).isEqualTo("bearer");
        assertThat(line(spec, "secret", "baseToken")).isEqualTo(PASSWORD);
        assertThat(line(spec, "config", "pushAuthType")).isNull();
        assertThat(line(spec, "secret", "pushToken")).isNull();
    }

    /** Push mode resolves the target registry too — a separate host from the base in general. */
    @Test
    void push_mode_carries_a_credential_for_the_target_registry(@TempDir Path tmp) throws Exception {
        Path module = module(
                tmp,
                "JK_REPO_REGISTRY_EXAMPLE_5000_USERNAME=robot\n" + "JK_REPO_REGISTRY_EXAMPLE_5000_PASSWORD=" + PASSWORD
                        + "\n");

        List<String> spec = spec(module, tmp.resolve("cache"), REGISTRY);

        assertThat(line(spec, "config", "pushAuthType")).isEqualTo("basic");
        assertThat(line(spec, "secret", "pushUser")).isEqualTo("robot");
        assertThat(line(spec, "secret", "pushPass")).isEqualTo(PASSWORD);
    }

    /** A registry nobody has credentials for stays anonymous, and files nothing. */
    @Test
    void a_registry_with_no_stored_credential_writes_no_secret(@TempDir Path tmp) throws Exception {
        List<String> spec = spec(module(tmp, null), tmp.resolve("cache"), null);

        assertThat(line(spec, "config", "baseAuthType")).isEqualTo("anonymous");
        assertThat(spec).noneMatch(l -> l.contains("\"t\":\"secret\""));
    }

    /**
     * The action key is a file name in the cache and a token list in {@code jk explain}. The base
     * image and the registry legitimately belong in it; the credential for them does not.
     */
    @Test
    void the_credential_is_no_part_of_the_action_key(@TempDir Path tmp) throws Exception {
        Path module = module(
                tmp,
                "JK_REPO_REGISTRY_EXAMPLE_5000_USERNAME=robot\n" + "JK_REPO_REGISTRY_EXAMPLE_5000_PASSWORD=" + PASSWORD
                        + "\n");
        Path mainJar = Files.writeString(tmp.resolve("app.jar"), "jar");
        Path workerJar = Files.writeString(tmp.resolve("worker.jar"), "worker");

        List<String> tokens = ImagePlans.imageTokens(
                mainJar, List.of(), List.of(), null, "com.example.Main", BASE, imageConfig(REGISTRY), "", workerJar);

        assertThat(String.join("\n", tokens))
                .contains(BASE)
                .doesNotContain(PASSWORD)
                .doesNotContain("robot");
        assertThat(module).exists(); // the .env is in scope for this module; the key still has none
    }

    /**
     * The spec file holds the secret in the clear, so it may not be world-readable — and the plan
     * has to be the thing that gets it that way. Asserting the helper alone would stay green with
     * the plan back on a default-permission {@code createTempFile}, which is the state this fixed.
     * POSIX mode bits are asserted only where the filesystem supports them; Windows falls through
     * to default ACLs in {@link ImageCredentials#newSpecFile}.
     */
    @Test
    void the_worker_spec_file_is_owner_only() throws Exception {
        Path spec = ImageCredentials.newSpecFile();
        try {
            if (spec.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Set<PosixFilePermission> mode = Files.getPosixFilePermissions(spec);
                assertThat(PosixFilePermissions.toString(mode))
                        .as("a default temp file is 0644 — every local account could read the registry password")
                        .isEqualTo("rw-------");
            }
        } finally {
            Files.deleteIfExists(spec);
        }
        assertThat(Files.readString(imagePlansSource()))
                .contains("ImageCredentials.newSpecFile()")
                .doesNotContain("createTempFile(\"jk-image-");
    }

    private static Path imagePlansSource() throws Exception {
        return RepoRoot.file(
                ImageCredentialsTest.class, "server/engine/src/main/java/cc/jumpkick/runtime/ImagePlans.java");
    }

    private static ImageConfig imageConfig(String registry) {
        return new ImageConfig(
                BASE, null, List.of(), Map.of(), Map.of(), registry, "1", List.of("linux/amd64"), null, null, null);
    }

    /** The worker spec for {@code module}: tarball mode unless {@code registry} is set. */
    private static List<String> spec(Path module, Path cache, String registry) throws Exception {
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        return ImagePlans.imageWorkerSpec(
                        cache,
                        project,
                        BuildLayout.of(module, project),
                        imageConfig(registry),
                        BASE,
                        "com.example.Main",
                        List.of(),
                        List.of(),
                        null,
                        registry == null ? module.resolve("target/app.tar") : null)
                .lines();
    }

    private static Path module(Path tmp, String dotEnv) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(module.resolve("jk.toml"), """
            group = "t"
            name = "app"
            version = "0.1.0"
            jdk = 25
            java = 25
            """);
        if (dotEnv != null) Files.writeString(module.resolve(".env"), dotEnv);
        return module;
    }
}
