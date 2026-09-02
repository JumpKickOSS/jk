// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NativePreflightTest {

    @Test
    void messages_are_terse() {
        assertThat(NativePreflight.GRAAL_UNSET.length()).isLessThan(78);
        assertThat(NativePreflight.NATIVE_IMAGE_MISSING.length()).isLessThan(78);
        assertThat(NativePreflight.NO_MAIN.length()).isLessThan(78);
        assertThat(NativePreflight.MANY_MAINS.length()).isLessThan(78);
    }

    @Test
    void graal_unset_when_env_missing() {
        assertThat(NativePreflight.graal(null)).isEqualTo(new NativePreflight.Graal.Fail(NativePreflight.GRAAL_UNSET));
        assertThat(NativePreflight.graal("  ")).isEqualTo(new NativePreflight.Graal.Fail(NativePreflight.GRAAL_UNSET));
    }

    @Test
    void graal_missing_binary(@TempDir Path dir) {
        assertThat(NativePreflight.graal(dir.toString()))
                .isEqualTo(new NativePreflight.Graal.Fail(NativePreflight.NATIVE_IMAGE_MISSING));
    }

    @Test
    void graal_ok_when_native_image_exists(@TempDir Path dir) throws Exception {
        Path bin = dir.resolve("bin");
        Files.createDirectories(bin);
        Files.writeString(bin.resolve("native-image"), "#!/bin/sh\n");
        assertThat(NativePreflight.graal(dir.toString())).isEqualTo(new NativePreflight.Graal.Ok(dir));
    }

    /** Accepts the {@code lib/svm/bin} launcher layout used by Windows GraalVM and older GraalVMs. */
    @Test
    void graal_ok_for_the_svm_layout(@TempDir Path dir) throws Exception {
        Path svmBin = dir.resolve("lib").resolve("svm").resolve("bin");
        Files.createDirectories(svmBin);
        Files.writeString(svmBin.resolve("native-image.exe"), "MZ\n");
        assertThat(NativePreflight.graal(dir.toString())).isEqualTo(new NativePreflight.Graal.Ok(dir));
    }

    /** The message names the directories the search actually looks in, not a subset of them. */
    @Test
    void missing_message_names_every_searched_directory() {
        assertThat(NativePreflight.NATIVE_IMAGE_MISSING).contains("bin", "lib/svm/bin");
    }

    @Test
    void specified_application_main(@TempDir Path dir) throws Exception {
        writeToml(dir, "group=\"g\"\nname=\"n\"\nversion=\"1\"\njava=25\n\n[application]\nmain=\"com.Acme\"\n");
        assertThat(NativePreflight.resolveMain(dir, null)).isEqualTo(new NativePreflight.Main.Unique("com.Acme"));
        assertThat(NativePreflight.resolveMain(dir, "cli.Main")).isEqualTo(new NativePreflight.Main.Unique("cli.Main"));
    }

    @Test
    void no_main_when_nothing_declared_or_found(@TempDir Path dir) throws Exception {
        writeToml(dir, "group=\"g\"\nname=\"n\"\nversion=\"1\"\njava=25\n");
        Path src = dir.resolve("src/main/java/com/Lib.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com; public class Lib { public int n() { return 1; } }\n");
        assertThat(NativePreflight.resolveMain(dir, null)).isEqualTo(new NativePreflight.Main.None());
        assertThat(NativePreflight.failMessage(new NativePreflight.Main.None())).isEqualTo(NativePreflight.NO_MAIN);
    }

    @Test
    void source_scan_finds_a_single_main(@TempDir Path dir) throws Exception {
        writeToml(dir, "group=\"g\"\nname=\"n\"\nversion=\"1\"\njava=25\n");
        Path src = dir.resolve("src/main/java/com/App.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, "package com;\npublic class App { public static void main(String[] a) {} }\n");
        var resolved = NativePreflight.resolveMain(dir, null);
        assertThat(resolved).isInstanceOf(NativePreflight.Main.Unique.class);
    }

    @Test
    void specified_main_wins_over_several_sources(@TempDir Path dir) throws Exception {
        writeToml(dir, "group=\"g\"\nname=\"n\"\nversion=\"1\"\njava=25\n\n[application]\nmain=\"A\"\n");
        Path a = dir.resolve("src/main/java/A.java");
        Path b = dir.resolve("src/main/java/B.java");
        Files.createDirectories(a.getParent());
        Files.writeString(a, "public class A { public static void main(String[] args) {} }\n");
        Files.writeString(b, "public class B { public static void main(String[] args) {} }\n");
        assertThat(NativePreflight.resolveMain(dir, null)).isEqualTo(new NativePreflight.Main.Unique("A"));
    }

    @Test
    void source_scan_reports_multiple_mains(@TempDir Path dir) throws Exception {
        writeToml(dir, "group=\"g\"\nname=\"n\"\nversion=\"1\"\njava=25\n");
        Path a = dir.resolve("src/main/java/A.java");
        Path b = dir.resolve("src/main/java/B.java");
        Files.createDirectories(a.getParent());
        Files.writeString(a, "public class A { public static void main(String[] args) {} }\n");
        Files.writeString(b, "public class B { void main() {} }\n");
        assertThat(NativePreflight.resolveMain(dir, null)).isEqualTo(new NativePreflight.Main.Ambiguous());
        assertThat(NativePreflight.failMessage(new NativePreflight.Main.Ambiguous()))
                .isEqualTo(NativePreflight.MANY_MAINS);
    }

    @Test
    void source_scan_ignores_commented_main(@TempDir Path dir) throws Exception {
        writeToml(dir, "group=\"g\"\nname=\"n\"\nversion=\"1\"\njava=25\n");
        Path src = dir.resolve("src/main/java/Lib.java");
        Files.createDirectories(src.getParent());
        Files.writeString(src, """
                public class Lib {
                    // public static void main(String[] args) {}
                    /* void main() {} */
                    public int n() { return 1; }
                }
                """);
        assertThat(NativePreflight.resolveMain(dir, null)).isEqualTo(new NativePreflight.Main.None());
    }

    private static void writeToml(Path dir, String body) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), body);
    }
}
