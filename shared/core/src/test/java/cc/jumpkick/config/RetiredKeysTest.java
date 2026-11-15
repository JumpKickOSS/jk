// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;

/**
 * Every retired key is refused by the parser with its release and remedy, and the docs print the
 * list {@link RetiredKeys#ALL} holds.
 */
class RetiredKeysTest {

    private static final String IDENTITY = """
            group = "t"
            name = "m"
            version = "1.0.0"
            """;

    @Test
    void the_parser_refuses_every_retired_key_naming_its_release_and_remedy() {
        assertThat(RetiredKeys.ALL).hasSizeGreaterThanOrEqualTo(9);
        for (RetiredKeys.Retired r : RetiredKeys.ALL) {
            String toml = manifestCarrying(r);
            String where = r.name().replace("<name>", "integration");
            assertThatThrownBy(() -> JkBuildParser.parse(toml))
                    .as("%s in%n%s", r.name(), toml)
                    .isInstanceOf(JkBuildParseException.class)
                    .hasMessageStartingWith(where + " was retired in JumpKick " + r.since() + ": ")
                    .hasMessageEndingWith(r.message());
        }
    }

    @Test
    void a_profile_that_chooses_tests_names_the_suite_table() {
        assertThatThrownBy(() -> JkBuildParser.parse(IDENTITY + "\n[profiles.ci]\ninclude-tags = [\"slow\"]\n"))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining("[profiles.ci] include-tags was retired")
                .hasMessageContaining("[test.suites.<name>] tags")
                .hasMessageContaining("jk test -s <name>");
    }

    @Test
    void found_lists_every_retired_key_a_manifest_carries() {
        var root = Toml.parse(IDENTITY + """
                m2integration = false

                [test]
                exclude-tags = ["slow"]

                [profiles.a]
                all-suites = true

                [profiles.b]
                jvm-args = ["-Xmx1g"]
                """);
        assertThat(RetiredKeys.found(root))
                .extracting(s -> s.substring(0, s.indexOf(" was retired")))
                .containsExactly("`m2integration`", "[profiles.a] all-suites", "[test] exclude-tags");
        assertThat(RetiredKeys.found(Toml.parse(IDENTITY))).isEmpty();
    }

    @Test
    void the_docs_table_is_the_list() throws IOException {
        Path docs = RepoRoot.find(RetiredKeysTest.class).resolve("docs/user/projects.md");
        Matcher m = Pattern.compile("(?s)<!-- retired-keys:start -->.*?<!-- retired-keys:end -->")
                .matcher(Files.readString(docs));
        assertThat(m.find()).as("%s has no retired-keys markers", docs).isTrue();
        assertThat(m.group())
                .as("%s's retired-keys table is RetiredKeys.markdown(); paste it again", docs)
                .isEqualTo(RetiredKeys.markdown());
    }

    /** A manifest that is valid but for {@code r}. */
    private static String manifestCarrying(RetiredKeys.Retired r) {
        String table = r.table().replace(".*", ".integration");
        if (table.isEmpty()) return r.key() + " = \"x\"\n" + IDENTITY;
        String body = r.key() == null ? "x = 1\n" : r.key() + " = [\"slow\"]\n";
        return IDENTITY + "\n[" + table + "]\n" + body;
    }
}
