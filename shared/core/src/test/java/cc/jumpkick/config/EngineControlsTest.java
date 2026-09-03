// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Docs, dashboard dump, and live env reads stay on {@link EngineControls}. */
class EngineControlsTest {

    private static final Pattern ENV = Pattern.compile("JK_ENGINE_[A-Z0-9_]+");

    private static final String[] MAIN = {
        "shared/core/src/main/java",
        "shared/host/src/main/java",
        "shared/wire/src/main/java",
        "server/engine/src/main/java",
        "clients/cli/src/main/java",
    };

    @Test
    void published_tables_match_the_catalog() throws IOException {
        String docs = Files.readString(RepoRoot.find(EngineControlsTest.class).resolve("docs/user/engine.md"));
        assertThat(block(docs, "engine-config")).isEqualTo(EngineControls.tableMarkdown());
        assertThat(block(docs, "engine-process")).isEqualTo(EngineControls.processMarkdown());
    }

    @Test
    void dashboard_lists_every_engine_toml_key() {
        var rows = EffectiveUserConfig.rows(Path.of("/no/such/config.toml"), k -> null);
        Set<String> keys = new LinkedHashSet<>();
        rows.forEach(r -> keys.add(r.key()));
        for (var c : EngineControls.TABLE) {
            assertThat(keys).as("EffectiveUserConfig missing [engine] key").contains("engine." + c.toml());
        }
    }

    @Test
    void toml_scan_keys_match_the_catalog() {
        assertThat(EngineControls.tomlScanKeys())
                .containsExactly(EngineControls.TABLE.stream()
                        .map(c -> "engine." + c.toml())
                        .toArray(String[]::new));
    }

    @Test
    void every_live_jk_engine_env_is_in_the_catalog() throws IOException {
        Set<String> live = new LinkedHashSet<>();
        Path root = RepoRoot.find(EngineControlsTest.class);
        for (String rel : MAIN) {
            Path dir = root.resolve(rel);
            if (!Files.isDirectory(dir)) continue;
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        Matcher m = ENV.matcher(Files.readString(p));
                        while (m.find()) live.add(m.group());
                    } catch (IOException e) {
                        throw new RuntimeException(p.toString(), e);
                    }
                });
            }
        }
        assertThat(live).as("no JK_ENGINE_* reads found — the scan broke").isNotEmpty();
        assertThat(EngineControls.envNames()).containsAll(live);
    }

    private static String block(String docs, String id) {
        Matcher m = Pattern.compile("(?s)<!-- " + id + ":start -->.*?<!-- " + id + ":end -->")
                .matcher(docs);
        assertThat(m.find()).as("docs/user/engine.md missing %s markers", id).isTrue();
        return m.group();
    }
}
