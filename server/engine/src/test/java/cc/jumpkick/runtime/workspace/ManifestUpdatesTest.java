// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk update}'s manifest phase: which declared pins move, to what, and how the text is
 * rewritten across a workspace.
 */
class ManifestUpdatesTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @TempDir
    Path isolatedStore;

    @BeforeEach
    void isolateStore() {
        System.setProperty("jk.env.JK_STORE_DIR", isolatedStore.resolve("store").toString());
    }

    @AfterEach
    void releaseStoreOverride() {
        System.clearProperty("jk.env.JK_STORE_DIR");
    }

    // ---- candidate selection --------------------------------------------------

    @Test
    void newest_stable_on_the_same_major_unless_major_is_allowed() {
        List<String> published = List.of("2.18.0", "2.18.2", "3.0.0", "3.1.0-rc1");
        assertThat(ManifestUpdates.newer("2.18.0", published, false)).isEqualTo("2.18.2");
        assertThat(ManifestUpdates.newer("2.18.0", published, true)).isEqualTo("3.0.0");
        assertThat(ManifestUpdates.newer("2.18.2", published, false)).isNull();
        assertThat(ManifestUpdates.newer("3.0.0", published, true)).isNull();
    }

    @Test
    void pre_releases_are_never_a_target_but_a_pre_release_pin_moves_to_its_stable() {
        assertThat(ManifestUpdates.newer("1.0", List.of("1.0", "1.1-SNAPSHOT", "1.1-rc1"), false))
                .isNull();
        assertThat(ManifestUpdates.newer("3.0.0-rc1", List.of("3.0.0-rc1", "3.0.0"), false))
                .isEqualTo("3.0.0");
    }

    @Test
    void the_maven_major_is_the_first_numeric_segment() {
        assertThat(ManifestUpdates.major("2.18.2")).isEqualTo(2);
        assertThat(ManifestUpdates.major("33.4.8-jre")).isEqualTo(33);
        assertThat(ManifestUpdates.major("1")).isEqualTo(1);
        assertThat(ManifestUpdates.major("Final")).isEqualTo(-1);
        assertThat(ManifestUpdates.newer("33.0.0-jre", List.of("33.0.0-jre", "33.4.8-jre", "34.0.0-jre"), false))
                .isEqualTo("33.4.8-jre");
    }

    // ---- planning over a workspace -----------------------------------------------

    @Test
    void every_exact_pin_in_every_manifest_moves_on_its_major_keeping_its_spelling(@TempDir Path ws) throws Exception {
        workspace(ws);
        publish();

        ManifestUpdates.Plan plan = ManifestUpdates.plan(ws, http.base(), ManifestUpdates.Selection.ALL);

        assertThat(plan.rewrites())
                .extracting(
                        r -> r.dir().getFileName().toString(),
                        r -> r.table(),
                        r -> r.handle(),
                        r -> r.from(),
                        r -> r.to())
                .containsExactlyInAnyOrder(
                        tuple(ws.getFileName().toString(), "dependencies", "jackson", "2.18.0", "2.18.2"),
                        tuple(ws.getFileName().toString(), "dependencies", "guava", "33.0.0-jre", "33.4.8-jre"),
                        tuple(ws.getFileName().toString(), "workspace.dependencies", "shared", "1.0.0", "1.2.0"),
                        tuple("lib", "dependencies", "jackson", "2.18.0", "2.18.2"),
                        tuple("lib", "test-dependencies", "tj", "1.0", "1.1"));

        String root = plan.contents().get(ws.resolve("jk.toml"));
        assertThat(root)
                .contains("jackson = \"com.acme:jackson:2.18.2\" # keep")
                .contains("version = \"33.4.8-jre\" }")
                .contains("shared = \"com.acme:shared:1.2.0\"")
                .as("opt-in and platform-managed entries keep their text")
                .contains("floaty = \"com.acme:floaty:^1.0\"")
                .contains("managed = \"com.acme:managed\"");
        String lib = plan.contents().get(ws.resolve("lib").resolve("jk.toml"));
        assertThat(lib)
                .contains("jackson = \"com.acme:jackson:2.18.2\"")
                .contains("tj = \"com.acme:tj:1.1\"")
                .as("an inherited entry is rewritten where its version lives, the root")
                .contains("shared = { workspace = true }");

        // Nothing on disk until apply.
        assertThat(Files.readString(ws.resolve("jk.toml"))).contains("com.acme:jackson:2.18.0");
        ManifestUpdates.apply(plan);
        assertThat(Files.readString(ws.resolve("jk.toml"))).isEqualTo(root);
        assertThat(Files.readString(ws.resolve("lib").resolve("jk.toml"))).isEqualTo(lib);
        assertThat(JkBuildParser.parse(ws.resolve("jk.toml")).isWorkspaceRoot()).isTrue();
        JkBuildParser.parseLocal(ws.resolve("lib").resolve("jk.toml"));
    }

    @Test
    void major_lets_the_selected_pins_cross_a_major_line(@TempDir Path ws) throws Exception {
        workspace(ws);
        publish();

        ManifestUpdates.Plan plan =
                ManifestUpdates.plan(ws, http.base(), new ManifestUpdates.Selection(List.of("jackson", "guava"), true));

        assertThat(plan.rewrites())
                .extracting(r -> r.handle(), r -> r.to())
                .containsExactlyInAnyOrder(
                        tuple("jackson", "3.0.0"), tuple("jackson", "3.0.0"), tuple("guava", "34.0.0-jre"));
    }

    @Test
    void a_selection_limits_the_rewrite_to_those_handles_or_coordinates(@TempDir Path ws) throws Exception {
        workspace(ws);
        publish();

        ManifestUpdates.Plan byHandle =
                ManifestUpdates.plan(ws, http.base(), new ManifestUpdates.Selection(List.of("jackson"), false));
        assertThat(byHandle.rewrites()).extracting(r -> r.handle()).containsOnly("jackson");
        assertThat(byHandle.rewrites()).hasSize(2);
        assertThat(byHandle.contents())
                .containsOnlyKeys(ws.resolve("jk.toml"), ws.resolve("lib").resolve("jk.toml"));

        ManifestUpdates.Plan byCoordinate =
                ManifestUpdates.plan(ws, http.base(), new ManifestUpdates.Selection(List.of("com.acme:tj"), false));
        assertThat(byCoordinate.rewrites())
                .extracting(r -> r.handle(), r -> r.to())
                .containsExactly(tuple("tj", "1.1"));
        assertThat(byCoordinate.contents()).containsOnlyKeys(ws.resolve("lib").resolve("jk.toml"));

        ManifestUpdates.Plan none =
                ManifestUpdates.plan(ws, http.base(), new ManifestUpdates.Selection(List.of("nothing"), false));
        assertThat(none.isEmpty()).isTrue();
        assertThat(none.contents()).isEmpty();
    }

    // ---- fixture ---------------------------------------------------------------

    private void publish() {
        MavenStub upstream = new MavenStub(http);
        versions(upstream, "jackson", "2.18.0", "2.18.2", "3.0.0", "3.1.0-rc1");
        versions(upstream, "guava", "33.0.0-jre", "33.4.8-jre", "34.0.0-jre");
        versions(upstream, "shared", "1.0.0", "1.2.0");
        versions(upstream, "floaty", "1.0", "1.5");
        versions(upstream, "managed", "1.0");
        versions(upstream, "tj", "1.0", "1.1");
    }

    private static void versions(MavenStub upstream, String artifact, String... versions) {
        for (String v : versions) {
            upstream.pom("com.acme", artifact, v, MavenStub.emptyPom("com.acme", artifact, v));
        }
        upstream.metadata("com.acme", artifact, versions);
    }

    private static void workspace(Path ws) throws IOException {
        Files.writeString(ws.resolve("jk.toml"), """
                group = "com.example"
                name = "root"
                version = "1.0.0"
                java = 25

                [workspace]
                modules = ["lib"]

                [workspace.dependencies]
                shared = "com.acme:shared:1.0.0"

                [dependencies]
                jackson = "com.acme:jackson:2.18.0" # keep
                guava = { group = "com.acme", name = "guava", version = "33.0.0-jre" }
                floaty = "com.acme:floaty:^1.0"
                managed = "com.acme:managed"
                """);
        Files.createDirectories(ws.resolve("lib"));
        Files.writeString(ws.resolve("lib").resolve("jk.toml"), """
                group = "com.example"
                name = "lib"
                version = "1.0.0"

                [dependencies]
                shared = { workspace = true }
                jackson = "com.acme:jackson:2.18.0"

                [test-dependencies]
                tj = "com.acme:tj:1.0"
                """);
    }
}
