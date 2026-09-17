// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.http.Http;
import cc.jumpkick.repo.EffectivePomBuilder;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.repo.RepoGroup;
import cc.jumpkick.resolver.pubgrub.Term;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.testing.MavenStub;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

class MavenPackageSourceTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp();

    private final MavenStub upstream = new MavenStub(http);

    @Test
    void versions_returns_highest_first(@TempDir Path tempDir) throws Exception {
        upstream.text("/com/foo/widget/maven-metadata.xml", """
                <metadata>
                  <groupId>com.foo</groupId>
                  <artifactId>widget</artifactId>
                  <versioning>
                    <versions>
                      <version>1.0</version>
                      <version>2.0</version>
                      <version>1.5</version>
                    </versions>
                  </versioning>
                </metadata>
                """);
        MavenPackageSource src = newSource(tempDir);
        assertThat(src.versions("com.foo:widget")).containsExactly("2.0", "1.5", "1.0");
    }

    @Test
    void dependencies_filters_to_compile_and_runtime(@TempDir Path tempDir) throws Exception {
        upstream.pomOnly("com.foo", "widget", "1.0", """
                <project>
                  <groupId>com.foo</groupId>
                  <artifactId>widget</artifactId>
                  <version>1.0</version>
                  <dependencies>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>a</artifactId><version>1.0</version>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>b</artifactId><version>1.0</version>
                      <scope>runtime</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>c</artifactId><version>1.0</version>
                      <scope>test</scope>
                    </dependency>
                    <dependency>
                      <groupId>com.foo</groupId><artifactId>d</artifactId><version>1.0</version>
                      <optional>true</optional>
                    </dependency>
                  </dependencies>
                </project>
                """);
        MavenPackageSource src = newSource(tempDir);
        assertThat(src.dependencies("com.foo:widget", "1.0"))
                .extracting(Term::pkg)
                .containsExactlyInAnyOrder("com.foo:a:jar:", "com.foo:b:jar:");
    }

    @Test
    void preferredVersion_lock_beats_bom(@TempDir Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        MavenRepo repo = new MavenRepo("local", http.base(), new Http(), cas);
        MavenPackageSource src = new MavenPackageSource(
                RepoGroup.of(repo),
                new EffectivePomBuilder(repo),
                Map.of("com.foo:widget", "1.0"),
                Map.of("com.foo:widget", "2.0"));
        assertThat(src.preferredVersion("com.foo:widget")).contains("2.0");
        assertThat(src.preferredVersion("com.foo:widget:jar:")).contains("2.0");
        assertThat(src.preferredVersion("com.other:lib")).isEmpty();
    }

    @Test
    void a_platform_pin_published_only_to_a_later_repository_is_walked_to(@TempDir Path tempDir) throws Exception {
        // The first repository's catalog ends the walk unless a version is asked for by name; the
        // version a BOM manages a package at is asked for by name.
        Path first = tempDir.resolve("first");
        Path second = tempDir.resolve("second");
        writeMetadata(first, "com.foo", "widget", "1.0");
        writeMetadata(second, "com.foo", "widget", "1.0", "2.0");
        Cas cas = new Cas(tempDir.resolve("cache"));
        MavenRepo a = new MavenRepo("first", first.toUri(), new Http(), cas);
        MavenRepo b = new MavenRepo("second", second.toUri(), new Http(), cas);
        RepoGroup repos = new RepoGroup(List.of(a, b));

        MavenPackageSource unpinned = new MavenPackageSource(repos, new EffectivePomBuilder(repos));
        assertThat(unpinned.versions("com.foo:widget:jar:"))
                .as("nothing asked for by name")
                .containsExactly("1.0");

        MavenPackageSource pinned =
                new MavenPackageSource(repos, new EffectivePomBuilder(repos), Map.of("com.foo:widget", "2.0"));
        assertThat(pinned.versions("com.foo:widget:jar:"))
                .as("the BOM pin is walked to")
                .contains("2.0");
    }

    private static void writeMetadata(Path repoDir, String group, String artifact, String... versions)
            throws Exception {
        Path dir = repoDir.resolve(group.replace('.', '/')).resolve(artifact);
        Files.createDirectories(dir);
        StringBuilder sb = new StringBuilder("<metadata><groupId>" + group + "</groupId><artifactId>" + artifact
                + "</artifactId><versioning><versions>");
        for (String v : versions) sb.append("<version>").append(v).append("</version>");
        sb.append("</versions></versioning></metadata>");
        Files.writeString(dir.resolve("maven-metadata.xml"), sb.toString());
    }

    private MavenPackageSource newSource(Path tempDir) {
        Cas cas = new Cas(tempDir.resolve("cache"));
        MavenRepo repo = new MavenRepo("local", http.base(), new Http(), cas);
        return new MavenPackageSource(repo, new EffectivePomBuilder(repo));
    }
}
