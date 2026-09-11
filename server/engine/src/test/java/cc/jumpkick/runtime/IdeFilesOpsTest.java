// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.ide.IdeGeneration;
import cc.jumpkick.ide.IdeTarget;
import cc.jumpkick.runtime.base.IdeFilesOps;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The engine-hosted {@code jk ide}: model, shared generators and the BSP file, with a preview that touches nothing. */
class IdeFilesOpsTest {

    private static Path project(Path tmp) throws IOException {
        Path dir = tmp.resolve("hello");
        Files.createDirectories(dir.resolve("src/com/acme"));
        Files.writeString(dir.resolve("src/com/acme/Main.java"), "package com.acme;\nclass Main {}\n");
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name = "hello"
                version = "1.0.0"
                java = 25

                [application]
                main = "com.acme.Main"
                """);
        return dir;
    }

    private static IdeFilesOps.Result run(Path tmp, Path dir, EnumSet<IdeTarget> targets, boolean preview) {
        return IdeFilesOps.generate(
                dir, tmp.resolve("cache"), tmp.resolve("jdks"), tmp.resolve("ide-config"), targets, preview);
    }

    private static List<Path> files(IdeFilesOps.Result result) {
        List<Path> out = new ArrayList<>();
        for (IdeGeneration g : result.generations()) out.addAll(g.files());
        return out;
    }

    @Test
    void a_directory_without_a_manifest_is_an_error(@TempDir Path tmp) {
        IdeFilesOps.Result result = run(tmp, tmp, EnumSet.allOf(IdeTarget.class), true);
        assertThat(result.error()).contains("no jk.toml");
        assertThat(result.generations()).isEmpty();
    }

    @Test
    void preview_lists_every_ide_file_and_writes_nothing(@TempDir Path tmp) throws IOException {
        Path dir = project(tmp);

        IdeFilesOps.Result result = run(tmp, dir, EnumSet.allOf(IdeTarget.class), true);

        assertThat(result.error()).isNull();
        assertThat(result.rootName()).isEqualTo("hello");
        assertThat(result.generations())
                .extracting(IdeGeneration::target)
                .containsExactly(IdeTarget.IDEA, IdeTarget.VSCODE);
        assertThat(files(result))
                .contains(
                        dir.resolve(".idea/modules.xml"),
                        dir.resolve("hello.iml"),
                        dir.resolve(".idea/runConfigurations/hello.xml"),
                        dir.resolve(".classpath"),
                        dir.resolve(".vscode/launch.json"));
        assertThat(result.bsp()).isEqualTo(dir.resolve(".bsp/jk.json"));
        assertThat(dir.resolve(".idea")).doesNotExist();
        assertThat(dir.resolve(".vscode")).doesNotExist();
        assertThat(dir.resolve(".bsp")).doesNotExist();
        assertThat(dir.resolve("hello.iml")).doesNotExist();
    }

    @Test
    void one_ide_writes_its_files_and_the_bsp_connection_file(@TempDir Path tmp) throws IOException {
        Path dir = project(tmp);

        IdeFilesOps.Result result = run(tmp, dir, EnumSet.of(IdeTarget.VSCODE), false);

        assertThat(result.error()).isNull();
        assertThat(result.generations()).extracting(IdeGeneration::target).containsExactly(IdeTarget.VSCODE);
        for (Path f : files(result)) assertThat(f).isRegularFile();
        assertThat(dir.resolve(".vscode/settings.json")).isRegularFile();
        assertThat(dir.resolve(".project")).isRegularFile();
        assertThat(dir.resolve(".idea")).doesNotExist();
        assertThat(result.bsp()).isNotNull();
        assertThat(Files.readString(result.bsp())).contains("\"argv\": [\"jk\", \"bsp\", \"serve\"]");
    }
}
