// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.host.Os;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.host.SearchPath;
import cc.jumpkick.model.RepositorySpec;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Fat-jar size against Gradle Shadow and Maven Shade, over the fixture apps in {@code
 * bench/jar-size/}. Each fixture is copied out of the tree three times and packaged by the installed
 * {@code jk}, by the repo's Gradle wrapper with the fixture's Shadow build, and by Maven with the
 * fixture's Shade build, all over the versions the fixture pins. The report prints, per tool, the
 * jar bytes, entry counts, compressed payload, extra-field bytes, the STORED/DEFLATE split and the
 * entry-name set difference both ways, then attributes every byte of the jk-minus-tool delta to a
 * named cause and compares jk's bytes with {@code jar-size-baseline.toml}.
 *
 * <pre>
 *   ./gradlew :engine:benchTest --tests cc.jumpkick.compile.JarSizeBenchTest
 * </pre>
 *
 * <p>Needs {@code jk} on {@code PATH} and Maven Central. {@code mvn} is used from {@code PATH} when
 * present; otherwise a Maven distribution is fetched once into the work directory. Fixture copies
 * and tool output live under {@code java.io.tmpdir/jar-size-bench}.
 */
@Tag("bench")
@Timeout(value = 40, unit = TimeUnit.MINUTES)
class JarSizeBenchTest {

    /** jk's fat jar may not exceed Shadow's by more than this, whatever the baseline says. */
    static final double GAP_CEILING = 0.01;

    private static final String MAVEN_VERSION = "3.9.16";
    private static final String FIXTURE_VERSION = "0.1.0";

    /** A fixture under {@code bench/jar-size/}; {@code boot} fixtures also produce a Boot jar. */
    record Fixture(String name, String projectPrefix, boolean boot) {}

    @Test
    void plain_cli() throws Exception {
        run(new Fixture("plain-cli", "demo/", false));
    }

    @Test
    void kotlin_cli() throws Exception {
        run(new Fixture("kotlin-cli", "demo/", false));
    }

    @Test
    void micronaut_http() throws Exception {
        run(new Fixture("micronaut-http", "demo/", false));
    }

    @Test
    void spring_boot_web() throws Exception {
        run(new Fixture("spring-boot-web", "demo/", true));
    }

    // ---- orchestration ------------------------------------------------------------------------

