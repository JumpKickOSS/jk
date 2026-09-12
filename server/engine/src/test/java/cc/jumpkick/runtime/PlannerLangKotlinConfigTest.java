// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.plugin.manifest.PluginDescriptorStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A third-party plugin's {@code [[contribute.kotlin-plugin]]} and kotlinc {@code compiler-args}
 * reach the compile through {@link PlannerLang#kotlinConfig}. The contributions are pinned by the
 * module's lock and materialized under the module's plugin store, so the lookup has to be keyed
 * by the module dir: keyed anywhere else, the same manifest contributes nothing.
 */
class PlannerLangKotlinConfigTest {

    private static final String SHA = "ab".repeat(32);

    private static final String MANIFEST = """
            [plugin]
            id      = "opener"
            table   = "opener"
            version = "1.0.0"

            [schema]
            preset = { type = "string", default = "spring" }

            [[contribute.compiler-args]]
            kotlin = ["-Xopener-flag"]

            [[contribute.kotlin-plugin]]
            id         = "com.example.opener"
            coordinate = "com.example:opener-compiler-plugin:${kotlin.version}"
            options    = ["preset=${config.preset}"]
            """;

    @Test
    void a_third_party_kotlin_plugin_contribution_is_resolved_against_the_module_dir(@TempDir Path tmp)
            throws Exception {
        Path module = Files.createDirectories(tmp.resolve("app"));
        Path descriptor = PluginDescriptorStore.fileFor(module, SHA);
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, MANIFEST);
        LockfileWriter.write(
                new Lockfile(
                        Lockfile.CURRENT_VERSION,
                        "test",
                        Lockfile.RESOLUTION_ALGORITHM,
                        null,
                        null,
                        null,
                        null,
                        List.of(),
                        List.of(new Lockfile.PluginEntry("com.example:kt-opener", "1.0.0", "sha256:" + SHA, null)),
                        List.of(),
                        List.of(),
                        null,
                        null,
                        null,
                        null),
                module.resolve("jk-lock.toml"));
        Files.writeString(module.resolve("jk.toml"), """
                name    = "demo"
                group   = "com.demo"
                version = "0.1.0"
                java    = 25
                kotlin  = "2.4.10"

                [plugins]
                opener = { group = "com.example", name = "kt-opener", version = "1.0.0", sha256 = "%s" }

                [opener]
                preset = "demo"
                """.formatted(SHA));
        JkBuild project = JkBuildParser.parse(module.resolve("jk.toml"));
        Lockfile lock = LockfileReader.read(module.resolve("jk-lock.toml"));
        Path javaHome = Path.of(System.getProperty("java.home"));

        PlannerLang.KotlinConfig config = PlannerLang.kotlinConfig(project, lock, module, 25, javaHome, null);
        assertThat(config.plugins())
                .extracting(PlannerLang.KotlinPluginUse::id, PlannerLang.KotlinPluginUse::version)
                .containsExactly(tuple("com.example.opener", "2.4.10"));
        assertThat(config.plugins().getFirst().options()).containsExactly("preset=demo");
        assertThat(config.args()).contains("-Xopener-flag");

        // The compiler's incremental working dir lives under the cache; no lock or descriptor is
        // there to pin the plugin, so a lookup keyed by it sees a module without contributions.
        Path cacheDir = Files.createDirectories(tmp.resolve("cache/actions/incremental-kotlin/compile-kotlin@abc"));
        PlannerLang.KotlinConfig elsewhere = PlannerLang.kotlinConfig(project, lock, cacheDir, 25, javaHome, null);
        assertThat(elsewhere.plugins()).isEmpty();
        assertThat(elsewhere.args()).doesNotContain("-Xopener-flag");
    }

    /**
     * The stamp digest moves with everything kotlinc is told that no source or classpath mtime
     * reflects: a declared compiler plugin, its options, the JVM target.
     */
    @Test
    void the_stamp_digest_moves_with_plugins_and_options_but_not_with_sources(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("app"));
        Path javaHome = Path.of(System.getProperty("java.home"));
        Lockfile lock = new Lockfile(
                Lockfile.CURRENT_VERSION,
                "test",
                Lockfile.RESOLUTION_ALGORITHM,
                null,
                null,
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null);
        String plain = """
                name    = "demo"
                group   = "com.demo"
                version = "0.1.0"
                java    = 25
                kotlin  = "2.4.10"
                """;
        String withPlugin = plain + """

                [[kotlin-plugins]]
                coordinate = "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable"
                """;
        String withOptions = plain + """

                [[kotlin-plugins]]
                coordinate = "org.jetbrains.kotlin:kotlin-serialization-compiler-plugin-embeddable"
                options    = ["mode=strict"]
                """;

        String base = digest(plain, lock, module, 25, javaHome);
        assertThat(digest(plain, lock, module, 25, javaHome)).isEqualTo(base);
        assertThat(digest(withPlugin, lock, module, 25, javaHome)).isNotEqualTo(base);
        assertThat(digest(withOptions, lock, module, 25, javaHome))
                .isNotEqualTo(digest(withPlugin, lock, module, 25, javaHome));
        // -module-name shapes internal-member mangling, so the name is an input too.
        assertThat(digest(plain.replace("\"demo\"", "\"renamed\""), lock, module, 25, javaHome))
                .isNotEqualTo(base);
        // A mixed module's Java roots are an argument too; a different root set is a different digest.
        JkBuild project = JkBuildParser.parse(plain);
        assertThat(PlannerLang.kotlinConfig(
                                project, lock, module, 25, javaHome, List.of(module.resolve("src/main/java")))
                        .digest())
                .isNotEqualTo(base);
    }

    private static String digest(String manifest, Lockfile lock, Path module, int release, Path javaHome)
            throws Exception {
        return PlannerLang.kotlinConfig(JkBuildParser.parse(manifest), lock, module, release, javaHome, null)
                .digest();
    }
}
