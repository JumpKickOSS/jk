// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.gradle;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code versions.properties} answers a refreshVersions {@code _}: an exact {@code group..artifact}
 * key, else the short key the plugin's bundled rules spell for the coordinate; a coordinate whose
 * key the file lacks is a miss naming that key. A plugin applied without a version reads its
 * {@code plugin.<id>} entry, Kotlin's {@code version.kotlin}.
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
        assertThat(launcher.key()).isEqualTo("version.org.junit.platform..junit-platform-launcher");
        assertThat(versions.lookup("io.github.microutils", "kotlin-logging").version())
                .isEqualTo("3.0.5");
        assertThat(versions.lookup("ch.qos.logback", "logback-classic").version())
                .isEqualTo("1.5.18");
    }

    /**
     * The short key is the one the plugin's rules spell, whether or not its segments are words of
     * the coordinate: {@code kotlinx.coroutines} for {@code kotlinx-coroutines-core}, {@code kotlin}
     * for every {@code org.jetbrains.kotlin:kotlin-*}, {@code google.android.play-services-maps} for
     * {@code com.google.android.gms:play-services-maps}, {@code androidx.test.ext.junit} for {@code
     * androidx.test.ext:junit-ktx}.
     */
    @Test
    void a_short_key_the_plugins_rules_spell_is_the_pin(@TempDir Path tmp) throws Exception {
        RefreshVersions versions = write(tmp, KOTLIN4EXAMPLE + """
                version.google.android.play-services-maps=19.0.0
                version.androidx.test.ext.junit=1.2.1
                """);

        RefreshVersions.Lookup coroutines = versions.lookup("org.jetbrains.kotlinx", "kotlinx-coroutines-core");
        assertThat(coroutines.version()).isEqualTo("1.10.2");
        assertThat(coroutines.key()).isEqualTo("version.kotlinx.coroutines");
        assertThat(versions.lookup("org.junit.jupiter", "junit-jupiter-api").version())
                .isEqualTo("5.13.4");
        assertThat(versions.lookup("io.kotest", "kotest-assertions-core").version())
                .isEqualTo("5.9.1");
        assertThat(versions.lookup("org.jetbrains.kotlin", "kotlin-stdlib-jdk8").version())
                .isEqualTo("2.2.0");
        assertThat(versions.lookup("com.google.android.gms", "play-services-maps")
                        .version())
                .isEqualTo("19.0.0");
        assertThat(versions.lookup("androidx.test.ext", "junit-ktx").version()).isEqualTo("1.2.1");
    }

    @Test
    void a_coordinate_whose_key_the_file_lacks_is_a_miss_naming_the_key(@TempDir Path tmp) throws Exception {
        RefreshVersions versions = write(tmp, "version.kotlin=2.2.0\n");

        RefreshVersions.Lookup serialization = versions.lookup("org.jetbrains.kotlinx", "kotlinx-serialization-json");
        assertThat(serialization.found())
                .as("`kotlin` pins the compiler, not kotlinx")
                .isFalse();
        assertThat(serialization.key()).isEqualTo("version.kotlinx.serialization");

        RefreshVersions.Lookup unruled = versions.lookup("com.acme", "widgets");
        assertThat(unruled.found()).isFalse();
        assertThat(unruled.key())
                .as("a coordinate no rule covers is kept under its exact key")
                .isEqualTo("version.com.acme..widgets");
    }

    /** A rule without a wildcard wins over a longer wildcard match; a value naming another key is followed. */
    @Test
    void an_exact_rule_wins_and_an_alias_value_is_followed(@TempDir Path tmp) throws Exception {
        RefreshVersions versions = write(tmp, """
                version.junit.junit=4.13.2
                version.junit.jupiter=5.13.4
                version.okhttp3=5.1.0
                version.kotlinpoet=2.2.0
                version.kotlinx.serialization=version.kotlin
                version.kotlin=2.4.10
                """);

        assertThat(versions.lookup("junit", "junit").version()).isEqualTo("4.13.2");
        assertThat(versions.lookup("org.junit.jupiter", "junit-jupiter-params").version())
                .isEqualTo("5.13.4");
        assertThat(versions.lookup("com.squareup.okhttp3", "logging-interceptor")
                        .version())
                .isEqualTo("5.1.0");
        assertThat(versions.lookup("com.squareup", "kotlinpoet-ksp").version()).isEqualTo("2.2.0");
        assertThat(versions.lookup("org.jetbrains.kotlinx", "kotlinx-serialization-json")
                        .version())
                .isEqualTo("2.4.10");
    }

    @Test
    void a_plugin_applied_without_a_version_reads_its_own_key(@TempDir Path tmp) throws Exception {
        RefreshVersions versions = write(tmp, """
                plugin.org.jetbrains.dokka=2.0.0
                plugin.android=8.13.0
                version.kotlin=2.4.10
                """);

        assertThat(versions.pluginVersion("org.jetbrains.dokka").version()).isEqualTo("2.0.0");
        assertThat(versions.pluginVersion("org.jetbrains.kotlin.plugin.serialization")
                        .version())
                .isEqualTo("2.4.10");
        assertThat(versions.pluginVersion("com.android.application").version()).isEqualTo("8.13.0");
        RefreshVersions.Lookup boot = versions.pluginVersion("org.springframework.boot");
        assertThat(boot.found()).isFalse();
        assertThat(boot.key()).isEqualTo("plugin.org.springframework.boot");
    }

    /** A build's own rules, named by the settings file's {@code extraArtifactVersionKeyRules}, join the plugin's. */
    @Test
    void the_settings_files_extra_rules_join_the_bundled_ones(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("settings.gradle.kts"), """
                plugins { id("de.fayard.refreshVersions") version "0.60.5" }
                refreshVersions {
                    extraArtifactVersionKeyRules(file("acme-rules.txt"))
                }
                """);
        Files.writeString(tmp.resolve("acme-rules.txt"), """
                com.acme.platform:acme-*
                    ^^^^.^^^^^^^^
                """);
        RefreshVersions versions = write(tmp, "version.acme.platform=3.1.0\n");

        assertThat(versions.lookup("com.acme.platform", "acme-core").version()).isEqualTo("3.1.0");
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
