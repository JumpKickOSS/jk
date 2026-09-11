// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.Scope;
import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The published schema is a second copy of the manifest vocabulary, so it is gated like every other
 * copy: the parser's key sets are the truth and the schema must name exactly them.
 */
class JkTomlSchemaTest {

    private static final Path SCHEMA = RepoRoot.find(JkTomlSchemaTest.class).resolve("docs/user/jk.toml.schema.json");

    @Test
    void top_level_properties_are_exactly_the_identity_keys_the_core_tables_and_the_scopes() throws Exception {
        String schema = Files.readString(SCHEMA);
        Set<String> expected = new HashSet<>(ManifestProject.PROJECT_KEYS);
        expected.addAll(ManifestBuild.CORE_TABLES);
        for (Scope s : Scope.values()) expected.add(s.tomlSection());
        assertThat(keysOf(Jsonl.nested(schema, "properties")))
                .as(
                        "docs/user/jk.toml.schema.json top-level properties vs ManifestProject.PROJECT_KEYS + CORE_TABLES + scopes")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void application_properties_are_exactly_the_parser_s_application_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String application = Jsonl.nested(Jsonl.nested(schema, "properties"), "application");
        assertThat(keysOf(Jsonl.nested(application, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestTables.APPLICATION_KEYS);
        assertThat(Jsonl.bool(application, "additionalProperties", true))
                .as("an unknown key under [application] is what an editor should flag")
                .isFalse();
    }

    @Test
    void dev_sidecar_properties_are_exactly_the_parser_s_sidecar_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String dev = Jsonl.nested(Jsonl.nested(schema, "properties"), "dev");
        assertThat(keysOf(Jsonl.nested(dev, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestBuild.DEV_KEYS);
        String sidecar =
                Jsonl.nested(Jsonl.nested(Jsonl.nested(dev, "properties"), "sidecars"), "additionalProperties");
        assertThat(keysOf(Jsonl.nested(sidecar, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.SIDECAR_KEYS);
        // the sidecar's own additionalProperties is its last one; env's nested one comes first
        int last = sidecar.lastIndexOf("\"additionalProperties\"");
        assertThat(sidecar.substring(last).replaceAll("\\s", "")).startsWith("\"additionalProperties\":false");
    }

    @Test
    void javac_properties_are_exactly_the_parser_s_javac_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String javac = Jsonl.nested(Jsonl.nested(schema, "properties"), "javac");
        assertThat(keysOf(Jsonl.nested(javac, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.JAVAC_KEYS);
        String test = Jsonl.nested(Jsonl.nested(javac, "properties"), "test");
        assertThat(keysOf(Jsonl.nested(test, "properties")))
                .as("[javac.test] is the same shape one level down, and cannot nest")
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.JAVAC_TEST_KEYS);
        String plugin =
                Jsonl.nested(Jsonl.nested(Jsonl.nested(javac, "properties"), "plugins"), "additionalProperties");
        assertThat(keysOf(Jsonl.nested(plugin, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.JAVAC_PLUGIN_KEYS);
        assertThat(Jsonl.bool(plugin, "additionalProperties", true))
                .as("an unknown key under [javac.plugins.<Name>] is what an editor should flag")
                .isFalse();
        // the table's own additionalProperties is its last one; plugins' nested one comes first
        int last = javac.lastIndexOf("\"additionalProperties\"");
        assertThat(javac.substring(last).replaceAll("\\s", "")).startsWith("\"additionalProperties\":false");
    }

    @Test
    void audit_properties_are_exactly_the_parser_s_audit_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String audit = Jsonl.nested(Jsonl.nested(schema, "properties"), "audit");
        assertThat(keysOf(Jsonl.nested(audit, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.AUDIT_KEYS);
        String entry = Jsonl.nested(Jsonl.nested(Jsonl.nested(audit, "properties"), "ignore"), "items");
        assertThat(keysOf(Jsonl.nested(entry, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.AUDIT_IGNORE_KEYS);
        assertThat(Jsonl.bool(entry, "additionalProperties", true))
                .as("an unknown key on an ignore entry is what an editor should flag")
                .isFalse();
        // the table's own additionalProperties is its last one; the entry's nested one comes first
        int last = audit.lastIndexOf("\"additionalProperties\"");
        assertThat(audit.substring(last).replaceAll("\\s", "")).startsWith("\"additionalProperties\":false");
    }

    @Test
    void the_schema_names_the_dependency_scope_tables_the_parser_reads() throws Exception {
        String schema = Files.readString(SCHEMA);
        for (Scope s : Scope.values()) {
            assertThat(Jsonl.nested(Jsonl.nested(schema, "properties"), s.tomlSection()))
                    .as(s.tomlSection())
                    .isNotNull();
        }
    }

    /** Keys of one JSON object: the names at brace depth one, in order. */
    private static List<String> keysOf(String object) {
        List<String> keys = new ArrayList<>();
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        boolean expectingKey = true;
        for (int i = 0; i < object.length(); i++) {
            char c = object.charAt(i);
            if (inString) {
                if (c == '"') {
                    inString = false;
                    if (depth == 1 && expectingKey) keys.add(current.toString());
                } else {
                    current.append(c);
                }
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    current.setLength(0);
                }
                case '{', '[' -> depth++;
                case '}', ']' -> depth--;
                case ':' -> {
                    if (depth == 1) expectingKey = false;
                }
                case ',' -> {
                    if (depth == 1) expectingKey = true;
                }
                default -> {}
            }
        }
        return keys;
    }
}
