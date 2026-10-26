// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.PomMetadata;
import cc.jumpkick.publish.PublishablePom;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** {@code [publish]} — the POM metadata a release carries, and how a workspace shares it. */
class ManifestPublishTest {

    private static final String TABLE = """
            [publish]
            url = "https://example.com/widget"
            licenses = [{ name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0" }]
            developers = [{ id = "ada", name = "Ada", email = "ada@example.com" }]
            scm = { url = "https://github.com/example/widget", connection = "scm:git:https://github.com/example/widget.git", developer-connection = "scm:git:ssh://git@github.com/example/widget.git" }
            """;

    @TempDir
    Path tmp;

    private JkBuild parse(String toml) throws Exception {
        Path f = tmp.resolve("jk.toml");
        Files.writeString(
                f,
                "name = \"widget\"\ngroup = \"com.example\"\nversion = \"1.0\"\ndescription = \"A widget\"\n" + toml);
        return JkBuildParser.parse(f);
    }

    @Test
    void an_ordinary_module_declares_nothing_and_its_pom_carries_no_name() throws Exception {
        JkBuild b = parse("");
        assertThat(b.publish()).isNull();
        assertThat(b.pomMetadata().isEmpty()).isTrue();
        assertThat(PublishablePom.render(b).xml()).doesNotContain("<name>");
    }

    @Test
    void the_table_names_where_a_release_goes() throws Exception {
        PomMetadata m = parse("""
                [publish]
                repository = "releases"
                sign = true
                key-file = "~/.gnupg/release.asc"
                """).pomMetadata();

        assertThat(m.repository()).isEqualTo("releases");
        assertThat(m.sign()).isTrue();
        assertThat(m.keyFile()).isEqualTo("~/.gnupg/release.asc");
    }

    @Test
    void the_release_tag_defaults_and_can_be_templated() throws Exception {
        parse("");
        assertThat(JkBuildParser.releaseTable(tmp.resolve("jk.toml")).tag()).isEqualTo("v{version}");

        parse("""
                [release]
                tag = "widget-{version}"
                """);
        assertThat(JkBuildParser.releaseTable(tmp.resolve("jk.toml")).tag()).isEqualTo("widget-{version}");
    }

    @Test
    void the_release_files_are_read_with_their_patterns() throws Exception {
        parse("""
                [release]
                files = [{ path = "install.sh", pattern = 'FLOOR="([^"]+)"' }]
                """);

        assertThat(JkBuildParser.releaseTable(tmp.resolve("jk.toml")).files())
                .containsExactly(Map.entry("install.sh", "FLOOR=\"([^\"]+)\""));
    }

    @Test
    void a_release_file_pattern_needs_exactly_one_group() {
        assertThatThrownBy(() -> {
                    parse("""
                            [release]
                            files = [{ path = "install.sh", pattern = 'FLOOR=.+' }]
                            """);
                    JkBuildParser.releaseTable(tmp.resolve("jk.toml"));
                })
                .hasMessageContaining("exactly one group");
    }

    @Test
    void a_release_tag_without_the_version_is_refused() {
        assertThatThrownBy(() -> {
                    parse("""
                            [release]
                            tag = "latest"
                            """);
                    JkBuildParser.releaseTable(tmp.resolve("jk.toml")).tag();
                })
                .hasMessageContaining("must contain {version}");
    }

    @Test
    void a_secret_in_the_table_is_refused_with_where_it_belongs() {
        assertThatThrownBy(() -> parse("""
                [publish]
                password = "hunter2"
                """))
                .hasMessageContaining("takes no secrets")
                .hasMessageContaining("jk repo login");
    }

    @Test
    void the_table_is_read_and_rendered_into_the_pom() throws Exception {
        JkBuild b = parse(TABLE);
        PomMetadata m = b.pomMetadata();
        assertThat(m.url()).isEqualTo("https://example.com/widget");
        assertThat(m.licenses())
                .containsExactly(new PomMetadata.License("Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0"));
        assertThat(m.developers()).containsExactly(new PomMetadata.Developer("ada", "Ada", "ada@example.com"));
        assertThat(requireNonNull(m.scm()).developerConnection())
                .isEqualTo("scm:git:ssh://git@github.com/example/widget.git");
        assertThat(m.centralGaps(b.project().description())).isEmpty();

        String xml = PublishablePom.render(b).xml();
        // Central requires <name>; the artifact id stands in when the table names nothing better.
        assertThat(xml).contains("<name>widget</name>");
        assertThat(xml).contains("<description>A widget</description>");
        assertThat(xml).contains("<url>https://example.com/widget</url>");
        assertThat(xml).contains("<name>Apache-2.0</name>");
        assertThat(xml).contains("<id>ada</id>");
        assertThat(xml)
                .contains("<developerConnection>scm:git:ssh://git@github.com/example/widget.git</developerConnection>");
    }

    @Test
    void central_gaps_name_every_missing_key() throws Exception {
        JkBuild b = parse("[publish]\nurl = \"https://example.com\"\n");
        assertThat(b.pomMetadata().centralGaps(null)).containsExactly("description", "licenses", "developers", "scm");
        assertThat(b.pomMetadata().centralGaps("described")).containsExactly("licenses", "developers", "scm");
    }

    @Test
    void an_unknown_key_is_a_parse_error() {
        assertThatThrownBy(() -> parse("[publish]\nhomepage = \"https://example.com\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("homepage")
                .hasMessageContaining("url");
        assertThatThrownBy(() -> parse("[publish]\nlicenses = [{ spdx = \"Apache-2.0\" }]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("spdx");
        assertThatThrownBy(() -> parse("[publish]\nscm = \"https://github.com/example/widget\"\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("scm");
    }

    @Test
    void a_member_takes_the_root_s_table_and_its_own_wins() throws Exception {
        Path root = tmp;
        Files.writeString(root.resolve("jk.toml"), """
                name = "ws"
                group = "com.example"
                version = "1.0"

                [workspace]
                modules = ["lib", "own"]
                """ + TABLE);
        Path lib = Files.createDirectories(root.resolve("lib"));
        Files.writeString(lib.resolve("jk.toml"), """
                name = "lib"
                group.workspace = true
                version.workspace = true
                """);
        Path own = Files.createDirectories(root.resolve("own"));
        Files.writeString(own.resolve("jk.toml"), """
                name = "own"
                group.workspace = true
                version.workspace = true

                [publish]
                url = "https://example.com/own"
                """);
        JkBuild libBuild = JkBuildParser.parse(lib.resolve("jk.toml"));
        assertThat(libBuild.pomMetadata().url()).isEqualTo("https://example.com/widget");
        assertThat(libBuild.pomMetadata().developers()).hasSize(1);
        JkBuild ownBuild = JkBuildParser.parse(own.resolve("jk.toml"));
        assertThat(ownBuild.pomMetadata().url()).isEqualTo("https://example.com/own");
        assertThat(ownBuild.pomMetadata().licenses()).isEqualTo(List.of());
    }
}
