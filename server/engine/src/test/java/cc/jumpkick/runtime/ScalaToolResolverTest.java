// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.model.VersionSelector;
import cc.jumpkick.resolver.VersionSelectors;
import cc.jumpkick.resolver.pubgrub.VersionSet;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ScalaToolResolverTest {

    @Test
    void round_trips_a_recorded_closure(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cache"));
        List<String> shas = seedBlobs(cas, "alpha", "beta");
        Path cacheFile = dir.resolve("closure.shas");
        ScalaToolResolver.writeCachedClosure(cacheFile, shas);
        List<Path> jars = ScalaToolResolver.readCachedClosure(cacheFile, cas);
        assertThat(jars).hasSize(2);
        assertThat(jars.getFirst()).isEqualTo(cas.pathFor(shas.getFirst()));
    }

    @Test
    void evicted_blob_invalidates_the_whole_closure(@TempDir Path dir) throws IOException {
        Cas cas = new Cas(dir.resolve("cache"));
        List<String> shas = seedBlobs(cas, "one", "two");
        Path cacheFile = dir.resolve("closure.shas");
        ScalaToolResolver.writeCachedClosure(cacheFile, shas);
        Files.delete(cas.pathFor(shas.getFirst()));
        assertThat(ScalaToolResolver.readCachedClosure(cacheFile, cas)).isNull();
    }

    @Test
    void rejects_scala_2() {
        assertThatThrownBy(() -> ScalaToolResolver.requireSupportedVersion("2.13.16"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Scala 3");
        ScalaToolResolver.requireSupportedVersion("3.8.4");
        ScalaToolResolver.requireSupportedVersion("3.0.0");
    }

    @Test
    void pick_scala_version_prefers_stable_highest() {
        VersionSet set = VersionSelectors.toVersionSet(VersionSelector.parseFloating("3"));
        assertThat(LockPlans.pickScalaVersion(set, List.of("3.8.2", "3.8.4", "3.9.0-RC6", "2.13.16")))
                .isEqualTo("3.8.4");
        assertThat(LockPlans.pickScalaVersion(set, List.of("3.8.4", "3.8.4"))).isEqualTo("3.8.4");
    }

    @Test
    void exact_scala_selector_does_not_need_the_catalog() {
        cc.jumpkick.model.JkBuild build =
                cc.jumpkick.model.JkBuild.of(cc.jumpkick.model.JkBuild.Project.builder("com.example", "app", "1.0.0")
                        .scala(VersionSelector.parse("=3.8.4"))
                        .build());
        assertThat(LockPlans.resolveScalaVersion(build, null)).isEqualTo("3.8.4");
        cc.jumpkick.model.JkBuild javaOnly =
                cc.jumpkick.model.JkBuild.of(cc.jumpkick.model.JkBuild.Project.builder("com.example", "app", "1.0.0")
                        .java(25)
                        .build());
        assertThat(LockPlans.resolveScalaVersion(javaOnly, null)).isNull();
    }

    private static List<String> seedBlobs(Cas cas, String... contents) throws IOException {
        List<String> shas = new ArrayList<>();
        for (String c : contents) {
            Path p = cas.put(c.getBytes(StandardCharsets.UTF_8));
            shas.add(cas.hashFromPath(p).orElseThrow());
        }
        return shas;
    }
}
