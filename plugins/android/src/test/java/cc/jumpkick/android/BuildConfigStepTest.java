// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generated {@code BuildConfig.java}: it lands under the namespace's package path in the
 * step's {@code gen} output, and every constant in it is something the Robolectric tests and the
 * app's own code read at runtime — a wrong {@code APPLICATION_ID} surfaces as a crash on device,
 * not a compile error here.
 */
class BuildConfigStepTest {

    /** The default build is debug: {@code DEBUG = true}, the app id is the bare namespace. */
    @Test
    void a_debug_build_generates_the_namespace_package_and_debug_true(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp);

        BuildConfigStep.run(exec);

        String src = generated(exec);
        assertThat(src)
                .contains("package com.example.app;")
                .contains("public final class BuildConfig {")
                .contains("public static final boolean DEBUG = true;")
                .contains("public static final String APPLICATION_ID = \"com.example.app\";")
                .contains("public static final String VERSION_NAME = \"1.0.0\";")
                .contains("public static final int VERSION_CODE = 1;");
        assertThat(exec.labels()).contains("BuildConfig");
    }

    @Test
    void a_release_build_generates_debug_false(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp).config("build-type", "release");

        BuildConfigStep.run(exec);

        assertThat(generated(exec)).contains("public static final boolean DEBUG = false;");
    }

    /** The variant's {@code application-id-suffix} rides {@code APPLICATION_ID}, not the package. */
    @Test
    void the_application_id_carries_the_variant_suffix_and_the_package_does_not(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp).config("application-id-suffix", ".debug");

        BuildConfigStep.run(exec);

        assertThat(generated(exec))
                .contains("public static final String APPLICATION_ID = \"com.example.app.debug\";")
                .contains("package com.example.app;");
    }

    /** {@code [android] version-code} is the constant the app reads, not a fixed 1. */
    @Test
    void the_version_code_is_the_configured_one(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp).config("version-code", 42L);

        BuildConfigStep.run(exec);

        assertThat(generated(exec)).contains("public static final int VERSION_CODE = 42;");
    }

    /** A version code no store or device accepts fails the step, naming the key. */
    @Test
    void a_version_code_out_of_range_is_refused(@TempDir Path tmp) throws Exception {
        FakeBuildIo zero = AndroidIo.step(tmp).config("version-code", 0L);
        FakeBuildIo huge = AndroidIo.step(tmp).config("version-code", 2_100_000_001L);

        assertThatThrownBy(() -> BuildConfigStep.run(zero)).hasMessageContaining("[android] version-code 0");
        assertThatThrownBy(() -> BuildConfigStep.run(huge)).hasMessageContaining("version-code 2100000001");
    }

    /** {@code build-config-fields} entries are verbatim declarations, trimmed and nothing more. */
    @Test
    void custom_fields_pass_through_verbatim(@TempDir Path tmp) throws Exception {
        FakeBuildIo exec = AndroidIo.step(tmp)
                .config(
                        "build-config-fields",
                        List.of("String BACKEND_URL = \"https://api.example.com\"", "  long TIMEOUT_MS = 30000L  "));

        BuildConfigStep.run(exec);

        assertThat(generated(exec))
                .contains("public static final String BACKEND_URL = \"https://api.example.com\";")
                .contains("public static final long TIMEOUT_MS = 30000L;");
    }

    /** The file the compile step will pick up out of the {@code gen} output dir. */
    private static String generated(FakeBuildIo exec) throws Exception {
        return Files.readString(exec.scratch().resolve("gen/com/example/app/BuildConfig.java"));
    }
}
