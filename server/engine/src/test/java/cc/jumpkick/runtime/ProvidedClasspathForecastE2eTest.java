// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.androidsdk.AndroidSdk;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.plugin.manifest.PluginDescriptors;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.task.ActionCache;
import cc.jumpkick.testing.TestCaches;
import cc.jumpkick.wire.runtime.TaskForecast;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A plugin's {@code [[contribute.provided-classpath]]} jars are on the build's compile classpath,
 * so they are on the forecast's: priced without them, the compile key named a request the build
 * never makes, and a module on a platform SDK (an IntelliJ distribution, an Android platform jar)
 * forecast a full compile on every build that then hit the cache.
 *
 * <p>The provided jar is a file under a fake managed SDK root ({@code sdk-component = "root"}), so
 * nothing is downloaded for it.
 */
@Tag("integration")
class ProvidedClasspathForecastE2eTest {

    @TempDir
    Path tmp;

    private @Nullable String oldRoot;

    @BeforeEach
    void fakeSdkRoot() throws IOException {
        Path sdk = Files.createDirectories(tmp.resolve("sdk"));
        jar(sdk.resolve("platform.jar"));
        oldRoot = System.getProperty(AndroidSdk.ROOT_PROPERTY);
        System.setProperty(AndroidSdk.ROOT_PROPERTY, sdk.toString());
        PluginTableRegistry.putBuiltIn(PluginDescriptors.parse("""
                        [plugin]
                        id = "provided-fixture"
                        table = "provided-fixture"

                        [[contribute.step-dependency]]
                        artifact = "platform"
                        sdk-component = "root"
                        sdk-path = "platform.jar"

                        [[contribute.provided-classpath]]
                        dependency = "platform"
                        """, "provided-fixture.toml"), null);
    }

    @AfterEach
    void restoreSdkRoot() {
        if (oldRoot == null) System.clearProperty(AndroidSdk.ROOT_PROPERTY);
        else System.setProperty(AndroidSdk.ROOT_PROPERTY, oldRoot);
    }

    @Test
    void a_built_module_on_a_provided_classpath_forecasts_its_compile_cached() throws Exception {
        Path cache = TestCaches.dir("provided-classpath-forecast-cache");
        Path ws = ClassesTreeRestoreE2eTest.workspace(tmp, """
                package com.example;

                public final class One {
                    private One() {}
                }
                """);
        Path manifest = ws.resolve("lib/jk.toml");
        Files.writeString(manifest, Files.readString(manifest) + "\n[provided-fixture]\n");
        ClassesTreeRestoreE2eTest.lock(ws, cache);

        ClassesTreeRestoreE2eTest.build(ws, cache, "initial");

        TaskForecast.Task compile = forecast(ws, cache).steps().stream()
                .filter(s -> TaskNames.COMPILE_MAIN.equals(s.name()))
                .findFirst()
                .orElseThrow();
        assertThat(compile.cached())
                .as("compile-main after a build that compiled it: %s", compile.text())
                .isTrue();
    }

    private static TaskForecast.Module forecast(Path ws, Path cache) throws IOException {
        BuildGraph.Result graph = BuildGraph.resolve(ws, JkBuildParser.parse(ws.resolve("jk.toml")));
        assertThat(graph.hasErrors()).isFalse();
        ActionCache actionCache =
                new ActionCache(JkStores.cacheCas(cache), CacheTree.ACTIONS.under(cache), JkStores.storeCas());
        return TaskForecaster.of(graph, JkStores.cacheCas(cache), actionCache, cache, true).stream()
                .filter(m -> m.dir().getFileName().toString().equals("lib"))
                .findFirst()
                .orElseThrow();
    }

    private static void jar(Path out) throws IOException {
        try (OutputStream os = Files.newOutputStream(out);
                JarOutputStream jar = new JarOutputStream(os)) {
            jar.putNextEntry(new JarEntry("platform.txt"));
            jar.write("platform".getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
    }
}
