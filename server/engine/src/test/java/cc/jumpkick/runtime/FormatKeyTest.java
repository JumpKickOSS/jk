// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.plugin.protocol.PluginSpec;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link FormatKey} is the one owner of what a {@code jk format} run is keyed by, and both format
 * stores are named by its digest. These are the two inputs that were in neither of the two keys it
 * replaced: the ktfmt max width, and the google-java-format version that {@code
 * removeUnusedImports} actually runs (a Palantir-styled project resolves GJF separately, so the
 * java-format version in the key was the wrong artifact's).
 */
class FormatKeyTest {

    @Test
    void kotlin_width_and_the_remove_unused_gjf_version_change_the_digest() {
        String base = key(120, true, "1.28.0");

        assertThat(key(100, true, "1.28.0"))
                .as("bumping the ktfmt max width must re-format, not restamp everything clean")
                .isNotEqualTo(base);
        assertThat(key(120, true, "1.29.0"))
                .as("removeUnusedImports runs google-java-format; its version is an output input")
                .isNotEqualTo(base);
    }

    /** With the step off, google-java-format never runs — bumping it must not churn the tree. */
    @Test
    void the_gjf_version_is_inert_while_remove_unused_imports_is_off() {
        assertThat(key(120, false, "1.29.0")).isEqualTo(key(120, false, "1.28.0"));
        assertThat(key(120, false, "1.28.0")).isNotEqualTo(key(120, true, "1.28.0"));
    }

    /**
     * The wiring half: {@code jk format}'s own key must carry the pinned width and GJF version, not
     * a zero or the style formatter's version. Compares the plan's digest against one built from
     * the constants — and then against neighbours, so the constants are load-bearing rather than
     * two fields nobody reads.
     */
    @Test
    void the_format_plan_feeds_the_pinned_width_and_gjf_version_in(@TempDir Path tmp) throws Exception {
        Path worker = tmp.resolve("jk-formatter.jar");
        Files.writeString(worker, "thin worker");
        Files.writeString(tmp.resolve("jk-formatter.pom"), "<project/>\n");
        String previous = System.getProperty(PluginJar.FORMATTER.jarProperty());
        System.setProperty(PluginJar.FORMATTER.jarProperty(), worker.toString());
        try {
            String planned =
                    FormatPlans.configKey(tmp.resolve("cache"), "palantir", "kotlinlang", true, true, true, List.of());

            assertThat(planned)
                    .isEqualTo(new FormatKey(
                                    "palantir",
                                    FormatPlans.PALANTIR_VERSION,
                                    "kotlinlang",
                                    FormatPlans.KTFMT_VERSION,
                                    FormatPlans.KOTLIN_MAX_WIDTH,
                                    true,
                                    true,
                                    true,
                                    FormatPlans.GOOGLE_VERSION,
                                    FormatPlans.SCALAFMT_VERSION,
                                    List.of(),
                                    worker)
                            .digest());
            assertThat(planned)
                    .as("a width the plan does not use would key the same — it must not")
                    .isNotEqualTo(new FormatKey(
                                    "palantir",
                                    FormatPlans.PALANTIR_VERSION,
                                    "kotlinlang",
                                    FormatPlans.KTFMT_VERSION,
                                    FormatPlans.KOTLIN_MAX_WIDTH + 1,
                                    true,
                                    true,
                                    true,
                                    FormatPlans.GOOGLE_VERSION,
                                    FormatPlans.SCALAFMT_VERSION,
                                    List.of(),
                                    worker)
                            .digest());
            assertThat(planned)
                    .as("the remove-unused GJF version is google-java-format's, not palantir's")
                    .isNotEqualTo(new FormatKey(
                                    "palantir",
                                    FormatPlans.PALANTIR_VERSION,
                                    "kotlinlang",
                                    FormatPlans.KTFMT_VERSION,
                                    FormatPlans.KOTLIN_MAX_WIDTH,
                                    true,
                                    true,
                                    true,
                                    FormatPlans.PALANTIR_VERSION,
                                    FormatPlans.SCALAFMT_VERSION,
                                    List.of(),
                                    worker)
                            .digest());
            assertThat(planned)
                    .as("bumping scalafmt must re-format, not restamp everything clean")
                    .isNotEqualTo(new FormatKey(
                                    "palantir",
                                    FormatPlans.PALANTIR_VERSION,
                                    "kotlinlang",
                                    FormatPlans.KTFMT_VERSION,
                                    FormatPlans.KOTLIN_MAX_WIDTH,
                                    true,
                                    true,
                                    true,
                                    FormatPlans.GOOGLE_VERSION,
                                    "9.9.9",
                                    List.of(),
                                    worker)
                            .digest());
        } finally {
            if (previous == null) System.clearProperty(PluginJar.FORMATTER.jarProperty());
            else System.setProperty(PluginJar.FORMATTER.jarProperty(), previous);
        }
    }

    /**
     * The worker must be told the key, not derive a second one — the drift that let both inputs go
     * unkeyed. The spec field name is the contract; {@code CodeFormatterStampKeyTest} reads it back
     * on the plugin side.
     */
    @Test
    void the_spec_carries_the_digest_to_the_worker(@TempDir Path tmp) throws Exception {
        Path source = tmp.resolve("A.java");
        Files.writeString(source, "class A {}");
        Path spec = FormatPlans.writeSpec(
                false,
                "palantir",
                "kotlinlang",
                List.of(source),
                List.of(tmp.resolve("palantir.jar")),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                false,
                true,
                true,
                List.of(source),
                tmp.resolve("cache"),
                "cafebabe",
                tmp.resolve("out.spec"));

        // Read it back through the same accessor the worker's Spec.from uses.
        assertThat(PluginSpec.read(spec).config().stringOpt("configKey")).contains("cafebabe");
    }

    @Test
    void the_spec_lists_groovy_and_scala_files(@TempDir Path tmp) throws Exception {
        Path groovy = tmp.resolve("A.groovy");
        Path scala = tmp.resolve("A.scala");
        Files.writeString(groovy, "class A {}");
        Files.writeString(scala, "class A");
        Path spec = FormatPlans.writeSpec(
                false,
                "palantir",
                "kotlinlang",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(groovy),
                List.of(scala),
                List.of(tmp.resolve("scalafmt.jar")),
                true,
                true,
                true,
                List.of(groovy, scala),
                tmp.resolve("cache"),
                "cafebabe",
                tmp.resolve("gs.spec"));

        var cfg = PluginSpec.read(spec).config();
        assertThat(cfg.stringList("groovyFiles"))
                .containsExactly(groovy.toAbsolutePath().toString());
        assertThat(cfg.stringList("scalaFiles"))
                .containsExactly(scala.toAbsolutePath().toString());
        assertThat(cfg.stringOpt("scalaVersion")).contains(FormatPlans.SCALAFMT_VERSION);
        assertThat(cfg.bool("optimizeImports", false)).isTrue();
    }

    private static String key(int kotlinMaxWidth, boolean removeUnusedImports, String gjfVersion) {
        return new FormatKey(
                        "palantir",
                        "2.80.0",
                        "kotlinlang",
                        "0.61",
                        kotlinMaxWidth,
                        true,
                        true,
                        removeUnusedImports,
                        gjfVersion,
                        FormatPlans.SCALAFMT_VERSION,
                        List.of(),
                        null)
                .digest();
    }
}
