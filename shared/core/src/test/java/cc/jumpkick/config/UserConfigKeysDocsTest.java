// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.tomlj.Toml;
import org.tomlj.TomlParseResult;

/** A key the user docs show in {@code ~/.jk/config.toml} is never reported as unknown. */
@Tag("docs")
class UserConfigKeysDocsTest {

    /** A {@code toml} block whose first line says it is {@code ~/.jk/config.toml}. */
    private static final Pattern BLOCK = Pattern.compile("(?s)```toml\\n(# ~/\\.jk/config\\.toml[^\\n]*\\n.*?)```");

    /** A commented-out key, as the docs show an optional one. */
    private static final Pattern COMMENTED_KEY = Pattern.compile("(?m)^#\\s*([A-Za-z0-9_\"@.-]+\\s*=)");

    @Test
    void every_key_a_doc_shows_in_config_toml_is_known() throws IOException {
        Path docs = RepoRoot.find(UserConfigKeysDocsTest.class).resolve("docs/user");
        List<String> unknown = new ArrayList<>();
        int blocks = 0;
        try (Stream<Path> pages = Files.list(docs)) {
            for (Path page :
                    pages.filter(p -> p.toString().endsWith(".md")).sorted().toList()) {
                Matcher m = BLOCK.matcher(Files.readString(page));
                while (m.find()) {
                    blocks++;
                    String toml = COMMENTED_KEY.matcher(m.group(1)).replaceAll("$1");
                    TomlParseResult parsed = Toml.parse(toml);
                    assertThat(parsed.errors())
                            .as("%s block:%n%s", page.getFileName(), toml)
                            .isEmpty();
                    for (String key : UserConfigKeys.unknown(parsed)) unknown.add(page.getFileName() + ": " + key);
                }
            }
        }
        assertThat(blocks)
                .as("config.toml blocks found in docs/user; the scan broke")
                .isGreaterThan(5);
        assertThat(unknown).isEmpty();
    }
}
