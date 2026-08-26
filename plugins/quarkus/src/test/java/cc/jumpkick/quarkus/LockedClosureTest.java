// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.quarkus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The lockfile-is-law half of the augment: which artifacts reach Quarkus's application model, at
 * which versions, out of which jars — and what happens when the bootstrap's own Aether disagrees.
 */
class LockedClosureTest {

    private static LockedClosure.Resolved resolved(String group, String artifact, String version) {
        return new LockedClosure.Resolved(group, artifact, "", version);
    }

    private static LockedClosure.Artifact locked(String group, String artifact, String version, Path jar) {
        return new LockedClosure.Artifact(group, artifact, version, jar, false);
    }

    @Test
    void parse_keeps_the_whole_closure_not_the_jars_that_look_like_extensions(@TempDir Path dir) throws Exception {
        List<String> lines = new ArrayList<>();
        lines.add("io.quarkus:quarkus-core:3.38.0\t" + dir.resolve("quarkus-core-3.38.0.jar"));
        lines.add("io.quarkus:quarkus-arc:3.38.0\t" + dir.resolve("quarkus-arc-3.38.0.jar"));
        // 40 plain transitives: no quarkus-extension.properties, no io.quarkus group. The augment
        // used to keep at most 30 extension-looking jars and let Maven re-resolve the rest.
        for (int i = 0; i < 40; i++) {
            lines.add("org.plain:lib" + i + ":1." + i + "\t" + dir.resolve("lib" + i + "-1." + i + ".jar"));
        }
        Path tsv = Files.write(dir.resolve("runtime-jars.tsv"), lines);

        LockedClosure closure = LockedClosure.parse(tsv);

        assertThat(closure.artifacts()).hasSize(42);
        assertThat(closure.artifacts())
                .extracting(LockedClosure.Artifact::coords)
                .contains("org.plain:lib39:1.39");
        assertThat(closure.byGa("org.plain:lib39")).isPresent();
    }

    @Test
    void the_lock_pins_a_transitive_maven_would_have_resolved_differently(@TempDir Path dir) {
        Path pinnedJar = dir.resolve("store/repos/central/com/example/transitive/2.4.0/transitive-2.4.0.jar");
        LockedClosure closure = LockedClosure.of(List.of(
                locked("org.acme", "app-lib", "1.0", dir.resolve("app-lib-1.0.jar")),
                locked("com.example", "transitive", "2.4.0", pinnedJar)));

        // What the platform BOM's managed version / nearest-wins would have produced instead.
        LockedClosure.Plan plan = closure.plan(
                List.of(resolved("org.acme", "app-lib", "1.0"), resolved("com.example", "transitive", "3.1.0")));

        assertThat(plan.pins()).allMatch(LockedClosure.Pin::ship);
        assertThat(plan.pins()).extracting(LockedClosure.Pin::version).containsExactly("1.0", "2.4.0");
        assertThat(plan.pins().get(1).jar()).isEqualTo(pinnedJar);
        assertThat(plan.unlocked()).isEmpty();
        assertThat(plan.unresolved()).isEmpty();
        // The disagreement is stated, never silent.
        assertThat(plan.overrides()).containsExactly("com.example:transitive: maven picked 3.1.0, lock pins 2.4.0");
    }

    @Test
    void a_runtime_artifact_the_lock_does_not_name_does_not_ship(@TempDir Path dir) {
        LockedClosure closure = LockedClosure.of(List.of(locked("org.acme", "app-lib", "1.0", dir.resolve("a.jar"))));

        // An OS-activated Maven profile is the usual source: the same lock would otherwise produce
        // a different application on a different host.
        LockedClosure.Plan plan = closure.plan(
                List.of(resolved("org.acme", "app-lib", "1.0"), resolved("com.brotli", "native-linux-x86_64", "1.23")));

        assertThat(plan.pins()).extracting(LockedClosure.Pin::ship).containsExactly(true, false);
        assertThat(plan.unlocked()).containsExactly("com.brotli:native-linux-x86_64:1.23");
    }

