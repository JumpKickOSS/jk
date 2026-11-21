// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.testing.FakeBuildIo;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * The AAR resources compile in {@code android-res-deps}, and {@code android-res} links what it
 * compiled. The stub {@code aapt2} ({@link TestConfigStepTest#fakeAapt2}) logs every {@code compile
 * --dir} to {@code aapt2-compile.txt}; it is a shell script, so the class skips Windows.
 */
@DisabledOnOs(OS.WINDOWS)
class ResourceStepTest {

    /** The closure's AAR resources merge into one tree, compiled once; the merged dirs are recorded. */
    @Test
    void the_dependency_step_compiles_the_closures_aar_resources_once(@TempDir Path tmp) throws Exception {
        FakeBuildIo deps = AndroidIo.step(Files.createDirectories(tmp.resolve("deps")))
                .extra("aapt2", TestConfigStepTest.fakeAapt2(tmp));
        Path core = aarWithString(deps, "androidx-core", "core");
        Path appcompat = aarWithString(deps, "androidx-appcompat", "appcompat");
        deps.container("no-res");

        ResourceStep.compileDeps(deps);

        Path out = deps.scratch().resolve(ResourceStep.DEPS_OUT);
        assertThat(out.resolve("deps-compiled.zip")).exists();
        assertThat(Files.readAllLines(out.resolve("deps-res-dirs.txt")))
                .containsExactly(
                        core.resolve("res").toAbsolutePath().toString(),
                        appcompat.resolve("res").toAbsolutePath().toString());
        assertThat(Files.readAllLines(tmp.resolve("aapt2-compile.txt")))
                .as("one compile, of the merged tree")
                .hasSize(1);
    }

    /** No AAR carries resources: no aapt2 run and no archive, only the empty record. */
    @Test
    void a_closure_without_aar_resources_compiles_nothing(@TempDir Path tmp) throws Exception {
        FakeBuildIo deps = AndroidIo.step(Files.createDirectories(tmp.resolve("deps")))
                .extra("aapt2", TestConfigStepTest.fakeAapt2(tmp));
        deps.container("no-res");

        ResourceStep.compileDeps(deps);

        Path out = deps.scratch().resolve(ResourceStep.DEPS_OUT);
        assertThat(out.resolve("deps-compiled.zip")).doesNotExist();
        assertThat(out.resolve("deps-res-dirs.txt")).isEmptyFile();
        assertThat(tmp.resolve("aapt2-compile.txt")).doesNotExist();
    }

    /**
     * {@code android-res} compiles the module's own resources alone and links the archive {@code
     * android-res-deps} left, so an edit that reruns it leaves the dependency resources compiled.
     */
    @Test
    void android_res_links_the_dependency_archive_and_compiles_only_the_modules_resources(@TempDir Path tmp)
            throws Exception {
        Path aapt2 = TestConfigStepTest.fakeAapt2(tmp);
        FakeBuildIo deps =
                AndroidIo.step(Files.createDirectories(tmp.resolve("deps"))).extra("aapt2", aapt2);
        FakeBuildIo res = AndroidIo.step(Files.createDirectories(tmp.resolve("res")))
                .extra("aapt2", aapt2)
                .extra("android-jar", Files.writeString(tmp.resolve("android.jar"), ""));
        aarWithString(deps, "androidx-core", "core");
        res.entry(deps.runtimeEntries().getFirst());
        ResourceStep.compileDeps(deps);
        Files.delete(tmp.resolve("aapt2-compile.txt"));
        res.step(ResourceStep.DEPS_STEP, deps.scratch());
        Files.writeString(
                Files.createDirectories(res.step("android-manifest").resolve("merged"))
                        .resolve("AndroidManifest.xml"),
                "<manifest/>\n");
        Path ownRes = Files.createDirectories(res.moduleDir().resolve("res/values"));
        Files.writeString(ownRes.resolve("strings.xml"), "<resources><string name=\"app\">x</string></resources>");

        ResourceStep.run(res);

        assertThat(Files.readAllLines(tmp.resolve("aapt2-compile.txt")))
                .containsExactly(res.moduleDir().resolve("res").toAbsolutePath().toString());
        assertThat(Files.readAllLines(tmp.resolve("aapt2-link.txt")))
                .contains(deps.scratch()
                        .resolve(ResourceStep.DEPS_OUT)
                        .resolve("deps-compiled.zip")
                        .toAbsolutePath()
                        .toString());
    }

    /** An AAR on {@code io}'s closure whose {@code res/values} defines one string. */
    private static Path aarWithString(FakeBuildIo io, String name, String string) throws Exception {
        Path aar = io.container(name);
        Files.writeString(
                Files.createDirectories(aar.resolve("res/values")).resolve("values.xml"),
                "<resources><string name=\"" + string + "\">v</string></resources>");
        return aar;
    }
}
