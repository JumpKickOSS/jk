// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Detects JUnit Jupiter <em>in-process</em> parallel execution configuration on a test classpath
 * ({@code junit.jupiter.execution.parallel.enabled=true} in {@code junit-platform.properties} or
 * as a system property). Used to warn when stacked with multi-worker {@code -w} forks — see
 * {@code docs/perf/junit-parallel-vs-jk-workers.md}.
 */
public final class JupiterParallelDetect {

    static final String PROP = "junit.jupiter.execution.parallel.enabled";
    static final String RESOURCE = "junit-platform.properties";

    private JupiterParallelDetect() {}

    /**
     * {@code true} when Jupiter parallel is enabled via JVM system property or a {@code
     * junit-platform.properties} resource on {@code classpath} (directory or jar roots).
     */
    public static boolean enabled(List<Path> classpath) {
        String sys = System.getProperty(PROP);
        if (isTrue(sys)) return true;
        if (classpath == null) return false;
        for (Path entry : classpath) {
            if (entry == null) continue;
            try {
                if (Files.isDirectory(entry) && dirHasEnabled(entry)) return true;
                if (Files.isRegularFile(entry) && nameLooksLikeJar(entry) && jarHasEnabled(entry)) return true;
            } catch (IOException ignored) {
                // best-effort: skip unreadable entries
            }
        }
        return false;
    }

    /** Warning text for {@code W>1} + Jupiter parallel stacking. */
    public static String stackWarning(int workers) {
        return "Test workers="
                + workers
                + " with junit.jupiter.execution.parallel.enabled — double parallelism may thrash "
                + "CPU/RAM or hide races. Prefer -w1 with Jupiter parallel, or disable Jupiter "
                + "parallel when using multi-worker -w (docs/user/test.md).";
    }

    private static boolean dirHasEnabled(Path dir) throws IOException {
        Path props = dir.resolve(RESOURCE);
        if (Files.isRegularFile(props)) return fileHasEnabled(Files.newInputStream(props));
        return false;
    }

    private static boolean jarHasEnabled(Path jar) throws IOException {
        try (JarFile jf = new JarFile(jar.toFile())) {
            // Root resource (common for test classes jars / flat layouts)
            JarEntry e = jf.getJarEntry(RESOURCE);
            if (e != null) {
                try (InputStream in = jf.getInputStream(e)) {
                    if (fileHasEnabled(in)) return true;
                }
            }
            // Scan for any junit-platform.properties (some layouts nest under a package dir)
            Enumeration<JarEntry> en = jf.entries();
            while (en.hasMoreElements()) {
                JarEntry je = en.nextElement();
                if (je.isDirectory()) continue;
                String name = je.getName();
                if (name.endsWith("/" + RESOURCE) || name.equals(RESOURCE)) {
                    try (InputStream in = jf.getInputStream(je)) {
                        if (fileHasEnabled(in)) return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean fileHasEnabled(InputStream in) throws IOException {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#") || line.startsWith("!")) continue;
                int eq = line.indexOf('=');
                if (eq <= 0) continue;
                String key = line.substring(0, eq).trim();
                if (!PROP.equals(key)) continue;
                return isTrue(line.substring(eq + 1).trim());
            }
        }
        return false;
    }

    /**
     * JUnit's truth set, not jk's — deliberately NOT {@code EnvValues.parseBool}. The Platform
     * reads this property with {@code Boolean.parseBoolean}, so {@code parallel.enabled=1} does
     * not enable anything; matching jk's wider {@code 1/yes/on} here would print the stacking
     * warning for a run that is not actually parallel.
     */
    private static boolean isTrue(String v) {
        return v != null && Boolean.parseBoolean(v.trim());
    }

    private static boolean nameLooksLikeJar(Path p) {
        String n = p.getFileName() != null ? p.getFileName().toString().toLowerCase() : "";
        return n.endsWith(".jar") || n.endsWith(".zip");
    }
}
