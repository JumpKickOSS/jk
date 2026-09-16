// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link PackagingKeys#libraryArtifacts}: a module with main sources and no {@code [application]}
 * table is a library and ships sources + javadoc jars; {@code sources = "always"} forces them on an
 * application; a coordinator root and a source-less module ship neither.
 */
class LibraryArtifactsPredicateTest {

    @TempDir
    Path tmp;

    @Test
    void a_module_without_an_application_table_is_a_library() throws IOException {
        Path dir = module("lib", "");
        assertThat(PackagingKeys.libraryArtifacts(parse(dir), dir)).isTrue();
    }

    @Test
    void an_application_is_not_a_library_unless_sources_says_always() throws IOException {
        Path app = module("app", "[application]\nmain = \"com.example.App\"\n");
        assertThat(PackagingKeys.libraryArtifacts(parse(app), app)).isFalse();
        Path published = module("cli", "sources = \"always\"\n\n[application]\nmain = \"com.example.App\"\n");
        assertThat(PackagingKeys.libraryArtifacts(parse(published), published)).isTrue();
    }

    @Test
    void a_kotlin_only_module_is_a_library_too() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("kt"));
        Files.writeString(dir.resolve("jk.toml"), manifest("kt", ""));
        Files.createDirectories(dir.resolve("src/main/kotlin/com/example"));
        Files.writeString(dir.resolve("src/main/kotlin/com/example/One.kt"), "package com.example\nclass One\n");
        assertThat(PackagingKeys.libraryArtifacts(parse(dir), dir)).isTrue();
    }

    @Test
    void a_module_without_sources_ships_no_library_jars() throws IOException {
        Path dir = Files.createDirectories(tmp.resolve("empty"));
        Files.writeString(dir.resolve("jk.toml"), manifest("empty", ""));
        assertThat(PackagingKeys.libraryArtifacts(parse(dir), dir)).isFalse();
    }

    @Test
    void a_coordinator_root_ships_no_library_jars() throws IOException {
        Path root = Files.createDirectories(tmp.resolve("ws"));
        Files.writeString(root.resolve("jk.toml"), manifest("ws", "[workspace]\nmodules = [\"lib\"]\n"));
        module("ws/lib", "");
        assertThat(PackagingKeys.libraryArtifacts(parse(root), root)).isFalse();
    }

    private Path module(String name, String extra) throws IOException {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Files.writeString(dir.resolve("jk.toml"), manifest(dir.getFileName().toString(), extra));
        Files.createDirectories(dir.resolve("src/com/example"));
        Files.writeString(dir.resolve("src/com/example/One.java"), "package com.example;\npublic class One {}\n");
        return dir;
    }

    private static String manifest(String name, String extra) {
        return "group = \"com.example\"\nname = \"" + name + "\"\nversion = \"1.0.0\"\njava = 25\n\n" + extra;
    }

    private static JkBuild parse(Path dir) throws IOException {
        return JkBuildParser.parse(dir.resolve("jk.toml"));
    }
}
