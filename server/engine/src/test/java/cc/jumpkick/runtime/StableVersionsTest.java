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
 * repositories. The fixture is a {@code file://} repository the manifest names, so nothing here
 * reaches the network.
 */
class StableVersionsTest {

    private static Path project(Path tmp) throws IOException {
        Path repo = tmp.resolve("repo");
        metadata(repo, "com.acme", "thing", "1.0.0", "1.2.0", "2.0.0-RC1");
        metadata(repo, "com.acme", "preview", "1.0.0-M1", "1.0.0-M2");
        Path dir = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(dir.resolve(ManifestPaths.MANIFEST), """
                group   = "com.acme"
                name    = "app"
                version = "0.1.0"

                [repositories]
                local = "%s"
                """.formatted(repo.toUri()));
        return dir;
    }

    private static void metadata(Path repo, String group, String artifact, String... versions) throws IOException {
        Path dir = Files.createDirectories(repo.resolve(group.replace('.', '/')).resolve(artifact));
        StringBuilder list = new StringBuilder();
        for (String v : versions) list.append("      <version>").append(v).append("</version>\n");
        Files.writeString(dir.resolve("maven-metadata.xml"), """
                <?xml version="1.0" encoding="UTF-8"?>
                <metadata>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <versioning>
                    <versions>
                %s    </versions>
                  </versioning>
                </metadata>
                """.formatted(group, artifact, list));
    }

    @Test
    void latest_pins_the_newest_stable_and_explicit_selectors_pass_through(@TempDir Path tmp) throws Exception {
        Path manifest = project(tmp).resolve(ManifestPaths.MANIFEST);
        assertThat(StableVersions.pinnedVersion(manifest, "com.acme", "thing", "latest"))
                .isEqualTo("1.2.0");
        assertThat(StableVersions.pinnedVersion(manifest, "com.acme", "thing", "1.0.0"))
                .isEqualTo("1.0.0");
        assertThat(StableVersions.pinnedVersion(manifest, "com.acme", "thing", "^1"))
                .isEqualTo("^1");
    }

    @Test
    void a_line_with_only_pre_releases_is_refused_with_the_number_to_pass(@TempDir Path tmp) throws Exception {
        Path manifest = project(tmp).resolve(ManifestPaths.MANIFEST);
        assertThatThrownBy(() -> StableVersions.pinnedVersion(manifest, "com.acme", "preview", "latest"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("1.0.0-M2")
                .hasMessageContaining("pass it explicitly");
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
