// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.http.Http;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.JkDirs;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Evaluates project build-logic {@code .groovy} scripts with a reflective {@code GroovyShell}.
 *
 * <p>Groovy is not linked into the engine fat jar — jars are fetched once into the product tool
 * cache (same pattern as Netty's collection codegen / Kotlin toolchain). Bindings exposed to
 * scripts:
 *
 * <ul>
 *   <li>{@code projectDir} — {@link Path} project root
 *   <li>{@code outDir} — {@link Path} action-cached task output (merged into classes)
 *   <li>{@code properties} — mutable {@link Map}{@code <String,Object>} (e.g. for nested scripts)
 *   <li>{@code ant} — {@code groovy.ant.AntBuilder} when groovy-ant + Ant resolve
 * </ul>
 */
final class BuildLogicGroovyHost {

    /** Match product catalog pin ({@code libs.versions.groovy}); scripts get Groovy 5. */
    static final String GROOVY_VER = "5.0.4";

    private static final String ANT_VER = "1.10.14";
    /** Legacy optional tasks (regexpmapper, …) still used by Netty codegen.groovy. */
    private static final String ANT_OPTIONAL_VER = "1.5.3-1";

    private BuildLogicGroovyHost() {}

    static void evaluate(Path script, Path projectDir, Path outDir) throws Exception {
        Path[] jars = ensureJars();
        URL[] urls = new URL[jars.length];
        for (int i = 0; i < jars.length; i++) {
            urls[i] = jars[i].toUri().toURL();
        }
        // Isolate from the engine classpath so user scripts do not see engine internals.
        try (URLClassLoader cl = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
            Class<?> bindingCl = Class.forName("groovy.lang.Binding", true, cl);
            Object binding = bindingCl.getConstructor().newInstance();
            Method setVariable = bindingCl.getMethod("setVariable", String.class, Object.class);
            setVariable.invoke(binding, "projectDir", projectDir);
            setVariable.invoke(binding, "outDir", outDir);
            // Name "props" would avoid Script#properties, but Netty codegen.groovy expects
            // "properties" — Binding.setVariable works; GroovyShell.setProperty does not.
            Map<String, Object> props = new HashMap<>();
            setVariable.invoke(binding, "properties", props);
            try {
                Class<?> antCl = Class.forName("groovy.ant.AntBuilder", true, cl);
                Object ant = antCl.getConstructor().newInstance();
                setVariable.invoke(binding, "ant", ant);
            } catch (ClassNotFoundException e) {
                // ant binding optional for scripts that do not need it
            }
            Class<?> shellCl = Class.forName("groovy.lang.GroovyShell", true, cl);
            Object shell = shellCl.getConstructor(bindingCl).newInstance(binding);
            Method evaluate = shellCl.getMethod("evaluate", File.class);
            evaluate.invoke(shell, script.toFile());
        }
    }

    /** Maven paths under the tool cache; fetch missing jars from Maven Central. */
    static Path[] ensureJars() throws IOException {
        Path cache = toolCache();
        Files.createDirectories(cache);
        Http http = new Http();
        return new Path[] {
            fetch(http, cache, "org/apache/groovy/groovy/" + GROOVY_VER + "/groovy-" + GROOVY_VER + ".jar"),
            fetch(http, cache, "org/apache/groovy/groovy-ant/" + GROOVY_VER + "/groovy-ant-" + GROOVY_VER + ".jar"),
            fetch(http, cache, "org/apache/ant/ant/" + ANT_VER + "/ant-" + ANT_VER + ".jar"),
            fetch(http, cache, "org/apache/ant/ant-launcher/" + ANT_VER + "/ant-launcher-" + ANT_VER + ".jar"),
            fetch(http, cache, "ant/ant-optional/" + ANT_OPTIONAL_VER + "/ant-optional-" + ANT_OPTIONAL_VER + ".jar"),
        };
    }

    static Path toolCache() {
        String override = System.getenv("JK_CACHE_DIR");
        if (override != null && !override.isBlank()) {
            return Path.of(override, "tools", "build-logic-groovy");
        }
        return JkDirs.current().cacheDir().resolve("tools").resolve("build-logic-groovy");
    }

    /**
     * Through {@link Http}, not {@code URL.openStream}: a raw stream reached Central without the
     * mirror, without the per-host cooldown, and without opening the rate-limit window, so five
     * jar fetches could spend a 429 the rest of the build never learned about.
     */
    private static Path fetch(Http http, Path cache, String mavenPath) throws IOException {
        String fileName = mavenPath.substring(mavenPath.lastIndexOf('/') + 1);
        Path out = cache.resolve(fileName);
        if (Files.isRegularFile(out) && Files.size(out) > 0) return out;
        URI uri = RepositorySpec.MAVEN_CENTRAL.url().resolve(mavenPath);
        HttpResponse<InputStream> res;
        try {
            res = http.getStream(uri);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + fileName, e);
        }
        if (res.statusCode() != 200) {
            throw new IOException("GET " + uri + " returned HTTP " + res.statusCode());
        }
        try (InputStream in = res.body()) {
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
        }
        return out;
    }
}
