// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Errors;
import io.quarkus.bootstrap.model.PlatformImportsImpl;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The platform properties artifact reaches the augment as an engine-supplied path — fetched
 * through jk's own repo stack as the {@code quarkus-platform-properties} step-dependency — and the
 * augment reads that file and nothing else: no store-mirror scan, no {@code ~/.m2}, and no Aether
 * resolve through the user's Maven environment.
 */
class QuarkusPlatformPropertiesTest {

    private static final String COORDINATE =
            "io.quarkus.platform:quarkus-bom-quarkus-platform-properties:3.38.3!properties";

    /**
     * The offline augment succeeds from the supplied file alone. The injection is asserted by
     * content — the file's entries land on the model — not by the absence of a refusal, which a
     * dropped path would also produce.
     */
    @Test
    void a_present_artifact_is_injected_from_the_supplied_path_offline(@TempDir Path dir) throws Exception {
        Path props = dir.resolve("quarkus-bom-quarkus-platform-properties-3.38.3.properties");
        Files.writeString(props, "platform.quarkus.native.builder-image=mandrel\n", StandardCharsets.UTF_8);
        PlatformImportsImpl platforms = new PlatformImportsImpl();

        QuarkusAugmentMain.injectPlatformProperties(platforms, "3.38.3", props, true);

        assertThat(platforms.getPlatformProperties()).containsEntry("platform.quarkus.native.builder-image", "mandrel");
        assertThat(platforms.getImportedPlatformBoms()).anySatisfy(bom -> {
            assertThat(bom.getGroupId()).isEqualTo("io.quarkus.platform");
            assertThat(bom.getArtifactId()).isEqualTo("quarkus-bom");
        });
    }

    /** A cold store offline is the one refusal, worded by its owner. */
    @Test
    void a_missing_artifact_offline_refuses_with_the_owner_wording(@TempDir Path dir) {
        assertThatThrownBy(() -> QuarkusAugmentMain.injectPlatformProperties(
                        new PlatformImportsImpl(), "3.38.3", dir.resolve("absent.properties"), true))
                .isInstanceOf(IOException.class)
                .hasMessage(Errors.offlineRefusal(COORDINATE));
    }

    /** A miss online is a store defect naming the coordinate and the re-lock fix — never a fetch. */
    @Test
    void a_missing_artifact_online_fails_naming_the_coordinate(@TempDir Path dir) {
        assertThatThrownBy(() -> QuarkusAugmentMain.injectPlatformProperties(
                        new PlatformImportsImpl(), "3.38.3", dir.resolve("absent.properties"), false))
                .isInstanceOf(IOException.class)
                .hasMessageContaining(COORDINATE)
                .hasMessageContaining("jk lock");
    }

    /**
     * No route to the network exists: no method of the augment entry point takes a resolver, so a
     * miss cannot fall back to resolving the coordinate through the user's {@code
     * ~/.m2/settings.xml}. A reinstated resolver parameter turns this red (or breaks reflection on
     * a test classpath that deliberately lacks the resolver types).
     */
    @Test
    void no_augment_method_accepts_a_maven_resolver() {
        for (Method m : QuarkusAugmentMain.class.getDeclaredMethods()) {
            for (Class<?> param : m.getParameterTypes()) {
                assertThat(param.getName())
                        .as("%s parameter of QuarkusAugmentMain.%s", param.getName(), m.getName())
                        .doesNotContain("MavenArtifactResolver");
            }
        }
    }

    /**
     * The delivery half lives in the manifest: the engine fetches the artifact because
     * {@code jk-plugin.toml} declares it, under the same name {@code augmentArgs} requires. Both
     * ends are pinned here so deleting either one is a red test, not a runtime surprise.
     */
    @Test
    void the_manifest_declares_the_platform_properties_step_dependency() throws Exception {
        List<String> manifest;
        try (var in = QuarkusPlugin.class.getResourceAsStream("/jk-plugin.toml")) {
            assertThat(in).as("jk-plugin.toml at the worker jar root").isNotNull();
            manifest = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .toList();
        }
        assertThat(manifest)
                .contains("artifact   = \"" + QuarkusPlugin.PLATFORM_PROPS_EXTRA + "\"")
                .contains("coordinate = \"io.quarkus.platform:quarkus-bom-quarkus-platform-properties"
                        + ":^${config.version}!properties\"");
    }
}
