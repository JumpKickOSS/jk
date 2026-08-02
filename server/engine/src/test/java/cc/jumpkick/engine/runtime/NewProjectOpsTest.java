// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NewProjectOpsTest {

    @Test
    void creates_plain_java_project(@TempDir Path temp) throws Exception {
        // temp is under java.io.tmpdir → allowed parent
        var result = NewProjectOps.create(new NewProjectOps.Request(
                "widget", temp.toString(), "com.acme", "java", "simple", null, true, null));
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
        NewProjectOps.create(new NewProjectOps.Request(
                "dup", temp.toString(), "com.example", "java", "simple", null, false, null));
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
        assertThatThrownBy(() -> NewProjectOps.create(new NewProjectOps.Request(
                        "x", "/etc", "com.example", "java", "simple", null, true, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("HOME");
    }
}
