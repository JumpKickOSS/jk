// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.ProtocolWriter;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.SpecWriter;
import cc.jumpkick.plugin.publish.testkit.GpgTestFixture;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises the terminal {@link cc.jumpkick.plugin.build.PublishExtension} path end-to-end in
 * dry-run mode (no network): the plugin spec is parsed, mapped onto a {@code PublishContext}, and
 * {@link Publisher#publish} assembles the artifacts and reports them — proving the spec→config
 * roundtrip without a live repository.
 */
class PublisherDryRunTest {

    @Test
    void dry_run_assembles_artifacts_and_reports_them(@TempDir Path dir) throws Exception {
        // A minimal, parseable project + a stand-in jar.
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "widget"
                version = "1.2.3"
                """);
        Path jar = dir.resolve("widget-1.2.3.jar");
        Files.write(jar, new byte[] {0x50, 0x4b, 0x05, 0x06}); // empty-zip signature

        Path spec = dir.resolve("publish.spec");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_PUBLISH, null, "jk-publisher")
                        .configString("repoUrl", "https://repo.example.com/")
                        .configString("repoAuthType", "anonymous")
                        .configBool("dryRun", true)
                        .artifact(jar)
                        .layout(Map.of("moduleDir", dir))
                        .lines());

        var buffer = new ByteArrayOutputStream();
        ProtocolWriter out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKPU:");
        int exit = new Publisher().run(List.of(spec.toString()), out);

        assertThat(exit).isZero();
        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(output).contains("\"t\":\"result\"").contains("\"dry_run\":true");
        // jar + pom at minimum (sources/slsa/sbom off) → files >= 2.
        assertThat(output).containsPattern("\"files\":[2-9]");
        assertThat(output).doesNotContain("\"written\"");
    }

    @Test
    void a_member_publishes_under_a_root_that_declares_a_tagged_suite(@TempDir Path ws) throws Exception {
        Files.writeString(ws.resolve("jk.toml"), """
                group   = "com.example"
                name    = "root"
                version = "1.2.3"

                [workspace]
                modules = ["core", "widget"]

                [test.suites.integration]
                tags = ["integration"]
                """);
        Files.writeString(Files.createDirectories(ws.resolve("core")).resolve("jk.toml"), "name = \"core\"\n");
        Path dir = Files.createDirectories(ws.resolve("widget"));
        Files.writeString(dir.resolve("jk.toml"), """
                name = "widget"

                [dependencies]
                core.workspace = true
                """);
        Path jar = dir.resolve("widget-1.2.3.jar");
        Files.write(jar, new byte[] {0x50, 0x4b, 0x05, 0x06});

        Path spec = dir.resolve("publish.spec");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_PUBLISH, null, "jk-publisher")
                        .configString("repoAuthType", "anonymous")
                        .configBool("dryRun", true)
                        .artifact(jar)
                        .layout(Map.of("moduleDir", dir))
                        .lines());

        var buffer = new ByteArrayOutputStream();
        ProtocolWriter out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKPU:");
        int exit = new Publisher().run(List.of(spec.toString()), out);

        String output = buffer.toString(StandardCharsets.UTF_8);
        assertThat(exit).as(output).isZero();
        assertThat(output).contains("\"dry_run\":true");
        assertThat(Files.readString(dir.resolve("target/publish/widget-1.2.3.pom")))
                .contains("<groupId>com.example</groupId>")
                .contains("<artifactId>core</artifactId>")
                .doesNotContain("workspace");
    }

    @Test
    void dry_run_with_sbom_writes_both_documents_under_target_and_names_them(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "widget"
                version = "1.2.3"
                """);
        Path jar = dir.resolve("widget-1.2.3.jar");
        Files.write(jar, new byte[] {0x50, 0x4b, 0x05, 0x06});

        // No repository URL: a dry run publishes nowhere, so it needs none.
        Path spec = dir.resolve("publish.spec");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_PUBLISH, null, "jk-publisher")
                        .configString("repoAuthType", "anonymous")
                        .configBool("dryRun", true)
                        .configBool("sbom", true)
                        .artifact(jar)
                        .layout(Map.of("moduleDir", dir))
                        .lines());

        var buffer = new ByteArrayOutputStream();
        ProtocolWriter out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKPU:");
        int exit = new Publisher().run(List.of(spec.toString()), out);

        assertThat(exit).isZero();
        Path cdx = dir.resolve("target/sbom/widget-1.2.3.cdx.json");
        Path spdx = dir.resolve("target/sbom/widget-1.2.3.spdx.json");
        assertThat(cdx).exists();
        assertThat(spdx).exists();
        assertThat(Files.readString(cdx))
                .contains("\"specVersion\": \"1.6\"")
                .contains("pkg:maven/com.example/widget@1.2.3");
        assertThat(Files.readString(spdx)).contains("\"spdxVersion\":\"SPDX-2.3\"");
        String output = buffer.toString(StandardCharsets.UTF_8);
        // JSON quotes the path; a Windows backslash is two chars on the wire.
        assertThat(output)
                .contains("\"written\":[")
                .contains(Jsonl.quote(cdx.toString()))
                .contains(Jsonl.quote(spdx.toString()));
    }

    @Test
    void a_signed_dry_run_wraps_the_provenance_in_an_envelope_its_key_verifies(@TempDir Path dir) throws Exception {
        var key = GpgTestFixture.generate(Files.createDirectories(dir.resolve("key")), "pass");
        Path out = slsaDryRun(dir, spec -> spec.configBool("signGpg", true)
                .configString("gpgKeyFile", key.secretKeyFile().toString())
                .secret("gpgPassphrase", "pass"));

        Path envelope = out.resolve("widget-1.2.3.intoto.jsonl");
        assertThat(out.resolve("widget-1.2.3.intoto.json")).doesNotExist();
        String statement = new String(
                GpgTestFixture.verifyEnvelope(Files.readAllBytes(envelope), key.publicRing()), StandardCharsets.UTF_8);
        assertThat(statement)
                .contains("\"predicateType\":\"https://slsa.dev/provenance/v1\"")
                .contains("\"name\":\"widget-1.2.3.jar\"");
    }

    @Test
    void an_unsigned_dry_run_writes_the_bare_statement(@TempDir Path dir) throws Exception {
        Path out = slsaDryRun(dir, spec -> spec);

        assertThat(out.resolve("widget-1.2.3.intoto.jsonl")).doesNotExist();
        assertThat(Files.readString(out.resolve("widget-1.2.3.intoto.json")))
                .startsWith("{\"_type\":\"https://in-toto.io/Statement/v1\"");
    }

    /** A {@code --slsa --dry-run} of a stand-in project; returns the directory the provenance is written to. */
    private static Path slsaDryRun(Path dir, UnaryOperator<SpecWriter> signing) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "widget"
                version = "1.2.3"
                """);
        Path jar = dir.resolve("widget-1.2.3.jar");
        Files.write(jar, new byte[] {0x50, 0x4b, 0x05, 0x06});
        Path spec = dir.resolve("publish.spec");
        Files.write(
                spec,
                signing.apply(new SpecWriter()
                                .op(PluginProtocol.OP_PUBLISH, null, "jk-publisher")
                                .configString("repoAuthType", "anonymous")
                                .configBool("dryRun", true)
                                .configBool("slsa", true)
                                .artifact(jar)
                                .layout(Map.of("moduleDir", dir)))
                        .lines());

        var buffer = new ByteArrayOutputStream();
        ProtocolWriter out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKPU:");
        assertThat(new Publisher().run(List.of(spec.toString()), out))
                .as(buffer.toString(StandardCharsets.UTF_8))
                .isZero();
        return dir.resolve("target/publish");
    }

    @Test
    void central_dry_run_for_assembly_app_ships_thin_jar_and_no_fat_jar(@TempDir Path dir) throws Exception {
        Map<String, byte[]> entries = centralAssemblyDryRun(dir);
        String stem = "com/example/cli/1.0.0/cli-1.0.0";

        assertThat(entries.keySet())
                .hasSize(16)
                .contains(stem + ".jar", stem + ".pom", stem + "-sources.jar", stem + "-javadoc.jar")
                .noneMatch(e -> e.contains("-all.jar"));
        assertThat(entries.get(stem + ".jar")).isEqualTo("THIN".getBytes(StandardCharsets.UTF_8));
        assertThat(new String(entries.get(stem + ".pom"), StandardCharsets.UTF_8))
                .contains("<artifactId>slf4j-api</artifactId>");
    }

    private static Map<String, byte[]> centralAssemblyDryRun(Path dir) throws Exception {
        var key = GpgTestFixture.generate(Files.createDirectories(dir.resolve("key")), "pass");
        Files.writeString(dir.resolve("jk.toml"), """
                group       = "com.example"
                name        = "cli"
                version     = "1.0.0"
                description = "A CLI"

                [application]
                main     = "com.example.Main"
                assembly = true

                [dependencies]
                slf4j-api = { group = "org.slf4j", name = "slf4j-api", version = "2.0.17" }

                [publish]
                url = "https://example.com/cli"
                licenses = [{ name = "Apache-2.0", url = "https://www.apache.org/licenses/LICENSE-2.0" }]
                developers = [{ id = "ada", name = "Ada Lovelace" }]
                scm = { url = "https://github.com/example/cli", connection = "scm:git:https://github.com/example/cli.git", developer-connection = "scm:git:ssh://git@github.com/example/cli.git" }
                """);
        Path target = Files.createDirectories(dir.resolve("target"));
        Path thin = Files.writeString(target.resolve("cli-1.0.0.jar"), "THIN");
        Files.writeString(target.resolve("cli-1.0.0-all.jar"), "FAT-ASSEMBLY");
        Files.writeString(target.resolve("cli-1.0.0-sources.jar"), "SOURCES");
        Files.writeString(target.resolve("cli-1.0.0-javadoc.jar"), "JAVADOC");

        Path spec = dir.resolve("publish.spec");
        Files.write(
                spec,
                new SpecWriter()
                        .op(PluginProtocol.OP_PUBLISH, null, "jk-publisher")
                        .configString("repoAuthType", "anonymous")
                        .configBool("dryRun", true)
                        .configBool("centralPortal", true)
                        .configBool("signGpg", true)
                        .configString("gpgKeyFile", key.secretKeyFile().toString())
                        .secret("gpgPassphrase", "pass")
                        .artifact(thin)
                        .layout(Map.of("moduleDir", dir))
                        .lines());

        var buffer = new ByteArrayOutputStream();
        ProtocolWriter out = new ProtocolWriter(new PrintStream(buffer, true, StandardCharsets.UTF_8), "##JKPU:");
        assertThat(new Publisher().run(List.of(spec.toString()), out))
                .as(buffer.toString(StandardCharsets.UTF_8))
                .isZero();

        Path zip = dir.resolve("target/publish/central-bundle.zip");
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(Files.readAllBytes(zip)))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                entries.put(e.getName(), in.readAllBytes());
            }
        }
        return entries;
    }
}
