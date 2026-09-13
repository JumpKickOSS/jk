// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TrustedSourcesTest {

    @Test
    void add_list_remove_round_trip(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        assertThat(t.list()).isEmpty();
        assertThat(t.add("https://github.com/acme/")).isTrue();
        assertThat(t.add("https://github.com/acme/")).isFalse(); // dedup

        TrustedSources reloaded = TrustedSources.load(state);
        assertThat(reloaded.list()).containsExactly("https://github.com/acme/");
        assertThat(reloaded.remove("https://github.com/acme/")).isTrue();
        assertThat(TrustedSources.load(state).list()).isEmpty();
    }

    @Test
    void prefix_matching_is_jbang_style(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        t.add("https://github.com/acme/");
        assertThat(t.isTrusted("https://github.com/acme/widgets/blob/main/x.java"))
                .isTrue();
        assertThat(t.isTrusted("HTTPS://GITHUB.COM/acme/x.java")).isTrue(); // scheme+host case-folded
        assertThat(t.isTrusted("https://github.com/other/x.java")).isFalse();
        assertThat(t.isTrusted("https://github.com/ACME/x.java")).isFalse(); // path stays case-sensitive
        assertThat(t.isTrusted("https://example.com/acme/x.java")).isFalse();
    }

    @Test
    void suggested_prefix_is_host_plus_first_segment() {
        assertThat(TrustedSources.suggestedPrefix("https://github.com/acme/widgets/blob/main/x.java"))
                .isEqualTo("https://github.com/acme/");
        assertThat(TrustedSources.suggestedPrefix("http://127.0.0.1:8080/scripts/x.java"))
                .isEqualTo("http://127.0.0.1:8080/scripts/");
        assertThat(TrustedSources.suggestedPrefix("https://example.com/")).isEqualTo("https://example.com/");
    }

    @Test
    void parses_jbang_trusted_sources_json_with_comments() {
        String json = """
                [
                  // added 2024-01-01
                  "https://github.com/jbangdev/",
                  "https://gist.github.com/maxandersen/"
                ]
                """;
        assertThat(TrustedSources.parseJBang(json))
                .containsExactly("https://github.com/jbangdev/", "https://gist.github.com/maxandersen/");
    }

    @Test
    void hand_edited_toml_loads(@TempDir Path state) throws Exception {
        Files.writeString(state.resolve("trusted-sources.toml"), """
                sources = [
                  "https://acme.dev/",
                ]
                """);
        assertThat(TrustedSources.load(state).isTrusted("https://acme.dev/tool.jar"))
                .isTrue();
    }

    @Test
    void single_line_array_and_ipv6_brackets_load(@TempDir Path state) throws Exception {
        Files.writeString(state.resolve("trusted-sources.toml"), """
                sources = ["https://[::1]:8443/tools/", "https://acme.dev/"]
                """);
        var trusted = TrustedSources.load(state);
        assertThat(trusted.list()).containsExactly("https://[::1]:8443/tools/", "https://acme.dev/");
        assertThat(trusted.isTrusted("https://acme.dev/x.jar")).isTrue();
    }

    @Test
    void a_host_prefix_covers_that_host_only(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        assertThat(t.add("https://GitHub.com")).isTrue();
        assertThat(t.list()).as("stored with the / an empty path means").containsExactly("https://github.com/");
        assertThat(t.isTrusted("https://github.com/acme/x.java")).isTrue();
        assertThat(t.isTrusted("https://github.com")).isTrue();
        assertThat(t.isTrusted("https://github.com.evil.example/x.java")).isFalse();
        assertThat(t.isTrusted("https://github.community/x.java")).isFalse();
        assertThat(t.isTrusted("https://evil.example/github.com/x.java")).isFalse();
    }

    @Test
    void a_path_prefix_stops_at_a_segment_boundary(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        t.add("https://github.com/acme");
        assertThat(t.isTrusted("https://github.com/acme")).isTrue();
        assertThat(t.isTrusted("https://github.com/acme/widgets/blob/main/x.java"))
                .isTrue();
        assertThat(t.isTrusted("https://github.com/acme-evil/x.java")).isFalse();
        assertThat(t.isTrusted("https://github.com/acmeX/x.java")).isFalse();
        assertThat(t.isTrusted("https://github.com/acme.evil/x.java")).isFalse();
    }

    @Test
    void a_file_prefix_covers_exactly_that_file(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        t.add("https://acme.dev/tool.jar");
        assertThat(t.isTrusted("https://acme.dev/tool.jar")).isTrue();
        assertThat(t.isTrusted("https://acme.dev/tool.jar?download=1")).isTrue();
        assertThat(t.isTrusted("https://acme.dev/tool.jar.evil")).isFalse();
        assertThat(t.isTrusted("https://acme.dev/other.jar")).isFalse();
    }

    @Test
    void dot_segments_encoded_slashes_and_empty_segments_are_never_trusted(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        t.add("https://github.com/acme/");
        // The forge rewriter accepts .. as a repo segment and the raw origin may resolve it to
        // another account; the prefix never covers what it cannot see.
        assertThat(t.isTrusted("https://github.com/acme/../blob/EVIL/REPO/main/x.java"))
                .isFalse();
        assertThat(t.isTrusted("https://github.com/acme/./x.java")).isFalse();
        assertThat(t.isTrusted("https://github.com/acme/%2e%2e/x.java")).isFalse();
        assertThat(t.isTrusted("https://github.com/acme%2F../x.java")).isFalse();
        assertThat(t.isTrusted("https://github.com/acme/%2Fevil/x.java")).isFalse();
        assertThat(t.isTrusted("https://github.com//acme/x.java")).isFalse();
        assertThat(t.isTrusted("https://github.com/acme//x.java")).isFalse();
        assertThat(t.isTrusted("https://github.com/acme/widgets/x.java")).isTrue();
    }

    @Test
    void credentials_and_default_ports_do_not_widen_a_prefix(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        t.add("https://github.com/acme/");
        assertThat(t.isTrusted("https://github.com@evil.example/acme/x.java")).isFalse();
        assertThat(t.isTrusted("https://user:pw@github.com/acme/x.java")).isFalse();
        assertThat(t.isTrusted("https://github.com:443/acme/x.java")).isTrue();
        assertThat(t.isTrusted("https://github.com:8443/acme/x.java")).isFalse();
        assertThat(t.isTrusted("http://github.com/acme/x.java")).isFalse();
    }

    @Test
    void a_prefix_needs_a_scheme_a_host_and_a_plain_path(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        for (String bad : List.of(
                "github.com/acme/",
                "https:///acme/",
                "https://github.com/acme/../",
                "https://github.com//acme/",
                "https://me@github.com/acme/",
                "https://github.com/acme%2Fx/",
                "not a url")) {
            assertThatThrownBy(() -> t.add(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(TrustedSources.load(state).list()).isEmpty();
    }

    @Test
    void a_file_url_prefix_covers_local_paths_below_it(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        assertThat(t.add("file:///work/tools/")).isTrue();
        assertThat(t.list()).containsExactly("file:///work/tools/");

        assertThat(t.isTrusted("file:///work/tools/repo")).isTrue();
        assertThat(t.isTrusted("file:///work/tools/mono/greeter")).isTrue();
        assertThat(t.isTrusted("FILE:///work/tools/repo")).isTrue();
        assertThat(t.isTrusted("file:///work/tools-evil/repo")).isFalse();
        assertThat(t.isTrusted("file:///work/")).isFalse();
        assertThat(t.isTrusted("file:///work/tools/../secrets/x")).isFalse();
    }

    @Test
    void a_file_url_with_a_host_or_a_hostless_remote_url_is_refused(@TempDir Path state) throws Exception {
        TrustedSources t = TrustedSources.load(state);
        for (String bad : List.of("file://evil.example/work/", "https:///acme/", "http:///", "file:relative/")) {
            assertThatThrownBy(() -> t.add(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(t.list()).isEmpty();
    }

    @Test
    void the_suggested_prefix_for_a_file_url_is_its_directory() {
        assertThat(TrustedSources.suggestedPrefix("file:///work/tools/repo")).isEqualTo("file:///work/tools/");
        assertThat(TrustedSources.suggestedPrefix("file:///work/tools/repo/")).isEqualTo("file:///work/tools/");
        assertThat(TrustedSources.suggestedPrefix("file:///repo")).isEqualTo("file:///");
    }

    @Test
    void a_hand_edited_prefix_that_is_not_a_url_trusts_nothing(@TempDir Path state) throws Exception {
        Files.writeString(state.resolve("trusted-sources.toml"), """
                sources = [
                  "github.com/",
                  "https://acme.dev/",
                ]
                """);
        TrustedSources t = TrustedSources.load(state);
        assertThat(t.isTrusted("https://acme.dev/x.jar")).isTrue();
        assertThat(t.isTrusted("github.com/x.jar")).isFalse();
        assertThat(t.isTrusted("https://github.com/x.jar")).isFalse();
        assertThat(t.remove("github.com/"))
                .as("the stray entry can still be removed")
                .isTrue();
        assertThat(TrustedSources.load(state).list()).containsExactly("https://acme.dev/");
    }
}
