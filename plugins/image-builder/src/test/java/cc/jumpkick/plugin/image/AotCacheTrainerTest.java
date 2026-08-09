// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.image.ImageConfig;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The decisions the trainer makes without needing a container runtime. */
class AotCacheTrainerTest {

    private static ImageConfig config(String base) {
        return new ImageConfig(
                base, null, List.of(), Map.of(), Map.of(), null, null, List.of(), null, null, null, true);
    }

    private static ImageBuilder.Plan plan(Path classesDir) {
        return new ImageBuilder.Plan(
                config("bellsoft/liberica-runtime-container:jre-25-slim-glibc"),
                "svc",
                "1.0.0",
                "com.example.Main",
                Path.of("/w/target/svc-1.0.0.jar"),
                List.of(Path.of("/cas/aa"), Path.of("/cas/bb")),
                List.of(Path.of("/cas/zz")),
                classesDir);
    }

    /**
     * An exploded-classes layout whose artifact is not a Boot jar has nothing to unpack: a CDS dump
     * refuses a directory on the classpath, and that is Won't Fix upstream (JDK-8329980).
     */
    @Test
    void a_non_boot_exploded_classes_layout_is_refused_with_the_reason() {
        assertThat(AotCacheTrainer.unsupportedReason(plan(Path.of("/w/target/classes"))))
                .contains("exploded-classes")
                .contains("directory");
    }

    /**
     * One fixed order for the training run and the entrypoint. A `*` wildcard expands in directory
     * order, and the training run reads a bind mount while the real run reads an overlay — a
     * different order is a rejected cache.
     */
    @Test
    void the_classpath_is_explicit_ordered_and_starts_with_the_main_jar() {
        String cp = AotCacheTrainer.relativeClasspath(plan(null));
        // Relative, because the archive records entries as given and the image sets WORKDIR /app.
        assertThat(cp).isEqualTo("classpath/svc-1.0.0.jar:libs/aa:libs/bb:libs/zz");
        assertThat(cp).doesNotContain("*").doesNotStartWith("/");
        // Stable across calls — the order is sorted, not whatever the plan happened to hold.
        assertThat(AotCacheTrainer.relativeClasspath(plan(null))).isEqualTo(cp);
    }

    /** Jib reads a bare name as Docker Hub; podman refuses to guess and cannot prompt in a build. */
    @Test
    void short_image_names_are_qualified_for_the_container_runtime() {
        assertThat(AotCacheTrainer.qualify("bellsoft/liberica-runtime-container:jre-25"))
                .isEqualTo("docker.io/bellsoft/liberica-runtime-container:jre-25");
        assertThat(AotCacheTrainer.qualify("eclipse-temurin")).isEqualTo("docker.io/library/eclipse-temurin");
        assertThat(AotCacheTrainer.qualify("docker.io/bellsoft/x:1")).isEqualTo("docker.io/bellsoft/x:1");
        assertThat(AotCacheTrainer.qualify("ghcr.io/acme/x:1")).isEqualTo("ghcr.io/acme/x:1");
        assertThat(AotCacheTrainer.qualify("localhost:5000/x:1")).isEqualTo("localhost:5000/x:1");
    }

    /** The four refusals the JVM emits, all of them only under -Xlog:aot. */
    @Test
    void a_refused_cache_is_recognised_from_the_aot_log() {
        assertThat(AotCacheTrainer.refusal("[0.004s][error  ][aot] shared class paths mismatch"))
                .contains("shared class paths mismatch");
        assertThat(AotCacheTrainer.refusal("[0.003s][warning][aot] The AOT cache was created by a"
                        + " different version or build of HotSpot"))
                .contains("different version");
        assertThat(AotCacheTrainer.refusal("[0.004s][error][aot] Unable to map shared spaces"))
                .contains("Unable to map");
        assertThat(AotCacheTrainer.refusal("[0.003s][error][aot] Loading static archive failed."))
                .contains("failed");
    }

    /**
     * The fast path runs the image's JVM on the host, so it needs a host that can execute it. A
     * linux-amd64 JRE runs on a linux-amd64 host and nowhere else, and a multi-arch image has no
     * single JVM to train with.
     */
    @Test
    void the_host_can_only_run_a_matching_linux_platform() {
        boolean linuxAmd64 = System.getProperty("os.name", "")
                        .toLowerCase(java.util.Locale.ROOT)
                        .contains("linux")
                && List.of("amd64", "x86_64").contains(System.getProperty("os.arch", ""));
        assertThat(BaseJre.hostCanExecute(List.of("linux/amd64"))).isEqualTo(linuxAmd64);
        assertThat(BaseJre.hostCanExecute(List.of())).isEqualTo(linuxAmd64); // default is linux/amd64
        assertThat(BaseJre.hostCanExecute(List.of("linux/s390x"))).isFalse();
        assertThat(BaseJre.hostCanExecute(List.of("windows/amd64"))).isFalse();
        assertThat(BaseJre.hostCanExecute(List.of("linux/amd64", "linux/arm64")))
                .as("multi-arch has no single JVM to train with")
                .isFalse();
    }

    @Test
    void a_cache_that_maps_reports_no_refusal() {
        assertThat(AotCacheTrainer.refusal("[0.008s][info][class,path] Archived app classpath validation: passed\n"
                        + "[0.004s][info][aot] Opened AOT cache app.aot."))
                .isNull();
    }
}
