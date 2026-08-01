// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.config.JkBuildParser;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * repos built for a real resolve must carry the HTTP client.
 *
 * <p>They did not. {@code RepoGroupBuilder} constructed every declared repository through the
 * transport-only constructor, which passes {@code null} for the client, and two HTTP-only features
 * switched off silently as a result: the {@code maven-metadata.xml} TTL + conditional-GET cache — which
 * also carries "reuse a stale copy rather than fail on 429" — and the {@code ~/.m2} probe. Only a test
 * pinning an override URL took the client-carrying path, so the metadata cache looked healthy in tests
 * while never running for an actual build.
 */
class RepoGroupBuilderHttpTest {

    @Test
    void every_http_repo_in_a_real_resolve_gets_a_metadata_cache(@TempDir Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "demo"
                name = "demo"
                version = "1.0.0"
                jdk = 25
                """);
        var project = JkBuildParser.parse(tmp.resolve("jk.toml"));

        var group = RepoGroupBuilder.buildFor(project, null, new Cas(tmp.resolve("store")));

        assertThat(group.repos()).isNotEmpty();
        assertThat(group.repos()).allSatisfy(repo -> assertThat(repo.hasMetadataCache())
                .as("%s must carry the HTTP client, or its metadata cache and ~/.m2 probe are dead", repo.name())
                .isTrue());
    }
}
