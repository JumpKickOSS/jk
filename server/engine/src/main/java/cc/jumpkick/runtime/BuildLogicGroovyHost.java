// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import cc.jumpkick.engine.JobWorkers;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Evaluates project build-logic {@code .groovy} scripts in a forked JVM ({@code groovy.ui.GroovyMain}).
 *
 * <p>Groovy is not linked into the engine fat jar — jars are fetched once into the product tool
 * cache. The child process is the isolation boundary: {@code System.exit} / OOM in a script cannot
 * take the engine down. Bindings exposed to scripts:
 *
 * <ul>
 *   <li>{@code projectDir} — {@link Path} project root
 *   <li>{@code outDir} — {@link Path} action-cached task output
 *   <li>{@code properties} — mutable {@link java.util.Map}{@code <String,Object>}
 *   <li>{@code ant} — {@code groovy.ant.AntBuilder} when groovy-ant + Ant resolve
 * </ul>
 *
 * <p>{@code @Grab} is available when Ivy is on the child classpath (Grape lives in groovy.jar).
 */
final class BuildLogicGroovyHost {

    /** Match product catalog pin ({@code libs.versions.groovy}); scripts get Groovy 5. */
    static final String GROOVY_VER = "5.0.4";

    private static final String ANT_VER = "1.10.14";
    /** Legacy optional tasks (regexpmapper, …) still used by Netty codegen.groovy. */
    private static final String ANT_OPTIONAL_VER = "1.5.3-1";

    private static final String IVY_VER = "2.5.3";

    private BuildLogicGroovyHost() {}

    static void evaluate(Path script, Path projectDir, Path outDir) throws Exception {
        Path[] jars = ensureJars();
        Path wrapper = Files.createTempFile("jk-logic-", ".groovy");
        try {
            Files.writeString(wrapper, wrap(script, projectDir, outDir), StandardCharsets.UTF_8);
            String javaBin = JdkFingerprint.java(JavaHomes.runningJavaHome()).toString();
            List<Path> cp = new ArrayList<>(jars.length);
            for (Path jar : jars) cp.add(jar);
            List<String> cmd = new ArrayList<>();
            cmd.add(javaBin);
            cmd.add("-cp");
            cmd.add(Classpaths.join(cp));
            cmd.add("groovy.ui.GroovyMain");
            cmd.add(wrapper.toString());
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.directory(projectDir.toFile());
            Process p = JobWorkers.start(pb);
            String log = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            int exit = p.waitFor();
            if (exit != 0) {
                String detail = log == null ? "" : log.strip();
                throw new IllegalStateException(
                        "[build] logic: groovy failed (exit " + exit + ")" + (detail.isEmpty() ? "" : ":\n" + detail));
            }
        } finally {
            Files.deleteIfExists(wrapper);
        }
    }

    /**
     * Child-side driver: bind the same variables {@code GroovyShell} used to, then evaluate the
     * user file so {@code @Grab} / imports on that file stay intact.
     */
    static String wrap(Path script, Path projectDir, Path outDir) {
        return """
                import java.nio.file.Path
                def binding = new groovy.lang.Binding()
                binding.setVariable('projectDir', Path.of(%s))
                binding.setVariable('outDir', Path.of(%s))
                binding.setVariable('properties', new java.util.HashMap())
                try {
                  binding.setVariable('ant', new groovy.ant.AntBuilder())
                } catch (Throwable ignored) {}
                new groovy.lang.GroovyShell(binding).evaluate(new File(%s))
                """.formatted(
                        groovyString(projectDir.toAbsolutePath().normalize().toString()),
                        groovyString(outDir.toAbsolutePath().normalize().toString()),
                        groovyString(script.toAbsolutePath().normalize().toString()));
    }

    /** Groovy single-quoted string literal. */
    static String groovyString(String s) {
        return "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    /** Maven paths under the tool cache; fetch missing jars from Maven Central. */
    static Path[] ensureJars() throws IOException {
        Path cache = toolCache();
        Files.createDirectories(cache);
        Http http = new Http();
        return new Path[] {
            fetch(http, cache, "org/apache/groovy/groovy/" + GROOVY_VER + "/groovy-" + GROOVY_VER + ".jar"),
            fetch(http, cache, "org/apache/groovy/groovy-ant/" + GROOVY_VER + "/groovy-ant-" + GROOVY_VER + ".jar"),
            fetch(http, cache, "org/apache/groovy/groovy-xml/" + GROOVY_VER + "/groovy-xml-" + GROOVY_VER + ".jar"),
            fetch(http, cache, "org/apache/ivy/ivy/" + IVY_VER + "/ivy-" + IVY_VER + ".jar"),
            fetch(http, cache, "org/apache/ant/ant/" + ANT_VER + "/ant-" + ANT_VER + ".jar"),
            fetch(http, cache, "org/apache/ant/ant-launcher/" + ANT_VER + "/ant-launcher-" + ANT_VER + ".jar"),
            fetch(http, cache, "ant/ant-optional/" + ANT_OPTIONAL_VER + "/ant-optional-" + ANT_OPTIONAL_VER + ".jar"),
        };
    }

    /**
     * Where the forked Groovy's jars live: {@code <store>/tools/build-logic-groovy}.
     *
     * <p>Beside the other provisioned distributions, and for the same reason — {@link
     * JkDirs#toolsDir()} states it. {@code JK_CACHE_DIR} is deliberately not read here: it selects
     * the action cache, and these are fetched artifacts, so honouring it put seven jars somewhere
     * the retention sweep reclaims.
     */
    static Path toolCache() {
        return JkDirs.tools().resolve("build-logic-groovy");
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
