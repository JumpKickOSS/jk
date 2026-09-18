// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.resolver;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.model.Dependency;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.VersionSelector;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
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

    /** `deps` is keyed the way LockOrchestrator keys it: by packageKey, not by bare GA. */
    private static String key(String ga) {
        return new Dependency(ga, VersionSelector.parse("*")).packageKey();
    }

    private static final String GROOVY = "org.apache.groovy:groovy";
    private static final String KOTLIN_STDLIB = "org.jetbrains.kotlin:kotlin-stdlib";

    @Test
    void inferred_groovy_without_pin_injects_the_runtime(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.writeString(dir.resolve("src/main/groovy/A.groovy"), "class A {}");
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        LanguageRuntimeInject.inject(p, dir, Map.of(), deps, LanguageRuntimeInject.ToolVersions.NONE);
        assertThat(deps).containsKey(key(GROOVY));
        assertThat(deps).doesNotContainKey(key(KOTLIN_STDLIB));
    }

    /**
     * The Groovy runtime is the compiler's runtime: a {@code groovy} below jk's floor resolves to
     * the compiler jk drives for it, and the runtime the lock carries follows that version, not the
     * manifest's literal — one Groovy compiles and runs the module.
     */
    @Test
    void injected_groovy_runtime_follows_the_resolved_compiler_version(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.writeString(dir.resolve("src/main/groovy/A.groovy"), "class A {}");
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\ngroovy=\"4.0.28\"\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        LanguageRuntimeInject.inject(
                p, dir, Map.of(), deps, new LanguageRuntimeInject.ToolVersions(null, null, "5.0.4"));
        assertThat(requireNonNull(deps.get(key(GROOVY))).version())
                .isInstanceOf(VersionSelector.Exact.class)
                .extracting(v -> ((VersionSelector.Exact) v).version())
                .isEqualTo("5.0.4");
    }

    /**
     * The compiler's stdlib family is the compiler's. A platform that manages {@code kotlin-stdlib}
     * at the line the manifest declared — a Kotlin the lock floored to the compiler jk drives —
     * would otherwise put its stdlib beside the compiler's own on the compile classpath, and the
     * suite would run classes the newer compiler wrote against the older library. Every family
     * coordinate the table manages follows the compiler, a {@code managed} root included; a
     * coordinate the table does not manage is left to the solve.
     */
    @Test
    void kotlin_stdlib_family_a_platform_manages_follows_the_compiler(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/kotlin"));
        Files.writeString(dir.resolve("src/main/kotlin/K.kt"), "class K");
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\nkotlin=\"2.4.10\"\n");
        Map<String, String> bom = new LinkedHashMap<>(Map.of(
                KOTLIN_STDLIB,
                "2.2.21",
                "org.jetbrains.kotlin:kotlin-reflect",
                "2.2.21",
                "com.fasterxml.jackson.core:jackson-databind",
                "2.19.0"));
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        Dependency managed = new Dependency(KOTLIN_STDLIB, VersionSelector.parse(Dependency.PLATFORM_MANAGED_VERSION));
        deps.put(managed.packageKey(), managed);
        var injected = LanguageRuntimeInject.inject(
                p, dir, bom, deps, new LanguageRuntimeInject.ToolVersions("2.4.10", null, null));
        assertThat(injected.notes())
                .singleElement()
                .asString()
                .startsWith("the module compiles with Kotlin 2.4.10, so its stdlib family follows the compiler")
                .contains(KOTLIN_STDLIB + " at 2.4.10 instead of 2.2.21")
                .contains("org.jetbrains.kotlin:kotlin-reflect at 2.4.10 instead of 2.2.21")
                .doesNotContain("jackson");
        assertThat(bom)
                .containsEntry(KOTLIN_STDLIB, "2.4.10")
                .containsEntry("org.jetbrains.kotlin:kotlin-reflect", "2.4.10")
                .containsEntry("com.fasterxml.jackson.core:jackson-databind", "2.19.0");
        assertThat(deps).hasSize(1);
    }

    /** A root the manifest pins at another version of the family is rewritten to the compiler's. */
    @Test
    void a_declared_kotlin_stdlib_root_is_rewritten_to_the_compiler_version(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/kotlin"));
        Files.writeString(dir.resolve("src/main/kotlin/K.kt"), "class K");
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\nkotlin=\"2.4.10\"\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        Dependency declared = new Dependency(KOTLIN_STDLIB, VersionSelector.parse("2.2.21"));
        deps.put(declared.packageKey(), declared);
        LanguageRuntimeInject.inject(
                p, dir, new LinkedHashMap<>(), deps, new LanguageRuntimeInject.ToolVersions("2.4.10", null, null));
        assertThat(requireNonNull(deps.get(key(KOTLIN_STDLIB))).version())
                .isInstanceOf(VersionSelector.Exact.class)
                .extracting(v -> ((VersionSelector.Exact) v).version())
                .isEqualTo("2.4.10");
    }

    @Test
    void explicit_java_release_disables_inference_like_the_lanes(@TempDir Path dir) throws IOException {
        // Mirrors BuildPlanner: java = 25 declared → groovy sources are ignored, no lane,
        // so no runtime inject either.
        Files.createDirectories(dir.resolve("src/main/groovy"));
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njava=25\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        LanguageRuntimeInject.inject(p, dir, Map.of(), deps, LanguageRuntimeInject.ToolVersions.NONE);
        assertThat(deps).isEmpty();
    }

    /**
     * The stdlib is the compiler's stdlib. A floating manifest selector resolved the compiler to one
     * version and the library to whatever was newest; with the resolved compiler version handed in,
     * the injected runtime is pinned to it exactly.
     */
    @Test
    void injected_runtime_is_pinned_to_the_resolved_compiler_version(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/scala"));
        Files.writeString(dir.resolve("src/main/scala/A.scala"), "class A");
        Files.createDirectories(dir.resolve("src/main/kotlin"));
        Files.writeString(dir.resolve("src/main/kotlin/K.kt"), "class K");
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\nscala=\"3.8.4\"\nkotlin=\"^2.2\"\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        var skipStrip = LanguageRuntimeInject.inject(
                p, dir, Map.of(), deps, new LanguageRuntimeInject.ToolVersions("2.2.20", "3.8.4", null));
        assertThat(requireNonNull(deps.get(key("org.scala-lang:scala3-library_3")))
                        .version())
                .isInstanceOf(VersionSelector.Exact.class)
                .extracting(v -> ((VersionSelector.Exact) v).version())
                .isEqualTo("3.8.4");
        // 3.8+: the stub's scala-library edge is the real stdlib and is rooted exactly as well.
        assertThat(requireNonNull(deps.get(key("org.scala-lang:scala-library"))).version())
                .isInstanceOf(VersionSelector.Exact.class)
                .extracting(v -> ((VersionSelector.Exact) v).version())
                .isEqualTo("3.8.4");
        assertThat(requireNonNull(deps.get(key("org.jetbrains.kotlin:kotlin-stdlib")))
                        .version())
                .isInstanceOf(VersionSelector.Exact.class)
                .extracting(v -> ((VersionSelector.Exact) v).version())
                .isEqualTo("2.2.20");
        assertThat(skipStrip.runtimes())
                .as("an exact pin is deliberate: not on the BOM strip skip-list")
                .isEmpty();
    }

    @Test
    void pre_3_8_compilers_leave_the_2_13_library_to_the_stub() {
        assertThat(LanguageRuntimeInject.ScalaVersions.stdlibIsScalaLibrary("3.7.2"))
                .isFalse();
        assertThat(LanguageRuntimeInject.ScalaVersions.stdlibIsScalaLibrary("3.8.0"))
                .isTrue();
        assertThat(LanguageRuntimeInject.ScalaVersions.stdlibIsScalaLibrary("3.9.0-RC1"))
                .isTrue();
        assertThat(LanguageRuntimeInject.ScalaVersions.stdlibIsScalaLibrary("garbage"))
                .isFalse();
    }

    @Test
    void exact_pin_wins_over_the_bom_and_keeps_the_strip(@TempDir Path dir) throws IOException {
        // an explicit exact pin is deliberate (grails needs a groovy NEWER than its
        // own bom manages) — it wins and is NOT in the strip skip-list.
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.writeString(dir.resolve("src/main/groovy/A.groovy"), "class A {}");
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\ngroovy=\"5.0.7\"\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        var skipStrip = LanguageRuntimeInject.inject(
                p, dir, Map.of("org.apache.groovy:groovy", "5.0.6"), deps, LanguageRuntimeInject.ToolVersions.NONE);
        assertThat(requireNonNull(deps.get(key(GROOVY))).version().raw()).contains("5.0.7");
        assertThat(skipStrip.runtimes()).isEmpty();
    }

    @Test
    void unpinned_inferred_runtime_follows_the_bom_and_skips_the_strip(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.writeString(dir.resolve("src/main/groovy/A.groovy"), "class A {}");
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        var skipStrip = LanguageRuntimeInject.inject(
                p, dir, Map.of("org.apache.groovy:groovy", "5.0.6"), deps, LanguageRuntimeInject.ToolVersions.NONE);
        assertThat(requireNonNull(deps.get(key(GROOVY))).version().raw()).contains("5.0.6");
        assertThat(skipStrip.runtimes()).containsExactly("org.apache.groovy:groovy");
    }

    @Test
    void sourceless_pin_does_not_inject_the_runtime(@TempDir Path dir) throws IOException {
        // A compiler-version pin on a module with no sources of that language locks the
        // compiler but has nothing to run — no runtime dep.
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\nkotlin=\"=2.1.0\"\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        LanguageRuntimeInject.inject(p, dir, Map.of(), deps, LanguageRuntimeInject.ToolVersions.NONE);
        assertThat(deps).isEmpty();
    }

    @Test
    void pinned_groovy_still_injects_and_user_dep_wins(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.writeString(dir.resolve("src/main/groovy/A.groovy"), "class A {}");
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\ngroovy=\"5.0.4\"\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        Dependency user = new Dependency("org.apache.groovy:groovy", VersionSelector.parse("=5.0.7"));
        deps.put(user.packageKey(), user);
        LanguageRuntimeInject.inject(p, dir, Map.of(), deps, LanguageRuntimeInject.ToolVersions.NONE);
        assertThat(deps).hasSize(1);
        assertThat(deps.get(user.packageKey())).isSameAs(user);
    }

    /**
     * The map the production caller passes is keyed by {@code packageKey}, so the inject must probe
     * with the same key. A bare-GA probe never sees the user's dep and adds a second root for one
     * solver package — two positive root terms with disjoint version sets, i.e. UNSAT on a
     * dependency declared exactly once.
     */
    @Test
    void unpinned_groovy_does_not_double_root_a_user_declared_dep(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.writeString(dir.resolve("src/main/groovy/A.groovy"), "class A {}");
        // No project groovy pin — the inject would otherwise float to the fallback major.
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        Dependency user = new Dependency("org.apache.groovy:groovy", VersionSelector.parse("=4.0.21"));
        deps.put(user.packageKey(), user);

        LanguageRuntimeInject.inject(p, dir, Map.of(), deps, LanguageRuntimeInject.ToolVersions.NONE);

        assertThat(deps).hasSize(1);
        assertThat(deps.get(user.packageKey())).isSameAs(user);
    }

    @Test
    void unpinned_kotlin_does_not_double_root_a_user_declared_stdlib(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/kotlin"));
        Files.writeString(dir.resolve("src/main/kotlin/A.kt"), "class A");
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        Dependency user = new Dependency("org.jetbrains.kotlin:kotlin-stdlib", VersionSelector.parse("=2.1.0"));
        deps.put(user.packageKey(), user);

        LanguageRuntimeInject.inject(p, dir, Map.of(), deps, LanguageRuntimeInject.ToolVersions.NONE);

        assertThat(deps).hasSize(1);
        assertThat(deps.get(user.packageKey())).isSameAs(user);
    }

    @Test
    void latest_groovy_overrides_the_bom_and_keeps_the_strip(@TempDir Path dir) throws IOException {
        Files.createDirectories(dir.resolve("src/main/groovy"));
        Files.writeString(dir.resolve("src/main/groovy/A.groovy"), "class A {}");
        JkBuild p = project("group=\"g\"\nname=\"n\"\nversion=\"1\"\njdk=25\ngroovy=\"latest\"\n");
        LinkedHashMap<String, Dependency> deps = new LinkedHashMap<>();
        var skipStrip = LanguageRuntimeInject.inject(
                p, dir, Map.of("org.apache.groovy:groovy", "5.0.6"), deps, LanguageRuntimeInject.ToolVersions.NONE);
        assertThat(requireNonNull(deps.get(key(GROOVY))).version()).isInstanceOf(VersionSelector.Latest.class);
        assertThat(skipStrip.runtimes()).isEmpty();
    }
}