    private void run(Fixture fx) throws Exception {
        Path jk = onPath(Os.isWindows() ? List.of("jk.exe", "jk.bat", "jk") : List.of("jk"));
        assumeTrue(jk != null, "the installed jk must be on PATH");
        Path root = RepoRoot.find(JarSizeBenchTest.class);
        Path fixture = RepoRoot.dir(JarSizeBenchTest.class, "bench/jar-size/" + fx.name());
        Path work = Path.of(System.getProperty("java.io.tmpdir")).resolve("jar-size-bench");
        Path gradlew = root.resolve(Os.isWindows() ? "gradlew.bat" : "gradlew");
        Path mvn = maven(work.resolve("tools"));

        Path jkDir = fresh(fixture, work.resolve(fx.name()).resolve("jk"));
        Path gradleDir = fresh(fixture, work.resolve(fx.name()).resolve("gradle"));
        Path mavenDir = fresh(fixture, work.resolve(fx.name()).resolve("maven"));

        exec(jkDir, jk.toString(), "build", "--no-ansi");
        List<String> gradleArgs = new ArrayList<>(
                List.of(gradlew.toString(), "-p", gradleDir.toString(), "--no-daemon", "-q", "--console=plain"));
        if (fx.boot()) gradleArgs.add("bootJar");
        gradleArgs.add("shadowJar");
        exec(gradleDir, gradleArgs.toArray(String[]::new));
        exec(mavenDir, mvn.toString(), "-q", "-B", "-DskipTests", "package");

        String v = fx.name() + "-" + FIXTURE_VERSION;
        JarAnatomy.Archive jkJar = JarAnatomy.read(jkDir.resolve("target").resolve(v + "-all.jar"));
        JarAnatomy.Archive shadow =
                JarAnatomy.read(gradleDir.resolve("build/libs").resolve(v + "-all.jar"));
        JarAnatomy.Archive shade = JarAnatomy.read(mavenDir.resolve("target").resolve(v + "-shaded.jar"));

        Versions versions = Versions.of(fixture, jk, gradlew, mvn);
        System.out.println();
        System.out.println("## " + fx.name() + "  (" + versions + ")");
        System.out.println();
        Map<String, JarAnatomy.Archive> flat = new LinkedHashMap<>();
        flat.put("jk assembly", jkJar);
        flat.put("Gradle Shadow " + versions.shadow, shadow);
        flat.put("Maven Shade " + versions.shade, shade);
        table(flat, jkJar);
        attribution(jkJar, shadow, "Shadow", fx.projectPrefix());
        attribution(jkJar, shade, "Shade", fx.projectPrefix());
        sharedWaste(jkJar, shadow);
        deflateLevels(jkJar);

        JarSizeBaseline baseline =
                JarSizeBaseline.read(RepoRoot.file(JarSizeBenchTest.class, "jar-size-baseline.toml"));
        System.out.printf(
                "context %s: shadow-bytes = %d, shade-bytes = %d%n", fx.name(), shadow.bytes(), shade.bytes());
        baseline.check(fx.name(), "jk-bytes", jkJar.bytes());
        assertTrue(
                jkJar.bytes() <= shadow.bytes() * (1 + GAP_CEILING),
                () -> String.format(
                        "jk fat jar %,d bytes is more than %.0f%% above Shadow's %,d",
                        jkJar.bytes(), GAP_CEILING * 100, shadow.bytes()));

        if (fx.boot()) {
            JarAnatomy.Archive jkBoot = JarAnatomy.read(jkDir.resolve("target").resolve(v + ".jar"));
            JarAnatomy.Archive gradleBoot =
                    JarAnatomy.read(gradleDir.resolve("build/libs").resolve(v + ".jar"));
            JarAnatomy.Archive mavenBoot =
                    JarAnatomy.read(mavenDir.resolve("target").resolve(v + "-boot.jar"));
            System.out.println();
            System.out.println("### Boot jar layout");
            System.out.println();
            Map<String, JarAnatomy.Archive> boot = new LinkedHashMap<>();
            boot.put("jk Boot jar", jkBoot);
            boot.put("Gradle bootJar " + versions.boot, gradleBoot);
            boot.put("Maven repackage " + versions.boot, mavenBoot);
            table(boot, jkBoot);
            attribution(jkBoot, gradleBoot, "Gradle bootJar", "BOOT-INF/classes/" + fx.projectPrefix());
            System.out.printf(
                    "context %s: gradle-boot-bytes = %d, maven-boot-bytes = %d%n",
                    fx.name(), gradleBoot.bytes(), mavenBoot.bytes());
            baseline.check(fx.name(), "jk-boot-bytes", jkBoot.bytes());
        }
    }

    // ---- report -------------------------------------------------------------------------------

    private static void table(Map<String, JarAnatomy.Archive> rows, JarAnatomy.Archive jk) {
        System.out.println("| tool | jar bytes | entries | files | dirs | classes | compressed payload"
                + " | extra-field bytes | STORED | DEFLATE | only in jk | only in tool |");
        System.out.println("|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|");
        for (Map.Entry<String, JarAnatomy.Archive> row : rows.entrySet()) {
            JarAnatomy.Archive a = row.getValue();
            assertEquals(0, a.unaccounted(), "every byte of " + a.file() + " is accounted for");
            long onlyJk = jk.entries().keySet().stream()
                    .filter(n -> !a.entries().containsKey(n))
                    .count();
            long onlyTool = a.entries().keySet().stream()
                    .filter(n -> !jk.entries().containsKey(n))
                    .count();
            System.out.printf(
                    "| %s | %,d | %,d | %,d | %,d | %,d | %,d | %,d | %,d | %,d | %s | %s |%n",
                    row.getKey(),
                    a.bytes(),
                    a.entries().size(),
                    a.files(),
                    a.dirs(),
                    a.classes(),
                    a.payload(),
                    a.extraBytes(),
                    a.stored(),
                    a.deflated(),
                    a == jk ? "—" : String.valueOf(onlyJk),
                    a == jk ? "—" : String.valueOf(onlyTool));
        }
        System.out.println();
    }

