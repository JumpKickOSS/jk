// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static cc.jumpkick.runtime.workspace.NativeImagePlans.Staging.NONE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.layout.ReleaseDeliverables;
import cc.jumpkick.model.ImageTable;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.runtime.base.FakeReleaseBucket;
import cc.jumpkick.runtime.base.ReleaseBucket;
import cc.jumpkick.wire.runtime.PublishLane;
import cc.jumpkick.wire.runtime.TailArgs;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code [image] kind = "native"}: which executables the image carries, the platforms it leaves
 * out, where a final publish fetches the missing ones from, its base, and what the worker is told.
 */
class NativeImagePlansTest {

    private static final String HOST = "linux-x86_64";

    @TempDir
    Path tmp;

    @Test
    void image_and_release_platforms_name_each_other() {
        assertThat(NativeImagePlans.imagePlatform("linux-x86_64")).isEqualTo("linux/amd64");
        assertThat(NativeImagePlans.imagePlatform("linux-aarch64")).isEqualTo("linux/arm64");
        assertThat(NativeImagePlans.releasePlatform("linux/amd64")).isEqualTo("linux-x86_64");
        assertThat(NativeImagePlans.releasePlatform("linux/arm64/v8")).isEqualTo("linux-aarch64");
        assertThatThrownBy(() -> NativeImagePlans.releasePlatform("amd64")).hasMessageContaining("not os/arch");
    }

    @Test
    void every_declared_platform_whose_executable_exists_is_in_the_image() throws Exception {
        JkBuild build = module("");
        BuildLayout layout = BuildLayout.of(tmp, build);
        Path host = write(layout.nativeBinary());
        Path arm = write(staged(layout, build, "linux-aarch64"));

        NativeImagePlans.Executables found =
                NativeImagePlans.executables(layout, build, List.of("linux/amd64", "linux/arm64"), HOST, NONE);

        assertThat(found.found()).containsExactly(Map.entry("linux/amd64", host), Map.entry("linux/arm64", arm));
        assertThat(found.skipped()).isEmpty();
        assertThat(found.skipNote()).isNull();
    }

    @Test
    void a_platform_without_its_executable_is_skipped_in_one_line() throws Exception {
        JkBuild build = module("");
        BuildLayout layout = BuildLayout.of(tmp, build);
        write(layout.nativeBinary());

        NativeImagePlans.Executables found =
                NativeImagePlans.executables(layout, build, List.of("linux/amd64", "linux/arm64"), HOST, NONE);

        assertThat(found.found()).containsOnlyKeys("linux/amd64");
        assertThat(found.skipped()).containsExactly("linux/arm64");
        assertThat(found.skipNote())
                .isEqualTo("[image] no executable for linux/arm64; built linux/amd64")
                .hasSizeLessThanOrEqualTo(78);
    }

    @Test
    void no_declared_platform_is_this_hosts_and_a_non_linux_host_is_told_to_declare_them() throws Exception {
        JkBuild build = module("");
        BuildLayout layout = BuildLayout.of(tmp, build);
        Path host = write(layout.nativeBinary());

        assertThat(NativeImagePlans.executables(layout, build, List.of(), HOST, NONE)
                        .found())
                .containsExactly(Map.entry("linux/amd64", host));
        assertThatThrownBy(() -> NativeImagePlans.executables(layout, build, List.of(), "macos-aarch64", NONE))
                .hasMessageContaining("set [image] platforms");
        assertThatThrownBy(() -> NativeImagePlans.executables(layout, build, List.of("windows/amd64"), HOST, NONE))
                .hasMessageContaining("linux executables");
    }

    @Test
    void another_hosts_staged_linux_executable_serves_a_mac_host() throws Exception {
        JkBuild build = module("");
        BuildLayout layout = BuildLayout.of(tmp, build);
        write(layout.nativeBinary());
        Path amd = write(staged(layout, build, "linux-x86_64"));

        assertThat(NativeImagePlans.executables(layout, build, List.of("linux/amd64"), "macos-aarch64", NONE)
                        .found())
                .containsExactly(Map.entry("linux/amd64", amd));
        assertThat(NativeImagePlans.needsHostExecutable(List.of("linux/amd64"), "macos-aarch64"))
                .isFalse();
        assertThat(NativeImagePlans.needsHostExecutable(List.of("linux/amd64"), HOST))
                .isTrue();
        assertThat(NativeImagePlans.needsHostExecutable(List.of(), HOST)).isTrue();
    }

