// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.credential.RepoCredential;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.GradleModuleMetadata;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * A module's Gradle metadata is asked of the repository that served its POM and of no other: the
 * {@code .module} sits beside the POM wherever Gradle published it, so a multi-repository walk pays
 * one request for it rather than one 404 per repository that never publishes module metadata.
 */
class KmpRedirectsModuleSourceTest {

    private static final String GROUP = "com.example.kmpdemo";
    private static final String POM = MavenStub.path(GROUP, "widget", "1.0.0", ".pom");
    private static final String MODULE = MavenStub.path(GROUP, "widget", "1.0.0", ".module");

    /** A repository that publishes no Gradle module metadata and holds none of the widget. */
    @RegisterExtension
    final LoopbackHttp plain = new LoopbackHttp();

    /** The repository the widget was published to, POM and {@code .module} together. */
    @RegisterExtension
    final LoopbackHttp home = new LoopbackHttp();

    @BeforeEach
    void clear() {
        KmpRedirects.clearProcessCache();
        GradleModuleMetadata.clearParseCache();
        RepoGroup.clearProcessFetchCache();
    }

    @Test
    void the_module_file_is_asked_only_of_the_repository_that_served_the_pom(@TempDir Path tmp) throws Exception {
        MavenStub stub = new MavenStub(home);
        stub.text(
                POM,
                "<?xml version=\"1.0\"?>\n" + GradleModuleMetadata.POM_MARKER + "\n"
                        + MavenStub.emptyPom(GROUP, "widget", "1.0.0"));
        stub.text(MODULE, module());
        Cas cas = new Cas(tmp);
        RepoGroup repos = new RepoGroup(List.of(
                new MavenRepo("plain", plain.base(), new Http(), cas, RepoCredential.ANONYMOUS, false),
                new MavenRepo("home", home.base(), new Http(), cas, RepoCredential.ANONYMOUS, false)));

        var selection = new KmpRedirects(repos, "standard-jvm").selectionFor(GROUP + ":widget", "1.0.0");

        assertThat(selection).isPresent();
        assertThat(selection.get().target().module()).isEqualTo("widget-jvm");
        assertThat(home.requestsFor(MODULE)).isEqualTo(1);
        assertThat(plain.requestsFor(POM))
                .as("the POM walk asks every repository in order")
                .isEqualTo(1);
        assertThat(plain.requestsFor(MODULE))
                .as("a repository that did not serve the POM is never asked for the .module")
                .isZero();
    }

    private static String module() {
        return """
                {
                  "formatVersion": "1.1",
                  "component": { "group": "%s", "module": "widget", "version": "1.0.0" },
                  "variants": [
                    {
                      "name": "jvmRuntimeElements-published",
                      "attributes": {
                        "org.gradle.usage": "java-runtime",
                        "org.gradle.category": "library",
                        "org.gradle.jvm.environment": "standard-jvm"
                      },
                      "available-at": {
                        "url": "../../widget-jvm/1.0.0/widget-jvm-1.0.0.module",
                        "group": "%s",
                        "module": "widget-jvm",
                        "version": "1.0.0"
                      }
                    }
                  ]
                }
                """.formatted(GROUP, GROUP);
    }
}
