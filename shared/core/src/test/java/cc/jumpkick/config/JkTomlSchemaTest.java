// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.model.Scope;
import cc.jumpkick.testing.RepoRoot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
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
        assertThat(keysOf(table(schema, "properties")))
                .as(
                        "docs/user/jk.toml.schema.json top-level properties vs ManifestProject.PROJECT_KEYS + CORE_TABLES + scopes")
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void node_properties_are_exactly_the_parser_s_node_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String node = table(table(schema, "properties"), "node");
        assertThat(keysOf(table(node, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestNode.KEYS);
        String step = table(table(table(node, "properties"), "steps"), "items");
        assertThat(keysOf(table(step, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestNode.STEP_KEYS);
    }

    @Test
    void application_properties_are_exactly_the_parser_s_application_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String application = table(table(schema, "properties"), "application");
        assertThat(keysOf(table(application, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestTables.APPLICATION_KEYS);
        assertThat(Jsonl.bool(application, "additionalProperties", true))
                .as("an unknown key under [application] is what an editor should flag")
                .isFalse();
    }

    @Test
    void application_artifact_switches_are_booleans_and_config_is_a_template_path() throws Exception {
        String properties = table(topLevel("application"), "properties");
        for (String key : List.of("assembly", "minified", "native")) {
            assertThat(table(properties, key).replaceAll("\\s", ""))
                    .as("[application] %s is a switch; its settings live in their own table", key)
                    .startsWith("{\"type\":\"boolean\"");
        }
        assertThat(table(properties, "config").replaceAll("\\s", ""))
                .as("[application] config names a module-relative template file")
                .startsWith("{\"type\":\"string\"");
    }

    @Test
    void dev_sidecar_properties_are_exactly_the_parser_s_sidecar_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String dev = table(table(schema, "properties"), "dev");
        assertThat(keysOf(table(dev, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestBuild.DEV_KEYS);
        String sidecar = table(table(table(dev, "properties"), "sidecars"), "additionalProperties");
        assertThat(keysOf(table(sidecar, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.SIDECAR_KEYS);
        // the sidecar's own additionalProperties is its last one; env's nested one comes first
        int last = sidecar.lastIndexOf("\"additionalProperties\"");
        assertThat(sidecar.substring(last).replaceAll("\\s", "")).startsWith("\"additionalProperties\":false");
    }

    @Test
    void build_properties_are_exactly_the_parser_s_build_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String build = table(table(schema, "properties"), "build");
        assertThat(keysOf(table(build, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuildTable.BUILD_KEYS);
        assertThat(Jsonl.bool(build, "additionalProperties", true))
                .as("an unknown key under [build] is what an editor should flag")
                .isFalse();
    }

    @Test
    void resolve_properties_are_exactly_the_parser_s_resolve_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String resolve = table(table(schema, "properties"), "resolve");
        assertThat(keysOf(table(resolve, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.RESOLVE_KEYS);
        assertThat(Jsonl.bool(resolve, "additionalProperties", true))
                .as("an unknown key under [resolve] is what an editor should flag")
                .isFalse();
    }

    @Test
    void javac_properties_are_exactly_the_parser_s_javac_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String javac = table(table(schema, "properties"), "javac");
        assertThat(keysOf(table(javac, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestBuild.JAVAC_KEYS);
        String test = table(table(javac, "properties"), "test");
        assertThat(keysOf(table(test, "properties")))
                .as("[javac.test] is the same shape one level down, and cannot nest")
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.JAVAC_TEST_KEYS);
        String plugin = table(table(table(javac, "properties"), "plugins"), "additionalProperties");
        assertThat(keysOf(table(plugin, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.JAVAC_PLUGIN_KEYS);
        assertThat(Jsonl.bool(plugin, "additionalProperties", true))
                .as("an unknown key under [javac.plugins.<Name>] is what an editor should flag")
                .isFalse();
        // the table's own additionalProperties is its last one; plugins' nested one comes first
        int last = javac.lastIndexOf("\"additionalProperties\"");
        assertThat(javac.substring(last).replaceAll("\\s", "")).startsWith("\"additionalProperties\":false");
    }

    @Test
    void nullness_properties_are_exactly_the_parser_s_nullness_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String nullness = table(table(schema, "properties"), "nullness");
        assertThat(keysOf(table(nullness, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestNullness.KEYS);
        assertThat(Jsonl.bool(nullness, "additionalProperties", true))
                .as("an unknown key under [nullness] is what an editor should flag")
                .isFalse();
    }

    @Test
    void audit_properties_are_exactly_the_parser_s_audit_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String audit = table(table(schema, "properties"), "audit");
        assertThat(keysOf(table(audit, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestBuild.AUDIT_KEYS);
        String entry = table(table(table(audit, "properties"), "ignore"), "items");
        assertThat(keysOf(table(entry, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.AUDIT_IGNORE_KEYS);
        assertThat(Jsonl.bool(entry, "additionalProperties", true))
                .as("an unknown key on an ignore entry is what an editor should flag")
                .isFalse();
        // the table's own additionalProperties is its last one; the entry's nested one comes first
        int last = audit.lastIndexOf("\"additionalProperties\"");
        assertThat(audit.substring(last).replaceAll("\\s", "")).startsWith("\"additionalProperties\":false");
    }

    @Test
    void kotlin_plugins_is_an_array_of_tables_with_the_parser_s_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String plugins = table(table(schema, "properties"), "kotlin-plugins");
        assertThat(plugins.replaceAll("\\s", "")).startsWith("{\"type\":\"array\"");
        String entry = table(plugins, "items");
        assertThat(keysOf(table(entry, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuild.KOTLIN_PLUGIN_KEYS);
        assertThat(Jsonl.bool(entry, "additionalProperties", true))
                .as("an unknown key on a [[kotlin-plugins]] entry is what an editor should flag")
                .isFalse();
    }

    @Test
    void dokka_properties_are_exactly_the_parser_s_dokka_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String dokka = table(table(schema, "properties"), "dokka");
        assertThat(keysOf(table(dokka, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestTables.DOKKA_KEYS);
        assertThat(Jsonl.bool(dokka, "additionalProperties", true))
                .as("an unknown key under [dokka] is what an editor should flag")
                .isFalse();
    }

    @Test
    void build_info_properties_are_exactly_the_parser_s_build_info_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String buildInfo = table(table(schema, "properties"), "build-info");
        assertThat(keysOf(table(buildInfo, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestTables.BUILD_INFO_KEYS);
        assertThat(Jsonl.bool(buildInfo, "additionalProperties", true))
                .as("an unknown key under [build-info] is what an editor should flag")
                .isFalse();
    }

    @Test
    void repository_entry_properties_are_exactly_the_parser_s_repository_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        String entry = table(table(table(schema, "properties"), "repositories"), "additionalProperties");
        assertThat(keysOf(table(entry, "properties")))
                .containsExactlyInAnyOrderElementsOf(RepositoryToml.REPOSITORY_KEYS);
        assertThat(Jsonl.bool(entry, "additionalProperties", true))
                .as("an unknown key under [repositories.<name>] is what an editor should flag")
                .isFalse();
    }

    @Test
    void env_properties_are_exactly_the_parser_s_env_keys() throws Exception {
        String schema = Files.readString(SCHEMA);
        // By position, not by first textual match: [dev.sidecars.<name>] has its own `env` key earlier.
        String env = requireNonNull(memberOf(table(schema, "properties"), "env"), "env");
        assertThat(keysOf(table(env, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestEnv.ENV_KEYS);
        // the table's own additionalProperties is its last one; the vars item's nested one comes first
        int last = env.lastIndexOf("\"additionalProperties\"");
        assertThat(env.substring(last).replaceAll("\\s", ""))
                .as("a variable written straight into [env] is what an editor should flag")
                .startsWith("\"additionalProperties\":false");
    }

    @Test
    void test_properties_are_exactly_the_parser_s_test_keys() throws Exception {
        String test = topLevel("test");
        assertThat(keysOf(table(test, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestBuildTable.TEST_KEYS);
        String suite = table(table(table(test, "properties"), "suites"), "additionalProperties");
        assertThat(keysOf(table(suite, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestBuildTable.SUITE_KEYS);
        assertThat(ownAdditionalProperties(test))
                .as("an unknown key under [test] is what an editor should flag")
                .isFalse();
    }

    @Test
    void image_properties_are_exactly_the_parser_s_image_keys() throws Exception {
        String image = topLevel("image");
        assertThat(keysOf(table(image, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestImage.KEYS);
        assertThat(ownAdditionalProperties(image))
                .as("an unknown key under [image] is what an editor should flag")
                .isFalse();
    }

    @Test
    void train_properties_are_exactly_the_parser_s_train_keys() throws Exception {
        String train = topLevel("train");
        assertThat(keysOf(table(train, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestTrain.KEYS);
        String profile = table(table(table(train, "properties"), "profile"), "items");
        assertThat(keysOf(table(profile, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestTrain.PROFILE_KEYS);
        assertThat(ownAdditionalProperties(profile))
                .as("an unknown key on a [[train.profile]] entry is what an editor should flag")
                .isFalse();
        assertThat(ownAdditionalProperties(train))
                .as("an unknown key under [train] is what an editor should flag")
                .isFalse();
    }

    @Test
    void deny_properties_are_exactly_the_parser_s_deny_keys() throws Exception {
        String deny = topLevel("deny");
        assertThat(keysOf(table(deny, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestDeny.KEYS);
        String sources = table(table(deny, "properties"), "sources");
        assertThat(keysOf(table(sources, "properties"))).containsExactlyInAnyOrderElementsOf(ManifestDeny.SOURCES_KEYS);
        assertThat(ownAdditionalProperties(sources)).isFalse();
        String licenses = table(table(deny, "properties"), "licenses");
        assertThat(keysOf(table(licenses, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestDeny.LICENSES_KEYS);
        assertThat(ownAdditionalProperties(licenses)).isFalse();
        assertThat(ownAdditionalProperties(deny))
                .as("an unknown key under [deny] is what an editor should flag")
                .isFalse();
    }

    @Test
    void jvm_properties_are_exactly_the_parser_s_jvm_keys() throws Exception {
        String jvm = topLevel("jvm");
        assertThat(keysOf(table(jvm, "properties"))).containsExactlyInAnyOrderElementsOf(PluginTunings.KEYS);
        assertThat(ownAdditionalProperties(jvm))
                .as("an unknown key under [jvm] is what an editor should flag")
                .isFalse();
    }

    @Test
    void native_properties_are_exactly_the_parser_s_native_keys() throws Exception {
        assertClosedTable("native", ManifestBuild.NATIVE_KEYS);
    }

    @Test
    void format_properties_are_exactly_the_parser_s_format_keys() throws Exception {
        assertClosedTable("format", ManifestTables.FORMAT_KEYS);
    }

    @Test
    void config_properties_are_exactly_the_config_loader_s_keys() throws Exception {
        assertClosedTable("config", JkConfigLoader.KEYS);
    }

    @Test
    void jlink_properties_are_exactly_the_parser_s_jlink_keys() throws Exception {
        assertClosedTable("jlink", ManifestJlink.KEYS);
    }

    @Test
    void library_properties_are_exactly_the_parser_s_library_keys() throws Exception {
        assertClosedTable("library", ManifestTables.LIBRARY_KEYS);
    }

    @Test
    void resources_properties_are_exactly_the_parser_s_resources_keys() throws Exception {
        assertClosedTable("resources", ManifestResources.KEYS);
    }

    @Test
    void war_properties_are_exactly_the_parser_s_war_keys() throws Exception {
        assertClosedTable("war", ManifestTables.WAR_KEYS);
    }

    @Test
    void release_properties_are_exactly_the_parser_s_release_keys() throws Exception {
        String release = assertClosedTable("release", ManifestTables.RELEASE_KEYS);
        String file = table(table(table(release, "properties"), "files"), "items");
        assertThat(keysOf(table(file, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestTables.RELEASE_FILE_KEYS);
        assertThat(ownAdditionalProperties(file))
                .as("an unknown key on a [release] files entry is what an editor should flag")
                .isFalse();
    }

    @Test
    void publish_properties_are_exactly_the_parser_s_publish_keys() throws Exception {
        String publish = assertClosedTable("publish", ManifestTables.PUBLISH_KEYS);
        String properties = table(publish, "properties");
        String license = table(table(properties, "licenses"), "items");
        assertThat(keysOf(table(license, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestTables.PUBLISH_LICENSE_KEYS);
        assertThat(ownAdditionalProperties(license)).isFalse();
        String developer = table(table(properties, "developers"), "items");
        assertThat(keysOf(table(developer, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestTables.PUBLISH_DEVELOPER_KEYS);
        assertThat(ownAdditionalProperties(developer)).isFalse();
        String scm = table(properties, "scm");
        assertThat(keysOf(table(scm, "properties")))
                .containsExactlyInAnyOrderElementsOf(ManifestTables.PUBLISH_SCM_KEYS);
        assertThat(ownAdditionalProperties(scm)).isFalse();
    }

    @Test
    void graph_properties_are_exactly_the_parser_s_graph_keys() throws Exception {
        assertClosedTable("graph", ManifestGraph.KEYS);
    }

    @Test
    void guards_properties_are_exactly_the_parser_s_guards_keys() throws Exception {
        assertClosedTable("guards", ManifestGuards.KEYS);
    }

    @Test
    void workspace_properties_are_exactly_the_parser_s_workspace_keys() throws Exception {
        assertClosedTable("workspace", ManifestTables.WORKSPACE_KEYS);
    }

    /**
     * Asserts the top-level table {@code key} names exactly {@code keys} and refuses any other,
     * as the parser does, and returns the table for the nested checks a caller adds.
     */
    private static String assertClosedTable(String key, Collection<String> keys) throws Exception {
        String declared = topLevel(key);
        assertThat(keysOf(table(declared, "properties")))
                .as("docs/user/jk.toml.schema.json [%s] properties", key)
                .containsExactlyInAnyOrderElementsOf(keys);
        assertThat(ownAdditionalProperties(declared))
                .as("an unknown key under [%s] is what an editor should flag", key)
                .isFalse();
        return declared;
    }

    @Test
    void the_schema_names_the_dependency_scope_tables_the_parser_reads() throws Exception {
        String schema = Files.readString(SCHEMA);
        for (Scope s : Scope.values()) {
            assertThat(table(table(schema, "properties"), s.tomlSection()))
                    .as(s.tomlSection())
                    .isNotNull();
        }
    }

    @Test
    void a_dependency_entry_s_inline_table_names_exactly_the_parser_s_entry_keys() throws Exception {
        String defs = requireNonNull(memberOf(Files.readString(SCHEMA), "$defs"), "$defs");
        String dependency = requireNonNull(memberOf(defs, "dependency"), "dependency");
        assertThat(keysOf(table(dependency, "properties")))
                .containsExactlyInAnyOrderElementsOf(DependencyEntryKeys.DEPENDENCY);
        assertThat(ownAdditionalProperties(dependency))
                .as("an unknown key in a dependency's inline table is what an editor should flag")
                .isFalse();
        String shared = requireNonNull(memberOf(defs, "workspace-dependency"), "workspace-dependency");
        assertThat(keysOf(table(shared, "properties")))
                .containsExactlyInAnyOrderElementsOf(DependencyEntryKeys.WORKSPACE);
        assertThat(ownAdditionalProperties(shared)).isFalse();

        for (Scope s : Scope.values()) {
            // jk lock writes the plugin scope itself; the schema refuses the table outright.
            if (s == Scope.PLUGIN) continue;
            String entry = requireNonNull(memberOf(topLevel(s.tomlSection()), "additionalProperties"), s.tomlSection());
            assertThat(entry).as(s.tomlSection()).contains("\"#/$defs/dependency\"");
            assertThat(entry).as(s.tomlSection()).doesNotContain("\"object\"");
        }
        String workspaceDependencies = requireNonNull(
                memberOf(table(topLevel("workspace"), "properties"), "dependencies"), "workspace.dependencies");
        String workspaceEntry =
                requireNonNull(memberOf(workspaceDependencies, "additionalProperties"), "workspace.dependencies entry");
        assertThat(workspaceEntry).contains("\"#/$defs/workspace-dependency\"").doesNotContain("\"object\"");
    }

    /** The top-level table {@code key} by position, not by its first textual match anywhere in the schema. */
    private static String topLevel(String key) throws Exception {
        return requireNonNull(memberOf(table(Files.readString(SCHEMA), "properties"), key), key);
    }

    /**
     * The object's own {@code additionalProperties}, read at brace depth one so a nested table's
     * cannot answer for it. Absent, or not a boolean, reads as JSON Schema's default: open.
     */
    private static boolean ownAdditionalProperties(String object) {
        int depth = 0;
        for (int i = 0; i < object.length(); i++) {
            char c = object.charAt(i);
            if (c == '"') {
                int end = i + 1;
                while (object.charAt(end) != '"') end += object.charAt(end) == '\\' ? 2 : 1;
                if (depth == 1 && object.substring(i + 1, end).equals("additionalProperties")) {
                    String rest = object.substring(end + 1).stripLeading();
                    return !(rest.startsWith(":")
                            && rest.substring(1).stripLeading().startsWith("false"));
                }
                i = end;
            } else if (c == '{' || c == '[') depth++;
            else if (c == '}' || c == ']') depth--;
        }
        return true;
    }

    /** The nested object the schema is expected to carry; its absence fails the test that reads it. */
    private static String table(String json, String key) {
        return requireNonNull(Jsonl.nested(json, key), key);
    }

    /** The object {@code key} maps to at brace depth one of {@code object}, or null when absent there. */
    private static @Nullable String memberOf(String object, String key) {
        int depth = 0;
        boolean inString = false;
        StringBuilder current = new StringBuilder();
        boolean expectingKey = true;
        boolean found = false;
        for (int i = 0; i < object.length(); i++) {
            char c = object.charAt(i);
            if (inString) {
                if (c == '"') {
                    inString = false;
                    if (depth == 1 && expectingKey && current.toString().equals(key)) found = true;
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
                case '{' -> {
                    if (found && depth == 1) {
                        int end = i;
                        int inner = 0;
                        boolean quoted = false;
                        for (; end < object.length(); end++) {
                            char d = object.charAt(end);
                            if (quoted) {
                                if (d == '\\') end++;
                                else if (d == '"') quoted = false;
                            } else if (d == '"') quoted = true;
                            else if (d == '{') inner++;
                            else if (d == '}' && --inner == 0) return object.substring(i, end + 1);
                        }
                        return null;
                    }
                    depth++;
                }
                case '[' -> depth++;
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
        return null;
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
