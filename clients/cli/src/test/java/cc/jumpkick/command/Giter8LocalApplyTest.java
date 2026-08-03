// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;

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
}
