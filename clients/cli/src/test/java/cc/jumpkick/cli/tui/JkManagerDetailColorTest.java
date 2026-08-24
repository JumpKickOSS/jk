// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.cli.tui;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.TestAnsi;
import cc.jumpkick.cli.theme.Coords;
import cc.jumpkick.cli.theme.Theme;
import org.junit.jupiter.api.Test;

/** Detail-segment classification and coloring ({@code JkManager.colorDetail} and friends). */
class JkManagerDetailColorTest {

    @Test
    void detailForDisplay_strips_module_prefix() {
        assertThat(JkManager.detailForDisplay("g:a", "g:a :: FooTest.t()")).isEqualTo("FooTest.t()");
        assertThat(JkManager.detailForDisplay("g:a", "shrinking jar")).isEqualTo("shrinking jar");
        assertThat(JkManager.detailForDisplay("g:a", "")).isEmpty();
    }

    @Test
    void looksLikeJavaMember_detects_class_method_form() {
        assertThat(JkManager.looksLikeJavaMember("FooTest.bar(Path)")).isTrue();
        assertThat(JkManager.looksLikeJavaMember("FooTest")).isTrue();
        assertThat(JkManager.looksLikeJavaMember("shrinking jar")).isFalse();
    }

    @Test
    void colorDetail_strips_fqcns_from_java_member_labels() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Test", "cc.jumpkick.runtime.FooTest.bar(java.nio.file.Path)", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("FooTest.bar(Path)");
        assertThat(TestAnsi.strip(painted)).doesNotContain("java.nio");
        assertThat(painted).contains(Theme.colorize("Path", t.synType()));
    }

    @Test
    void prose_detail_defaults_to_mid_gray_not_dim_or_cyan() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Package", "shrinking jar", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("shrinking jar");
        // Body tokens are mid-gray (#A0A0A0) — not dim bright-black, not cyan.
        assertThat(painted).contains(Theme.colorize("shrinking", t.midGray()));
        assertThat(painted).doesNotContain(Theme.colorize("shrinking", t.darkGray()));
        assertThat(painted).doesNotContain(Theme.colorize("shrinking", t.activeStep()));
        assertThat(painted).doesNotContain(Theme.colorize("shrinking", t.brightCyan()));
    }

    @Test
    void compile_test_under_test_phase_is_prose_mid_gray_not_syntax_white() {
        // compile-test is "test" wire-wise, but labels are "compiling N sources" — not FooTest.bar.
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Test", "compiling 12 Groovy test sources", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("compiling 12 Groovy test sources");
        assertThat(painted).contains(Theme.colorize("compiling", t.midGray()));
        assertThat(painted).contains(Theme.colorize("12", t.warning()));
        assertThat(painted).contains(Theme.colorize("Groovy", t.midGray()));
        // Must not route through SyntaxHighlight (PLAIN = terminal default/white).
        assertThat(painted).doesNotContain("compiling 12 Groovy test sources"); // unstyled whole string
    }

    @Test
    void package_detail_uses_path_color_for_jar_name() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Package", "package jk-engine-0.12.0.jar", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("package jk-engine-0.12.0.jar");
        assertThat(painted).contains(Theme.colorize("package", t.midGray()));
        assertThat(painted).contains(Theme.colorize("jk-engine-0.12.0.jar", t.path()));
    }

    @Test
    void compile_detail_uses_yellow_for_source_count() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Compile", "compiling 42 sources", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("compiling 42 sources");
        assertThat(painted).contains(Theme.colorize("compiling", t.midGray()));
        assertThat(painted).contains(Theme.colorize("42", t.warning()));
        assertThat(painted).contains(Theme.colorize("sources", t.midGray()));
    }

    @Test
    void size_uses_yellow_number_and_gray_unit() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Package", "shrunk 4.2 MiB → 1.1 MiB", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("shrunk 4.2 MiB → 1.1 MiB");
        assertThat(painted).contains(Theme.colorize("4.2", t.warning()));
        assertThat(painted).contains(Theme.colorize("1.1", t.warning()));
        assertThat(painted).contains(Theme.colorize("MiB", t.midGray()));
    }

    @Test
    void resolve_detail_colors_maven_coords() {
        String painted = JkManager.colorDetail(
                "Resolve", "fetched com.fasterxml.jackson.core:jackson-core:2.18.0", Theme.active());
        assertThat(TestAnsi.strip(painted)).isEqualTo("fetched com.fasterxml.jackson.core:jackson-core:2.18.0");
        // Coords.gav splits group / artifact / version with their theme roles.
        assertThat(painted).contains(Coords.gav("com.fasterxml.jackson.core", "jackson-core", "2.18.0"));
    }

    @Test
    void jdk_download_detail_is_cyan_name_blue_bar_gray_percent() {
        Theme t = Theme.active();
        String detail = "downloading Temurin 25 ▰▰▰▰▰▱▱▱▱▱ 50%";
        String painted = JkManager.colorDetail("Resolve", detail, t);
        assertThat(TestAnsi.strip(painted)).isEqualTo(detail);
        assertThat(painted).contains(Theme.colorize("downloading", t.midGray()));
        assertThat(painted).contains(Theme.colorize("Temurin 25", t.cyan()));
        assertThat(painted).contains(Theme.colorize("▰", t.blue()));
        assertThat(painted).contains(Theme.colorize("▱", t.darkGray()));
        assertThat(painted).contains(Theme.colorize("50%", t.midGray()));
        assertThat(painted).doesNotContain(Theme.colorize("50", t.warning()));
    }

    @Test
    void jdk_install_detail_swaps_the_verb() {
        Theme t = Theme.active();
        String detail = "installing Temurin 25 ▰▰▰▰▰▰▰▰▰▰ 100%";
        String painted = JkManager.colorDetail("Resolve", detail, t);
        assertThat(TestAnsi.strip(painted)).isEqualTo(detail);
        assertThat(painted).contains(Theme.colorize("installing", t.midGray()));
        assertThat(painted).contains(Theme.colorize("Temurin 25", t.cyan()));
        assertThat(painted).contains(Theme.colorize("100%", t.midGray()));
    }

    @Test
    void fetch_detail_colors_library_short_name() {
        String painted = JkManager.colorDetail("Resolve", "fetched jackson-core", Theme.active());
        assertThat(TestAnsi.strip(painted)).isEqualTo("fetched jackson-core");
        assertThat(painted).contains(Coords.shortName("jackson-core"));
    }

    @Test
    void cache_hit_hex_is_dim_not_number_yellow() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Compile", "cache hit 9aa55003", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("cache hit 9aa55003");
        // Slightly dimmer than mid-gray body prose, still not number-yellow.
        assertThat(painted).contains(Theme.colorize("9aa55003", t.darkGray()));
        assertThat(painted).doesNotContain(Theme.colorize("9aa55003", t.warning()));
    }

    @Test
    void paren_count_still_yellows_the_number() {
        Theme t = Theme.active();
        String painted = JkManager.colorDetail("Compile", "d8 (12 classes + 3 jars)", t);
        assertThat(TestAnsi.strip(painted)).isEqualTo("d8 (12 classes + 3 jars)");
        assertThat(painted).contains(Theme.colorize("12", t.warning()));
        assertThat(painted).contains(Theme.colorize("3", t.warning()));
    }

    @Test
    void looksLikePathOrArtifact_detects_jars_and_paths() {
        assertThat(JkManager.looksLikePathOrArtifact("lib.jar")).isTrue();
        assertThat(JkManager.looksLikePathOrArtifact("app.aar")).isTrue();
        assertThat(JkManager.looksLikePathOrArtifact("target/classes")).isTrue();
        assertThat(JkManager.looksLikePathOrArtifact("sources")).isFalse();
        assertThat(JkManager.looksLikePathOrArtifact("up-to-date")).isFalse();
    }

    @Test
    void native_classpath_size_detail_uses_path_and_bold_white() {
        Theme t = Theme.active();
        String detail = "jk-cli · classpath input size: ~3.8 MiB";
        String painted = JkManager.colorDetail("Native", detail, t);
        assertThat(TestAnsi.strip(painted)).isEqualTo(detail);
        // Filename: Theme.path (periwinkle #969DD4) — same as other file/path designations.
        assertThat(painted).contains(Theme.colorize("jk-cli", t.path()));
        // Size number: focused = bold + bright white (not count-yellow).
        assertThat(painted).contains(Theme.colorize("~3.8", t.focused()));
        assertThat(painted).doesNotContain(Theme.colorize("~3.8", t.warning()));
        assertThat(painted).contains(Theme.colorize(" MiB", t.midGray()));
        assertThat(painted).contains(Theme.colorize(" · classpath input size: ", t.midGray()));
    }

    @Test
    void native_classpath_size_detail_win_exe_basename() {
        Theme t = Theme.active();
        String detail = "cli.exe · classpath input size: ~1.2 MiB";
        String painted = JkManager.colorDetail("Native", detail, t);
        assertThat(TestAnsi.strip(painted)).isEqualTo(detail);
        assertThat(painted).contains(Theme.colorize("cli.exe", t.path()));
        assertThat(painted).contains(Theme.colorize("~1.2", t.focused()));
    }

    @Test
    void looksLikeCoord_detects_gav() {
        assertThat(JkManager.looksLikeCoord("com.foo:bar:1.0")).isTrue();
        assertThat(JkManager.looksLikeCoord("com.foo:bar")).isTrue();
        assertThat(JkManager.looksLikeCoord("com.foo:bar:1.0!aar")).isTrue(); // packaging type after GAV
        assertThat(JkManager.looksLikeCoord("com.foo:bar@1.2")).isTrue(); // version selector
        assertThat(JkManager.looksLikeCoord("com.foo:bar:jar:")).isFalse(); // empty classifier segment
        assertThat(JkManager.looksLikeCoord("lib.jar")).isFalse();
        assertThat(JkManager.looksLikeCoord("compiling")).isFalse();
    }

    @Test
    void resolve_detail_colors_display_coord_not_package_key() {
        String painted = JkManager.colorDetail(
                "Resolve", "fetched org.jetbrains.kotlin:kotlin-build-tools-api:2.1.10", Theme.active());
        assertThat(TestAnsi.strip(painted)).isEqualTo("fetched org.jetbrains.kotlin:kotlin-build-tools-api:2.1.10");
        assertThat(painted).contains(Coords.gav("org.jetbrains.kotlin", "kotlin-build-tools-api", "2.1.10"));
    }
}
