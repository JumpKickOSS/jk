// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleVersionCatalogTest {

    @Test
    void parses_versions_libraries_and_bundles() {
        GradleVersionCatalog cat = GradleVersionCatalog.parseToml("""
                [versions]
                junit = "5.10.2"
                guava = "33.0.0-jre"

                [libraries]
                junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
                guava = { group = "com.google.guava", name = "guava", version.ref = "guava" }
                string-form = "com.example:stringy:1.2.3"
                bom-managed = { module = "org.springframework.boot:spring-boot-starter-web" }

                [bundles]
                testing = ["junit-jupiter", "guava"]
                """);

        assertThat(cat.resolveLibrary("junit.jupiter")).hasValue("org.junit.jupiter:junit-jupiter:5.10.2");
        assertThat(cat.resolveLibrary("guava")).hasValue("com.google.guava:guava:33.0.0-jre");
        assertThat(cat.resolveLibrary("string.form")).hasValue("com.example:stringy:1.2.3");
        // Version-less (BOM-managed) keeps GA form — not silently dropped.
        assertThat(cat.resolveLibrary("bom.managed"))
                .hasValue("org.springframework.boot:spring-boot-starter-web");

        var bundle = cat.resolveBundle("testing").orElseThrow();
        assertThat(bundle.coordinates())
                .containsExactly(
                        "org.junit.jupiter:junit-jupiter:5.10.2", "com.google.guava:guava:33.0.0-jre");
        assertThat(bundle.missingMembers()).isEmpty();
        assertThat(cat.parseNotes()).isEmpty();
    }

    @Test
    void unresolved_version_ref_is_noted_and_imports_as_versionless() {
        GradleVersionCatalog cat = GradleVersionCatalog.parseToml("""
                [libraries]
                orphan = { module = "com.example:orphan", version.ref = "missing" }
                """);

        assertThat(cat.resolveLibrary("orphan")).hasValue("com.example:orphan");
        assertThat(cat.parseNotes()).anyMatch(n -> n.contains("version.ref `missing`"));
    }

    @Test
    void bundle_reports_missing_members() {
        GradleVersionCatalog cat = GradleVersionCatalog.parseToml("""
                [versions]
                junit = "5.10.2"

                [libraries]
                junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }

                [bundles]
                testing = ["junit-jupiter", "does-not-exist"]
                """);

        var bundle = cat.resolveBundle("testing").orElseThrow();
        assertThat(bundle.coordinates()).containsExactly("org.junit.jupiter:junit-jupiter:5.10.2");
        assertThat(bundle.missingMembers()).containsExactly("does.not.exist");
    }

    @Test
    void locate_finds_catalog_in_project_or_parent(@TempDir Path tmp) throws Exception {
        Path project = tmp.resolve("app");
        Files.createDirectories(project.resolve("src"));
        Path gradle = tmp.resolve("gradle");
        Files.createDirectories(gradle);
        Path catalog = gradle.resolve("libs.versions.toml");
        Files.writeString(catalog, """
                [libraries]
                leaf = { module = "com.foo:leaf", version = "1.0" }
                """);

        assertThat(GradleVersionCatalog.locate(project)).hasValue(catalog);
        assertThat(GradleVersionCatalog.forProject(project)).isPresent();
        assertThat(GradleVersionCatalog.forProject(project).get().resolveLibrary("leaf"))
                .hasValue("com.foo:leaf:1.0");
    }
}
