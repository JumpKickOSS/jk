// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.runtime.base.TestEnv;
import cc.jumpkick.util.TestHomes;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * jk building itself: which of its modules run a nested engine in their tests, the engine and
 * worker jars such a suite launches, and the isolated homes it runs them under.
 */
public final class SelfHostEngine {

    private SelfHostEngine() {}

    /**
     * CLI integration suite: tests spawn a real engine via the wire and register {@code
     * EngineTestExtension}, which force-stops the engine after each class. Under pure-jk {@code jk
     * test} that must not share the host engine's {@code JK_STATE_DIR} (host would die mid-suite).
     */
    public static boolean needsNestedEngineIsolation(JkBuild project) {
        if (project == null || !selfHosted(project)) return false;
        return "jk-cli".equals(project.project().name()) || "cc.jumpkick.cli.Jk".equals(project.mainClass());
    }

    /** The group of jk's own modules: a project named {@code jk-cli} under any other is the user's. */
    static final String SELF_GROUP = "cc.jumpkick";

    private static boolean selfHosted(JkBuild project) {
        return project.project() != null && SELF_GROUP.equals(project.project().group());
    }

    /** The workspace module a nested-engine suite launches, from its assembly jar. */
    public static final String ENGINE_MODULE = "jk-engine";

    /** True when {@code module} is the engine a nested-engine suite launches. */
    public static boolean isEngineModule(JkBuild module) {
        return selfHosted(module) && ENGINE_MODULE.equals(module.project().name());
    }

    /**
     * Resolve engine assembly + every first-party worker jar so CLI tests find the engine and the
     * workers this build produced through {@code -Djk.engine.jar} / {@code -Djk.*.plugin.jar}.
     */
    static void enrichCliTestProps(Path moduleDir, Map<String, String> props) throws IOException {
        Map<String, Path> siblings = SiblingJars.siblingMainJars(moduleDir);
        Path engine = resolveEngineJarForNestedTests(siblings);
        if (engine != null) {
            props.put("jk.engine.jar", engine.toAbsolutePath().toString());
        }
        for (PluginJar w : PluginJar.values()) {
            if (props.containsKey(w.jarProperty())) continue;
            Path jar = siblings.get(w.artifactId());
            if (jar == null && w.artifactId().startsWith("jk-")) {
                jar = siblings.get(w.artifactId().substring(3));
            }
            if (jar != null) {
                // A sibling's jar whether or not it is written yet: run-tests waits for it to be.
                props.put(w.jarProperty(), jar.toAbsolutePath().toString());
            } else {
                Path located = w.locateStored(JkStores.storeCas());
                if (located != null) props.put(w.jarProperty(), located.toString());
            }
        }
    }

    /**
     * Engine jar for nested CLI suites: the workspace engine's assembly, which a test run packages
     * before the suite starts ({@link #isEngineModule}). Outside a workspace with an engine module,
     * the host process's fat jar or the installed engine.
     */
    static @Nullable Path resolveEngineJarForNestedTests(Map<String, Path> siblings) {
        Path engine = siblings.get(ENGINE_MODULE);
        if (engine != null) return engine.normalize();
        return locateHostEngineJar();
    }

