// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import cc.jumpkick.scaffold.Giter8LocalApply;

class Giter8LocalApplyTest {

    @Test
    void substitutes_paths_and_content(@TempDir Path tmp) throws Exception {
        Path template = tmp.resolve("t.g8");
        Path g8 = template.resolve("src/main/g8");
        Files.createDirectories(g8.resolve("src/$package$"));
        Files.writeString(template.resolve("default.properties"), """
                name=demo
                package=com.demo
                organization=com.demo
                """);
        Files.writeString(g8.resolve("jk.toml"), """
                name = "$name$"
                group = "$organization$"
                """);
        Files.writeString(g8.resolve("src/$package$/Main.java"), "package $package$;\n// $name$\n");

        Path dest = tmp.resolve("out");
        int n = Giter8LocalApply.apply(template, dest, Map.of("name", "widget"));
        assertThat(n).isEqualTo(2);
        assertThat(dest.resolve("jk.toml")).content().contains("name = \"widget\"");
        assertThat(dest.resolve("src/com/demo/Main.java")).content().contains("package com.demo;");
    }

    @Test
    void template_property_cannot_escape_the_destination(@TempDir Path tmp) throws Exception {
        // JK-1463: a hostile template controls both default.properties and the file names that
        // reference it — a traversing value must not place a write outside dest.
        Path template = tmp.resolve("evil.g8");
        Path g8 = template.resolve("src/main/g8");
        Files.createDirectories(g8.resolve("$evil$"));
        Files.writeString(template.resolve("default.properties"), "name=demo\nevil=../../../pwned\n");
        Files.writeString(g8.resolve("$evil$/payload.txt"), "owned\n");

        Path dest = tmp.resolve("proj/out");
        Files.createDirectories(dest);
        assertThatThrownBy(() -> Giter8LocalApply.apply(template, dest, Map.of()))
                .isInstanceOf(java.io.IOException.class)
                .hasMessageContaining("escapes the project directory");
        assertThat(tmp.resolve("pwned")).doesNotExist();
    }

    @Test
    void symlinked_template_entries_are_skipped_not_dereferenced(@TempDir Path tmp) throws Exception {
        // JK-1465: following a link would copy host files into the generated project.
        Path secret = tmp.resolve("id_ed25519");
        Files.writeString(secret, "PRIVATE KEY MATERIAL\n");
        Path template = tmp.resolve("t.g8");
        Path g8 = template.resolve("src/main/g8");
        Files.createDirectories(g8);
        Files.writeString(template.resolve("default.properties"), "name=demo\n");
        Files.writeString(g8.resolve("jk.toml"), "name = \"$name$\"\n");
        Files.createSymbolicLink(g8.resolve("secrets.txt"), secret);

        Path dest = tmp.resolve("out");
        Giter8LocalApply.apply(template, dest, Map.of());
        assertThat(dest.resolve("jk.toml")).exists();
        assertThat(dest.resolve("secrets.txt")).doesNotExist();
    }
}
