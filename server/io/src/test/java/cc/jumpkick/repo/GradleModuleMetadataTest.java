// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.repo;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
    /** androidx's plain-Android shape: no jvm.environment attribute, api + runtime variants. */
    private static final String ALIGNED_FAMILY = """
            {
              "formatVersion": "1.1",
              "component": { "group": "androidx.core", "module": "core-ktx", "version": "1.19.0" },
              "variants": [
                {
                  "name": "releaseVariantReleaseApiPublication",
                  "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-api" },
                  "dependencies": [ { "group": "androidx.core", "module": "core", "version": { "requires": "1.19.0" } } ],
                  "dependencyConstraints": [
                    { "group": "androidx.core", "module": "core", "version": { "requires": "1.19.0" } },
                    { "group": "androidx.core", "module": "core-soft", "version": { "prefers": "1.19.0" } }
                  ]
                },
                {
                  "name": "releaseVariantReleaseRuntimePublication",
                  "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                  "dependencyConstraints": [
                    { "group": "androidx.core", "module": "core", "version": { "requires": "1.18.0" } },
                    { "group": "androidx.core", "module": "core-strict", "version": { "strictly": "[1.0,2.0)" } }
                  ]
                },
                {
                  "name": "sourcesElements",
                  "attributes": { "org.gradle.category": "documentation", "org.gradle.usage": "java-runtime" },
                  "dependencyConstraints": [
                    { "group": "androidx.core", "module": "docs-only", "version": { "requires": "9" } }
                  ]
                }
              ]
            }
            """;

    @Test
    void dependency_constraints_come_from_the_library_jvm_variants_one_per_module(@TempDir Path dir) throws Exception {
        Path module = Files.writeString(dir.resolve("m.module"), ALIGNED_FAMILY);
        var gmm = GradleModuleMetadata.parse(module);

        // No environment attribute reads as standard-jvm; an android build falls back to it.
        for (String env : List.of("standard-jvm", "android")) {
            assertThat(gmm.dependencyConstraints(env))
                    .as(env)
                    .containsExactly(
                            new GradleModuleMetadata.Constraint("androidx.core", "core", "1.19.0", false),
                            new GradleModuleMetadata.Constraint("androidx.core", "core-strict", "[1.0,2.0)", true));
        }
        assertThat(gmm.runtimeRedirect("android")).isEmpty();
    }

    @Test
    void constraints_follow_the_environment_of_the_variant(@TempDir Path dir) throws Exception {
        String perEnvironment = """
                { "formatVersion": "1.1", "variants": [ {
                    "name": "androidApiElements-published",
                    "attributes": { "org.gradle.category": "library", "org.gradle.jvm.environment": "android",
                                    "org.gradle.usage": "java-api" },
                    "dependencyConstraints": [ { "group": "g", "module": "x", "version": { "requires": "1" } } ]
                  }, {
                    "name": "jvmApiElements-published",
                    "attributes": { "org.gradle.category": "library", "org.gradle.jvm.environment": "standard-jvm",
                                    "org.gradle.usage": "java-api" },
                    "dependencyConstraints": [ { "group": "g", "module": "x", "version": { "requires": "2" } } ]
                } ] }
                """;
        Path module = Files.writeString(dir.resolve("m.module"), perEnvironment);
        var gmm = GradleModuleMetadata.parse(module);

        assertThat(gmm.dependencyConstraints("android"))
                .extracting(GradleModuleMetadata.Constraint::version)
                .containsExactly("1");
        assertThat(gmm.dependencyConstraints("standard-jvm"))
                .extracting(GradleModuleMetadata.Constraint::version)
                .containsExactly("2");
    }

    @Test
    void a_module_without_constraints_publishes_none(@TempDir Path dir) throws Exception {
        String inPlace = """
                { "formatVersion": "1.1", "variants": [ {
                    "name": "runtimeElements",
                    "attributes": { "org.gradle.category": "library", "org.gradle.usage": "java-runtime" },
                    "dependencies": [ { "group": "g", "module": "y", "version": { "requires": "1" } } ],
                    "files": [ { "name": "lib-1.0.jar" } ]
                } ] }
                """;
        Path module = Files.writeString(dir.resolve("m.module"), inPlace);

        assertThat(GradleModuleMetadata.parse(module).dependencyConstraints("standard-jvm"))
                .isEmpty();
    }
}
