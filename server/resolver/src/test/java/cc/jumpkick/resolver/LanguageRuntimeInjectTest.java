// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * the language-runtime inject must key on the same inference the engine's lanes use
 * an unpinned project with {@code src/main/groovy} compiles the groovy lane, so its runtime must
 * land in the lock (jk run / packaging read the lock only).
 */
class LanguageRuntimeInjectTest {

    private static JkBuild project(String toml) {
        return JkBuildParser.parse(toml);
    }

    @Test
    void inferred_groovy_without_pin_injects_the_runtime(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.writeString(dir.resolve("src/main/groovy/A.groovy"), "class A {}");
        JkBuild p = project("[project]\ngroup=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        LockOrchestrator.injectLanguageRuntimes(p, dir, java.util.Map.of(), deps);
        assertThat(deps).containsKey("org.apache.groovy:groovy");
        assertThat(deps).doesNotContainKey("org.jetbrains.kotlin:kotlin-stdlib");
    }

    @Test
    void explicit_java_release_disables_inference_like_the_lanes(@TempDir Path dir) throws IOException {
        // Mirrors BuildPlanner: java = 25 declared → groovy sources are ignored, no lane,
        // so no runtime inject either.
        Files.createDirectories(dir.resolve("src/main/groovy"));
        JkBuild p = project("[project]\ngroup=\"g\"\nname=\"n\"\nversion=\"1\"\njava=25\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        LockOrchestrator.injectLanguageRuntimes(p, dir, java.util.Map.of(), deps);
        assertThat(deps).isEmpty();
    }

    @Test
    void exact_pin_wins_over_the_bom_and_keeps_the_strip(@TempDir Path dir) throws IOException {
        // an explicit exact pin is deliberate (grails needs a groovy NEWER than its
        // own bom manages) — it wins and is NOT in the strip skip-list.
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.writeString(dir.resolve("src/main/groovy/A.groovy"), "class A {}");
        JkBuild p = project("[project]\ngroup=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\ngroovy=\"5.0.7\"\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        var skipStrip = LockOrchestrator.injectLanguageRuntimes(
                p, dir, java.util.Map.of("org.apache.groovy:groovy", "5.0.6"), deps);
        assertThat(deps.get("org.apache.groovy:groovy").version().raw()).contains("5.0.7");
        assertThat(skipStrip).isEmpty();
    }

    @Test
    void unpinned_inferred_runtime_follows_the_bom_and_skips_the_strip(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.writeString(dir.resolve("src/main/groovy/A.groovy"), "class A {}");
        JkBuild p = project("[project]\ngroup=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        var skipStrip = LockOrchestrator.injectLanguageRuntimes(
                p, dir, java.util.Map.of("org.apache.groovy:groovy", "5.0.6"), deps);
        assertThat(deps.get("org.apache.groovy:groovy").version().raw()).contains("5.0.6");
        assertThat(skipStrip).containsExactly("org.apache.groovy:groovy");
    }

    @Test
    void sourceless_pin_does_not_inject_the_runtime(@TempDir Path dir) throws IOException {
        // A compiler-version pin on a module with no sources of that language locks the
        // compiler but has nothing to run — no runtime dep.
        JkBuild p = project("[project]\ngroup=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\nkotlin=\"=2.1.0\"\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        LockOrchestrator.injectLanguageRuntimes(p, dir, java.util.Map.of(), deps);
        assertThat(deps).isEmpty();
    }

    @Test
    void pinned_groovy_still_injects_and_user_dep_wins(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.writeString(dir.resolve("src/main/groovy/A.groovy"), "class A {}");
        JkBuild p = project("[project]\ngroup=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\ngroovy=\"5.0.4\"\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        Dependency user = new Dependency("org.apache.groovy:groovy", cc.jumpkick.model.VersionSelector.parse("=5.0.7"));
        deps.put("org.apache.groovy:groovy", user);
        LockOrchestrator.injectLanguageRuntimes(p, dir, java.util.Map.of(), deps);
        assertThat(deps.get("org.apache.groovy:groovy")).isSameAs(user);
    }
}
