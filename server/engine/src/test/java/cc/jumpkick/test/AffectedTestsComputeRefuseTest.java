// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/** The honesty refuses the PRD promised and JK-2605 shipped without (JK-2612). */
class AffectedTestsComputeRefuseTest {

    @Test
    void dirty_source_newer_than_its_class_refuses_stale(@TempDir Path ws) throws Exception {
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
        Files.writeString(src, "package com.acme;\npublic class Foo { int x; }\n");

        var layout = BuildLayout.of(ws, JkBuildParser.parse(ws.resolve(ManifestPaths.MANIFEST)));
        Path classFile = layout.classesDir().resolve("com/acme/Foo.class");
        Files.createDirectories(classFile.getParent());
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "com/acme/Foo", null, "java/lang/Object", null);
        cw.visitEnd();
        Files.write(classFile, cw.toByteArray());
        // The class predates the edit: its bytecode is a lie about the dirty source.
        Files.setLastModifiedTime(classFile, FileTime.from(Instant.now().minusSeconds(3600)));

        AffectedTests r = AffectedTestsCompute.fromDisk(ws, TestSelection.DEFAULT, null, null);
        assertThat(r.refused()).isTrue();
        assertThat(r.refuse().code()).isEqualTo("stale");
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
        assertThat(r.refuse().code()).isEqualTo("too-many-modules");
    }

    private static void git(Path dir, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("git");
        Collections.addAll(cmd, args);
        Process p = new ProcessBuilder(cmd)
                .directory(dir.toFile())
                .redirectErrorStream(true)
                .start();
        p.getInputStream().readAllBytes();
        assertThat(p.waitFor()).isZero();
    }
}