    @Test
    void a_snapshot_build_takes_the_executable_named_for_the_release() throws Exception {
        JkBuild build = module("name = \"hello\"\n");
        BuildLayout layout = BuildLayout.of(tmp, build);
        Path released = write(layout.artifactDir().resolve("hello-1.0.0-linux-aarch64"));

        assertThat(NativeImagePlans.executables(layout, build, List.of("linux/arm64"), HOST, NONE)
                        .found())
                .containsExactly(Map.entry("linux/arm64", released));
    }

    @Test
    void a_platform_missing_from_target_is_fetched_from_the_release_staging_area() throws Exception {
        JkBuild build = module("name = \"hello\"\n");
        BuildLayout layout = BuildLayout.of(tmp, build);
        Path host = write(layout.nativeBinary());
        try (FakeReleaseBucket fake = new FakeReleaseBucket()) {
            ReleaseBucket store = fake.bucket("cli/", "1.0.0");
            store.put("hello-1.0.0-linux-aarch64", "arm ELF".getBytes(StandardCharsets.UTF_8));
            List<String> asked = new CopyOnWriteArrayList<>();
            NativeImagePlans.Staging staging = (name, file) -> {
                asked.add(name);
                return store.fetch(name, file).isPresent();
            };

            NativeImagePlans.Executables found = NativeImagePlans.executables(
                    layout, build, List.of("linux/amd64", "linux/arm64", "linux/s390x"), HOST, staging);

            Path fetched = layout.artifactDir().resolve("staged/hello-1.0.0-linux-aarch64");
            assertThat(found.found())
                    .containsExactly(Map.entry("linux/amd64", host), Map.entry("linux/arm64", fetched));
            assertThat(Files.readString(fetched)).isEqualTo("arm ELF");
            assertThat(found.skipped()).as("neither in target/ nor staged").containsExactly("linux/s390x");
            assertThat(asked).as("this host's executable is never fetched").noneMatch(n -> n.endsWith("x86_64"));
        }
    }

    @Test
    void only_a_final_run_of_the_release_lane_reads_the_staging_area() {
        assertThat(NativeImagePlans.staging(tmp, publish(true, false, PublishLane.RELEASE, PublishLane.IMAGE)))
                .as("a stage run")
                .isSameAs(NONE);
        assertThat(NativeImagePlans.staging(tmp, publish(false, true, PublishLane.RELEASE, PublishLane.IMAGE)))
                .as("a dry run")
                .isSameAs(NONE);
        assertThat(NativeImagePlans.staging(tmp, publish(false, false, PublishLane.IMAGE)))
                .as("--only image")
                .isSameAs(NONE);
        assertThat(NativeImagePlans.staging(tmp, publish(false, false, PublishLane.RELEASE, PublishLane.IMAGE)))
                .isNotSameAs(NONE);
    }

    @Test
    void a_workspace_without_a_release_has_nothing_staged() throws Exception {
        module("");
        NativeImagePlans.Staging staging =
                NativeImagePlans.staging(tmp, publish(false, false, PublishLane.RELEASE, PublishLane.IMAGE));

        assertThat(staging.fetch("cli-1.0.0-linux-aarch64", tmp.resolve("staged/x")))
                .isFalse();
    }

    @Test
    void a_daemon_takes_this_hosts_platform_alone() {
        NativeImagePlans.Executables both = new NativeImagePlans.Executables(
                Map.of("linux/arm64", tmp.resolve("a"), "linux/amd64", tmp.resolve("b")), List.of());

        assertThat(both.forDaemon("linux/amd64").found()).containsOnlyKeys("linux/amd64");
    }

