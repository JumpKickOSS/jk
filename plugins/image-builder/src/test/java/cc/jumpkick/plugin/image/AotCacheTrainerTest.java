// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.image;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Os;
import cc.jumpkick.image.ImageConfig;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The decisions the trainer makes without needing a container runtime. */
class AotCacheTrainerTest {

    private static ImageConfig config(String base) {
        return new ImageConfig(
                base, null, null, List.of(), Map.of(), Map.of(), Map.of(), null, null, List.of(), null, null, null,
                true);
    }

    private static ImageBuilder.Plan plan(@Nullable Path classesDir) {
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
                .contains("exploded classes")
                .contains("Spring Boot jar");
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
                "quarkus-run.jar",
                null);
        assertThat(appTree.hasAppTree()).isTrue();
        String reason = AotCacheTrainer.unsupportedReason(appTree);
        if (canTrainOnThisHost(appTree)) {
            assertThat(reason).isNull();
        } else {
            assertThat(reason).doesNotContain("exploded classes").contains("docker");
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
    void training_runs_see_the_image_env_with_the_training_env_over_it() {
        ImageConfig c = new ImageConfig(
                "b",
                null,
                null,
                List.of(),
                Map.of("LANG", "C.UTF-8", "SPRING_FLYWAY_ENABLED", "true"),
                Map.of("SPRING_FLYWAY_ENABLED", "false", "SPRING_CONTEXT_EXIT", "onRefresh"),
                Map.of(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                true);
        Map<String, String> env = AotCacheTrainer.trainingEnvironment(c);
        assertThat(env)
                .containsEntry("LANG", "C.UTF-8")
                .containsEntry("SPRING_FLYWAY_ENABLED", "false")
                .containsEntry("SPRING_CONTEXT_EXIT", "onRefresh");
        List<String> prefix =
                AotCacheTrainer.containerPrefix("podman", Path.of("/w/aot-train"), "img", "linux/amd64", "n", env);
        assertThat(String.join(" ", prefix))
                .contains("-e SPRING_CONTEXT_EXIT=onRefresh")
                .endsWith(" img");
    }

    /**
     * The training runs ask for the image's platform. Without it the runtime runs the host's
     * variant of a multi-platform base, and an arm64 Mac trained an arm64 cache into an amd64
     * image whose JVM refused it at every start.
     */
    @Test
    void training_runs_in_the_images_platform() {
        assertThat(AotCacheTrainer.platform(config("b"))).isEqualTo("linux/amd64");
        assertThat(AotCacheTrainer.platform(withPlatforms(List.of("linux/arm64"))))
                .isEqualTo("linux/arm64");
        Path staging = Path.of("/w/aot-train");
        List<String> prefix = AotCacheTrainer.containerPrefix("podman", staging, "img", "linux/arm64", "n", Map.of());
        assertThat(String.join(" ", prefix)).startsWith("podman run --rm --platform linux/arm64 ");
        // The mount names the host's absolute path: C:\w\aot-train on Windows, which podman and docker take there.
        assertThat(String.join(" ", prefix)).contains(" -v " + staging.toAbsolutePath() + ":/app");
    }

    /** One cache layer cannot serve the JVMs of several platforms: the image ships without one and says so. */
    @Test
    void a_multi_platform_image_gets_no_cache_and_says_why() throws Exception {
        ImageConfig both = withPlatforms(List.of("linux/amd64", "linux/arm64"));
        ImageBuilder.Plan plan = new ImageBuilder.Plan(
                both, "svc", "1.0.0", "com.example.Main", Path.of("/w/target/svc-1.0.0.jar"), List.of());
        assertThat(AotCacheTrainer.platformCheck(plan).noCache())
                .contains("only on the platform that trained it")
                .contains("linux/amd64, linux/arm64");
    }

    /**
     * An emulated training run leaves AOT code out of the cache: compiled for the emulator's CPU, it
     * names features (the hypervisor bit) a real CPU lacks, and a JVM run with -XX:AOTMode=on on
     * real amd64 refused the whole cache over it. The switches are read from the base's JVM, since
     * an unknown -XX flag stops a JVM from starting.
     */
    @Test
    void emulated_training_turns_off_every_aot_code_switch_the_jvm_has() {
        String jdk27 = """
                     bool AOTAdapterCaching                        = false                                  {diagnostic} {ergonomic}
                     uint AOTCodeMaxSize                           = 10485760                               {diagnostic} {default}
                     bool AOTStubCaching                           = false                                  {diagnostic} {ergonomic}
                     bool AbortVMOnAOTCodeFailure                  = false                                  {diagnostic} {default}
                """;
        assertThat(AotCacheTrainer.noCodeFlags(jdk27))
                .containsExactly("-XX:+UnlockDiagnosticVMOptions", "-XX:-AOTAdapterCaching", "-XX:-AOTStubCaching");
        assertThat(AotCacheTrainer.noCodeFlags("     bool AOTClassLinking = false {product} {default}\n"))
                .isEmpty();
    }

    /**
     * The platform probe ({@code java -XshowSettings:properties -version} in the base, for the
     * image's platform): the JVM that ran must be the platform's, a host that cannot run it ships
     * no cache, and any other failure is the build's error.
     */
    @Test
    void the_platform_probe_trains_only_in_the_images_jvm() throws Exception {
        assertThat(AotCacheTrainer.platformRefusal("podman", "linux/amd64", 0, "    os.arch = amd64\n"))
                .isNull();
        assertThat(AotCacheTrainer.platformRefusal("docker", "linux/arm64", 0, "    os.arch = aarch64\n"))
                .isNull();
        assertThat(AotCacheTrainer.platformRefusal("podman", "linux/amd64", 0, "    os.arch = aarch64\n"))
                .contains("ran the arm64 JVM when asked for linux/amd64");
        assertThat(AotCacheTrainer.platformRefusal(
                        "docker", "linux/arm64", 1, "exec /usr/bin/java: exec format error\n"))
                .isEqualTo("docker on this host cannot run linux/arm64 programs (exec format error)");
        assertThatThrownBy(() -> AotCacheTrainer.platformRefusal(
                        "podman", "linux/amd64", 125, "Error: initializing source: unauthorized\n"))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("unauthorized")
                .hasMessageContaining("linux/amd64");
    }

    private static ImageConfig withPlatforms(List<String> platforms) {
        return new ImageConfig(
                "b", null, null, List.of(), Map.of(), Map.of(), Map.of(), null, null, platforms, null, null, null,
                true);
    }

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
     * CacheTree.BASE_JRE}'s bound reaches. Under the module's build output nothing reclaims it
     * and the next {@code jk clean} deletes it.
     *
     * <p>Proven by the {@code .extracted} marker, which {@link BaseJre} touches on use: only a
     * lookup rooted at the cache root can find this one. The reference is digest-pinned and the
     * tree already exists, so the found path never reaches the registry — a lookup anywhere else
     * would, and comes back empty.
     */
    @Test
    void the_base_jre_is_taken_from_the_cache_root(@TempDir Path cacheRoot) throws Exception {
        String base = "example.invalid/jre@sha256:" + "a".repeat(64);
        Path marker = BaseJre.treeOf(base, "linux/amd64", cacheRoot).resolve(".extracted");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, base + "\nsha256:cafe\n");
        Files.setLastModifiedTime(marker, FileTime.fromMillis(0));

        AotCacheTrainer.localBaseJre(plan(null), base, cacheRoot, RegistryAuth.NONE, msg -> {});

        assertThat(Files.getLastModifiedTime(marker).toMillis() > 0)
                .as("the tree under the cache root is the one the trainer used")
                .isEqualTo(BaseJre.hostCanExecute(plan(null).config().platforms()));
    }
}
