// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime.base;

import cc.jumpkick.cache.JkStores;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.engine.plugin.JobWorkers;
import cc.jumpkick.host.Classpaths;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.jdk.JdkFingerprint;
import cc.jumpkick.model.Coordinate;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.repo.MavenRepo;
import cc.jumpkick.util.JkDirs;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.jspecify.annotations.Nullable;

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
public final class BuildLogicGroovyHost {

    /** Match product catalog pin ({@code libs.versions.groovy}); scripts get Groovy 5. */
    static final String GROOVY_VER = "5.0.4";

    private static final String ANT_VER = "1.10.14";
    /** Ant's optional tasks (regexpmapper, …), which Netty's codegen.groovy still uses. */
    private static final String ANT_OPTIONAL_VER = "1.5.3-1";

    private static final String IVY_VER = "2.5.3";

    /** How often a run looks at its step's cancel probe while the child evaluates. */
    private static final long CANCEL_POLL_MS = 50;

    private BuildLogicGroovyHost() {}

    /**
     * Run one script, returning its captured stdout/stderr. See {@link BuildLogicKtsHost#evaluate}.
     * The child is killed when {@code cancelled} — the owning step's probe, covering a sibling
     * step's failure as well as Ctrl-C — flips while it runs, and the run reports as cancelled with
     * the jk prefix rather than as a script failure.
     */
    public static String evaluate(Path script, Path projectDir, Path outDir, BooleanSupplier cancelled)
            throws Exception {
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
            // Drained on a thread of its own so this one can watch the cancel probe between
            // polls; a blocking read would see the cancel only once the script chose to exit.
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Thread pump = SessionContext.startVirtual("jk-groovy-pump", () -> {
                try {
                    p.getInputStream().transferTo(captured);
                } catch (IOException gone) {
                    // The child is gone; what it printed so far is the log.
                }
            });
            while (!p.waitFor(CANCEL_POLL_MS, TimeUnit.MILLISECONDS)) {
                if (cancelled.getAsBoolean()) {
                    p.destroyForcibly();
                    p.waitFor();
                    pump.join();
                    throw new IllegalStateException(
                            "[build] logic: " + script.getFileName() + " cancelled with its build");
                }
            }
            pump.join();
            String log = captured.toString(StandardCharsets.UTF_8);
            int exit = p.exitValue();
            if (exit != 0) {
                // The compiler dump is the message, verbatim — its first error line already names
                // file and line. The one jk-authored prefix is registerScripts', not this host's.
                String detail = log.strip();
                throw new IllegalStateException(
                        detail.isEmpty() ? "groovy exited " + exit + " with no output" : detail);
            }
            return log;
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
                } catch (Throwable ignored) {
                  // no Ant on the child's classpath: the script has no `ant` binding
                }
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

    /** One child-classpath jar: Maven coordinate plus the sha256 jk pins for it. */
    record PinnedJar(String group, String artifact, String version, String sha256) {
        Coordinate coordinate() {
            return Coordinate.of(group, artifact, version);
        }

        String fileName() {
            return artifact + "-" + version + ".jar";
        }
    }

