// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.android;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.plugin.PluginConfig;
import cc.jumpkick.plugin.build.BuildPluginContext;
import cc.jumpkick.plugin.build.PackagerSpec;
import cc.jumpkick.plugin.build.PluginCommandSpec;
import cc.jumpkick.plugin.build.ProjectFacts;
import cc.jumpkick.plugin.build.TaskSpec;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * {@code jk-plugin.toml} and {@link AndroidPlugin} are two halves of one contract, and nothing in
 * the build made them agree: the engine reads the descriptor for packaging, run/install/deploy and
 * the config schema without executing a line of plugin code, while the plugin's {@code register}
 * decides — in Java — which packager and which commands actually exist. A key that drifts on one
 * side is not a compile error on the other. It shipped once already, as {@code deploy-verb} in the
 * descriptor against {@code deploy-command} in the engine's reader.
 *
 * <p>So every case here reads the <em>shipped</em> descriptor — the one on the classpath, put at the
 * jar root by {@code jk.plugin-conventions}, not a source file found relative to some working
 * directory — and drives {@link AndroidPlugin#register} with the config its own conditions name.
 * Nothing is hard-coded from the descriptor side: rename a variant's condition key, change a
 * packager name, or drop the deploy command, and the registration stops matching.
 */
class ManifestContractTest {

    private static final Toml DESCRIPTOR = Toml.onClasspath("/jk-plugin.toml");

    /**
     * The harness has teeth. Every case below is a lookup into the parsed descriptor, so a reader
     * that quietly produced an empty model would make them vacuous rather than red — the first
     * mechanism in {@code code-as-art.md}'s list of ways a test passes without executing.
     */
    @Test
    void the_shipped_descriptor_is_the_one_being_read() {
        assertThat(DESCRIPTOR.headers())
                .contains("plugin", "code", "packaging", "packaging.variant", "schema", "sub-schema.signing");
        assertThat(DESCRIPTOR.array("packaging.variant"))
                .as("the two conditional packagers: library -> aar, release -> aab")
                .hasSize(2);
        assertThat(DESCRIPTOR.table("schema").keys())
                .as("the config keys the engine validates jk.toml against")
                .contains("namespace", "compile-sdk", "min-sdk", "library", "build-config", "hilt");
    }

    /**
     * The worker's identity. {@code [code] worker} is the artifactId the engine launches and
     * {@code protocol-prefix} is what it demuxes the worker's stdout on; the running plugin
     * announces both from Java. Disagree on the prefix and every protocol line is read as
     * passthrough output.
     */
    @Test
    void the_worker_id_and_protocol_prefix_are_the_ones_the_plugin_announces() {
        var manifest = new AndroidPlugin().manifest();

        assertThat(DESCRIPTOR.table("code").string("worker")).isEqualTo(manifest.id());
        assertThat(DESCRIPTOR.table("code").string("protocol-prefix")).isEqualTo(manifest.protocolPrefix());
    }

    /** No variant condition matches a plain debug app, so the base {@code [packaging]} applies. */
    @Test
    void the_base_packager_is_the_one_registration_chooses_for_a_plain_debug_app() {
        assertThat(register(Map.of()).packager.name())
                .isEqualTo(DESCRIPTOR.table("packaging").string("packager"));
    }

    /**
     * The real pairing: for each declared {@code [[packaging.variant]]}, build the config its own
     * {@code when} names and assert the plugin registers the packager the variant promises. The
     * engine picks the artifact extension, {@code jk run} and {@code jk install} behaviour from the
     * descriptor; the bytes come from whatever {@code register} chose. These must be the same
     * packager.
     */
    @Test
    void every_declared_packaging_variant_names_the_packager_its_condition_actually_selects() {
        List<Toml.Table> variants = DESCRIPTOR.array("packaging.variant");
        for (Toml.Table variant : variants) {
            Map<String, Object> config = conditionOf(variant);

            assertThat(register(config).packager.name())
                    .as("[[packaging.variant]] %s declares packager `%s`", config, variant.string("packager"))
                    .isEqualTo(variant.string("packager"));
        }
    }

    /**
     * Variant order is significant — the engine's {@code Packaging.resolve} takes the first match —
     * and the descriptor says so in a comment: a release <em>library</em> is an AAR, not an AAB.
     * The plugin's {@code if (library) … else if (release) …} has to agree, and a comment cannot
     * make it.
     */
    @Test
    void a_release_library_takes_the_earlier_variant_in_both_halves() {
        List<Toml.Table> variants = DESCRIPTOR.array("packaging.variant");
        Map<String, Object> both = new LinkedHashMap<>();
        both.putAll(conditionOf(variants.get(0)));
        both.putAll(conditionOf(variants.get(1)));

        assertThat(register(both).packager.name())
                .as("both conditions hold; the descriptor's first-listed variant wins")
                .isEqualTo(variants.get(0).string("packager"));
    }

    /**
     * {@code deploy-command} names a plugin command by string. The engine dispatches {@code jk run}
     * and {@code jk dev} straight to it; if {@code register} never defined a command by that name
     * the failure surfaces at deploy time, on a device, with the app already built. This is the
     * exact shape of the shipped {@code deploy-verb} defect.
     */
    @Test
    void the_declared_deploy_command_is_a_command_the_plugin_really_registers() {
        for (Toml.Table packaging : deployablePackagings()) {
            Map<String, Object> config = packaging.has("when") ? conditionOf(packaging) : Map.of();

            assertThat(register(config).commands)
                    .as("deploy-command = `%s`", packaging.string("deploy-command"))
                    .contains(packaging.string("deploy-command"));
        }
        assertThat(deployablePackagings()).as("apk and aab both deploy").hasSize(2);
    }

    /**
     * The library variant is {@code exec-mode = "none"} and declares no deploy command, so the
     * device verbs must not exist for a library build either — a registered {@code deploy} the
     * descriptor says is unreachable is a command that fails the moment anyone finds it.
     */
    @Test
    void a_library_declares_no_device_verbs_and_registers_none() {
        Toml.Table library = DESCRIPTOR.array("packaging.variant").get(0);

        assertThat(library.string("exec-mode")).isEqualTo("none");
        assertThat(library.has("deploy-command")).isFalse();
        assertThat(register(conditionOf(library)).commands)
                .as("a library has nothing to install on a device")
                .doesNotContain("deploy", "instrument")
                .as("the SDK-provisioning verbs are not device verbs and stay")
                .contains("android", "avd");
    }

    /**
     * {@code artifact-extension} is not decoration: {@link AarPackager} derives the sibling
     * classes-jar name by stripping exactly {@code ".aar"} off the artifact the engine handed it.
     * Change the declared extension and that substring arithmetic produces a nonsense file name.
     */
    @Test
    void the_aar_variants_declared_extension_is_the_one_the_packager_strips() {
        assertThat(DESCRIPTOR.array("packaging.variant").get(0).string("artifact-extension"))
                .isEqualTo("aar");
    }

    // ---- driving the plugin -------------------------------------------------------------

    /**
     * The config a variant's {@code when} selects on. The engine compares with
     * {@code String.valueOf(configValue).equals(declared)}, which is type-blind — but the plugin
     * reads {@code library} as a bool and {@code build-type} as a string, so the value has to carry
     * the schema's type or the plugin ignores it while the engine still matches. Keys absent from
     * {@code [schema]} are engine-injected (a variant/build-type token) and are strings.
     */
    private static Map<String, Object> conditionOf(Toml.Table variant) {
        Map<String, String> when = variant.inline("when");
        String key = when.get("config");
        String literal = when.get("equals");
        assertThat(key).as("[[packaging.variant]] when.config").isNotNull();
        assertThat(literal).as("[[packaging.variant]] when.equals").isNotNull();
        Toml.Table schema = DESCRIPTOR.table("schema");
        String type = schema.has(key) ? schema.inline(key).get("type") : "string";
        return Map.of(key, "bool".equals(type) ? Boolean.valueOf(literal) : literal);
    }

    /** Every {@code [packaging]} table — base and variants — that declares a deploy command. */
    private static List<Toml.Table> deployablePackagings() {
        List<Toml.Table> out = new ArrayList<>();
        if (DESCRIPTOR.table("packaging").has("deploy-command")) out.add(DESCRIPTOR.table("packaging"));
        for (Toml.Table variant : DESCRIPTOR.array("packaging.variant")) {
            if (variant.has("deploy-command")) out.add(variant);
        }
        return out;
    }

    private static Registrations register(Map<String, Object> config) {
        Map<String, Object> all =
                new LinkedHashMap<>(Map.of("namespace", "com.example.app", "compile-sdk", 36L, "min-sdk", 24L));
        all.putAll(config);
        Registrations registrations = new Registrations(new PluginConfig("android", all));
        new AndroidPlugin().register(registrations);
        assertThat(registrations.packager)
                .as("android always registers exactly one packager")
                .isNotNull();
        return registrations;
    }

    /** A recording {@link BuildPluginContext} — registration records, it never executes. */
    private static final class Registrations implements BuildPluginContext {
        private final PluginConfig config;
        private final List<String> commands = new ArrayList<>();
        private PackagerSpec packager;

        private Registrations(PluginConfig config) {
            this.config = config;
        }

        @Override
        public PluginConfig config() {
            return config;
        }

        @Override
        public ProjectFacts project() {
            return new ProjectFacts("com.example", "app", "1.0.0", 25, null, false, false, Map.of());
        }

        @Override
        public void task(TaskSpec spec) {}

        @Override
        public void packaging(PackagerSpec spec) {
            packager = spec;
        }

        @Override
        public void command(PluginCommandSpec spec) {
            commands.add(spec.name());
        }
    }

    // ---- the descriptor -----------------------------------------------------------------

    /**
     * Just enough TOML for a plugin descriptor: {@code [table]} and {@code [[array-of-table]]}
     * headers, scalar values, and one level of inline table. Deliberately not a dependency —
     * {@code :core}'s parser is unreachable from a plugin's classpath by design (it drags tomlj
     * onto every forked worker), and the four sections this reads have no nesting beyond that.
     * Every lookup below fails loudly on a miss, so a parse that found nothing cannot pass.
     */
    private static final class Toml {

        private final List<Table> blocks = new ArrayList<>();

        static Toml onClasspath(String resource) {
            try (InputStream in = ManifestContractTest.class.getResourceAsStream(resource)) {
                assertThat(in).as("%s is not on the test classpath", resource).isNotNull();
                return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new AssertionError("cannot read " + resource, e);
            }
        }

        static Toml parse(String text) {
            Toml toml = new Toml();
            Table current = null;
            for (String raw : text.split("\n", -1)) {
                String line = stripComment(raw).trim();
                if (line.isEmpty()) continue;
                if (line.startsWith("[")) {
                    boolean array = line.startsWith("[[");
                    String header = line.substring(array ? 2 : 1, line.length() - (array ? 2 : 1));
                    current = new Table(header.trim());
                    toml.blocks.add(current);
                } else if (current != null) {
                    int eq = line.indexOf('=');
                    if (eq < 0) continue;
                    current.values.put(
                            line.substring(0, eq).trim(), line.substring(eq + 1).trim());
                }
            }
            return toml;
        }

        List<String> headers() {
            return blocks.stream().map(Table::header).toList();
        }

        /** The single table under {@code header} — an assertion failure when it is not there. */
        Table table(String header) {
            List<Table> found = array(header);
            assertThat(found).as("[%s]", header).hasSize(1);
            return found.get(0);
        }

        /** Every {@code [[header]]} block, in file order — order is semantic for variants. */
        List<Table> array(String header) {
            List<Table> found =
                    blocks.stream().filter(b -> b.header().equals(header)).toList();
            assertThat(found).as("[[%s]]", header).isNotEmpty();
            return found;
        }

        /** Text before the first {@code #} that is not inside a double-quoted string. */
        private static String stripComment(String line) {
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '"') quoted = !quoted;
                else if (c == '#' && !quoted) return line.substring(0, i);
            }
            return line;
        }

        /** One header's key/value lines, values still in their source spelling. */
        record Table(String header, Map<String, String> values) {

            Table(String header) {
                this(header, new LinkedHashMap<>());
            }

            List<String> keys() {
                return List.copyOf(values.keySet());
            }

            boolean has(String key) {
                return values.containsKey(key);
            }

            String string(String key) {
                String value = values.get(key);
                assertThat(value).as("[%s] %s", header, key).isNotNull();
                return unquote(value);
            }

            /** {@code key = { a = "x", b = true }} → the pairs, values unquoted. */
            Map<String, String> inline(String key) {
                String value = values.get(key);
                assertThat(value).as("[%s] %s", header, key).isNotNull();
                assertThat(value).as("[%s] %s is an inline table", header, key).startsWith("{");
                Map<String, String> out = new LinkedHashMap<>();
                for (String pair : splitTopLevel(value.substring(1, value.lastIndexOf('}')))) {
                    int eq = pair.indexOf('=');
                    if (eq < 0) continue;
                    out.put(
                            pair.substring(0, eq).trim(),
                            unquote(pair.substring(eq + 1).trim()));
                }
                return out;
            }

            /** Split on commas outside quotes and outside a bracketed list. */
            private static List<String> splitTopLevel(String body) {
                List<String> parts = new ArrayList<>();
                boolean quoted = false;
                int depth = 0;
                int start = 0;
                for (int i = 0; i < body.length(); i++) {
                    char c = body.charAt(i);
                    if (c == '"') quoted = !quoted;
                    else if (!quoted && (c == '[' || c == '{')) depth++;
                    else if (!quoted && (c == ']' || c == '}')) depth--;
                    else if (c == ',' && !quoted && depth == 0) {
                        parts.add(body.substring(start, i));
                        start = i + 1;
                    }
                }
                parts.add(body.substring(start));
                return parts;
            }

            private static String unquote(String value) {
                return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")
                        ? value.substring(1, value.length() - 1)
                        : value;
            }
        }
    }
}