    @Test
    void the_base_carries_glibc_unless_the_executable_is_static() throws Exception {
        assertThat(NativeImagePlans.defaultBase(module(""))).isEqualTo(NativeImagePlans.DYNAMIC_BASE);
        assertThat(NativeImagePlans.defaultBase(module("args = [\"--static-nolibc\"]\n")))
                .isEqualTo(NativeImagePlans.DYNAMIC_BASE);
        assertThat(NativeImagePlans.defaultBase(module("args = [\"--static\", \"--libc=musl\"]\n")))
                .isEqualTo(NativeImagePlans.STATIC_BASE);
    }

    @Test
    void the_config_runs_as_the_nonroot_user_on_the_default_base_unless_the_table_says_otherwise() throws Exception {
        JkBuild build = module("");
        ImageConfig config =
                NativeImagePlans.config(ImageTable.EMPTY, build, List.of("linux/amd64", "linux/arm64"), null, "docker");

        assertThat(config.base()).isEqualTo(NativeImagePlans.DYNAMIC_BASE);
        assertThat(config.user()).isEqualTo("65532");
        assertThat(config.platforms()).containsExactly("linux/amd64", "linux/arm64");
        assertThat(config.aotCache()).isFalse();
    }

    @Test
    void a_module_without_a_native_table_or_with_a_jvm_only_key_is_refused() throws Exception {
        JkBuild jvm = JkBuildParser.parse(Files.writeString(
                Files.createDirectories(tmp.resolve("jvm")).resolve("jk.toml"),
                "group = \"ex\"\nname = \"jvm\"\nversion = \"1.0.0\"\n\n[image]\nkind = \"native\"\n"));
        assertThat(NativeImagePlans.refusal(jvm.image(), jvm))
                .isEqualTo("[image] kind = \"native\": jvm has no [native] table");
        JkBuild build = module("");
        ImageTable aot = new ImageTable(
                null,
                null,
                null,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                null,
                null,
                List.of(),
                null,
                null,
                null,
                true,
                ImageTable.KIND_NATIVE,
                null,
                null);
        assertThat(NativeImagePlans.refusal(aot, build)).startsWith("[image] aot-cache:");
        assertThat(NativeImagePlans.refusal(build.image(), build)).isNull();
    }

    @Test
    void the_worker_spec_names_each_platforms_executable_and_where_it_lies() throws Exception {
        JkBuild build = module("name = \"hello\"\n");
        BuildLayout layout = BuildLayout.of(tmp, build);
        NativeImagePlans.Executables found =
                new NativeImagePlans.Executables(Map.of("linux/amd64", tmp.resolve("hello")), List.of());
        ImageConfig config = NativeImagePlans.config(ImageTable.EMPTY, build, List.of("linux/amd64"), null, "docker");

        List<String> lines = ImagePlans.specLines(
                build,
                NativeImagePlans.spec(
                        tmp.resolve("cache"),
                        build,
                        layout,
                        config,
                        "base@sha256:00",
                        found,
                        tmp.resolve("i.tar"),
                        false));

        assertThat(String.join("\n", lines))
                .contains("kind", "native")
                .contains("executablePath")
                .contains("/app/hello")
                .as("the spec is JSON: the tab between platform and file is escaped")
                .contains("linux/amd64\\t" + tmp.resolve("hello").toAbsolutePath());
    }

    private JkBuild module(String nativeKeys) throws Exception {
        Path dir = Files.createDirectories(tmp);
        return JkBuildParser.parse(Files.writeString(dir.resolve("jk.toml"), """
                group = "ex"
                name = "cli"
                version = "1.0.0"
                java = 25

                [application]
                main = "ex.Cli"

                [image]
                kind = "native"

                [native]
                %s""".formatted(nativeKeys)));
    }

    private static TailArgs.Publish publish(boolean stage, boolean dryRun, PublishLane... lanes) {
        return new TailArgs.Publish(
                null, false, dryRun, null, null, RepoCredential.ANONYMOUS, false, List.of(lanes), stage, null, false);
    }

    private static Path staged(BuildLayout layout, JkBuild build, String platform) {
        return layout.artifactDir().resolve(ReleaseDeliverables.nativeName(layout, build, platform));
    }

    private static Path write(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        return Files.writeString(file, "ELF");
    }
}
