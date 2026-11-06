// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.plugin;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParseException;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.util.MinimalToml;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A user-config plugin pin that cannot be honored is a loud error — silently running the shipped
 * plugin instead is wrong code with no diagnostic — and the pins follow config.toml while the engine
 * runs: added, edited or removed, the next manifest parse sees the file as it is now.
 */
class UserConfigPluginPinTest {

    private static final String MANIFEST = """
            [plugin]
            id      = "zz-userpin"
            table   = "zz-userpin"
            version = "1.0.0"

            [schema]
            enabled = { type = "bool", default = true }
            """;

    @AfterEach
    void restoreConfigOverride(@TempDir Path empty) {
        // Unregister whatever a test pinned, so no other test in this JVM sees it.
        PluginTableRegistry.userPinSync(null);
        System.setProperty("jk.env.JK_HOME", empty.resolve("no-home").toString());
        UserPluginPins.sync();
    }

    @Test
    void missing_pinned_jar_is_a_loud_error(@TempDir Path tmp) throws Exception {
        Path config = tmp.resolve("config.toml");
        Files.writeString(
                config, """
                [plugins]
                acme = { path = %s, sha256 = "%s" }
                """.formatted(MinimalToml.quote(tmp.resolve("gone.jar").toString()), "ab".repeat(32)));
        System.setProperty("jk.env.JK_HOME", requireNonNull(config.getParent()).toString());

        assertThatThrownBy(UserPluginPins::sync)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no file exists")
                .hasMessageContaining("gone.jar");
    }

    @Test
    void hash_mismatch_names_both_digests(@TempDir Path tmp) throws Exception {
        Path jar = writePluginJar(tmp.resolve("acme.jar"));
        String actual = Hashing.sha256Hex(jar);
        String declared = "cd".repeat(32);
        Path config = tmp.resolve("config.toml");
        Files.writeString(config, """
                [plugins]
                acme = { path = %s, sha256 = "%s" }
                """.formatted(MinimalToml.quote(jar.toString()), declared));
        System.setProperty("jk.env.JK_HOME", requireNonNull(config.getParent()).toString());

        assertThatThrownBy(UserPluginPins::sync)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(declared)
                .hasMessageContaining(actual);
    }

    @Test
    void a_config_that_does_not_parse_registers_nothing_and_lets_the_engine_start(@TempDir Path tmp) throws Exception {
        Path jar = writePluginJar(tmp.resolve("acme.jar"), MANIFEST.replace("zz-userpin", "zz-userpin-broken"));
        Path config = tmp.resolve("config.toml");
        Files.writeString(config, """
                [plugins]
                acme = { path = %s, sha256 = "%s" }

                [network
                """.formatted(MinimalToml.quote(jar.toString()), Hashing.sha256Hex(jar)));
        System.setProperty("jk.env.JK_HOME", requireNonNull(config.getParent()).toString());

        UserPluginPins.install();

        assertThat(PluginTableRegistry.byTable("zz-userpin-broken")).isEmpty();
    }

    @Test
    void matching_pin_installs_the_manifest(@TempDir Path tmp) throws Exception {
        Path jar = writePluginJar(tmp.resolve("acme.jar"));
        Path config = tmp.resolve("config.toml");
        Files.writeString(config, """
                [plugins]
                acme = { path = %s, sha256 = "%s" }
                """.formatted(MinimalToml.quote(jar.toString()), Hashing.sha256Hex(jar)));
        System.setProperty("jk.env.JK_HOME", requireNonNull(config.getParent()).toString());

        UserPluginPins.sync();

        assertThat(PluginTableRegistry.byTable("zz-userpin")).isPresent();
    }

    @Test
    void a_pin_added_while_the_engine_runs_validates_the_next_parse(@TempDir Path tmp) throws Exception {
        Path config = home(tmp);
        Files.writeString(config, "");
        UserPluginPins.install();
        Path manifest = project(tmp);
        assertThatThrownBy(() -> JkBuildParser.parseLocal(manifest)).isInstanceOf(JkBuildParseException.class);

        Path jar = writePluginJar(tmp.resolve("acme.jar"));
        writePin(config, jar, Hashing.sha256Hex(jar));

        assertThat(JkBuildParser.parseLocal(manifest)).isNotNull();
        assertThat(PluginTableRegistry.byTable("zz-userpin")).isPresent();
    }

