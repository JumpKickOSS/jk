// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code versions.properties} answers a refreshVersions {@code _}: an exact {@code group..artifact}
 * key, else the one short key whose segments are all words of the coordinate; two short keys that
 * fit equally are an ambiguity, and a coordinate no key fits is a miss.
 */
class RefreshVersionsTest {

    /** kotlin4example's file, less the {@code ##} update hints. */
    private static final String KOTLIN4EXAMPLE = """
            #### Dependencies and Plugin versions with their available updates.
            plugin.org.jetbrains.dokka=2.0.0

            version.kotlinx.coroutines=1.10.2

            version.org.junit.platform..junit-platform-launcher=1.13.4

            version.org.slf4j..slf4j-api=2.0.17

            version.kotlin=2.2.0

            version.kotest=5.9.1

            version.junit.jupiter=5.13.4

            version.io.github.microutils..kotlin-logging=3.0.5

            version.ch.qos.logback..logback-classic=1.5.18
            """;

    @Test
    void an_exact_group_and_artifact_key_is_the_pin(@TempDir Path tmp) throws Exception {
        RefreshVersions versions = write(tmp, KOTLIN4EXAMPLE);

        RefreshVersions.Lookup launcher = versions.lookup("org.junit.platform", "junit-platform-launcher");
        assertThat(launcher.version()).isEqualTo("1.13.4");
        assertThat(launcher.keys()).containsExactly("org.junit.platform..junit-platform-launcher");
        assertThat(versions.lookup("io.github.microutils", "kotlin-logging").version())
                .isEqualTo("3.0.5");
        assertThat(versions.lookup("ch.qos.logback", "logback-classic").version())
                .isEqualTo("1.5.18");
    }

    @Test
    void a_short_key_whose_segments_are_words_of_the_coordinate_is_the_pin(@TempDir Path tmp) throws Exception {
        RefreshVersions versions = write(tmp, KOTLIN4EXAMPLE);

        RefreshVersions.Lookup coroutines = versions.lookup("org.jetbrains.kotlinx", "kotlinx-coroutines-core");
        assertThat(coroutines.version()).isEqualTo("1.10.2");
        assertThat(coroutines.keys()).containsExactly("kotlinx.coroutines");
        assertThat(versions.lookup("org.junit.jupiter", "junit-jupiter-api").version())
                .isEqualTo("5.13.4");
        assertThat(versions.lookup("io.kotest", "kotest-assertions-core").version())
                .isEqualTo("5.9.1");
        assertThat(versions.lookup("org.jetbrains.kotlin", "kotlin-stdlib-jdk8").version())
                .isEqualTo("2.2.0");
    }

    @Test
    void a_word_that_only_starts_a_key_segment_does_not_match(@TempDir Path tmp) throws Exception {
        RefreshVersions versions = write(tmp, "version.kotlin=2.2.0\n");

        RefreshVersions.Lookup lookup = versions.lookup("org.jetbrains.kotlinx", "kotlinx-serialization-json");

        assertThat(lookup.found()).as("`kotlin` is not the word `kotlinx`").isFalse();
        assertThat(lookup.keys()).isEmpty();
    }

    @Test
    void the_key_with_more_segments_wins_and_an_equal_fit_is_an_ambiguity(@TempDir Path tmp) throws Exception {
        RefreshVersions versions = write(tmp, """
                version.junit=4.13.2
                version.junit.jupiter=5.13.4
                version.squareup.okhttp3=5.1.0
                version.okhttp3.logging=4.12.0
                """);

        assertThat(versions.lookup("org.junit.jupiter", "junit-jupiter-api").version())
                .isEqualTo("5.13.4");
        assertThat(versions.lookup("junit", "junit").version()).isEqualTo("4.13.2");
        RefreshVersions.Lookup interceptor = versions.lookup("com.squareup.okhttp3", "logging-interceptor");
        assertThat(interceptor.ambiguous()).isTrue();
        assertThat(interceptor.keys()).containsExactly("okhttp3.logging", "squareup.okhttp3");
    }

    @Test
    void the_file_is_read_from_the_build_root_for_a_subproject(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("settings.gradle.kts"), "include(\"core\")\n");
        Files.writeString(tmp.resolve(RefreshVersions.FILE), "version.kotest=5.9.1\n");
        Path core = Files.createDirectories(tmp.resolve("core"));

        assertThat(RefreshVersions.beside(core)
                        .lookup("io.kotest", "kotest-assertions-core")
                        .version())
                .isEqualTo("5.9.1");
        assertThat(RefreshVersions.beside(Files.createDirectories(tmp.resolve("other/nested")))
                        .isEmpty())
                .as("a directory whose parent is not a build root reads no file")
                .isTrue();
    }

    private static RefreshVersions write(Path dir, String body) throws Exception {
        Files.writeString(dir.resolve(RefreshVersions.FILE), body);
        return RefreshVersions.beside(dir);
    }
}
