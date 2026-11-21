// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.Scope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CycloneDxSbomTest {

    private static final String CENTRAL = "central+https://repo.maven.apache.org/maven2/";

    private static final CycloneDxSbom.Closure NONE = CycloneDxSbom.Closure.NONE;

    private static CycloneDxSbom.Closure only(CycloneDxSbom.Component... components) {
        return new CycloneDxSbom.Closure(List.of(components), List.of(), Map.of());
    }

    @Test
    void write_is_stable_regardless_of_component_order() {
        var guava = new CycloneDxSbom.Component(
                "com.google.guava",
                "guava",
                "33.6.0-jre",
                "dc573e1fca4fd5454f4a5fd3d7da2df03002876a4175bafc14a95980dd7713b3");
        var annotations = new CycloneDxSbom.Component(
                "com.google.errorprone",
                "error_prone_annotations",
                "2.50.0",
                "4667724877f1d37a689202da191e23efa7657c62eef93ccdac406eccfe5cdd0a");
        byte[] solverOrder = CycloneDxSbom.write("io.example", "app", "0.1.0", only(guava, annotations));
        byte[] lockOrder = CycloneDxSbom.write("io.example", "app", "0.1.0", only(annotations, guava));
        assertThat(solverOrder).isEqualTo(lockOrder);

        String json = new String(lockOrder, StandardCharsets.UTF_8);
        int errorProne = json.indexOf("error_prone_annotations");
        int guavaName = json.indexOf("\"name\": \"guava\"");
        assertThat(errorProne)
                .as("components sorted by group then artifact")
                .isGreaterThan(0)
                .isLessThan(guavaName);
    }

    @Test
    void document_names_the_spec_version_its_schema_and_jk_as_the_tool() {
        String json = new String(CycloneDxSbom.write("io.example", "app", "0.1.0", NONE), StandardCharsets.UTF_8);
        assertThat(json)
                .contains("\"$schema\": \"http://cyclonedx.org/schema/bom-1.6.schema.json\"")
                .contains("\"specVersion\": \"1.6\"")
                .contains("\"name\": \"jk\"")
                .contains("\"bom-ref\": \"pkg:maven/io.example/app@0.1.0\"")
                .contains("\"purl\": \"pkg:maven/io.example/app@0.1.0\"")
                .contains("\"components\": []")
                .doesNotContain("serialNumber")
                .doesNotContain("timestamp");
    }

    @Test
    void document_does_not_depend_on_the_jk_that_writes_it() {
        String json = new String(CycloneDxSbom.write("io.example", "app", "0.1.0", NONE), StandardCharsets.UTF_8);
        int tools = json.indexOf("\"tools\"");
        String toolsBlock = json.substring(tools, json.indexOf("\"component\":", tools));
        assertThat(toolsBlock).contains("\"name\": \"jk\"").doesNotContain("\"version\"");
    }

    @Test
    void closure_is_what_the_module_reaches_at_runtime_with_its_edges(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "io.example"
                name = "app"
                version = "0.1.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.2.3" }

                [runtime-dependencies]
                driver = { group = "com.example", name = "driver", version = "2.0.0" }

                [test-dependencies]
                junit = { group = "org.junit", name = "junit", version = "6.0.0" }
                """);
        Lockfile lock = lock(
                row("com.example:lib:jar:", "1.2.3", "abcdef", List.of("com.example:core:jar:@1.0.0"), Scope.MAIN),
                row("com.example:core:jar:", "1.0.0", "c0c0", List.of(), Scope.MAIN),
                row("com.example:driver:jar:", "2.0.0", "0123", List.of(), Scope.RUNTIME),
                row("com.example:stray:jar:", "9.9.9", "5555", List.of(), Scope.MAIN),
                row("org.junit:junit:jar:", "6.0.0", "ffff", List.of(), Scope.TEST));

        CycloneDxSbom.Closure closure = CycloneDxSbom.closure(lock, JkBuildParser.parse(dir.resolve("jk.toml")), dir);

        assertThat(closure.components())
                .extracting(CycloneDxSbom.Component::artifact)
                .as("a row nothing the module depends on, and test rows, are not what it ships")
                .containsExactly("core", "driver", "lib");
        assertThat(closure.direct())
                .containsExactly("pkg:maven/com.example/driver@2.0.0", "pkg:maven/com.example/lib@1.2.3");
        assertThat(closure.edges())
                .containsEntry("pkg:maven/com.example/lib@1.2.3", List.of("pkg:maven/com.example/core@1.0.0"))
                .containsEntry("pkg:maven/com.example/core@1.0.0", List.of())
                .doesNotContainKey("pkg:maven/com.example/stray@9.9.9");

        String json = new String(CycloneDxSbom.write("io.example", "app", "0.1.0", closure), StandardCharsets.UTF_8);
        assertThat(json)
                .contains("\"hashes\": [{ \"alg\": \"SHA-256\", \"content\": \"abcdef\" }]")
                .contains("{ \"ref\": \"pkg:maven/io.example/app@0.1.0\", \"dependsOn\": "
                        + "[\"pkg:maven/com.example/driver@2.0.0\", \"pkg:maven/com.example/lib@1.2.3\"] }")
                .contains("{ \"ref\": \"pkg:maven/com.example/lib@1.2.3\", \"dependsOn\": "
                        + "[\"pkg:maven/com.example/core@1.0.0\"] }")
                .doesNotContain("junit")
                .doesNotContain("stray");
    }

    @Test
    void a_workspace_member_names_its_own_closure_and_not_its_siblings(@TempDir Path tmp) throws Exception {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), """
                group = "com.ex"
                name = "ws"
                version = "1.0"

                [workspace]
                modules = ["core", "other", "app"]
                """);
        module(root, "core", """
                [dependencies]
                riding = { group = "com.foo", name = "riding", version = "1.0" }
                """);
        module(root, "other", """
                [dependencies]
                elsewhere = { group = "com.foo", name = "elsewhere", version = "1.0" }
                """);
        Path app = module(root, "app", """
                [dependencies]
                core = { workspace = true }
                own = { group = "com.foo", name = "own", version = "1.0" }
                """);
        Lockfile lock = lock(
                row("com.foo:riding:jar:", "1.0", "aaaa", List.of(), Scope.MAIN),
                row("com.foo:elsewhere:jar:", "1.0", "bbbb", List.of(), Scope.MAIN),
                row("com.foo:own:jar:", "1.0", "cccc", List.of(), Scope.MAIN));

        CycloneDxSbom.Closure closure = CycloneDxSbom.closure(lock, JkBuildParser.parse(app.resolve("jk.toml")), app);

        assertThat(closure.components())
                .extracting(CycloneDxSbom.Component::artifact)
                .as("the sibling it depends on passes its rows on; the one it does not, nothing")
                .containsExactly("own", "riding");
        assertThat(closure.direct()).containsExactly("pkg:maven/com.foo/own@1.0", "pkg:maven/com.foo/riding@1.0");
    }

    @Test
    void an_edge_through_a_pom_only_row_lands_on_the_jars_it_stands_for(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "io.example"
                name = "app"
                version = "0.1.0"

                [dependencies]
                lib = { group = "com.example", name = "lib", version = "1.0" }
                """);
        Lockfile.Artifact alias = new Lockfile.Artifact(
                "com.example:alias:jar:",
                "1.0",
                CENTRAL,
                null,
                null,
                List.of(Scope.MAIN),
                List.of("com.example:real:jar:@1.0"));
        Lockfile lock = lock(
                row("com.example:lib:jar:", "1.0", "aaaa", List.of("com.example:alias:jar:@1.0"), Scope.MAIN),
                alias,
                row("com.example:real:jar:", "1.0", "bbbb", List.of(), Scope.MAIN));

        CycloneDxSbom.Closure closure = CycloneDxSbom.closure(lock, JkBuildParser.parse(dir.resolve("jk.toml")), dir);

        assertThat(closure.components())
                .extracting(CycloneDxSbom.Component::artifact)
                .containsExactly("lib", "real");
        assertThat(closure.edges())
                .containsEntry("pkg:maven/com.example/lib@1.0", List.of("pkg:maven/com.example/real@1.0"));
    }

    @Test
    void classifier_variants_of_one_version_are_distinct_components(@TempDir Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "io.example"
                name = "app"
                version = "0.1.0"

                [dependencies]
                lwjgl = { group = "org.lwjgl", name = "lwjgl", version = "3.3.6" }
                natives = { group = "org.lwjgl", name = "lwjgl", version = "3.3.6", classifier = "natives-linux" }
                """);
        Lockfile lock = lock(
                row("org.lwjgl:lwjgl:jar:", "3.3.6", "aaaa", List.of("com.example:core:jar:@1.0"), Scope.MAIN),
                row("org.lwjgl:lwjgl:jar:natives-linux", "3.3.6", "bbbb", List.of(), Scope.MAIN),
                row("com.example:core:jar:", "1.0", "cccc", List.of(), Scope.MAIN));

        CycloneDxSbom.Closure closure = CycloneDxSbom.closure(lock, JkBuildParser.parse(dir.resolve("jk.toml")), dir);

        String plain = "pkg:maven/org.lwjgl/lwjgl@3.3.6";
        String natives = "pkg:maven/org.lwjgl/lwjgl@3.3.6?classifier=natives-linux";
        assertThat(closure.components())
                .extracting(CycloneDxSbom.Component::purl)
                .containsExactly("pkg:maven/com.example/core@1.0", plain, natives);
        assertThat(closure.direct()).containsExactly(plain, natives);
        assertThat(closure.edges())
                .as("each variant keeps its own edges")
                .containsEntry(plain, List.of("pkg:maven/com.example/core@1.0"))
                .containsEntry(natives, List.of());

        String json = new String(CycloneDxSbom.write("io.example", "app", "0.1.0", closure), StandardCharsets.UTF_8);
        assertThat(json).contains("\"bom-ref\": \"" + plain + "\"").contains("\"bom-ref\": \"" + natives + "\"");
    }

    @Test
    void purl_carries_a_type_other_than_jar_and_the_classifier_as_qualifiers() {
        assertThat(new CycloneDxSbom.Component("g", "a", "1.0", null, "jar", null).purl())
                .isEqualTo("pkg:maven/g/a@1.0");
        assertThat(new CycloneDxSbom.Component("g", "a", "1.0", null, "aar", null).purl())
                .isEqualTo("pkg:maven/g/a@1.0?type=aar");
        assertThat(new CycloneDxSbom.Component("g", "a", "1.0", "linux-x86_64", "jar", null).purl())
                .isEqualTo("pkg:maven/g/a@1.0?classifier=linux-x86_64");
        assertThat(new CycloneDxSbom.Component("g", "a", "1.0", "tests", "test-jar", null).purl())
                .as("qualifiers sorted by key")
                .isEqualTo("pkg:maven/g/a@1.0?classifier=tests&type=test-jar");
        assertThat(new CycloneDxSbom.Component("g", "a", "1.0", "x+y/z", "jar", null).purl())
                .as("a qualifier value outside the unreserved set is percent-encoded")
                .isEqualTo("pkg:maven/g/a@1.0?classifier=x%2By%2Fz");
    }

    @Test
    void a_lock_row_is_a_component_of_its_own_classifier_and_type() {
        var natives = CycloneDxSbom.Component.of(
                row("org.lwjgl:lwjgl:jar:natives-linux", "3.3.6", "bbbb", List.of(), Scope.MAIN));
        assertThat(natives.classifier()).isEqualTo("natives-linux");
        assertThat(natives.type()).isEqualTo("jar");
        var aar = CycloneDxSbom.Component.of(row("androidx.core:core:aar:", "1.0", "dddd", List.of(), Scope.MAIN));
        assertThat(aar.purl()).isEqualTo("pkg:maven/androidx.core/core@1.0?type=aar");
    }

    private static Lockfile lock(Lockfile.Artifact... rows) {
        return new Lockfile(Lockfile.CURRENT_VERSION, "jk test", Lockfile.RESOLUTION_ALGORITHM, List.of(rows));
    }

    private static Lockfile.Artifact row(String name, String version, String sha, List<String> deps, Scope scope) {
        return new Lockfile.Artifact(name, version, CENTRAL, "sha256:" + sha, null, List.of(scope), deps);
    }

    private static Path module(Path root, String name, String tables) throws Exception {
        Path dir = Files.createDirectories(root.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.ex"
                name = "%s"
                version = "1.0"

                %s""".formatted(name, tables));
        return dir;
    }
}
