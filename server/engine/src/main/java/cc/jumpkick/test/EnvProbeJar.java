// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.test;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.host.AtomicWrites;
import cc.jumpkick.host.CacheTree;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Log;
import cc.jumpkick.host.time.Clock;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.jspecify.annotations.Nullable;

/**
 * The agent jar a test JVM records its environment lookups with: the test runner's probe classes
 * ({@code cc.jumpkick.testrunner.env}) alone, under a manifest that names the agent and puts the
 * jar on the bootstrap class path, so a class any loader defines can reach the recorder. Cut from
 * the runner jar once per runner content.
 */
public final class EnvProbeJar {

    private static final String PACKAGE = "cc/jumpkick/testrunner/env/";
    private static final String AGENT = "cc.jumpkick.testrunner.env.EnvProbe";

    /** The agent argument's prefix that leaves system-property reads unprobed; no absolute path starts so. */
    static final String NO_PROPS = "!props ";

    /**
     * The probe's argument prefix that marks the workspace's classes (the probe's {@code
     * EnvProbe.USES}), the workspace following as {@code <length>:<path> }.
     */
    static final String USES = "+uses ";

    private EnvProbeJar() {}

    /**
     * The probe's flag for a suite recording into {@code recordDir}, the jar cut from jk's own test
     * runner under {@code cacheRoot}; {@code null} when that fails, and the suite then runs
     * unprobed. Whoever reads the records empties the directory after.
     *
     * <p>A suite whose own {@code classesDir} defines the probe's package — the test runner's —
     * runs unprobed too: the probe sits on the bootstrap class path, and would stand in for the
     * very classes the suite tests.
     *
     * <p>With a {@code uses} workspace, the probe also marks every class loaded from a directory or
     * from a jar inside that workspace, so the recording names the classes whose code ran in each
     * test class's window ({@link ClassReads}).
     */
    public static @Nullable String prepare(Path cacheRoot, Path recordDir, Path classesDir, @Nullable Path uses) {
        if (Files.isDirectory(classesDir.resolve(PACKAGE))) return null;
        try {
            Files.createDirectories(recordDir);
            Path runnerJar = PluginJar.TEST_RUNNER.locate(JkStores.storeCas());
            return flag(runnerJar, CacheTree.ENV_READS.under(cacheRoot), recordDir, uses);
        } catch (IOException | RuntimeException e) {
            Log.debug("EnvProbeJar: the suite runs without the environment probe", e);
            return null;
        }
    }

    /** The {@code -javaagent} flag that records into {@code recordDir}, cutting the jar into {@code cacheDir} when needed. */
    public static String flag(Path runnerJar, Path cacheDir, Path recordDir) throws IOException {
        return flag(runnerJar, cacheDir, recordDir, null);
    }

    /** As {@link #flag(Path, Path, Path)}, the probe marking the classes of the workspace {@code uses} when given. */
    public static String flag(Path runnerJar, Path cacheDir, Path recordDir, @Nullable Path uses) throws IOException {
        // Absolute: the test JVM resolves the agent against its own working directory, the module,
        // so a cache directory given relative (as a build root can be) would name no jar there.
        return spell(
                jar(runnerJar, cacheDir).toAbsolutePath().toString(),
                uses == null ? null : uses.toAbsolutePath().normalize().toString(),
                TestTelemetry.on(),
                recordDir.toAbsolutePath().toString());
    }

    /**
     * The flag naming the probe's {@code jar}, marking the classes of the workspace {@code uses} when
     * given and recording into {@code recordDir}. With the class telemetry off ({@code props} false)
     * the agent repoints environment lookups alone.
     */
    static String spell(String jar, @Nullable String uses, boolean props, String recordDir) {
        return "-javaagent:" + jar + "=" + (uses == null ? "" : USES + uses.length() + ":" + uses + " ")
                + (props ? "" : NO_PROPS) + recordDir;
    }

    /**
     * The run-tests stamp's spelling of the probe: its flag under each telemetry setting, with and
     * without the workspace's classes marked, the jar, workspace and record directory as stand-ins,
     * then the manifest the jar is cut under and the entries cut. The classes come from jk's test
     * runner, which the stamp keys by content.
     */
    public static String token() {
        List<String> parts = new ArrayList<>();
        for (boolean props : new boolean[] {true, false}) {
            parts.add(spell("@probe", null, props, "@record"));
            parts.add(spell("@probe", "@workspace", props, "@record"));
        }
        Map<String, String> manifest = new TreeMap<>();
        manifest("@probe")
                .getMainAttributes()
                .forEach((name, value) -> manifest.put(name.toString(), value.toString()));
        manifest.forEach((name, value) -> parts.add(name + ": " + value));
        parts.add("cut=" + PACKAGE + "*.class");
        return String.join(" | ", parts);
    }

    /** The manifest of the probe's jar, the jar named {@code name} on its own boot class path. */
    static Manifest manifest(String name) {
        Manifest manifest = new Manifest();
        Attributes main = manifest.getMainAttributes();
        main.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        main.putValue("Premain-Class", AGENT);
        main.putValue("Boot-Class-Path", name);
        // ProcessBuilder is loaded before a test runs; the agent retransforms it when it hands the
        // caller's environment on to the programs a test starts.
        main.putValue("Can-Retransform-Classes", "true");
        return manifest;
    }

    static Path jar(Path runnerJar, Path cacheDir) throws IOException {
        // The manifest's shape is part of the name: a jar cut before it changed is never taken for one after.
        String name = "env-probe-2-" + Hashing.sha256Hex(runnerJar).substring(0, 16) + ".jar";
        Path jar = cacheDir.resolve(name);
        if (Files.isRegularFile(jar)) {
            Files.setLastModifiedTime(jar, FileTime.fromMillis(Clock.SYSTEM.millis()));
            return jar;
        }
        Manifest manifest = manifest(name);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int classes = 0;
        try (ZipFile runner = new ZipFile(runnerJar.toFile());
                JarOutputStream out = new JarOutputStream(bytes, manifest)) {
            Enumeration<? extends ZipEntry> entries = runner.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (e.isDirectory()
                        || !e.getName().startsWith(PACKAGE)
                        || !e.getName().endsWith(".class")) continue;
                out.putNextEntry(new JarEntry(e.getName()));
                try (InputStream in = runner.getInputStream(e)) {
                    in.transferTo(out);
                }
                out.closeEntry();
                classes++;
            }
        }
        if (classes == 0) throw new IOException(runnerJar + " holds no environment probe");
        Files.createDirectories(cacheDir);
        AtomicWrites.replace(jar, bytes.toByteArray());
        return jar;
    }
}
