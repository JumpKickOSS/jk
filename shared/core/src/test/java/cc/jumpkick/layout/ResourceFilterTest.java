// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.layout;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.JkBuild;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

class ResourceFilterTest {

    private static final Map<String, String> VALUES = Map.of("remoting.version", "3391", "app", "web");

    @Test
    void both_default_delimiters_expand() {
        ResourceFilter.Result r = ResourceFilter.expand("v=${remoting.version}\nname=@app@\n", VALUES);
        assertThat(r.text()).isEqualTo("v=3391\nname=web\n");
        assertThat(r.unresolved()).isEmpty();
    }

    @Test
    void an_escaped_reference_stays_literal_without_its_backslash() {
        assertThat(ResourceFilter.expand("keep=\\${remoting.version}", VALUES).text())
                .isEqualTo("keep=${remoting.version}");
    }

    @Test
    void an_unresolved_reference_is_left_as_written_and_only_the_dollar_form_is_reported() {
        ResourceFilter.Result r = ResourceFilter.expand("a=${changelog.url} mail=me@example.com@x", VALUES);
        assertThat(r.text()).isEqualTo("a=${changelog.url} mail=me@example.com@x");
        assertThat(r.unresolved()).containsExactly("changelog.url");
    }

    @Test
    void images_and_bytes_that_are_not_utf8_are_copied_as_they_are() {
        byte[] png = "${remoting.version}".getBytes(StandardCharsets.UTF_8);
        assertThat(ResourceFilter.filter("logo.PNG", png, VALUES, new TreeSet<>()))
                .isSameAs(png);
        byte[] binary = {(byte) 0xff, (byte) 0xfe, '$', '{', 'a', '}'};
        assertThat(ResourceFilter.filter("blob.bin", binary, VALUES, new TreeSet<>()))
                .isSameAs(binary);
        Set<String> unresolved = new TreeSet<>();
        assertThat(new String(
                        ResourceFilter.filter(
                                "a.properties", "x=${nope}".getBytes(StandardCharsets.UTF_8), VALUES, unresolved),
                        StandardCharsets.UTF_8))
                .isEqualTo("x=${nope}");
        assertThat(unresolved).containsExactly("nope");
    }

    @Test
    void the_manifest_s_properties_win_over_what_jk_computes() {
        JkBuild build = JkBuildParser.parse("""
                group = "org.ex"
                name = "core"
                version = "2.0"

                [resources.properties]
                "remoting.version" = "3391"
                "project.version" = "2.0-custom"
                """);
        Map<String, String> values = ResourceFilter.values(build);
        assertThat(values).containsEntry("project.groupId", "org.ex").containsEntry("project.artifactId", "core");
        assertThat(values).containsEntry("project.version", "2.0-custom").containsEntry("remoting.version", "3391");
    }

    @Test
    void references_names_both_forms_and_skips_escaped_ones() {
        assertThat(ResourceFilter.references("${a} @b@ \\${c}")).containsExactly("a", "b");
    }
}