    private static void attribution(JarAnatomy.Archive jk, JarAnatomy.Archive other, String tool, String prefix) {
        JarAnatomy.Attribution a = JarAnatomy.attribute(jk, other, prefix);
        assertEquals(a.total, a.sum(), "attribution buckets sum to the byte delta");
        System.out.printf("jk − %s = %+,d bytes (%+.3f%%):%n", tool, a.total, 100.0 * a.total / other.bytes());
        for (Map.Entry<String, Long> b : a.buckets().entrySet()) {
            List<String> names = a.names(b.getKey());
            String sample = names.size() <= 3
                    ? String.join(", ", names)
                    : String.join(", ", names.subList(0, 3)) + ", … (" + names.size() + " entries)";
            System.out.printf("  %+,10d  %s  [%s]%n", b.getValue(), b.getKey(), sample);
        }
        System.out.println();
    }

    /**
     * Bytes nothing reads at run time: the per-dependency Maven metadata jk drops and Shadow keeps,
     * and the licence and notice files both carry because they must.
     */
    private static void sharedWaste(JarAnatomy.Archive jk, JarAnatomy.Archive shadow) {
        System.out.printf(
                "metadata: META-INF/maven/** = %,d bytes in %,d entries (jk) / %,d bytes (Shadow);"
                        + " licence and notice files = %,d bytes in %,d entries (jk) / %,d bytes (Shadow)%n",
                jk.footprint(JarAnatomy::isMavenMetadata),
                jk.count(e -> JarAnatomy.isMavenMetadata(e) && !e.directory()),
                shadow.footprint(JarAnatomy::isMavenMetadata),
                jk.footprint(JarAnatomy::isLicenceFile),
                jk.count(JarAnatomy::isLicenceFile),
                shadow.footprint(JarAnatomy::isLicenceFile));
        System.out.println();
    }

