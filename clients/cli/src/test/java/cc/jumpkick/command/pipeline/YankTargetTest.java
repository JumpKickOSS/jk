// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.pipeline;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.model.ObjectStoreConfig;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class YankTargetTest {

    @TempDir
    Path dir;

    @Test
    void maven_central_by_name_is_refused() {
        var resolved = PublishTarget.yank("central", dir.resolve("jk.toml"), null);

        assertThat(resolved.target()).isNull();
        assertThat(resolved.refusal()).isEqualTo(PublishTarget.CENTRAL_YANK);
    }

    @Test
    void maven_central_by_url_is_refused() {
        for (String url : new String[] {"https://repo.maven.apache.org/maven2/", "https://repo1.maven.org/maven2/"}) {
            var resolved = PublishTarget.yank("", dir.resolve("jk.toml"), url);

            assertThat(resolved.refusal()).as(url).isEqualTo(PublishTarget.CENTRAL_YANK);
        }
    }

    @Test
    void no_repository_is_refused_with_the_fix() {
        assertThat(PublishTarget.yank("", dir.resolve("jk.toml"), null).refusal())
                .startsWith("no repository to yank from");
    }

    @Test
    void an_undeclared_name_is_refused() {
        assertThat(PublishTarget.yank("", dir.resolve("jk.toml"), "nowhere").refusal())
                .contains("`nowhere`");
    }

    @Test
    void a_declared_repository_yanks_at_its_publish_url_with_its_region_and_endpoint() throws Exception {
        Path manifest = Files.writeString(dir.resolve("jk.toml"), """
                [repositories.acme]
                url = "https://maven.acme.dev/"
                publish-url = "s3://acme-maven/releases"
                region = "eu-west-1"
                endpoint = "http://127.0.0.1:9000"
                """);

        URI target = requireNonNull(
                requireNonNull(PublishTarget.yank("", manifest, "acme").target())
                        .repoUrl());

        assertThat(target).isEqualTo(URI.create("s3://acme-maven/releases"));
        assertThat(PublishTarget.objectStore(manifest, target))
                .isEqualTo(new ObjectStoreConfig("eu-west-1", "http://127.0.0.1:9000", null, null, null));
    }
}