    /**
     * Fat engine jar this process was launched from, the same version under {@link EngineInstall},
     * or a monorepo product path ({@code target/dist/lib}, {@code target/server/engine}). Null only
     * when none of those exist (cold checkout with no install and no prior package).
     */
    static @Nullable Path locateHostEngineJar() {
        try {
            var cs = SelfHostEngine.class.getProtectionDomain().getCodeSource();
            if (cs != null && cs.getLocation() != null) {
                Path p = Path.of(cs.getLocation().toURI());
                if (Files.isRegularFile(p) && p.getFileName().toString().endsWith(".jar")) {
                    return p.normalize();
                }
            }
        } catch (Exception e) {
            // fall through — an exploded test classpath has no jar to name
            Log.debug("locateHostEngineJar: fall through", e);
        }
        for (Path p : Classpaths.split(System.getProperty("java.class.path", ""))) {
            String name = p.getFileName() != null ? p.getFileName().toString() : "";
            if (Files.isRegularFile(p)
                    && name.endsWith(".jar")
                    && (name.startsWith("jk-engine") || name.equals("jk-engine.jar"))) {
                return p.toAbsolutePath().normalize();
            }
        }
        try {
            var mat = EngineInstall.current().resolve(JkVersion.VERSION);
            if (mat.isPresent() && Files.isRegularFile(mat.get().engineJar())) {
                return mat.get().engineJar().toAbsolutePath().normalize();
            }
        } catch (RuntimeException e) {
            // An isolated test JK_HOME (a module sandbox / nested CLI suite) has no engine jar.
            Log.debug(
                    "locateHostEngineJar: isolated test JK_HOME (module sandbox / nested CLI suite) has no engine jar",
                    e);
        }
        // Last resort: monorepo product outputs relative to user.dir (and parents). Nested
        // isolation runs with user.dir = clients/cli; a module-rooted run has the module as cwd.
        return findMonorepoEngineJar(
                Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize());
    }

    /** Prefer fat assembly, then dist/shadow, then thin main jar under known layout roots. */
    static @Nullable Path findMonorepoEngineJar(Path start) {
        String ver = JkVersion.VERSION;
        Path walk = start;
        for (int up = 0; up < 5 && walk != null; up++, walk = walk.getParent()) {
            for (String rel : List.of(
                    "target/jk-engine-" + ver + "-all.jar",
                    "target/dist/lib/jk-engine-" + ver + ".jar",
                    "target/server/engine/jk-engine-" + ver + "-all.jar",
                    "target/server/engine/jk-engine-" + ver + ".jar",
                    "server/engine/target/jk-engine-" + ver + "-all.jar",
                    "server/engine/target/jk-engine-" + ver + ".jar")) {
                Path p = walk.resolve(rel);
                if (Files.isRegularFile(p)) return p.normalize();
            }
        }
        return null;
    }

    /**
     * Isolated {@code JK_HOME} + {@code JK_STATE_DIR} in this module's sandbox slot ({@link TestHomes})
     * for nested-engine CLI tests. Keeps the host engine's socket, cache, and store alone.
     *
     * <p>Nested destructive tests must never receive the host's {@code JK_CACHE_DIR} or {@code
     * JK_STORE_DIR}: {@link TestEnv#forModule} pins both under the sandbox home, and this layers
     * a per-run state dir over its state.
     *
     * <p>Plugin/worker jars for nested suites still arrive via {@code -Djk.*.plugin.jar} props
     * ({@link #enrichCliTestProps}), not by sharing the host store.
     */
    static Map<String, String> nestedEngineTestEnv(Path moduleDir) throws IOException {
        Path jkHome = TestHomes.prepare(moduleDir);
        String runId = Long.toString(Clock.SYSTEM.millis(), 36) + "-"
                + Integer.toHexString(System.identityHashCode(moduleDir) & 0xffff);
        // Under this module's sandbox home, which is outside the project (see TestHomes) and which
        // `jk clean` removes for this module. Nested engines here use TCP
        // (JK_ENGINE_TRANSPORT=tcp), so UDS path length does not constrain depth.
        Path stateDir = jkHome.resolve("engine-state").resolve(runId);
        Files.createDirectories(stateDir);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("JK_HOME", jkHome.toAbsolutePath().toString());
        env.put("JK_JDKS_DIR", jkHome.resolve("jdks").toAbsolutePath().toString());
        env.put("JK_STATE_DIR", stateDir.toAbsolutePath().toString());
        env.put("JK_HTTP_ENABLED", "false");
        env.put("JK_HTTP_PORT", "0");
        env.put("JK_STREAM_IDLE_MS", "45000");
        env.put("TERM", "xterm-256color");
        env.put("CI", "false");
        // Clear NO_COLOR so TUI ANSI assertions are deterministic.
        env.put("NO_COLOR", "");
        // Nested engines and workers under the suite must not record startup caches.
        env.put("JK_WORKER_AOT", "off");
        return env;
    }
}
