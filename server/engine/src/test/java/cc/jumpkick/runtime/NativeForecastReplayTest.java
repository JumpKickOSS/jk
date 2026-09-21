// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.surface.TrainLayout;
import cc.jumpkick.task.ActionCache;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * After {@code jk clean} the native binary is gone while its action key usually still hits. The
 * forecast replays the step's last record — recomputing what it can see, trusting what it cannot
 * — and prices a restore only when the replayed key is in the cache. Every input the replay
 * cannot vouch for keeps the pessimistic answer.
 */
class NativeForecastReplayTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    @Test
    void a_wiped_executable_forecasts_a_restore_when_its_replayed_key_hits(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path dir = module(tmp, "[\"-O2\"]");
        JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(dir, project);
        Path lockFile = dir.resolve("jk-lock.toml");
        Path jar = layout.mainJar();
        Path target = Objects.requireNonNull(jar.getParent(), "the jar's directory");
        Files.createDirectories(target);
        Files.writeString(jar, "jar-bytes");
        Path out = layout.nativeBinary();
        Files.writeString(out, "binary");
        Path graal = Files.createDirectories(tmp.resolve("graal"));
        Files.writeString(graal.resolve("release"), "JAVA_VERSION=\"25\"\n");

        // The step's own key, as the build derives and stores it: the metadata-repository prefix
        // the step prepends rides in the args token, spaces and all, and has to read back whole.
        List<String> liveArgs = List.of(
                "-H:+UnlockExperimentalVMOptions",
                "-H:ConfigurationFileDirectories=/m/a,/m/b",
                "-H:-UnlockExperimentalVMOptions",
                "-O2");
        PlannerNative.ImageKey key =
                PlannerNative.imageKey(graal, List.of(jar), liveArgs, "t.Main", false, out, null, null, List.of());
        Session session = Session.defaults().withCacheDir(cache);
        SessionContext.where(session, () -> {
            PlannerSupport.storePackagedForTest(cache, key.task(), key.key(), key.tokens(), target, List.of(out), true);
            return null;
        });
        ActionCache ac = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache));

        // jk clean: the binary and the jar are gone.
        Files.delete(out);
        Files.delete(jar);
        Map<Path, String> pinned = new HashMap<>();
        assertThat(PackagingKeys.nativeActionCached(dir, project, layout, lockFile, ac, cache, pinned))
                .as("a jar nobody pinned is a missing classpath entry: no false restore")
                .isFalse();

        // The package forecast pins the jar's content from its own record; the classpath token
        // then spells the bytes the live restore produces, and the replayed key hits.
        String sha = ac.cas()
                .hashFromPath(ac.cas().put("jar-bytes".getBytes(StandardCharsets.UTF_8)))
                .orElseThrow();
        pinned.put(jar.toAbsolutePath().normalize(), sha);
        assertThat(PackagingKeys.nativeActionCached(dir, project, layout, lockFile, ac, cache, pinned))
                .isTrue();

        // An arg the manifest changed is a key the record does not hold.
        JkBuild changedArgs = JkBuildParser.parse(module(tmp, "[\"-O3\"]").resolve("jk.toml"));
        assertThat(PackagingKeys.nativeActionCached(dir, changedArgs, layout, lockFile, ac, cache, pinned))
                .isFalse();

        // A trained reachability tree the record never saw moves the key too.
        Path train = layout.moduleTargetDir()
                .resolve(TrainLayout.ROOT)
                .resolve("merged")
                .resolve(TrainLayout.REACHABILITY);
        Files.createDirectories(train);
        Files.writeString(train.resolve("reachability-metadata.json"), "{}");
        assertThat(PackagingKeys.nativeActionCached(dir, project, layout, lockFile, ac, cache, pinned))
                .isFalse();
    }

    @Test
    void a_module_with_no_native_record_forecasts_no_restore(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path dir = module(tmp, "[]");
        JkBuild project = JkBuildParser.parse(dir.resolve("jk.toml"));
        BuildLayout layout = BuildLayout.of(dir, project);
        ActionCache ac = new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache));
        assertThat(PackagingKeys.nativeActionCached(
                        dir, project, layout, dir.resolve("jk-lock.toml"), ac, cache, Map.of()))
                .isFalse();
    }

    @Test
    void the_record_tokens_read_back_with_a_joiner_inside_an_arg() {
        Map<String, String> tokens = PackagingKeys.nativeTokens(
                "cp:abc;args:-Da=1;2 -O2;main:t.Main;shared:false;out:demo;graal:g;framework:;train:");
        assertThat(tokens)
                .containsEntry("cp:", "abc")
                .containsEntry("args:", "-Da=1;2 -O2")
                .containsEntry("main:", "t.Main")
                .containsEntry("framework:", "")
                .containsEntry("train:", "");
        assertThat(PackagingKeys.nativeTokens(null)).isEmpty();
    }

    @Test
    void declared_args_match_the_record_with_or_without_the_metadata_prefix() {
        String prefix = "-H:+UnlockExperimentalVMOptions,-H:ConfigurationFileDirectories=m/a,m/b"
                + ",-H:-UnlockExperimentalVMOptions";
        assertThat(PackagingKeys.declaredArgsMatch("-O2", List.of("-O2"))).isTrue();
        assertThat(PackagingKeys.declaredArgsMatch("", List.of())).isTrue();
        assertThat(PackagingKeys.declaredArgsMatch(prefix + ",-O2", List.of("-O2")))
                .isTrue();
        assertThat(PackagingKeys.declaredArgsMatch(prefix, List.of())).isTrue();
        assertThat(PackagingKeys.declaredArgsMatch("-O3", List.of("-O2"))).isFalse();
        assertThat(PackagingKeys.declaredArgsMatch(prefix + ",-O2", List.of())).isFalse();
    }

    private static Path module(Path tmp, String args) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve("demo"));
        Files.writeString(dir.resolve("jk.toml"), """
                group   = "t"
                name    = "demo"
                version = "0.0.1"
                java    = 25

                [native]
                enabled = "always"
                main    = "t.Main"
                args    = %s
                """.formatted(args));
        Files.writeString(dir.resolve("jk-lock.toml"), """
                version = 1
                generated-by = "test"
                resolution-algorithm = "pubgrub-v1"
                """);
        Path src = Files.createDirectories(dir.resolve("src/main/java/t"));
        Files.writeString(
                src.resolve("Main.java"), "package t;\nclass Main { public static void main(String[] a) {} }\n");
        return dir;
    }
}
