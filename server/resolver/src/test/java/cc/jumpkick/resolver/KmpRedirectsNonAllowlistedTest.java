// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * KMP discovery must not depend on a hard-coded group allowlist — any POM with the Gradle
 * metadata marker + {@code available-at} redirect is authoritative.
 */
class KmpRedirectsNonAllowlistedTest {

    @BeforeEach
    void clear() {
        KmpRedirects.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void redirects_outside_historical_allowlist(@TempDir Path tmp) throws Exception {
        // Fictional multiplatform group that was never on the old allowlist.
        Path repo = tmp.resolve("repo");
        String g = "com.example.kmpdemo";
        String a = "widget";
        String v = "1.0.0";
        Path base = repo.resolve(g.replace('.', '/')).resolve(a).resolve(v);
        Files.createDirectories(base);
        String pom = """
                <?xml version="1.0"?>
                <!-- do_not_remove: published-with-gradle-metadata -->
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                </project>
                """.formatted(g, a, v);
        Files.writeString(base.resolve(a + "-" + v + ".pom"), pom);
        String module = """
                {
                  "formatVersion": "1.1",
                  "component": { "group": "%s", "module": "%s", "version": "%s" },
                  "variants": [
                    {
                      "name": "jvmRuntimeElements",
                      "attributes": {
                        "org.gradle.usage": "java-runtime",
                        "org.gradle.category": "library",
                        "org.gradle.jvm.environment": "standard-jvm"
                      },
                      "available-at": {
                        "url": "../../widget-jvm/1.0.0/widget-jvm-1.0.0.module",
                        "group": "%s",
                        "module": "widget-jvm",
                        "version": "%s"
                      }
                    }
                  ]
                }
                """.formatted(g, a, v, g, v);
        Files.writeString(base.resolve(a + "-" + v + ".module"), module, StandardCharsets.UTF_8);

        Cas cas = new Cas(tmp.resolve("cas"));
        RepoGroup repos = new RepoGroup(List.of(new MavenRepo("local", repo.toUri(), new Http(), cas)));
        KmpRedirects kmp = new KmpRedirects(repos, "standard-jvm");

        var sel = kmp.selectionFor(g + ":" + a, v);
        assertThat(sel).isPresent();
        assertThat(sel.get().target().module()).isEqualTo("widget-jvm");
    }
}
