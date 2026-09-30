// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.engine.http.mcp.McpManifest;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.runtime.base.EditOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The number a writer pins for a version-less coordinate comes from the project's declared
 * repositories, and an exact version must be one they serve. The fixture is a {@code file://}
 * repository the manifest names, so nothing here reaches the network.
 */
class StableVersionsTest {

    private static Path project(Path tmp) throws IOException {
        Path repo = tmp.resolve("repo");
        RepoFixtures.module(repo, "com.acme", "thing", "1.0.0", "1.2.0", "2.0.0-RC1");
        RepoFixtures.module(repo, "com.acme", "preview", "1.0.0-M1", "1.0.0-M2");
        Path dir = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(dir.resolve(ManifestPaths.MANIFEST), """
                group   = "com.acme"
                name    = "app"
                version = "0.1.0"

                [repositories.local]
                url = "%s"
                groups = ["com.acme"]
                """.formatted(repo.toUri()));
        return dir;
    }

    /** {@link #project}, with a platform BOM in the same repository that manages {@code com.acme:thing} at 1.0.0. */
    private static Path projectUnderBom(Path tmp) throws IOException {
        Path dir = project(tmp);
        Path bom = Files.createDirectories(tmp.resolve("repo/com/acme/bom/1.0"));
        Files.writeString(bom.resolve("bom-1.0.pom"), """
                <project>
                  <modelVersion>4.0.0</modelVersion>
                  <groupId>com.acme</groupId>
                  <artifactId>bom</artifactId>
                  <version>1.0</version>
                  <packaging>pom</packaging>
                  <dependencyManagement><dependencies>
                    <dependency><groupId>com.acme</groupId><artifactId>thing</artifactId><version>1.0.0</version></dependency>
                  </dependencies></dependencyManagement>
                </project>
                """);
        Path manifest = dir.resolve(ManifestPaths.MANIFEST);
        Files.writeString(manifest, Files.readString(manifest) + """

                [platform-dependencies]
                bom = "com.acme:bom:1.0"
                """);
        return dir;
    }

    @Test
    void latest_pins_the_newest_stable_and_explicit_selectors_pass_through(@TempDir Path tmp) throws Exception {
        Path manifest = project(tmp).resolve(ManifestPaths.MANIFEST);
        assertThat(StableVersions.versionToWrite(manifest, "com.acme", "thing", "latest"))
                .isEqualTo("1.2.0");
        assertThat(StableVersions.versionToWrite(manifest, "com.acme", "thing", "1.0.0"))
                .isEqualTo("1.0.0");
        assertThat(StableVersions.versionToWrite(manifest, "com.acme", "thing", "^1"))
                .isEqualTo("^1");
    }

    /**
     * A coordinate the manifest's platform BOM manages is written {@code managed} when no version
     * is given, so the BOM keeps owning it; a version given explicitly is written as given.
     */
    @Test
    void a_coordinate_a_platform_bom_manages_is_written_managed(@TempDir Path tmp) throws Exception {
        Path manifest = projectUnderBom(tmp).resolve(ManifestPaths.MANIFEST);
        assertThat(StableVersions.versionToWrite(manifest, "com.acme", "thing", "latest"))
                .isEqualTo("managed");
        assertThat(StableVersions.versionToWrite(manifest, "com.acme", "thing", "1.2.0"))
                .isEqualTo("1.2.0");
    }

    @Test
    void mcp_deps_writes_a_managed_coordinate_versionless_and_says_so(@TempDir Path tmp) throws Exception {
        Path dir = projectUnderBom(tmp);
        Map<String, Object> out = McpManifest.deps(dir.toString(), "add", List.of("com.acme:thing"), "main", false);
        assertThat(out.get("error")).isNull();
        assertThat(out.get("changed")).isEqualTo(true);
        assertThat((String) out.get("preview"))
                .contains("thing = \"com.acme:thing\"\n")
                .doesNotContain("= \"managed\"");
        assertThat(String.valueOf(out.get("notes"))).contains("add com.acme:thing (version managed by the platform)");
    }

    @Test
    void a_line_with_only_pre_releases_is_refused_with_the_number_to_pass(@TempDir Path tmp) throws Exception {
        Path manifest = project(tmp).resolve(ManifestPaths.MANIFEST);
        assertThatThrownBy(() -> StableVersions.versionToWrite(manifest, "com.acme", "preview", "latest"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("1.0.0-M2")
                .hasMessageContaining("pass it explicitly");
    }

    @Test
    void a_coordinate_no_repository_serves_is_named_without_a_made_up_version(@TempDir Path tmp) throws Exception {
        Path manifest = project(tmp).resolve(ManifestPaths.MANIFEST);
        assertThatThrownBy(() -> StableVersions.versionToWrite(manifest, "com.acme", "nothing", "latest"))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("no com.acme:nothing in the configured repositories; ")
                .hasMessageNotContaining("1.2.3");
        assertThatThrownBy(() -> StableVersions.requireExists(manifest, "com.acme", "nothing", "4.0.2"))
                .isInstanceOf(IOException.class)
                .hasMessageStartingWith("no com.acme:nothing in the configured repositories; ");
    }

    @Test
    void an_exact_version_the_repository_lacks_is_refused_with_the_newest_release(@TempDir Path tmp) throws Exception {
        Path manifest = project(tmp).resolve(ManifestPaths.MANIFEST);
        assertThatThrownBy(() -> StableVersions.requireExists(manifest, "com.acme", "thing", "9.9.9"))
                .isInstanceOf(IOException.class)
                .hasMessage("no com.acme:thing:9.9.9 in the configured repositories; the newest release is 1.2.0");
        StableVersions.requireExists(manifest, "com.acme", "thing", "1.0.0");
        StableVersions.requireExists(manifest, "com.acme", "nothing", "^1");
        StableVersions.requireExists(manifest, "com.acme", "nothing", "managed");
    }

    @Test
    void mcp_deps_preview_of_a_missing_version_is_an_error_and_writes_nothing(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        String before = Files.readString(dir.resolve(ManifestPaths.MANIFEST));
        Map<String, Object> out =
                McpManifest.deps(dir.toString(), "add", List.of("com.acme:thing:9.9.9"), "main", false);
        assertThat(String.valueOf(out.get("error")))
                .contains("no com.acme:thing:9.9.9 in the configured repositories; the newest release is 1.2.0");
        assertThat(Files.readString(dir.resolve(ManifestPaths.MANIFEST))).isEqualTo(before);

        assertThat(McpManifest.deps(dir.toString(), "add", List.of("com.acme:thing:1.0.0"), "main", true)
                        .get("error"))
                .isNull();
        String added = Files.readString(dir.resolve(ManifestPaths.MANIFEST));
        Map<String, Object> pin =
                McpManifest.deps(dir.toString(), "pin", List.of("com.acme:thing:9.9.9"), "main", true);
        assertThat(String.valueOf(pin.get("error"))).contains("no com.acme:thing:9.9.9");
        assertThat(Files.readString(dir.resolve(ManifestPaths.MANIFEST))).isEqualTo(added);
    }

    @Test
    void add_dependency_edit_writes_the_coordinate_string_and_reports_the_version(@TempDir Path tmp) throws Exception {
        Path manifest = project(tmp).resolve(ManifestPaths.MANIFEST);
        EditOps.Result r =
                EditOps.apply(manifest, "add-dependency", List.of("main", "thing", "com.acme", "thing", "1.2.0"));
        assertThat(r.error()).isNull();
        assertThat(r.detail()).isEqualTo("1.2.0");
        assertThat(Files.readString(manifest)).contains("thing = \"com.acme:thing:1.2.0\"");
    }

    @Test
    void mcp_deps_pins_a_versionless_coordinate_through_the_same_writer(@TempDir Path tmp) throws Exception {
        Path dir = project(tmp);
        Map<String, Object> out = McpManifest.deps(dir.toString(), "add", List.of("com.acme:thing"), "main", false);
        assertThat(out.get("error")).isNull();
        assertThat(out.get("changed")).isEqualTo(true);
        assertThat((String) out.get("preview"))
                .contains("thing = \"com.acme:thing:1.2.0\"")
                .doesNotContain("latest");
        assertThat(String.valueOf(out.get("notes"))).contains("add com.acme:thing:1.2.0");
        assertThat(Files.readString(dir.resolve(ManifestPaths.MANIFEST))).doesNotContain("thing =");
    }
}
