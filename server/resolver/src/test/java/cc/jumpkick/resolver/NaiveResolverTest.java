// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class NaiveResolverTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    @Test
    void single_root_with_no_transitives(@TempDir Path tempDir) throws Exception {
        registerPom("com.foo", "leaf", "1.0", project("com.foo", "leaf", "1.0", ""));

        Resolution result = resolver(tempDir).resolve(List.of(dep("com.foo:leaf", "1.0")));

        assertThat(result.modules()).containsOnlyKeys("com.foo:leaf");
        assertThat(requireNonNull(result.modules().get("com.foo:leaf")).version())
                .isEqualTo("1.0");
    }

    @Test
    void follows_transitives(@TempDir Path tempDir) throws Exception {
        registerPom("com.foo", "leaf", "1.0", project("com.foo", "leaf", "1.0", ""));
        registerPom("com.foo", "root", "1.0", project("com.foo", "root", "1.0", dep("com.foo", "leaf", "1.0", null)));

        Resolution result = resolver(tempDir).resolve(List.of(dep("com.foo:root", "1.0")));

        assertThat(result.modules()).containsOnlyKeys("com.foo:leaf", "com.foo:root");
    }

    @Test
    void picks_highest_version_on_conflict(@TempDir Path tempDir) throws Exception {
        // root depends on libA-1.0 (which depends on shared-1.0)
        // and libB-1.0 (which depends on shared-2.0).
        registerPom("com.foo", "shared", "1.0", project("com.foo", "shared", "1.0", ""));
        registerPom("com.foo", "shared", "2.0", project("com.foo", "shared", "2.0", ""));
        registerPom("com.foo", "libA", "1.0", project("com.foo", "libA", "1.0", dep("com.foo", "shared", "1.0", null)));
        registerPom("com.foo", "libB", "1.0", project("com.foo", "libB", "1.0", dep("com.foo", "shared", "2.0", null)));
        registerPom(
                "com.foo",
                "root",
                "1.0",
                project(
                        "com.foo",
                        "root",
                        "1.0",
                        dep("com.foo", "libA", "1.0", null) + dep("com.foo", "libB", "1.0", null)));

        Resolution result = resolver(tempDir).resolve(List.of(dep("com.foo:root", "1.0")));
        assertThat(requireNonNull(result.modules().get("com.foo:shared")).version())
                .isEqualTo("2.0");
    }

    @Test
    void test_scope_does_not_leak_from_transitive(@TempDir Path tempDir) throws Exception {
        registerPom("com.foo", "leaf-test", "1.0", project("com.foo", "leaf-test", "1.0", ""));
        registerPom("com.foo", "leaf-compile", "1.0", project("com.foo", "leaf-compile", "1.0", ""));
        registerPom(
                "com.foo",
                "lib",
                "1.0",
                project(
                        "com.foo",
                        "lib",
                        "1.0",
                        dep("com.foo", "leaf-test", "1.0", "test") + dep("com.foo", "leaf-compile", "1.0", null)));

        Resolution result = resolver(tempDir).resolve(List.of(dep("com.foo:lib", "1.0")));

        assertThat(result.modules())
                .containsKeys("com.foo:lib", "com.foo:leaf-compile")
                .doesNotContainKey("com.foo:leaf-test");
    }

    @Test
    void optional_transitives_are_skipped(@TempDir Path tempDir) throws Exception {
        registerPom("com.foo", "optional-leaf", "1.0", project("com.foo", "optional-leaf", "1.0", ""));
        registerPom("com.foo", "lib", "1.0", project("com.foo", "lib", "1.0", """
                <dependency>
                  <groupId>com.foo</groupId>
                  <artifactId>optional-leaf</artifactId>
                  <version>1.0</version>
                  <optional>true</optional>
                </dependency>
                """));

        Resolution result = resolver(tempDir).resolve(List.of(dep("com.foo:lib", "1.0")));
        assertThat(result.modules()).doesNotContainKey("com.foo:optional-leaf");
    }

    // --- helpers -----------------------------------------------------------

    private Resolver resolver(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        MavenRepo repo = new MavenRepo("local", http.base(), new Http(), cas);
        return new NaiveResolver(new EffectivePomBuilder(repo));
    }

    private void registerPom(String group, String artifact, String version, String body) {
        String path = "/"
                + group.replace('.', '/')
                + "/"
                + artifact
                + "/"
                + version
                + "/"
                + artifact
                + "-"
                + version
                + ".pom";
        http.served().put(path, body.getBytes(StandardCharsets.UTF_8));
    }

    private static String project(String group, String artifact, String version, String depBodies) {
        return """
                <project>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  <dependencies>
                  %s
                  </dependencies>
                </project>
                """.formatted(group, artifact, version, depBodies);
    }

    private static String dep(String group, String artifact, String version, @Nullable String scope) {
        String scopeElement = scope == null ? "" : "<scope>" + scope + "</scope>";
        return """
                <dependency>
                  <groupId>%s</groupId>
                  <artifactId>%s</artifactId>
                  <version>%s</version>
                  %s
                </dependency>
                """.formatted(group, artifact, version, scopeElement);
    }

    private static Dependency dep(String module, String version) {
        return new Dependency(module, new VersionSelector.Exact("=" + version, version));
    }
}
