// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewProjectOpsTest {

    @Test
    void creates_plain_java_project(@TempDir Path temp) throws Exception {
        // temp is under java.io.tmpdir → allowed parent
        var result = NewProjectOps.create(
                new NewProjectOps.Request("widget", temp.toString(), "com.acme", "java", "simple", null, true, null));
        Path root = result.path();
        assertThat(root).isEqualTo(temp.resolve("widget"));
        assertThat(root.resolve("jk.toml")).exists();
        String toml = Files.readString(root.resolve("jk.toml"));
        assertThat(toml).contains("name     = \"widget\"");
        assertThat(toml).contains("group    = \"com.acme\"");
        assertThat(root.resolve("src")).isDirectory();
    }

    @Test
    void rejects_existing_project(@TempDir Path temp) throws Exception {
        NewProjectOps.create(
                new NewProjectOps.Request("dup", temp.toString(), "com.example", "java", "simple", null, false, null));
        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "dup", temp.toString(), "com.example", "java", "simple", null, false, null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void rejects_bad_name(@TempDir Path temp) {
        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "../evil", temp.toString(), "com.example", "java", "simple", null, true, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejects_path_outside_home_and_tmp() {
        assertThatThrownBy(() -> NewProjectOps.create(
                        new NewProjectOps.Request("x", "/etc", "com.example", "java", "simple", null, true, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HOME");
    }

    @Test
    void symlinked_parent_pointing_outside_the_allowlist_is_refused(@TempDir Path temp) throws Exception {
        // JK-1485: java.io.tmpdir is world-writable, so another local user can plant a link there;
        // a lexical check would accept it and the scaffolder would write through it. The target
        // must be genuinely outside both roots — @TempDir usually lives under /tmp, so it is not.
        Path outside = Path.of("/etc");
        Path home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize();
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"));
        org.junit.jupiter.api.Assumptions.assumeTrue(
                Files.isDirectory(outside)
                        && !outside.startsWith(home)
                        && !outside.startsWith(tmpDir.toAbsolutePath().normalize()),
                "needs a directory outside $HOME and the temp dir");
        Path link = tmpDir.resolve("jk-test-escape-" + ProcessHandle.current().pid());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException unsupported) {
            return; // no symlink support — nothing to prove
        }
        try {
            assertThatThrownBy(() -> NewProjectOps.assertAllowedParent(link))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must be under");
        } finally {
            Files.deleteIfExists(link);
        }
    }

    @Test
    void a_real_directory_under_tmp_is_still_allowed(@TempDir Path temp) throws Exception {
        Path real = Files.createDirectories(Path.of(
                System.getProperty("java.io.tmpdir"),
                "jk-test-ok-" + ProcessHandle.current().pid()));
        try {
            NewProjectOps.assertAllowedParent(real); // must not throw
        } finally {
            Files.deleteIfExists(real);
        }
    }

    @Test
    void extract_from_jar_removed_no_classpath_bundle(@TempDir Path temp) throws Exception {
        // Bundled giter8 resources removed (jk-templates cache only). This test now just
        // verifies the test jar was created correctly – the engine no longer extracts
        // from classpath.
        Path jar = temp.resolve("templates.jar");
        try (var out = new ZipOutputStream(Files.newOutputStream(jar))) {
            out.putNextEntry(new ZipEntry("giter8/acme-jar-demo/default.properties"));
            out.write("name=demo\n".getBytes());
            out.closeEntry();
            out.putNextEntry(new ZipEntry("giter8/acme-jar-demo/src/main/g8/jk.toml"));
            out.write("name=demo\n".getBytes());
            out.closeEntry();
        }
        assertThat(Files.isRegularFile(jar)).isTrue();
    }

    @Test
    void resolve_template_finds_dogfood_short_name(@TempDir Path temp) throws Exception {
        // Unique short name so the official cache cannot steal the hit.
        Path templates = temp.resolve("templates");
        Path g8 = templates.resolve("acme-demo.g8");
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve("default.properties"), "name=demo\njk_languages=java\njk_layout=traditional\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), "name=demo\n");
        Path parent = temp.resolve("apps");
        Files.createDirectories(parent);
        Path resolved = NewProjectOps.resolveTemplate("acme-demo", parent);
        assertThat(resolved).isEqualTo(g8.toAbsolutePath().normalize());
    }
}
