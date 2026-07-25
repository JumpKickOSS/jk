// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Engine-hosted `jk new --<flag>` rendering from manifest [scaffold] data. */
class ScaffoldOpsTest {

    @Test
    void grails_scaffold_renders_grails_app_tree_and_toml_append(@TempDir Path tmp) {
        var files = ScaffoldOps.scaffold(
                tmp,
                Map.of(
                        "plugin", "grails",
                        "lang", "groovy",
                        "package", "com.example",
                        "simpleLayout", "true",
                        "sample", "true",
                        "baseToml", "[project]\nname = \"demo\"\n"));
        assertThat(files.error()).isNull();
        assertThat(files.paths())
                .anyMatch(p -> p.endsWith("jk.toml"))
                .anyMatch(p -> p.endsWith("grails-app/init/com/example/Application.groovy"))
                .anyMatch(p -> p.endsWith("grails-app/init/com/example/BootStrap.groovy"))
                .anyMatch(p -> p.endsWith("grails-app/domain/com/example/Note.groovy"))
                .anyMatch(p -> p.endsWith("grails-app/controllers/com/example/NoteController.groovy"))
                .anyMatch(p -> p.endsWith("grails-app/conf/application.yml"));
        String toml = files.contents().get(files.paths().indexOf(tmp.resolve("jk.toml").toString()));
        assertThat(toml).contains("[grails]").contains("version = \"8.0.0-M4\"").contains("grails-web-boot");
        int note = -1;
        for (int i = 0; i < files.paths().size(); i++) {
            if (files.paths().get(i).endsWith("Note.groovy")) note = i;
        }
        assertThat(files.contents().get(note)).startsWith("package com.example");
    }

    @Test
    void unknown_flag_reports_an_error() {
        var files = ScaffoldOps.scaffold(Path.of("."), Map.of("plugin", "micronaut", "package", "x"));
        assertThat(files.error()).contains("micronaut");
    }
}
