// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.ReleasePlatform;
import cc.jumpkick.model.JkBuild;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** What a module puts in a release, named as the release names it, neutral or for one platform. */
class ReleaseDeliverablesTest {

    @TempDir
    Path tmp;

    private JkBuild build(String toml) throws Exception {
        return JkBuildParser.parse(Files.writeString(
                tmp.resolve("jk.toml"), "name = \"app\"\ngroup = \"com.example\"\nversion = \"1.4.7\"\n" + toml));
    }

    @Test
    void a_library_puts_nothing_in_a_release() throws Exception {
        JkBuild lib = build("");
        assertThat(ReleaseDeliverables.local(BuildLayout.of(tmp, lib), lib)).isEmpty();
        assertThat(ReleaseDeliverables.platformSpecific(lib)).isFalse();
    }

    @Test
    void the_fat_jar_and_the_lib_dir_archive_are_neutral() throws Exception {
        JkBuild app = build("""
                [application]
                main = "com.example.Main"
                assembly = true
                lib-dir = true
                """);
        BuildLayout layout = BuildLayout.of(tmp, app);
        String v = layout.version();
        var files = ReleaseDeliverables.local(layout, app);
        assertThat(files)
                .extracting(ReleaseDeliverables.Deliverable::name)
                .containsExactly("app-" + v + "-all.jar", "app-" + v + ".zip");
        assertThat(files).allMatch(ReleaseDeliverables.Deliverable::neutral);
        assertThat(files.getFirst().file()).isEqualTo(tmp.resolve("target/app-" + v + "-all.jar"));
        assertThat(files.getFirst().module()).isEqualTo("app");
    }

    @Test
    void the_runtime_image_and_the_native_executable_are_named_for_their_platform() throws Exception {
        JkBuild app = build("""
                [application]
                main = "com.example.Main"

                [jlink]

                [native]
                enabled = "always"
                name = "appctl"
                """);
        BuildLayout layout = BuildLayout.of(tmp, app);
        String host = ReleasePlatform.current();
        String v = layout.version();
        var files = ReleaseDeliverables.local(layout, app);
        assertThat(files).allMatch(d -> host.equals(d.platform()));
        assertThat(files)
                .extracting(ReleaseDeliverables.Deliverable::file)
                .containsExactly(layout.jlinkArchive(), layout.nativeBinary());
        assertThat(ReleaseDeliverables.platformNames(layout, app, "windows-x86_64"))
                .containsExactly("app-" + v + "-windows-x86_64.zip", "appctl-" + v + "-windows-x86_64.exe");
        assertThat(ReleaseDeliverables.platformNames(layout, app, "macos-aarch64"))
                .containsExactly("app-" + v + "-macos-aarch64.tar.gz", "appctl-" + v + "-macos-aarch64");
        assertThat(ReleaseDeliverables.platformSpecific(app)).isTrue();
    }

    @Test
    void a_native_image_built_only_by_jk_native_is_no_release_file() throws Exception {
        JkBuild app = build("""
                [application]
                main = "com.example.Main"

                [native]
                """);
        assertThat(ReleaseDeliverables.local(BuildLayout.of(tmp, app), app)).isEmpty();
    }
}
