// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkConfig;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.model.BuildIdentity;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.task.ActionKey;
import cc.jumpkick.task.ClasspathFingerprint;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * After {@code jk build --rebuild}, package-jar and run-tests must leave action-cache records under
 * the <em>same</em> keys a normal build uses — so {@code jk explain} does not report a phantom
 * repackage / retest while {@code jk build} (preflight memo) says fully cached.
 *
 * <p>Compile already had this contract ({@code JavaIncrementalCompile}: rebuild skips restore but
 * still persists). Packaging used to early-return from {@code storePackaged} under rebuild, and
 * run-tests skipped the green marker store when {@code rerun} — both left explain permanently
 * dirty after a successful rebuild.
 */
class RebuildPackageStoreParityTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    private static JkConfig rebuildConfig() {
        return new JkConfig(
                Optional.empty(),
                Optional.empty(),
                Optional.of(true), // rebuild
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    @Test
    void package_action_key_is_stored_even_when_session_is_rebuild(@TempDir Path tmp) throws Exception {
        Path cache = tmp.resolve("cache");
        Path classes = Files.createDirectories(tmp.resolve("classes"));
        Files.writeString(Files.createDirectories(classes.resolve("t")).resolve("Lib.class"), "fake");
        Path jarDir = Files.createDirectories(tmp.resolve("target"));
        Path jar = jarDir.resolve("lib.jar");
        Files.writeString(jar, "jar-bytes");

        List<String> tokens =
                List.of("classes:" + ClasspathFingerprint.entry(classes), "main:", "sbom:", "manifest:" + Map.of());
        String task = ActionKey.qualifiedTaskId("package-jar", jar);
        String key = ActionKey.forArtifact(task, BuildIdentity.cacheKeyVersion(), tokens);

        Session session = Session.defaults().withConfig(rebuildConfig()).withCacheDir(cache);
        SessionContext.where(session, () -> {
            // Same store path the package step uses (must not no-op under rebuild).
            BuildPipelines.storePackagedForTest(cache, task, key, tokens, jarDir, List.of(jar));
            return null;
        });

        ActionCache ac = new ActionCache(JkStores.cas(cache), cache.resolve("actions"));
        assertThat(ac.lookup(key))
                .as("rebuild must persist package-jar action keys for the next explain/build")
                .isPresent();
    }
}
