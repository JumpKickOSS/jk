// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.host.GitCommand;
import cc.jumpkick.host.Processes;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.task.SourceApiIndex;
import cc.jumpkick.test.AffectedTests;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** The honesty refuses the PRD promised and shipped without. */
class AffectedTestsComputeRefuseTest {

    @Test
    void dirty_source_newer_than_its_class_refuses_stale(@TempDir Path ws) throws Exception {
        // The compile of the classes saw the source before the edit.
        builtThenEdited(ws, "package com.acme;\npublic class Foo {}\n");

        AffectedTests r = AffectedTestsCompute.fromDisk(ws, TestSelection.DEFAULT, null, null);
        assertThat(r.refused()).isTrue();
        assertThat(requireNonNull(r.refuse()).code()).isEqualTo("stale");
        assertThat(requireNonNull(r.refuse()).message()).startsWith("src/main/java/com/acme/Foo.java ");
    }

    @Test
    void source_rewritten_with_the_compiled_content_is_not_stale(@TempDir Path ws) throws Exception {
        // A checkout rewrites the source after the build with the bytes the build compiled: its
        // time moves past the class's, its content does not.
        builtThenEdited(ws, "package com.acme;\npublic class Foo { int x; }\n");

        AffectedTests r = AffectedTestsCompute.fromDisk(ws, TestSelection.DEFAULT, null, null);
        assertThat(r.refused()).as(String.valueOf(r.refuse())).isFalse();
    }

    /**
     * A one-class project whose committed {@code Foo.java} is edited to add a field, and whose
     * classes are an hour older than that edit; the compile of those classes recorded {@code
     * compiled} as the source's content.
     */
    private static void builtThenEdited(Path ws, String compiled) throws Exception {
        git(ws, "init");
        git(ws, "config", "user.email", "t@t");
        git(ws, "config", "user.name", "t");
        Files.writeString(ws.resolve(ManifestPaths.MANIFEST), """
                group = "com.acme"
                name = "solo"
                version = "0.1.0"
                """);
        Path src = ws.resolve("src/main/java/com/acme/Foo.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com.acme;\npublic class Foo {}\n");
        git(ws, "add", ".");
        git(ws, "commit", "-q", "-m", "base");

        var layout = BuildLayout.of(ws, JkBuildParser.parse(ws.resolve(ManifestPaths.MANIFEST)));
        Files.writeString(src, compiled);
        SourceApiIndex.write(SourceApiIndex.path(layout.buildDir()), SourceApiIndex.of(ws, List.of(src)));
        Files.writeString(src, "package com.acme;\npublic class Foo { int x; }\n");

        Path classFile = layout.classesDir().resolve("com/acme/Foo.class");
        Files.createDirectories(classFile.getParent());
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "com/acme/Foo", null, "java/lang/Object", null);
        cw.visitEnd();
        Files.write(classFile, cw.toByteArray());
        Files.setLastModifiedTime(classFile, FileTime.from(Instant.now().minusSeconds(3600)));
    }

    @Test
    void more_than_twelve_dirty_modules_refuses(@TempDir Path ws) throws Exception {
        git(ws, "init");
        git(ws, "config", "user.email", "t@t");
        git(ws, "config", "user.name", "t");
        StringBuilder mods = new StringBuilder();
        for (int i = 1; i <= 13; i++) {
            if (i > 1) mods.append(", ");
            mods.append("\"m").append(i).append("\"");
        }
        Files.writeString(
                ws.resolve(ManifestPaths.MANIFEST),
                "group = \"com.acme\"\nname = \"ws\"\nversion = \"0.1.0\"\n\n[workspace]\nmodules = [" + mods + "]\n");
        for (int i = 1; i <= 13; i++) {
            Path mod = Files.createDirectories(ws.resolve("m" + i));
            Files.writeString(
                    mod.resolve(ManifestPaths.MANIFEST),
                    "group = \"com.acme\"\nname = \"m" + i + "\"\nversion = \"0.1.0\"\n");
        }
        git(ws, "add", ".");
        git(ws, "commit", "-q", "-m", "base");
        for (int i = 1; i <= 13; i++) {
            Path src = ws.resolve("m" + i + "/src/main/java/com/acme/M" + i + ".java");
            Files.createDirectories(src.getParent());
            Files.writeString(src, "package com.acme;\nclass M" + i + " {}\n");
        }

        AffectedTests r = AffectedTestsCompute.fromDisk(ws, TestSelection.DEFAULT, null, null);
        assertThat(r.refused()).isTrue();
        assertThat(requireNonNull(r.refuse()).code()).isEqualTo("too-many-modules");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = GitCommand.of();
        Collections.addAll(cmd, args);
        Processes.Captured run = Processes.capture(
                new ProcessBuilder(cmd).directory(dir.toFile()).redirectErrorStream(true), Duration.ofMinutes(1));
        assertThat(run.exit()).as(run.output()).isZero();
    }
}