    @Test
    void a_locked_artifact_the_framework_excluded_is_reported_not_reinstated(@TempDir Path dir) {
        LockedClosure closure = LockedClosure.of(List.of(
                locked("org.acme", "app-lib", "1.0", dir.resolve("a.jar")),
                locked("io.quarkus", "quarkus-ide-launcher", "3.38.3", dir.resolve("ide.jar"))));

        LockedClosure.Plan plan = closure.plan(List.of(resolved("org.acme", "app-lib", "1.0")));

        assertThat(plan.pins()).hasSize(1);
        assertThat(plan.unresolved()).containsExactly("io.quarkus:quarkus-ide-launcher:3.38.3");
    }

    @Test
    void classified_variants_take_the_locked_version_but_keep_their_own_file(@TempDir Path dir) {
        LockedClosure closure = LockedClosure.of(
                List.of(locked("io.netty", "netty-transport-native-epoll", "4.2.0", dir.resolve("epoll-4.2.0.jar"))));

        LockedClosure.Plan plan = closure.plan(List.of(
                new LockedClosure.Resolved("io.netty", "netty-transport-native-epoll", "linux-x86_64", "4.1.9"),
                new LockedClosure.Resolved("io.netty", "netty-transport-native-epoll", "linux-aarch_64", "4.1.9")));

        assertThat(plan.pins()).extracting(LockedClosure.Pin::version).containsExactly("4.2.0", "4.2.0");
        // One locked jar cannot answer for two classifiers, so neither variant is repointed.
        assertThat(plan.pins()).extracting(LockedClosure.Pin::jar).containsOnlyNulls();
        assertThat(plan.overrides()).hasSize(1);
    }

    @Test
    void workspace_jars_get_a_coordinate_synthesized_from_their_file_name(@TempDir Path dir) throws Exception {
        Path sibling = dir.resolve("out/domain-0.3.1.jar");
        Path tsv = Files.write(
                dir.resolve("runtime-jars.tsv"),
                List.of(
                        "unknown:unknown:0\t" + sibling,
                        "org.acme:app-lib:1.0\t" + dir.resolve("store/repos/central/app-lib-1.0.jar")));

        LockedClosure closure = LockedClosure.parse(tsv);

        LockedClosure.Artifact synthesized = closure.artifacts().get(0);
        assertThat(synthesized.coords()).isEqualTo("jk.workspace:domain:0.3.1");
        assertThat(synthesized.workspace()).isTrue();
        assertThat(synthesized.jar()).isEqualTo(sibling);
        assertThat(closure.artifacts().get(1).workspace()).isFalse();
    }

    @Test
    void one_coordinate_named_twice_is_refused_rather_than_guessed(@TempDir Path dir) {
        List<LockedClosure.Artifact> twice = List.of(
                locked("io.netty", "netty-transport-native-epoll", "4.2.0", dir.resolve("plain.jar")),
                locked("io.netty", "netty-transport-native-epoll", "4.2.0", dir.resolve("epoll.jar")));

        assertThatThrownBy(() -> LockedClosure.of(twice))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("netty-transport-native-epoll")
                .hasMessageContaining("ambiguous");
    }

    @Test
    void mirror_roots_come_from_the_locked_jars_own_store(@TempDir Path dir) throws Exception {
        Path store = dir.resolve("store");
        Files.createDirectories(store.resolve("repos/central/org/acme"));
        Files.createDirectories(store.resolve("repos/jk-local"));
        Path jar = store.resolve("repos/central/org/acme/app-lib-1.0.jar");

        LockedClosure closure = LockedClosure.of(List.of(locked("org.acme", "app-lib", "1.0", jar)));

        assertThat(closure.mirrorRepoRoots())
                .containsExactly(store.resolve("repos/central"), store.resolve("repos/jk-local"));
    }
}
