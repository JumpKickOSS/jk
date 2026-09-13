// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.model.Scope;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class CycloneDxSbomTest {

    private static final String CENTRAL = "central+https://repo.maven.apache.org/maven2/";

    private static Lockfile.Artifact row(String module, String version, String sha, Scope... scopes) {
        return new Lockfile.Artifact(module, version, CENTRAL, "sha256:" + sha, null, List.of(scopes), List.of());
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
        byte[] solverOrder = CycloneDxSbom.write("io.example", "app", "0.1.0", List.of(guava, annotations));
        byte[] lockOrder = CycloneDxSbom.write("io.example", "app", "0.1.0", List.of(annotations, guava));
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
        String json = new String(CycloneDxSbom.write("io.example", "app", "0.1.0", List.of()), StandardCharsets.UTF_8);
        assertThat(json)
                .contains("\"$schema\": \"http://cyclonedx.org/schema/bom-1.6.schema.json\"")
                .contains("\"specVersion\": \"1.6\"")
                .contains("\"name\": \"jk\"")
                .contains("\"version\": \"" + JkVersion.VERSION + "\"")
                .contains("\"bom-ref\": \"pkg:maven/io.example/app@0.1.0\"")
                .contains("\"purl\": \"pkg:maven/io.example/app@0.1.0\"")
                .contains("\"components\": []")
                .doesNotContain("serialNumber")
                .doesNotContain("timestamp");
    }

    @Test
    void components_are_the_runtime_rows_of_the_lock_with_their_checksums() {
        Lockfile lock = new Lockfile(
                5,
                "jk test",
                "pubgrub-v1",
                List.of(
                        row("com.example:lib", "1.2.3", "abcdef", Scope.MAIN),
                        row("com.example:driver", "2.0.0", "0123", Scope.RUNTIME),
                        row("org.junit:junit", "6.0.0", "ffff", Scope.TEST),
                        row("com.google.errorprone:error_prone_core", "2.50.0", "eeee", Scope.PROCESSOR)));
        List<CycloneDxSbom.Component> components = CycloneDxSbom.components(lock);
        assertThat(components)
                .extracting(CycloneDxSbom.Component::artifact)
                .as("test and processor rows are not part of what ships")
                .containsExactlyInAnyOrder("lib", "driver");
        assertThat(components.get(0).group()).isEqualTo("com.example");
        assertThat(components.get(0).sha256()).isEqualTo("abcdef");

        String json = new String(CycloneDxSbom.write("io.example", "app", "0.1.0", components), StandardCharsets.UTF_8);
        assertThat(json)
                .contains("\"purl\": \"pkg:maven/com.example/lib@1.2.3\"")
                .contains("\"hashes\": [{ \"alg\": \"SHA-256\", \"content\": \"abcdef\" }]")
                .doesNotContain("junit");
    }
}