    @Test
    void a_pin_removed_while_the_engine_runs_is_unregistered_and_the_shipped_plugin_returns(@TempDir Path tmp)
            throws Exception {
        PluginDescriptor shipped = PluginTableRegistry.manifests().getFirst();
        Path shippedArchive = PluginTableRegistry.archive(shipped.id());
        Path jar = writePluginJar(tmp.resolve("override.jar"), """
                [plugin]
                id      = "%s"
                table   = "%s"
                version = "99.0.0"
                """.formatted(shipped.id(), shipped.table()));
        Path config = home(tmp);
        writePin(config, jar, Hashing.sha256Hex(jar));
        UserPluginPins.install();
        assertThat(PluginTableRegistry.byTable(shipped.table()))
                .hasValueSatisfying(m -> assertThat(m.version()).isEqualTo("99.0.0"));
        assertThat(PluginTableRegistry.archive(shipped.id())).isEqualTo(jar);

        Files.writeString(config, "");
        PluginTableRegistry.syncUserPins();

        assertThat(PluginTableRegistry.byTable(shipped.table())).contains(shipped);
        assertThat(PluginTableRegistry.archive(shipped.id())).isEqualTo(shippedArchive);
    }

    @Test
    void a_config_broken_at_start_registers_its_pin_once_it_is_fixed(@TempDir Path tmp) throws Exception {
        Path jar = writePluginJar(tmp.resolve("acme.jar"));
        Path config = home(tmp);
        Files.writeString(config, "[network\n");
        UserPluginPins.install();
        assertThat(PluginTableRegistry.byTable("zz-userpin")).isEmpty();

        writePin(config, jar, Hashing.sha256Hex(jar));
        PluginTableRegistry.syncUserPins();

        assertThat(PluginTableRegistry.byTable("zz-userpin")).isPresent();
    }

    @Test
    void a_pin_edited_to_a_wrong_digest_fails_the_parse_and_keeps_the_registered_plugin(@TempDir Path tmp)
            throws Exception {
        Path jar = writePluginJar(tmp.resolve("acme.jar"));
        Path config = home(tmp);
        writePin(config, jar, Hashing.sha256Hex(jar));
        UserPluginPins.install();
        Path manifest = project(tmp);

        String declared = "ef".repeat(32);
        writePin(config, jar, declared);

        assertThatThrownBy(() -> JkBuildParser.parseLocal(manifest))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining(declared)
                .hasMessageContaining(config.toString());
        assertThat(PluginTableRegistry.byTable("zz-userpin")).isPresent();
    }

    @Test
    void a_pinned_jar_rebuilt_in_place_is_refused_on_the_next_parse_until_its_pin_matches(@TempDir Path tmp)
            throws Exception {
        Path jar = writePluginJar(tmp.resolve("acme.jar"));
        String pinned = Hashing.sha256Hex(jar);
        Path config = home(tmp);
        writePin(config, jar, pinned);
        UserPluginPins.install();
        Path manifest = project(tmp);
        assertThat(JkBuildParser.parseLocal(manifest)).isNotNull();

        writePluginJar(jar, MANIFEST.replace("1.0.0", "1.0.1-rebuilt"));
        String rebuilt = Hashing.sha256Hex(jar);

        assertThatThrownBy(() -> JkBuildParser.parseLocal(manifest))
                .isInstanceOf(JkBuildParseException.class)
                .hasMessageContaining(jar.toString())
                .hasMessageContaining(pinned)
                .hasMessageContaining(rebuilt);
        assertThat(PluginTableRegistry.byTable("zz-userpin"))
                .hasValueSatisfying(m -> assertThat(m.version()).isEqualTo("1.0.0"));

        writePin(config, jar, rebuilt);

        assertThat(JkBuildParser.parseLocal(manifest)).isNotNull();
        assertThat(PluginTableRegistry.byTable("zz-userpin"))
                .hasValueSatisfying(m -> assertThat(m.version()).isEqualTo("1.0.1-rebuilt"));
    }

    /** A jk home under {@code tmp}; its config.toml path. */
    private static Path home(Path tmp) throws Exception {
        Path home = Files.createDirectories(tmp.resolve("home"));
        System.setProperty("jk.env.JK_HOME", home.toString());
        return home.resolve("config.toml");
    }

    /** A module whose jk.toml configures the pinned plugin's table. */
    private static Path project(Path tmp) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("app"));
        return Files.writeString(dir.resolve("jk.toml"), """
                group   = "g"
                name    = "app"
                version = "1.0"
                java    = 25

                [zz-userpin]
                enabled = false
                """);
    }

    private static void writePin(Path config, Path jar, String sha256) throws Exception {
        Files.writeString(config, """
                [plugins]
                acme = { path = %s, sha256 = "%s" }
                """.formatted(MinimalToml.quote(jar.toString()), sha256));
    }

    private static Path writePluginJar(Path target) throws Exception {
        return writePluginJar(target, MANIFEST);
    }

    private static Path writePluginJar(Path target, String manifest) throws Exception {
        try (OutputStream out = Files.newOutputStream(target);
                ZipOutputStream zip = new ZipOutputStream(out)) {
            zip.putNextEntry(new ZipEntry("jk-plugin.toml"));
            zip.write(manifest.getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        return target;
    }
}
