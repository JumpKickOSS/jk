// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.verbs;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.wire.runtime.TailArgs;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** An agent's publish dry run names the URL the project's [publish] repository writes to. */
class UploadTailTest {

    @Test
    void a_dry_run_names_the_publish_url_and_carries_no_credential(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"

                [repositories.acme]
                url         = "https://maven.acme.dev/"
                publish-url = "https://nexus.acme.dev/repository/hosted/"

                [publish]
                repository = "acme"
                """);

        TailArgs.Publish tail = UploadTail.dryRun(dir);

        assertThat(tail.repoUrl()).isEqualTo(URI.create("https://nexus.acme.dev/repository/hosted/"));
        assertThat(tail.dryRun()).isTrue();
        assertThat(tail.credential()).isEqualTo(RepoCredential.ANONYMOUS);
    }

    @Test
    void a_dry_run_with_no_repository_names_none(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "com.example"
                name    = "lib"
                version = "1.0.0"
                """);

        assertThat(UploadTail.dryRun(dir).repoUrl()).isNull();
    }
}
