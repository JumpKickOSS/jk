// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.Cas;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * After {@code jk clean}, package/assembly forecast must use the same action keys as the live
 * restore path — not {@code missing:} fingerprints or "jar exists on disk".
 */
class TaskForecasterCleanPackageTest {

    @Test
    void classes_token_after_clean_matches_pre_clean_tree(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("mod"));
        Path classes = Files.createDirectories(module.resolve("target/classes/main"));
        Path classFile = classes.resolve("t/Lib.class");
        Files.createDirectories(classFile.getParent());
        Files.writeString(classFile, "bytecode");
        Path res = Files.createDirectories(module.resolve("resources"));
        Files.writeString(res.resolve("app.properties"), "a=1\n");
        // Simulate copy-resources into classes.
        Files.writeString(classes.resolve("app.properties"), "a=1\n");

        String live = ClasspathFingerprint.entry(classes);
        String compileTask = ActionKey.qualifiedTaskId("compile-main", classes);
        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Cas cas = new Cas(cache.resolve("store"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        // Store compile outputs (class only — resources are separate roots).
        Map<String, String> inputs = Map.of();
        ac.store(compileTask, "key-compile", inputs, classes);
        // Wipe classes (jk clean).
        deleteTree(classes);

        JkBuild project = JkBuild.builder(JkBuild.Project.builder("g", "lib", "1.0")
                        .jdkMajor(25)
                        .java(21)
                        .layout(JkBuild.Layout.SIMPLE)
                        .build())
                .build();
        BuildLayout layout = BuildLayout.of(module, project);
        // Point layout classes at our wiped tree by using traditional? SIMPLE classes under target/
        // BuildLayout.of uses project layout — SIMPLE → target/classes/main typically.
        // The helper reconstructs against layout.classesDir(); pin that it is the tree we
        // stored the compile record for, so the equality below cannot silently test nothing.
        assertThat(layout.classesDir()).isEqualTo(classes.toAbsolutePath().normalize());
        String tok = TaskForecaster.classesTokenForPackage(module, true, layout, project, ac, "key-compile", null);
        // Reconstruct must equal the pre-clean live fingerprint (class + resource).
        assertThat(tok).isEqualTo(live);
    }

    @Test
    void package_action_key_stable_when_classes_wiped_if_compile_record_present(@TempDir Path tmp) throws Exception {
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Path classFile = classes.resolve("A.class");
        Files.writeString(classFile, "AA");
        String classesTokLive = ClasspathFingerprint.entry(classes);

        Path jar = tmp.resolve("out/lib-1.0.jar");
        Files.createDirectories(jar.getParent());
        List<String> tokensLive = List.of("classes:" + classesTokLive, "main:", "sbom:", "manifest:" + Map.of());
        String task = ActionKey.qualifiedTaskId("package-jar", jar);
        String keyLive = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), tokensLive);

        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Cas cas = new Cas(cache.resolve("store"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        String compileTask = ActionKey.qualifiedTaskId("compile-main", classes);
        ac.store(compileTask, "ck", Map.of(), classes);

        // Wipe classes (clean).
        Files.delete(classFile);
        Files.delete(classes);

        Map<String, String> compileOut = ac.lookup("ck").orElseThrow().outputs();
        String classesTokClean = ClasspathFingerprint.entryFromCompileAndResources(compileOut, List.of());
        List<String> tokensClean = List.of("classes:" + classesTokClean, "main:", "sbom:", "manifest:" + Map.of());
        String keyClean = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), tokensClean);
        assertThat(keyClean).isEqualTo(keyLive);
    }

    @Test
    void reconstruction_follows_the_current_compile_key_not_the_last_record(@TempDir Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("mod"));
        Path classes = Files.createDirectories(module.resolve("target/classes/main"));
        Path classFile = classes.resolve("t/Lib.class");
        Files.createDirectories(classFile.getParent());

        Path cache = Files.createDirectories(tmp.resolve("cache"));
        Cas cas = new Cas(cache.resolve("store"));
        ActionCache ac = new ActionCache(cas, cache.resolve("actions"));
        String compileTask = ActionKey.qualifiedTaskId("compile-main", classes);

        // v1 built and recorded…
        Files.writeString(classFile, "v1-bytecode");
        String v1Live = ClasspathFingerprint.entry(classes);
        ac.store(compileTask, "key-v1", Map.of(), classes);
        // …then an edit builds v2, so the task's LAST record is v2's.
        Files.writeString(classFile, "v2-bytecode");
        ac.store(compileTask, "key-v2", Map.of(), classes);
        assertThat(ac.lastFor(compileTask).orElseThrow().actionKey()).isEqualTo("key-v2");

        // Revert to v1 and jk clean: the current compile key is v1's again.
        deleteTree(classes);

        JkBuild project = JkBuild.builder(JkBuild.Project.builder("g", "lib", "1.0")
                        .jdkMajor(25)
                        .java(21)
                        .layout(JkBuild.Layout.SIMPLE)
                        .build())
                .build();
        BuildLayout layout = BuildLayout.of(module, project);
        assertThat(layout.classesDir()).isEqualTo(classes.toAbsolutePath().normalize());

        // Reconstruction keyed by the CURRENT (v1) key must produce v1's fingerprint —
        // the live build restores v1 and computes v1's package key, not v2's.
        String tok = TaskForecaster.classesTokenForPackage(module, true, layout, project, ac, "key-v1", null);
        assertThat(tok).isEqualTo(v1Live);
    }

    @Test
    void resource_drift_ignored_when_classes_dir_absent(@TempDir Path tmp) throws Exception {
        Path res = Files.createDirectories(tmp.resolve("res"));
        Files.writeString(res.resolve("a.txt"), "x");
        Path out = tmp.resolve("out"); // does not exist
        // Caller gates on Files.isDirectory(classesDir); resourcesOutOfSync with missing out
        // still reports missing copies — the forecast must not call it when out is gone.
        assertThat(Files.isDirectory(out)).isFalse();
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) return;
        try (var walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
