// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.layout.BuildLayout;
import cc.jumpkick.lock.LockManifestDigest;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * JK-1648: a registered workspace module with no sources was forecast as a "source-less
 * aggregator — nothing to package", so it never scheduled, never produced its (empty) jar, and any
 * sibling depending on it failed with a misleading "sibling not built". The forecast must keep the
 * module dirty until its jar exists; once packaged, it goes back to clean.
 */
class SourcelessModuleForecastTest {

    @AfterEach
    void tidy() {
        SessionContext.reset();
    }

    private static Path workspaceWithSourcelessModule(Path tmp) throws Exception {
        Files.writeString(tmp.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "ws"
                version = "0.1.0"
                jdk = 25
                java = 25

                [workspace]
                modules = ["libb"]
                """);
        Path libb = Files.createDirectories(tmp.resolve("libb"));
        Files.writeString(libb.resolve("jk.toml"), """
                [project]
                group = "t"
                name = "libb"
                version = "0.1.0"
                jdk = 25
                java = 25
                """);
        // A fresh (non-stale) workspace lock so the forecast reaches the per-step walk.
        Lockfile lf = new Lockfile(1, "test", "pubgrub-v1", null, null, List.of(), List.of(), List.of());
        LockfileWriter.write(lf, tmp.resolve("jk-lock.toml"), LockManifestDigest.compute(tmp));
        return libb;
    }

    @Test
    void sourceless_module_is_dirty_until_its_jar_exists(@TempDir Path tmp) throws Exception {
        Path libb = workspaceWithSourcelessModule(tmp);

        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        assertThat(graph.hasErrors()).isFalse();
        assertThat(graph.topoOrder()).hasSize(1);

        Set<Path> dirty = SessionContext.where(
                Session.defaults(), () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache")));
        assertThat(dirty).contains(libb);
    }

    @Test
    void sourceless_module_is_clean_once_its_jar_exists(@TempDir Path tmp) throws Exception {
        Path libb = workspaceWithSourcelessModule(tmp);
        var build = JkBuildParser.parse(libb.resolve("jk.toml"));
        Path jar = BuildLayout.of(libb, build).mainJar();
        Files.createDirectories(jar.getParent());
        Files.write(jar, new byte[] {0x50, 0x4b, 0x05, 0x06});

        BuildGraph.Result graph =
                BuildGraph.resolve(tmp, JkBuildParser.parse(Files.readString(tmp.resolve("jk.toml"))));
        Set<Path> dirty = SessionContext.where(
                Session.defaults(), () -> BuildService.forecastDirtyDirs(graph, tmp.resolve("cache")));
        assertThat(dirty).doesNotContain(libb);
    }
}
