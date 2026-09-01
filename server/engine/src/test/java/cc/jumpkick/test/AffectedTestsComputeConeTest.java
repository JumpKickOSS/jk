// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.TestSelection;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.ManifestPaths;
import cc.jumpkick.task.ClassAbi;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Cross-module list path (JK-2606): an ABI edit in a dirty module ranks the *dependent* module's
 * importing test, and the module table carries both {@code dirty} and {@code dependent} rows.
 */
class AffectedTestsComputeConeTest {

    @Test
    void dependent_module_ranks_importers_of_a_dirty_modules_abi_change(@TempDir Path ws) throws Exception {
        git(ws, "init");
        git(ws, "config", "user.email", "t@t");
        git(ws, "config", "user.name", "t");

        Files.writeString(ws.resolve(ManifestPaths.MANIFEST), """
                group = "com.acme"
                name = "ws"
                version = "0.1.0"

                [workspace]
                modules = ["lib", "app"]
                """);
        Path lib = Files.createDirectories(ws.resolve("lib"));
        Path app = Files.createDirectories(ws.resolve("app"));
        Files.writeString(lib.resolve(ManifestPaths.MANIFEST), """
                group = "com.acme"
                name = "lib"
                version = "0.1.0"
                """);
        Files.writeString(app.resolve(ManifestPaths.MANIFEST), """
                group = "com.acme"
                name = "app"
                version = "0.1.0"

                [dependencies]
                lib = { group = "com.acme", name = "lib", version = "0.1.0" }
                """);
        Path fooSrc = lib.resolve("src/main/java/com/acme/lib/Foo.java");
        Files.createDirectories(fooSrc.getParent());
        Files.writeString(fooSrc, "package com.acme.lib;\npublic class Foo { public int n() { return 1; } }\n");
        git(ws, "add", ".");
        git(ws, "commit", "-q", "-m", "base");

        // The edit: Foo grows a method (ABI). On disk: the *pre* fingerprint in lib's abi idx, the
        // *current* class file with the new method — exactly what a post-edit compile leaves.
        Files.writeString(
                fooSrc,
                "package com.acme.lib;\npublic class Foo { public int n() { return 1; } public int m() { return 2; } }\n");
        var rootBuild = JkBuildParser.parse(ws.resolve(ManifestPaths.MANIFEST));
        var libLayout = BuildLayout.of(ws, lib, JkBuildParser.parse(lib.resolve(ManifestPaths.MANIFEST)));
        var appLayout = BuildLayout.of(ws, app, JkBuildParser.parse(app.resolve(ManifestPaths.MANIFEST)));
        byte[] fooPre = fooClass(false);
        byte[] fooNow = fooClass(true);
        AbiIndex.write(AbiIndex.path(libLayout.buildDir()), Map.of("com.acme.lib.Foo", ClassAbi.of(fooPre)));
        writeClass(libLayout.classesDir(), "com/acme/lib/Foo.class", fooNow);
        writeClass(appLayout.testClassesDir(), "com/acme/app/BarTest.class", barTestClass());

        AffectedTests r = AffectedTestsCompute.fromDisk(ws, TestSelection.DEFAULT, null, null);

        assertThat(r.refused()).isFalse();
        assertThat(r.classNames()).contains("com.acme.app.BarTest");
        var byClass = r.ranked().stream()
                .filter(row -> row.className().equals("com.acme.app.BarTest"))
                .findFirst()
                .orElseThrow();
        assertThat(byClass.reason()).isEqualTo("abi-import:com.acme.lib.Foo");
        assertThat(byClass.score()).isEqualTo(90);
        assertThat(r.modules())
                .anySatisfy(m -> {
                    assertThat(m.path()).isEqualTo("lib");
                    assertThat(m.why()).isEqualTo("dirty");
                })
                .anySatisfy(m -> {
                    assertThat(m.path()).isEqualTo("app");
                    assertThat(m.why()).isEqualTo("dependent");
                });
        assertThat(rootBuild.isWorkspaceRoot()).isTrue();

        // -m intersects the ranked list (JK-2613): selecting only `app` still ranks BarTest —
        // the unselected dirty module keeps classifying its changed types — but drops lib's rows.
        AffectedTests onlyApp = AffectedTestsCompute.fromDisk(
                ws, TestSelection.DEFAULT, Set.of(app.toAbsolutePath().normalize()), null);
        assertThat(onlyApp.refused()).isFalse();
        assertThat(onlyApp.classNames()).containsExactly("com.acme.app.BarTest");
        assertThat(onlyApp.modules()).allSatisfy(m -> assertThat(m.path()).isEqualTo("app"));
    }

    private static void writeClass(Path dir, String rel, byte[] bytes) throws Exception {
        Path f = dir.resolve(rel);
        Files.createDirectories(f.getParent());
        Files.write(f, bytes);
    }

    /** {@code public class com.acme.lib.Foo} with {@code n()} and, when grown, {@code m()}. */
    private static byte[] fooClass(boolean withExtraMethod) {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "com/acme/lib/Foo", null, "java/lang/Object", null);
        method(cw, "n", 1);
        if (withExtraMethod) method(cw, "m", 2);
        cw.visitEnd();
        return cw.toByteArray();
    }

    private static void method(ClassWriter cw, String name, int v) {
        MethodVisitor mv = cw.visitMethod(Opcodes.ACC_PUBLIC, name, "()I", null, null);
        mv.visitCode();
        mv.visitLdcInsn(v);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitMaxs(1, 1);
        mv.visitEnd();
    }

    /** {@code com.acme.app.BarTest} holding a {@code Foo} field — an importer of the changed type. */
    private static byte[] barTestClass() {
        ClassWriter cw = new ClassWriter(0);
        cw.visit(Opcodes.V25, Opcodes.ACC_PUBLIC, "com/acme/app/BarTest", null, "java/lang/Object", null);
        FieldVisitor fv = cw.visitField(Opcodes.ACC_PUBLIC, "foo", "Lcom/acme/lib/Foo;", null, null);
        fv.visitEnd();
        cw.visitEnd();
        return cw.toByteArray();
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