    /**
     * The seven jars with the digests jk pins for them. They become the classpath of a JVM that
     * evaluates the user's script, so they are code: bytes that do not hash to a pin are never
     * published and never trusted. Bump a digest with its version.
     */
    static final List<PinnedJar> PINNED_JARS = List.of(
            new PinnedJar(
                    "org.apache.groovy",
                    "groovy",
                    GROOVY_VER,
                    "35d6e6a658ebf549c736c06fbaf370521a3a72e3a044f6a868b33a0844d40d6c"),
            new PinnedJar(
                    "org.apache.groovy",
                    "groovy-ant",
                    GROOVY_VER,
                    "334fa7bafc99c3f9b4cea0d28a5e0cdafe69f61c0e8d0c09a8e2d8bd1b8b3f46"),
            new PinnedJar(
                    "org.apache.groovy",
                    "groovy-xml",
                    GROOVY_VER,
                    "bad962021645d20a1c7c297d4d18f42f2d07599777381811dfda059d748f562d"),
            new PinnedJar(
                    "org.apache.ivy",
                    "ivy",
                    IVY_VER,
                    "9502aa5aabf0b1484924a13ef4b5d24f121f7f91aab6768f11cc9d0906c0803b"),
            new PinnedJar(
                    "org.apache.ant",
                    "ant",
                    ANT_VER,
                    "4cbbd9243de4c1042d61d9a15db4c43c90ff93b16d78b39481da1c956c8e9671"),
            new PinnedJar(
                    "org.apache.ant",
                    "ant-launcher",
                    ANT_VER,
                    "f0909725a7a24e393888f3fbb558347abf506ce2f7ebc581ff26331b94d951a5"),
            new PinnedJar(
                    "ant",
                    "ant-optional",
                    ANT_OPTIONAL_VER,
                    "206b6edca13aeafe4f10995589b7cefd7aff403a24cb34cf085893e2b3e44b19"));

    /**
     * The verified closure, once per engine process. {@link #published} hashes every jar (groovy
     * alone is ~8 MB), and a truncated download only needs catching on the first touch; before
     * this every script evaluation on a cache miss re-hashed all seven.
     */
    private static volatile Path @Nullable [] verifiedJars;

    /** Verified jars under the tool cache; anything absent or off-pin is re-fetched from Central. */
    static Path[] ensureJars() throws IOException {
        Path[] known = verifiedJars;
        if (known != null) return known;
        Path cache = toolCache();
        Files.createDirectories(cache);
        Path[] jars = new Path[PINNED_JARS.size()];
        for (int i = 0; i < jars.length; i++) {
            PinnedJar jar = PINNED_JARS.get(i);
            Path out = cache.resolve(jar.fileName());
            if (!published(out, jar.sha256())) {
                publish(fetchVerified(jar), out, jar.sha256());
            }
            jars[i] = out;
        }
        verifiedJars = jars;
        return jars;
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
     * True when {@code out} holds exactly the pinned bytes. Presence alone is not enough: the old
     * {@code size > 0} test accepted a download truncated by a killed engine <em>forever</em>, and
     * the only symptom was a bare {@code ClassNotFoundException} from the child JVM.
     */
    static boolean published(Path out, String sha256) {
        try {
            return Files.isRegularFile(out) && sha256.equalsIgnoreCase(Hashing.sha256Hex(out));
        } catch (IOException unreadable) {
            return false;
        }
    }

    /**
     * Through {@link MavenRepo} — the verified fetch jk already has: streamed to a temp in a CAS
     * shard, hashed while written, deleted on failure, mirror-and-cooldown routed. Never a second
     * hand-rolled downloader.
     */
    private static Path fetchVerified(PinnedJar jar) throws IOException {
        MavenRepo central = new MavenRepo(
                RepositorySpec.MAVEN_CENTRAL.name(),
                RepositorySpec.MAVEN_CENTRAL.url(),
                Http.forRepositories(),
                JkStores.storeCas());
        try {
            return central.fetchArtifact(jar.coordinate(), jar.sha256(), () -> false)
                    .cachePath();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted fetching " + jar.fileName(), e);
        }
    }

    /**
     * Verify {@code source} against the pin, then publish atomically: temp beside the target, one
     * rename. A partial file can never appear at {@code out}, and a mismatch names the jar and the
     * directory so the user can act on it.
     */
    static void publish(Path source, Path out, String sha256) throws IOException {
        String actual = Hashing.sha256Hex(source);
        if (!sha256.equalsIgnoreCase(actual)) {
            throw new IOException("sha256 mismatch for " + out.getFileName() + " under " + out.getParent()
                    + " — expected " + sha256 + ", got " + actual);
        }
        Path tmp = Files.createTempFile(out.getParent(), out.getFileName().toString() + ".", ".tmp");
        try {
            Files.copy(source, tmp, StandardCopyOption.REPLACE_EXISTING);
            Files.move(tmp, out, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }
}
