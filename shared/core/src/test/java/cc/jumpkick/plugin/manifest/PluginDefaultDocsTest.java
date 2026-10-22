// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.manifest;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every release a docs/user page quotes as a built-in plugin's default is the one its manifest
 * defaults to, so a bumped tool default cannot leave a page naming the old one.
 */
class PluginDefaultDocsTest {

    private static final Pattern VERSION = Pattern.compile("\\d+(\\.\\d+)+");
    /** A key table row: {@code | `checkstyle-version` | The Checkstyle release … | `"14.3.0"` |}. */
    private static final Pattern TABLE_ROW = Pattern.compile("^\\| `([a-z0-9-]+)` \\|.*\\| `\"([^\"]+)\"` \\|$");
    /** A commented sample key: {@code # version = "3.21.9"   # the jOOQ release; …}, matched by the hint it opens with. */
    private static final Pattern SAMPLE_LINE = Pattern.compile("^#\\s*([a-z0-9-]+)\\s*=\\s*\"([^\"]+)\"\\s*#\\s*(.+)$");

    private record VersionDefault(String plugin, String key, String value, String hint) {}

    @Test
    void docs_quote_each_built_in_release_default_as_the_manifest_does() throws IOException {
        List<VersionDefault> defaults = new ArrayList<>();
        for (PluginDescriptor manifest : PluginTableRegistry.manifests()) {
            if (!PluginTableRegistry.isBuiltIn(manifest.id())) continue;
            for (PluginDescriptor.SchemaKey key : manifest.schema().values()) {
                if (key.defaultValue() instanceof String value
                        && VERSION.matcher(value).matches()) {
                    defaults.add(
                            new VersionDefault(manifest.id(), key.name(), value, key.hint() == null ? "" : key.hint()));
                }
            }
        }
        assertThat(defaults)
                .as("the lint and jooq manifests load from the workspace")
                .extracting(VersionDefault::key)
                .contains("checkstyle-version", "pmd-version", "spotbugs-version");
        Map<String, List<VersionDefault>> byKey = new HashMap<>();
        defaults.forEach(
                d -> byKey.computeIfAbsent(d.key(), k -> new ArrayList<>()).add(d));

        Path docs = RepoRoot.find(PluginDefaultDocsTest.class).resolve("docs/user");
        List<String> stale = new ArrayList<>();
        int quoted = 0;
        try (Stream<Path> pages = Files.walk(docs)) {
            for (Path page : pages.filter(p -> p.toString().endsWith(".md")).toList()) {
                List<String> lines = Files.readAllLines(page);
                for (int i = 0; i < lines.size(); i++) {
                    String line = lines.get(i).strip();
                    VersionDefault expected = null;
                    String written = null;
                    Matcher row = TABLE_ROW.matcher(line);
                    Matcher sample = SAMPLE_LINE.matcher(line);
                    if (row.matches()) {
                        List<VersionDefault> owners = byKey.getOrDefault(row.group(1), List.of());
                        if (owners.size() == 1) {
                            expected = owners.getFirst();
                            written = row.group(2);
                        }
                    } else if (sample.matches()) {
                        for (VersionDefault d : byKey.getOrDefault(sample.group(1), List.of())) {
                            if (!d.hint().isEmpty()
                                    && d.hint().startsWith(sample.group(3).strip())) {
                                expected = d;
                                written = sample.group(2);
                            }
                        }
                    }
                    if (expected == null) continue;
                    quoted++;
                    if (!expected.value().equals(written)) {
                        stale.add(docs.relativize(page) + ":" + (i + 1) + " quotes [" + expected.plugin() + "] "
                                + expected.key() + " = \"" + written + "\"; the manifest defaults to \""
                                + expected.value() + "\"");
                    }
                }
            }
        }
        assertThat(quoted).as("the pages quoting release defaults were found").isGreaterThanOrEqualTo(4);
        assertThat(stale).isEmpty();
    }
}
