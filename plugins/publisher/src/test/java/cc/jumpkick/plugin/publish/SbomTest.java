// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compile.CycloneDxSbom;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.WorkspaceResolve;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.Project;
import cc.jumpkick.model.Scope;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SbomTest {

    private static final String CENTRAL = "central+https://repo.maven.apache.org/maven2/";

    private static final JkBuild PROJECT =
            new JkBuild(new Project("com.example", "widget", "1.0.0", 21), JkBuild.Dependencies.empty());

    private static final CycloneDxSbom.Component A = new CycloneDxSbom.Component("com.example", "a", "1.0.0", "aaaa");
    private static final CycloneDxSbom.Component B = new CycloneDxSbom.Component("com.example", "b", "2.0.0", "bbbb");

    /** The widget depends on {@code a}, which depends on {@code b}. */
    private static final CycloneDxSbom.Closure A_THEN_B = new CycloneDxSbom.Closure(
            List.of(B, A),
            List.of("pkg:maven/com.example/a@1.0.0"),
            Map.of("pkg:maven/com.example/a@1.0.0", List.of("pkg:maven/com.example/b@2.0.0")));

    @Test
    void cyclonedx_without_lockfile_lists_only_the_root() {
        String json = new String(Sbom.cyclonedx(PROJECT, CycloneDxSbom.Closure.NONE), StandardCharsets.UTF_8);
        assertThat(json).contains("\"bomFormat\": \"CycloneDX\"");
        assertThat(json).contains("\"specVersion\": \"1.6\"");
        assertThat(json).contains("\"purl\": \"pkg:maven/com.example/widget@1.0.0\"");
        assertThat(json).contains("\"components\": []");
        assertThat(json).contains("{ \"ref\": \"pkg:maven/com.example/widget@1.0.0\", \"dependsOn\": [] }");
    }

    @Test
    void cyclonedx_lists_the_closure_and_its_dependencies() {
        String json = new String(Sbom.cyclonedx(PROJECT, A_THEN_B), StandardCharsets.UTF_8);
        assertThat(json).contains("\"purl\": \"pkg:maven/com.example/a@1.0.0\"");
        assertThat(json).contains("\"content\": \"aaaa\"");
        assertThat(json)
                .contains("{ \"ref\": \"pkg:maven/com.example/widget@1.0.0\", \"dependsOn\": "
                        + "[\"pkg:maven/com.example/a@1.0.0\"] }")
                .contains("{ \"ref\": \"pkg:maven/com.example/a@1.0.0\", \"dependsOn\": "
                        + "[\"pkg:maven/com.example/b@2.0.0\"] }")
                .contains("{ \"ref\": \"pkg:maven/com.example/b@2.0.0\", \"dependsOn\": [] }");
    }

    @Test
    void spdx_emits_root_package_and_describe_relation() {
        String json = new String(Sbom.spdx(PROJECT, CycloneDxSbom.Closure.NONE), StandardCharsets.UTF_8);
        assertThat(json).contains("\"spdxVersion\":\"SPDX-2.3\"");
        assertThat(json).contains("\"dataLicense\":\"CC0-1.0\"");
        assertThat(json).contains("\"SPDXID\":\"SPDXRef-DOCUMENT\"");
        assertThat(json)
                .contains("\"relationships\":[{\"spdxElementId\":\"SPDXRef-DOCUMENT\","
                        + "\"relationshipType\":\"DESCRIBES\",\"relatedSpdxElement\":\"SPDXRef-Package-Root\"}]");
        assertThat(json).contains("pkg:maven/com.example/widget@1.0.0");
    }

    @Test
    void spdx_emits_one_package_per_component_and_depends_on_from_the_closure_edges() {
        String json = new String(Sbom.spdx(PROJECT, A_THEN_B), StandardCharsets.UTF_8);
        assertThat(json).contains("pkg:maven/com.example/a@1.0.0");
        assertThat(json).contains("pkg:maven/com.example/b@2.0.0");
        assertThat(json).contains("\"algorithm\":\"SHA256\"");
        assertThat(json).contains("\"checksumValue\":\"aaaa\"");
        assertThat(json).contains("\"checksumValue\":\"bbbb\"");
        String a = "SPDXRef-Package-com.example-a-1.0.0-0";
        String b = "SPDXRef-Package-com.example-b-2.0.0-1";
        assertThat(json)
                .contains("\"SPDXID\":\"" + a + "\"")
                .contains("{\"spdxElementId\":\"SPDXRef-Package-Root\",\"relationshipType\":\"DEPENDS_ON\","
                        + "\"relatedSpdxElement\":\"" + a + "\"}")
                .contains("{\"spdxElementId\":\"" + a + "\",\"relationshipType\":\"DEPENDS_ON\","
                        + "\"relatedSpdxElement\":\"" + b + "\"}")
                .doesNotContain("{\"spdxElementId\":\"SPDXRef-Package-Root\",\"relationshipType\":\"DEPENDS_ON\","
                        + "\"relatedSpdxElement\":\"" + b + "\"}");
    }

    @Test
    void spdx_tells_a_classifier_variant_from_the_main_jar_of_one_version() {
        var main = new CycloneDxSbom.Component("com.example", "a", "1.0.0", "aaaa");
        var natives = new CycloneDxSbom.Component("com.example", "a", "1.0.0", "linux", "jar", "cccc");
        var closure = new CycloneDxSbom.Closure(
                List.of(main, natives, B),
                List.of(main.purl(), natives.purl()),
                Map.of(natives.purl(), List.of(B.purl())));

        String json = new String(Sbom.spdx(PROJECT, closure), StandardCharsets.UTF_8);

        String a = "SPDXRef-Package-com.example-a-1.0.0-0";
        String n = "SPDXRef-Package-com.example-a-1.0.0-1";
        String b = "SPDXRef-Package-com.example-b-2.0.0-2";
        assertThat(json)
                .contains("\"referenceLocator\":\"pkg:maven/com.example/a@1.0.0?classifier=linux\"")
                .contains("{\"SPDXID\":\"" + a + "\",\"name\":\"com.example:a\",")
                .contains("{\"SPDXID\":\"" + n + "\",\"name\":\"com.example:a:jar:linux\",")
                .contains("{\"spdxElementId\":\"SPDXRef-Package-Root\",\"relationshipType\":\"DEPENDS_ON\","
                        + "\"relatedSpdxElement\":\"" + n + "\"}")
                .contains("{\"spdxElementId\":\"" + n + "\",\"relationshipType\":\"DEPENDS_ON\","
                        + "\"relatedSpdxElement\":\"" + b + "\"}")
                .doesNotContain("{\"spdxElementId\":\"" + a + "\",\"relationshipType\":\"DEPENDS_ON\"");
    }

    @Test
    void spdx_names_a_type_other_than_jar_in_the_package_name() {
        var aar = new CycloneDxSbom.Component("androidx.core", "core", "1.0", null, "aar", "dddd");
        String json = new String(
                Sbom.spdx(PROJECT, new CycloneDxSbom.Closure(List.of(aar), List.of(aar.purl()), Map.of())),
                StandardCharsets.UTF_8);
        assertThat(json).contains("\"name\":\"androidx.core:core:aar\"");
    }

    @Test
    void spdx_renders_the_same_bytes_every_time_so_a_publish_rerun_matches_what_it_uploaded() {
        assertThat(Sbom.spdx(PROJECT, A_THEN_B)).isEqualTo(Sbom.spdx(PROJECT, A_THEN_B));
    }

    /**
     * A member publishes from the workspace lock as it reads it: its own dependencies and what the
     * siblings it depends on pass on, not the rows another member put in the lock.
     */
    @Test
    void a_members_sidecars_name_its_own_closure_and_not_its_siblings(@TempDir Path tmp) throws Exception {
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
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "jk test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        List.of(
                                row("com.foo:riding", "aaaa"),
                                row("com.foo:elsewhere", "bbbb"),
                                row("com.foo:own", "cccc"))),
                root.resolve("jk-lock.toml"));
        JkBuild project = WorkspaceResolve.applyWorkspace(app, JkBuildParser.parse(app.resolve("jk.toml")));

        CycloneDxSbom.Closure closure = Publisher.runtimeClosure(app, project);

        String cdx = new String(Sbom.cyclonedx(project, closure), StandardCharsets.UTF_8);
        String spdx = new String(Sbom.spdx(project, closure), StandardCharsets.UTF_8);
        assertThat(cdx)
                .contains("pkg:maven/com.foo/own@1.0", "pkg:maven/com.foo/riding@1.0")
                .doesNotContain("elsewhere");
        assertThat(spdx)
                .contains("pkg:maven/com.foo/own@1.0", "pkg:maven/com.foo/riding@1.0")
                .doesNotContain("elsewhere");
        assertThat(Publisher.resolvedDependencies(closure))
                .containsExactly(
                        new SlsaProvenance.Dependency("pkg:maven/com.foo/own@1.0", "cccc"),
                        new SlsaProvenance.Dependency("pkg:maven/com.foo/riding@1.0", "aaaa"));
    }

    private static Lockfile.Artifact row(String module, String sha) {
        return new Lockfile.Artifact(
                module + ":jar:", "1.0", CENTRAL, "sha256:" + sha, null, List.of(Scope.MAIN), List.of());
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
