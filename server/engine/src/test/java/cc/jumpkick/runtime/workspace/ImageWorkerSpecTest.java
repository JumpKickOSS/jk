// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.workspace;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.image.ImageConfig;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockfileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Two of the worker's inputs are the engine's alone to supply, and both were wrong before: the
 * base image, which the worker must pull by the digest the action key was built from rather than
 * by a tag that may have moved since; and jk's cache root, without which the worker extracted a
 * 50–200 MB base JRE into the module's {@code target/} — outside every {@link
 * CacheTier} bound, and thrown away by the next {@code jk clean}.
 */
class ImageWorkerSpecTest {

    private static final String TAG = "eclipse-temurin:25-jre";
    private static final String PINNED = TAG + "@sha256:" + "a".repeat(64);

    /** The one value in the spec for {@code key}, or null. */
    private static @Nullable String config(List<String> spec, String key) {
        for (String line : spec) {
            if (MiniJson.parse(line) instanceof Map<?, ?> m
                    && "config".equals(m.get("t"))
                    && key.equals(m.get("key"))) {
                return String.valueOf(m.get("value"));
            }
        }
        return null;
    }

    private static List<String> spec(Path module, Path cache, String base) throws Exception {
        return spec(module, cache, base, null, module.resolve("target/app.tar"));
    }

    /** The spec for a push to {@code registry}, a daemon load when it is null, or a tarball. */
    private static List<String> spec(
            Path module, Path cache, String base, @Nullable String registry, @Nullable Path tarball) throws Exception {
        var project = JkBuildParser.parse(module.resolve("jk.toml"));
        return ImagePlans.imageWorkerSpec(
                        cache,
                        project,
                        BuildLayout.of(module, project),
                        new ImageConfig(
                                TAG, null, null, List.of(), Map.of(), Map.of(), Map.of(), registry, null, List.of(),
                                null, null, null, true),
                        base,
                        "com.example.Main",
                        new ImagePlans.RuntimeJars(List.of(), List.of(), List.of(), Map.of()),
                        null,
                        tarball)
                .lines();
    }

    private static Path module(Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(module.resolve("jk.toml"), """
            group = "t"
            name = "app"
            version = "0.1.0"
            jdk = { version = "25" }
            java = 25
            """);
        return module;
    }

    /**
     * The worker pulls what the key describes. Handing it {@code config.base()} instead would let
     * the cached tarball and the pushed image disagree the moment upstream republishes the tag.
     */
    @Test
    void the_worker_is_told_the_resolved_base_not_the_configured_tag(@TempDir Path tmp) throws Exception {
        assertThat(config(spec(module(tmp), tmp.resolve("cache"), PINNED), "base"))
                .isEqualTo(PINNED)
                .isNotEqualTo(TAG);
    }

    /**
     * {@code CacheTree.BASE_JRE} is bounded at {@code <cache>/base-jre}, and this key is the only
     * that tells the worker where that is — a tier whose path production never writes is a bound
     * that governs nothing.
     */
    @Test
    void the_worker_is_told_jks_cache_root_so_the_base_jre_lands_in_a_bounded_tier(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path module = module(tmp);

        String jkCache = config(spec(module, cache, PINNED), "jkCache");

        assertThat(jkCache)
                .as("without this key the worker has nowhere bounded to put the base JRE")
                .isEqualTo(cache.toAbsolutePath().toString());
        Path target = BuildLayout.of(module, JkBuildParser.parse(module.resolve("jk.toml")))
                .targetDir();
        assertThat(Path.of(jkCache).resolve(CacheTree.BASE_JRE.entry()).startsWith(target))
                .as("%s is module build output — nothing reclaims it and `jk clean` deletes it", target)
                .isFalse();
    }

    /** A push hands the worker the module's SBOM to attach; the bill is the lock's runtime closure. */
    @Test
    void a_push_hands_the_worker_the_modules_sbom(@TempDir Path tmp) throws Exception {
        Path module = lockedModule(tmp);

        String sbom = config(spec(module, tmp.resolve("cache"), PINNED, "registry.example:5000", null), "sbom");

        assertThat(sbom).isNotNull();
        assertThat(Path.of(sbom))
                .isEqualTo(module.resolve("target/sbom/image.cdx.json").toAbsolutePath());
        assertThat(Files.readString(Path.of(sbom)))
                .contains("\"bomFormat\": \"CycloneDX\"")
                .contains("pkg:maven/com.foo/leaf@1.0");
    }

    /** A tarball or a daemon load is not a registry push: there is nothing to attach the SBOM to. */
    @Test
    void a_tarball_build_hands_the_worker_no_sbom(@TempDir Path tmp) throws Exception {
        Path module = lockedModule(tmp);

        assertThat(config(spec(module, tmp.resolve("cache"), PINNED), "sbom")).isNull();
        assertThat(config(spec(module, tmp.resolve("cache"), PINNED, null, null), "sbom"))
                .isNull();
        assertThat(module.resolve("target/sbom")).doesNotExist();
    }

    private static Path lockedModule(Path tmp) throws Exception {
        Path module = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(module.resolve("jk.toml"), """
            group = "t"
            name = "app"
            version = "0.1.0"
            java = 25

            [dependencies]
            leaf = "com.foo:leaf:1.0"
            """);
        Files.writeString(module.resolve("jk-lock.toml"), """
            version = 1
            generated-by = "jk test"
            resolution-algorithm = "pubgrub-v1"

            [[artifact]]
            name     = "com.foo:leaf"
            version  = "1.0"
            source   = "central+https://repo.maven.apache.org/maven2/"
            checksum = "sha256:d65226949713c4c61a784f41c51167e7b0316f93764398ebba9e4336b3d954c2"
            scopes   = ["main"]
            """);
        LockfileReader.clearCache();
        return module;
    }
}
