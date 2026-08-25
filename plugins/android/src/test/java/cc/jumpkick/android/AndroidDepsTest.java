// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.host.Os;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Android view of a module's layout and its AAR dependencies — the plugin's most-called
 * helpers, reached by the manifest step, the resource step, the Robolectric config step and both
 * app packagers.
 *
 * <p>{@link ApkPackagerTest} and {@link AabPackagerTest} drive the asset and native-library merge
 * through the packagers. What is left, and covered here, is the part with no packager above it:
 * which of the two supported source layouts a path resolves in, what a dependency's namespace is,
 * and the per-OS aapt2 extraction — a case whose failure mode is a fetched jar for the wrong
 * platform, which is a message worth having.
 */
class AndroidDepsTest {

    /** jk's simple layout: {@code <module>/res}, {@code <module>/AndroidManifest.xml}. */
    @Test
    void the_simple_layout_at_the_module_root_is_found(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("res"));

        assertThat(AndroidDeps.androidFile(tmp, "res")).isEqualTo(tmp.resolve("res"));
    }

    /** The AGP/traditional home. A project imported from Gradle has its sources here and nowhere else. */
    @Test
    void the_traditional_src_main_layout_is_found_when_the_simple_one_is_absent(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("src/main/res"));

        assertThat(AndroidDeps.androidFile(tmp, "res")).isEqualTo(tmp.resolve("src/main/res"));
    }

    /** Both present is a mixed tree; the module root is jk's convention and wins. */
    @Test
    void the_module_root_wins_when_both_layouts_exist(@TempDir Path tmp) throws Exception {
        Files.createDirectories(tmp.resolve("res"));
        Files.createDirectories(tmp.resolve("src/main/res"));

        assertThat(AndroidDeps.androidFile(tmp, "res")).isEqualTo(tmp.resolve("res"));
    }

    /**
     * Neither exists, and the answer still has to be a path — it goes straight into
     * "an [android] application needs an AndroidManifest.xml at …", so it must name the convention
     * a reader should follow rather than the fallback they did not use.
     */
    @Test
    void a_path_that_exists_in_neither_layout_resolves_to_the_primary_convention(@TempDir Path tmp) {
        assertThat(AndroidDeps.androidFile(tmp, "AndroidManifest.xml")).isEqualTo(tmp.resolve("AndroidManifest.xml"));
    }

    /**
     * Only entries with an exploded container are AARs. A plain jar dependency has none, and
     * treating one as an AAR would look for {@code res/} inside a directory that is not there.
     */
    @Test
    void only_container_entries_are_aars_and_they_keep_classpath_order(@TempDir Path tmp) throws Exception {
        FakePackageIo io = new FakePackageIo(tmp, "app.apk");
        Path first = io.aar("first.aar");
        io.jar("plain.jar");
        Path second = io.aar("second.aar");

        List<AndroidDeps.Aar> aars = AndroidDeps.aars(io.runtimeEntries());

        assertThat(aars).extracting(AndroidDeps.Aar::container).containsExactly(first, second);
        assertThat(aars).extracting(AndroidDeps.Aar::fileName).containsExactly("first.aar", "second.aar");
    }

    /**
     * The dependency's namespace decides where its regenerated non-transitive {@code R} class is
     * written. An AAR without a manifest has none, and a null there means "skip it", not a crash.
     */
    @Test
    void an_aars_namespace_comes_from_its_manifests_package_attribute(@TempDir Path tmp) throws Exception {
        FakePackageIo io = new FakePackageIo(tmp, "app.apk");
        Path withManifest = io.aar("widgets.aar");
        FakePackageIo.write(
                withManifest.resolve("AndroidManifest.xml"),
                "<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\"\n"
                        + "    package=\"com.example.widgets\" />\n");
        Path without = io.aar("bare.aar");

        assertThat(new AndroidDeps.Aar("widgets.aar", withManifest).namespace()).isEqualTo("com.example.widgets");
        assertThat(new AndroidDeps.Aar("bare.aar", without).namespace()).isNull();
    }

    /** An empty {@code res/} is not resources; the merge must not add it as a link input. */
    @Test
    void an_empty_or_absent_res_directory_is_not_resources(@TempDir Path tmp) throws Exception {
        FakePackageIo io = new FakePackageIo(tmp, "app.apk");
        Path empty = io.aar("empty.aar");
        Files.createDirectories(empty.resolve("res"));
        Path none = io.aar("none.aar");
        Path some = io.aar("some.aar");
        FakePackageIo.write(some.resolve("res/values/strings.xml"), "<resources/>");

        assertThat(new AndroidDeps.Aar("empty.aar", empty).hasRes()).isFalse();
        assertThat(new AndroidDeps.Aar("none.aar", none).hasRes()).isFalse();
        assertThat(new AndroidDeps.Aar("some.aar", some).hasRes()).isTrue();
    }

    /**
     * One AAR-vs-AAR rule for the whole module, and it is {@code ResourceMerger}'s: the earlier
     * classpath entry wins. The module's own {@code assets/} still beats every AAR — the collection
     * order carries that, not a last-writer map.
     */
    @Test
    void the_earlier_aars_asset_wins_and_the_modules_own_beats_both(@TempDir Path tmp) throws Exception {
        FakePackageIo io = new FakePackageIo(tmp, "app.apk");
        FakePackageIo.write(io.moduleDir().resolve("assets/dup.txt"), "from-the-module");
        Path first = io.aar("first.aar");
        FakePackageIo.write(first.resolve("assets/dup.txt"), "from-first");
        FakePackageIo.write(first.resolve("assets/aar-dup.txt"), "from-first");
        Path second = io.aar("second.aar");
        FakePackageIo.write(second.resolve("assets/dup.txt"), "from-second");
        FakePackageIo.write(second.resolve("assets/aar-dup.txt"), "from-second");

        Map<String, Path> merged = AndroidDeps.mergedAssets(io);

        assertThat(Files.readString(merged.get("dup.txt")))
                .as("the app beats every AAR")
                .isEqualTo("from-the-module");
        assertThat(Files.readString(merged.get("aar-dup.txt")))
                .as("between AARs, the earlier classpath entry wins")
                .isEqualTo("from-first");
    }

    /** The same rule for {@code jni/<abi>/*.so}: two AARs shipping the same library, earlier wins. */
    @Test
    void the_earlier_aars_native_lib_wins_a_path_conflict(@TempDir Path tmp) throws Exception {
        FakePackageIo io = new FakePackageIo(tmp, "app.apk");
        FakePackageIo.write(io.aar("first.aar").resolve("jni/arm64-v8a/dup.so"), "ELF-first");
        FakePackageIo.write(io.aar("second.aar").resolve("jni/arm64-v8a/dup.so"), "ELF-second");

        Map<String, Path> libs = AndroidDeps.nativeLibs(io);

        assertThat(Files.readString(libs.get("arm64-v8a/dup.so"))).isEqualTo("ELF-first");
    }

    /**
     * aapt2 ships as a per-OS native binary inside a Maven jar, picked by classifier. The wrapper
     * for another platform is a perfectly valid jar, so the only sign of a wrong classifier is the
     * missing entry — extracting the other OS's binary and then failing to run it would be a much
     * worse message.
     */
    @Test
    void the_aapt2_binary_for_this_host_is_the_one_extracted(@TempDir Path tmp) throws Exception {
        Path jar = wrapperJar(tmp.resolve("aapt2.jar"), "aapt2", "aapt2.exe");

        Path binary = AndroidDeps.extractAapt2(jar, tmp.resolve("tools"));

        assertThat(Files.readString(binary)).isEqualTo(Os.isWindows() ? "aapt2.exe" : "aapt2");
        assertThat(Files.isExecutable(binary)).isTrue();
    }

    @Test
    void a_wrapper_jar_for_another_platform_names_the_classifier(@TempDir Path tmp) throws Exception {
        Path jar = wrapperJar(tmp.resolve("aapt2-wrong.jar"), Os.isWindows() ? "aapt2" : "aapt2.exe");

        assertThatThrownBy(() -> AndroidDeps.extractAapt2(jar, tmp.resolve("tools")))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("aapt2-wrong.jar")
                .hasMessageContaining("classifier");
    }

    /** A Maven jar wrapping one binary per named entry; each entry's body is its own name. */
    private static Path wrapperJar(Path jar, String... entries) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            for (String name : entries) {
                zip.putNextEntry(new ZipEntry(name));
                zip.write(name.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return jar;
    }
}