    /**
     * Re-deflate every entry of jk's jar at the level jk uses and at the maximum, in-process, best of
     * three: the size a higher level would buy and the CPU it would cost, so the choice stays a
     * measured one.
     */
    private static void deflateLevels(JarAnatomy.Archive jk) throws IOException {
        List<byte[]> contents = new ArrayList<>();
        try (ZipFile zf = new ZipFile(jk.file().toFile())) {
            var en = zf.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                if (e.isDirectory()) continue;
                try (InputStream in = zf.getInputStream(e)) {
                    contents.add(in.readAllBytes());
                }
            }
        }
        long[] payload = new long[10];
        long[] best = new long[10];
        byte[] buf = new byte[1 << 16];
        for (int level : new int[] {6, 9}) {
            best[level] = Long.MAX_VALUE;
            for (int rep = 0; rep < 3; rep++) {
                long t0 = System.nanoTime();
                long out = 0;
                Deflater def = new Deflater(level, true);
                for (byte[] data : contents) {
                    def.reset();
                    def.setInput(data);
                    def.finish();
                    while (!def.finished()) out += def.deflate(buf);
                }
                def.end();
                payload[level] = out;
                best[level] = Math.min(best[level], (System.nanoTime() - t0) / 1_000_000);
            }
        }
        System.out.printf(
                "deflate: level 6 (jk, Shadow, Shade) payload %,d in %d ms; level 9 payload %,d in %d ms"
                        + " → level 9 saves %,d bytes (%.3f%% of the jar) for %+.0f%% deflate CPU%n",
                payload[6],
                best[6],
                payload[9],
                best[9],
                payload[6] - payload[9],
                100.0 * (payload[6] - payload[9]) / jk.bytes(),
                best[6] == 0 ? 0.0 : 100.0 * (best[9] - best[6]) / best[6]);
        System.out.println();
    }

    /** The tool versions a run compares, read from the fixture's own build files and the tools. */
    record Versions(String jk, String gradle, String maven, String shadow, String shade, String boot, String kotlin) {

        static Versions of(Path fixture, Path jk, Path gradlew, Path mvn) throws IOException {
            String gradleBuild = Files.readString(fixture.resolve("build.gradle.kts"));
            String pom = Files.readString(fixture.resolve("pom.xml"));
            String wrapper = Files.readString(gradlew.resolveSibling("gradle/wrapper/gradle-wrapper.properties"));
            return new Versions(
                    firstLine(jk.toString(), "--version"),
                    find(wrapper, "gradle-([0-9.]+)-bin\\.zip"),
                    firstLine(mvn.toString(), "--version"),
                    find(gradleBuild, "id\\(\"com\\.gradleup\\.shadow\"\\) version \"([^\"]+)\""),
                    find(pom, "maven-shade-plugin</artifactId>\\s*<version>([^<]+)<"),
                    find(gradleBuild, "id\\(\"org\\.springframework\\.boot\"\\) version \"([^\"]+)\""),
                    find(gradleBuild, "kotlin\\(\"jvm\"\\) version \"([^\"]+)\""));
        }

        private static String find(String text, String regex) {
            Matcher m = Pattern.compile(regex).matcher(text);
            return m.find() ? m.group(1) : "—";
        }

        @Override
        public String toString() {
            return jk + "; Gradle " + gradle + "; " + maven + "; Shadow " + shadow + "; Shade " + shade
                    + (boot.equals("—") ? "" : "; Spring Boot " + boot)
                    + (kotlin.equals("—") ? "" : "; Kotlin " + kotlin)
                    + "; JDK " + Runtime.version();
        }
    }

    // ---- tools --------------------------------------------------------------------------------

    private static @Nullable Path onPath(List<String> names) {
        String path = System.getenv("PATH");
        if (path == null) return null;
        for (String dir : SearchPath.entries(path)) {
            for (String name : names) {
                Path candidate = Path.of(dir).resolve(name);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate;
            }
        }
        return null;
    }

    /** {@code mvn} from PATH, else a Maven distribution fetched from Central once into {@code tools}. */
    private static Path maven(Path tools) throws Exception {
        Path onPath = onPath(Os.isWindows() ? List.of("mvn.cmd", "mvn") : List.of("mvn"));
        if (onPath != null) return onPath;
        Path home = tools.resolve("apache-maven-" + MAVEN_VERSION);
        Path mvn = home.resolve("bin").resolve(Os.isWindows() ? "mvn.cmd" : "mvn");
        if (Files.isRegularFile(mvn)) return mvn;
        Files.createDirectories(tools);
        String zipName = "apache-maven-" + MAVEN_VERSION + "-bin.zip";
        URI uri = URI.create(
                RepositorySpec.MAVEN_CENTRAL.url() + "org/apache/maven/apache-maven/" + MAVEN_VERSION + "/" + zipName);
        Path zip = tools.resolve(zipName);
        try (HttpClient client = HttpClient.newHttpClient()) {
            HttpResponse<Path> response =
                    client.send(HttpRequest.newBuilder(uri).GET().build(), HttpResponse.BodyHandlers.ofFile(zip));
            assertEquals(200, response.statusCode(), "download " + uri);
        }
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(zip))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                Path out = tools.resolve(e.getName()).normalize();
                assertTrue(out.startsWith(tools), "zip entry escapes the tools dir: " + e.getName());
                if (e.isDirectory()) {
                    Files.createDirectories(out);
                } else {
                    Files.createDirectories(out.getParent());
                    Files.copy(in, out);
                }
            }
        }
        if (!Os.isWindows()) {
            Files.setPosixFilePermissions(mvn, EnumSet.allOf(PosixFilePermission.class));
        }
        return mvn;
    }

    /** A clean copy of {@code fixture} at {@code target}, so the three tools never share an output dir. */
    private static Path fresh(Path fixture, Path target) throws IOException {
        PathUtil.deleteRecursivelyOrThrow(target);
        Files.createDirectories(target.getParent());
        PathUtil.copyTree(fixture, target);
        return target;
    }

    private static void exec(Path cwd, String... command) throws Exception {
        Path log = cwd.resolveSibling(cwd.getFileName() + ".log");
        ProcessBuilder pb = new ProcessBuilder(command)
                .directory(cwd.toFile())
                .redirectErrorStream(true)
                .redirectOutput(log.toFile());
        // The test task relocates JK_HOME to an empty home; the bench measures the installed product.
        pb.environment().keySet().removeIf(k -> k.startsWith("JK_"));
        Process p = pb.start();
        boolean done = p.waitFor(30, TimeUnit.MINUTES);
        if (!done) p.destroyForcibly();
        assertTrue(done && p.exitValue() == 0, () -> {
            String tail;
            try {
                List<String> lines = Files.readAllLines(log);
                tail = String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
            } catch (IOException e) {
                tail = "(no log: " + e + ")";
            }
            return String.join(" ", command) + " in " + cwd + " failed (exit " + (done ? p.exitValue() : "timeout")
                    + "):\n" + tail;
        });
    }

    private static String firstLine(String... command) throws IOException {
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (InputStream in = p.getInputStream()) {
            String out = new String(in.readAllBytes()).strip();
            return out.isEmpty()
                    ? String.join(" ", command)
                    : out.lines().findFirst().orElse("");
        }
    }
}
