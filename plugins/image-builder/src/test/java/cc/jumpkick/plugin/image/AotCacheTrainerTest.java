// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.host.Os;
import cc.jumpkick.image.ImageConfig;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    /** A packager tree (Quarkus) is never refused for layout — only for host/runtime. */
    @Test
    void a_packager_app_tree_is_never_refused_for_layout() {
        ImageBuilder.Plan appTree = new ImageBuilder.Plan(
                config("bellsoft/liberica-runtime-container:jre-25-slim-glibc"),
                "svc",
                "1.0.0",
                "com.example.Main",
                Path.of("/w/target/svc-1.0.0.jar"),
                List.of(),
                List.of(),
                null,
                Map.of(),
                Path.of("/w/target/quarkus-app"),
                "quarkus-run.jar");
        assertThat(appTree.hasAppTree()).isTrue();
        String reason = AotCacheTrainer.unsupportedReason(appTree);
        if (canTrainOnThisHost(appTree)) {
            assertThat(reason).isNull();
        } else {
            assertThat(reason).doesNotContain("exploded-classes").contains("container runtime");
        }
    }

    private static boolean canTrainOnThisHost(ImageBuilder.Plan plan) {
        return AotCacheTrainer.containerRuntime(plan.config().dockerExecutable()) != null
                || BaseJre.hostCanExecute(plan.config().platforms());
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

    /**
     * The fast path runs the image's JVM on the host, so it needs a host that can execute it. A
     * linux-amd64 JRE runs on a linux-amd64 host and nowhere else, and a multi-arch image has no
     * single JVM to train with.
     */
    @Test
    void the_host_can_only_run_a_matching_linux_platform() {
        boolean linuxAmd64 = Os.isLinux() && List.of("amd64", "x86_64").contains(System.getProperty("os.arch", ""));
        assertThat(BaseJre.hostCanExecute(List.of("linux/amd64"))).isEqualTo(linuxAmd64);
        assertThat(BaseJre.hostCanExecute(List.of())).isEqualTo(linuxAmd64); // default is linux/amd64
        assertThat(BaseJre.hostCanExecute(List.of("linux/s390x"))).isFalse();
        assertThat(BaseJre.hostCanExecute(List.of("windows/amd64"))).isFalse();
        assertThat(BaseJre.hostCanExecute(List.of("linux/amd64", "linux/arm64")))
                .as("multi-arch has no single JVM to train with")
                .isFalse();
    }

    /**
     * Where the image's JVM is looked for. The extracted tree is 50–200 MB and is shared by every
     * module built on the same base, so it belongs under jk's cache root — the only place {@code
     * CacheTree.BASE_JRE}'s bound reaches. It used to be written under the module's build output,
     * where nothing reclaims it and the next {@code jk clean} deletes it.
     *
     * <p>Proven by the {@code .extracted} marker, which {@link BaseJre} touches on use: only a
     * lookup rooted at the cache root can find this one. The reference is digest-pinned and the
     * tree already exists, so the found path never reaches the registry — a lookup anywhere else
     * would, and comes back empty.
     */
    @Test
    void the_base_jre_is_taken_from_the_cache_root(@TempDir Path cacheRoot) throws Exception {
        String base = "example.invalid/jre@sha256:" + "a".repeat(64);
        Path marker = cacheRoot
                .resolve("base-jre")
                .resolve(HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(base.getBytes(StandardCharsets.UTF_8))))
                .resolve(".extracted");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, base + "\nsha256:cafe\n");
        Files.setLastModifiedTime(marker, FileTime.fromMillis(0));

        AotCacheTrainer.localBaseJre(plan(null), base, cacheRoot, RegistryAuth.NONE, msg -> {});

        assertThat(Files.getLastModifiedTime(marker).toMillis() > 0)
                .as("the tree under the cache root is the one the trainer used")
                .isEqualTo(BaseJre.hostCanExecute(plan(null).config().platforms()));
    }
}
