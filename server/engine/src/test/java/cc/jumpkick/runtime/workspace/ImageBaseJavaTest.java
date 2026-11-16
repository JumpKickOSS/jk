// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** The default image base follows the module's Java level, and a hand-written base names its Java where it can. */
class ImageBaseJavaTest {

    @Test
    void the_default_base_is_a_jre_of_the_module_s_level() {
        for (int level : new int[] {17, 21, 25, 26, 27}) {
            String base = ImageBaseJava.resolve(null, level);
            assertThat(base).isEqualTo("bellsoft/liberica-runtime-container:jre-" + level + "-slim-glibc");
            assertThat(ImageBaseJava.javaMajor(base)).hasValue(level);
        }
        assertThat(ImageBaseJava.resolve("  ", 27)).endsWith(":jre-27-slim-glibc");
        assertThat(ImageBaseJava.resolve("eclipse-temurin:{java-major-version}-jre", 26))
                .isEqualTo("eclipse-temurin:26-jre");
        assertThat(ImageBaseJava.resolve("ghcr.io/acme/base:3", 25)).isEqualTo("ghcr.io/acme/base:3");
    }

    @Test
    void a_jdk_image_s_reference_names_its_java_major() {
        assertThat(ImageBaseJava.javaMajor("eclipse-temurin:25-jre")).hasValue(25);
        assertThat(ImageBaseJava.javaMajor("docker.io/library/eclipse-temurin:21.0.4_7-jre-noble"))
                .hasValue(21);
        assertThat(ImageBaseJava.javaMajor("docker.io/bellsoft/liberica-runtime-container:jre-25-slim-glibc"))
                .hasValue(25);
        assertThat(ImageBaseJava.javaMajor("bellsoft/liberica-openjre-debian:21"))
                .hasValue(21);
        assertThat(ImageBaseJava.javaMajor("amazoncorretto:17-al2023-headless")).hasValue(17);
        assertThat(ImageBaseJava.javaMajor("ibm-semeru-runtimes:open-21-jre")).hasValue(21);
        assertThat(ImageBaseJava.javaMajor("azul/zulu-openjdk:8u412-jre")).hasValue(8);
        assertThat(ImageBaseJava.javaMajor("gcr.io/distroless/java21-debian12:nonroot"))
                .hasValue(21);
        assertThat(ImageBaseJava.javaMajor("registry.access.redhat.com/ubi9/openjdk-21-runtime:1.20"))
                .hasValue(21);
        assertThat(ImageBaseJava.javaMajor("eclipse-temurin:25-jre@sha256:" + "a".repeat(64)))
                .hasValue(25);
        assertThat(ImageBaseJava.javaMajor("localhost:5000/eclipse-temurin:21-jre"))
                .hasValue(21);
        assertThat(ImageBaseJava.javaMajor("ghcr.io/acme/runtime:jre-17")).hasValue(17);
    }

    @Test
    void a_reference_that_does_not_say_its_java_is_not_read() {
        assertThat(ImageBaseJava.javaMajor("eclipse-temurin:latest")).isEmpty();
        assertThat(ImageBaseJava.javaMajor("eclipse-temurin")).isEmpty();
        assertThat(ImageBaseJava.javaMajor("eclipse-temurin@sha256:" + "a".repeat(64)))
                .isEmpty();
        // A company base's own version is not a Java.
        assertThat(ImageBaseJava.javaMajor("ghcr.io/acme/base:10")).isEmpty();
        assertThat(ImageBaseJava.javaMajor("localhost:5000/acme/base")).isEmpty();
        assertThat(ImageBaseJava.javaMajor("debian:12-slim")).isEmpty();
    }

    @Test
    void a_base_older_than_the_module_s_level_is_refused_with_both_ways_out() {
        String why = ImageBaseJava.tooOld("eclipse-temurin:21-jre", 25);
        assertThat(why)
                .contains("eclipse-temurin:21-jre", "Java 21", "Java 25")
                .contains("Remove [image] base", "jre-25-slim-glibc");
        assertThat(ImageBaseJava.tooOld("docker.io/bellsoft/liberica-runtime-container:jre-25-slim-glibc", 27))
                .contains("Java 25", "Java 27");
        assertThat(ImageBaseJava.tooOld("eclipse-temurin:25-jre", 25)).isNull();
        assertThat(ImageBaseJava.tooOld("eclipse-temurin:27-jre", 25)).isNull();
        assertThat(ImageBaseJava.tooOld("ghcr.io/acme/base:10", 25)).isNull();
    }
}
