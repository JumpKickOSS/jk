// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.plugin.publish;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.plugin.protocol.PluginProtocol;
import cc.jumpkick.plugin.protocol.ProtocolWriter;
import cc.jumpkick.plugin.protocol.SpecWriter;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
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
}
