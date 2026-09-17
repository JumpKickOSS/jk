// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
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

    /**
     * The tool tables move with the dependency tables: an exact {@code [dokka] version}, {@code
     * [protobuf] version}, a {@code [protobuf.<id>] plugin} and a {@code [generate.<name>] tool} or
     * {@code unpack} coordinate each go to the newest stable on their major, under the same
     * selection and major gate, and a floating selector keeps its text.
     */
    @Test
    void tool_table_pins_move_like_dependency_pins(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name = "tools"
                version = "1.0.0"
                java = 25

                [dokka]
                version = "2.0.0"

                [protobuf]
                version = "4.30.0" # protoc

                [protobuf.grpc-java]
                plugin = "io.grpc:protoc-gen-grpc-java:1.70.0"

                [protobuf.floating]
                plugin = "io.acme:protoc-gen-floating:^1.0"

                [generate.api]
                tool   = "org.openapitools:openapi-generator-cli:7.10.0"
                unpack = "io.zipkin.proto3:zipkin-proto3:1.0.0"
                inputs = ["api/openapi.yaml"]
                """);
        MavenStub upstream = new MavenStub(http);
        published(upstream, "org.jetbrains.dokka", "dokka-cli", "2.0.0", "2.1.0", "3.0.0");
        published(upstream, "com.google.protobuf", "protoc", "4.30.0", "4.33.1");
        published(upstream, "io.grpc", "protoc-gen-grpc-java", "1.70.0", "1.81.0");
        published(upstream, "io.acme", "protoc-gen-floating", "1.0", "1.5");
        published(upstream, "org.openapitools", "openapi-generator-cli", "7.10.0", "7.11.0");
        published(upstream, "io.zipkin.proto3", "zipkin-proto3", "1.0.0", "1.0.1");

        ManifestUpdates.Plan plan = ManifestUpdates.plan(project, http.base(), ManifestUpdates.Selection.ALL);

        assertThat(plan.rewrites())
                .extracting(r -> r.table(), r -> r.handle(), r -> r.module(), r -> r.from(), r -> r.to())
                .containsExactlyInAnyOrder(
                        tuple("dokka.version", "dokka", "org.jetbrains.dokka:dokka-cli", "2.0.0", "2.1.0"),
                        tuple("protobuf.version", "protobuf", "com.google.protobuf:protoc", "4.30.0", "4.33.1"),
                        tuple(
                                "protobuf.grpc-java.plugin",
                                "grpc-java",
                                "io.grpc:protoc-gen-grpc-java",
                                "1.70.0",
                                "1.81.0"),
                        tuple("generate.api.tool", "api", "org.openapitools:openapi-generator-cli", "7.10.0", "7.11.0"),
                        tuple("generate.api.unpack", "api", "io.zipkin.proto3:zipkin-proto3", "1.0.0", "1.0.1"));
        String text = Objects.requireNonNull(plan.contents().get(project.resolve("jk.toml")));
        assertThat(text)
                .contains("[dokka]\nversion = \"2.1.0\"")
                .contains("version = \"4.33.1\" # protoc")
                .contains("plugin = \"io.grpc:protoc-gen-grpc-java:1.81.0\"")
                .contains("plugin = \"io.acme:protoc-gen-floating:^1.0\"")
                .contains("tool   = \"org.openapitools:openapi-generator-cli:7.11.0\"")
                .contains("unpack = \"io.zipkin.proto3:zipkin-proto3:1.0.1\"");
        JkBuildParser.parse(text);

        ManifestUpdates.Plan major = ManifestUpdates.plan(
                project, http.base(), new ManifestUpdates.Selection(List.of("dokka", "grpc-java"), true));
        assertThat(major.rewrites())
                .extracting(r -> r.handle(), r -> r.to())
                .containsExactlyInAnyOrder(tuple("dokka", "3.0.0"), tuple("grpc-java", "1.81.0"));
    }

    /**
     * A plugin whose step-dependency coordinate templates its group or artifact from the table —
     * or, per entry, from the entry — pins the module those values name, and the key holding the
     * version moves like any other tool pin.
     */
    @Test
    void a_templated_group_or_artifact_is_read_through_the_table(@TempDir Path project) throws Exception {
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                        [plugin]
                        id = "templated-tool"
                        table = "templated-tool"

                        [schema]
                        group   = { type = "string", required = true }
                        tool    = { type = "string", required = true }
                        version = { type = "string", required = true }

                        [entries]
                        schema = "gen"

                        [sub-schema.gen]
                        version = { type = "string", required = true }

                        [[contribute.step-dependency]]
                        artifact   = "tool"
                        coordinate = "${config.group}:${config.tool}-cli:${config.version}"

                        [[contribute.step-dependency]]
                        per-entry  = true
                        artifact   = "gen-${entry.name}"
                        coordinate = "${config.group}:gen-${entry.name}:${entry.version}"
                        """, "templated-tool.toml"), null);
        Files.writeString(project.resolve("jk.toml"), """
                group = "com.example"
                name = "tools"
                version = "1.0.0"
                java = 25

                [templated-tool]
                group   = "io.acme"
                tool    = "shaper"
                version = "1.0.0"

                [templated-tool.widget]
                version = "2.0.0"
                """);
        MavenStub upstream = new MavenStub(http);
        published(upstream, "io.acme", "shaper-cli", "1.0.0", "1.1.0");
        published(upstream, "io.acme", "gen-widget", "2.0.0", "2.2.0");

        ManifestUpdates.Plan plan = ManifestUpdates.plan(project, http.base(), ManifestUpdates.Selection.ALL);

        assertThat(plan.rewrites())
                .extracting(r -> r.table(), r -> r.handle(), r -> r.module(), r -> r.from(), r -> r.to())
                .containsExactlyInAnyOrder(
                        tuple("templated-tool.version", "templated-tool", "io.acme:shaper-cli", "1.0.0", "1.1.0"),
                        tuple("templated-tool.widget.version", "widget", "io.acme:gen-widget", "2.0.0", "2.2.0"));
        String text = Objects.requireNonNull(plan.contents().get(project.resolve("jk.toml")));
        assertThat(text).contains("version = \"1.1.0\"").contains("[templated-tool.widget]\nversion = \"2.2.0\"");
        JkBuildParser.parse(text);
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
        published(upstream, "com.acme", artifact, versions);
    }

    private static void published(MavenStub upstream, String group, String artifact, String... versions) {
        for (String v : versions) {
            upstream.pom(group, artifact, v, MavenStub.emptyPom(group, artifact, v));
        }
        upstream.metadata(group, artifact, versions);
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
