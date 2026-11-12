// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.changes.Change;
import cc.jumpkick.changes.ChangeActor;
import cc.jumpkick.changes.ChangeJournal;
import cc.jumpkick.config.Request;
import cc.jumpkick.config.UserConfigEditor;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectImportCredentialsTest {

    @Test
    void a_credential_an_import_adds_is_journaled_as_the_import_s_doing(@TempDir Path dir) throws Exception {
        Path config = dir.resolve("config.toml");
        Path journal = dir.resolve("changes");
        Path pom = dir.resolve("shop/pom.xml");
        var table = new UserConfigEditor.CredentialTable(
                "kafka-helper",
                URI.create("https://registry.example.com"),
                Map.of("username", "${env:REGISTRY_KEY}", "password", "${env:REGISTRY_SECRET}"));
        ChangeActor agent = ChangeActor.agent("s9", "Claude Code", "import the shop", "import");

        List<Path> wrote = new ArrayList<>();
        ChangeJournal.into(
                journal,
                () -> Request.actingAs(
                        agent,
                        () -> ProjectImport.machineCredentials(
                                ImportReport.builder().build(), List.of(table), config, wrote, pom)));

        assertThat(wrote).containsExactly(config);
        assertThat(Files.readString(config)).contains("${env:REGISTRY_SECRET}");
        Change entry = new ChangeJournal(journal).page(null, 10).entries().getFirst();
        assertThat(entry.action()).isEqualTo(Change.EDIT);
        assertThat(entry.diff()).contains("+[repositories.kafka-helper]").doesNotContain("REGISTRY_SECRET");
        assertThat(entry.actor().kind()).isEqualTo(ChangeActor.IMPORT);
        assertThat(entry.actor().details())
                .containsEntry("source", pom.toAbsolutePath().normalize().toString())
                .containsEntry("byKind", ChangeActor.AGENT)
                .containsEntry("client", "Claude Code");
    }
}
