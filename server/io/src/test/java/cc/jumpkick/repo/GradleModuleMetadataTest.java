// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * GMM variant selection for KMP roots: the runtime variant for this build's
 * {@code org.gradle.jvm.environment} decides the redirect; every {@code available-at} target is a
 * platform sibling whose POM-fallback edge gets dropped. Shape mirrors androidx's real publications
 * (compose runtime-annotation).
 */
class GradleModuleMetadataTest {

    private static final String KMP_ROOT = """
            {
              "formatVersion": "1.1",
              "component": { "group": "androidx.compose.runtime", "module": "runtime-annotation", "version": "1.9.0" },
              "variants": [
                {
                  "name": "metadataApiElements",
                  "attributes": { "org.gradle.usage": "kotlin-metadata" },
                  "files": [ { "name": "runtime-annotation-metadata-1.9.0.jar" } ]
                },
                {
                  "name": "androidRuntimeElements-published",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "android",
                    "org.gradle.usage": "java-runtime"
                  },
                  "available-at": {
                    "group": "androidx.compose.runtime", "module": "runtime-annotation-android", "version": "1.9.0"
                  }
                },
                {
                  "name": "jvmRuntimeElements-published",
                  "attributes": {
                    "org.gradle.category": "library",
                    "org.gradle.jvm.environment": "standard-jvm",
                    "org.gradle.usage": "java-runtime"
                  },
                  "available-at": {
                    "group": "androidx.compose.runtime", "module": "runtime-annotation-jvm", "version": "1.9.0"
                  }
                },
                {
                  "name": "iosArm64ApiElements-published",
                  "attributes": { "org.gradle.usage": "kotlin-api" },
                  "available-at": {
                    "group": "androidx.compose.runtime", "module": "runtime-annotation-iosarm64", "version": "1.9.0"
                  }
                }
              ]
            }
            """;

    @Test
    void selects_the_environment_matched_runtime_variant(@TempDir Path dir) throws Exception {
        Path module = Files.writeString(dir.resolve("m.module"), KMP_ROOT);
        var gmm = GradleModuleMetadata.parse(module);

        assertThat(gmm.runtimeRedirect("android").orElseThrow().module()).isEqualTo("runtime-annotation-android");
        assertThat(gmm.runtimeRedirect("standard-jvm").orElseThrow().module()).isEqualTo("runtime-annotation-jvm");
    }

    @Test
    void falls_back_to_the_other_environment_when_preferred_is_absent(@TempDir Path dir) throws Exception {
        String jvmOnly = KMP_ROOT.replace(
                "\"org.gradle.jvm.environment\": \"android\"", "\"org.gradle.jvm.environment\": \"absent-env\"");
        Path module = Files.writeString(dir.resolve("m.module"), jvmOnly);
        var gmm = GradleModuleMetadata.parse(module);

        assertThat(gmm.runtimeRedirect("android").orElseThrow().module()).isEqualTo("runtime-annotation-jvm");
    }

    @Test
    void all_available_at_targets_are_reported_for_fallback_dropping(@TempDir Path dir) throws Exception {
        Path module = Files.writeString(dir.resolve("m.module"), KMP_ROOT);
        var gmm = GradleModuleMetadata.parse(module);

        assertThat(gmm.redirectTargetModules())
                .containsExactlyInAnyOrder(
                        "androidx.compose.runtime:runtime-annotation-android",
                        "androidx.compose.runtime:runtime-annotation-jvm",
                        "androidx.compose.runtime:runtime-annotation-iosarm64");
    }

    @Test
    void in_place_runtime_wins_over_a_later_feature_variant_redirect(@TempDir Path dir) throws Exception {
        // grails-core's real shape: runtimeElements ships the jar in place; the cli FEATURE
        // variants carry available-at → grails-core-cli. The first matching runtime variant
        // decides — this is NOT a KMP root, and no redirect may be taken.
        String featureVariants = """
                { "formatVersion": "1.1", "variants": [ {
                    "name": "runtimeElements",
                    "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                    "files": [ { "name": "grails-core-8.0.0-M4.jar" } ]
                  }, {
                    "name": "cliRuntimeElements",
                    "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                    "available-at": {
                      "group": "org.apache.grails", "module": "grails-core-cli", "version": "8.0.0-M4"
                    }
                } ] }
                """;
        Path module = Files.writeString(dir.resolve("m.module"), featureVariants);
        var gmm = GradleModuleMetadata.parse(module);

        assertThat(gmm.runtimeRedirect("standard-jvm")).isEmpty();
        assertThat(gmm.runtimeRedirect("android")).isEmpty();
    }

    @Test
    void an_in_place_publication_reads_as_no_redirect(@TempDir Path dir) throws Exception {
        String inPlace = """
                { "formatVersion": "1.1", "variants": [ {
                    "name": "runtimeElements",
                    "attributes": { "org.gradle.usage": "java-runtime", "org.gradle.jvm.environment": "standard-jvm" },
                    "files": [ { "name": "lib-1.0.jar" } ]
                } ] }
                """;
        Path module = Files.writeString(dir.resolve("m.module"), inPlace);
        var gmm = GradleModuleMetadata.parse(module);

        assertThat(gmm.runtimeRedirect("standard-jvm")).isEmpty();
        assertThat(gmm.redirectTargetModules()).isEmpty();
    }
}
