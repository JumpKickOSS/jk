// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.config;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RepositoriesScanTest {

    @Test
    void reads_all_three_documented_shapes(@TempDir Path dir) throws Exception {
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, """
                name = "demo"

                [repositories]
                plain = "https://plain.example/maven2"
                inline = { url = "https://inline.example/", username = "bob", password = "s3cret" }

                [repositories.sectioned]
                url = "https://sectioned.example/"
                token = "tok-123"

                [dependencies]
                junit = "junit:junit:4.13"
                """);
        var repos = RepositoriesScan.scan(toml);
        assertThat(repos).hasSize(3);

        var plain = repos.get(0);
        assertThat(plain.name()).isEqualTo("plain");
        assertThat(plain.url()).isEqualTo("https://plain.example/maven2");
        assertThat(plain.credentialOpt()).isEmpty();

        var inline = repos.get(1);
        assertThat(inline.credentialOpt()).containsInstanceOf(RepoCredential.Basic.class);

        var sectioned = repos.get(2);
        assertThat(sectioned.name()).isEqualTo("sectioned");
        assertThat(sectioned.credentialOpt()).containsInstanceOf(RepoCredential.Bearer.class);
    }

    @Test
    void missing_file_and_missing_table_read_empty(@TempDir Path dir) throws Exception {
        assertThat(RepositoriesScan.scan(dir.resolve("nope.toml"))).isEmpty();
        Path toml = dir.resolve("jk.toml");
        Files.writeString(toml, "name = \"demo\"\n");
        assertThat(RepositoriesScan.scan(toml)).isEmpty();
    }
}
