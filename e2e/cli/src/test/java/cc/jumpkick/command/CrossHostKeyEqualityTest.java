// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.cli.engine.EngineTestSupport;
import cc.jumpkick.host.Os;
import cc.jumpkick.jdk.JavaHomes;
import cc.jumpkick.testing.Symlinks;
import cc.jumpkick.util.JkOwnership;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guard for every cross-machine key leak: one project built by two engines that share nothing a
 * shared cache would not share, each started from a client process of its own with an environment
 * of its own, computes every action key alike. The two sides differ in the jk home (and so the
 * store, cache, state and engine), the checkout path and its depth, {@code $HOME}, the user name,
 * the temp root, the locale ({@code en_US.UTF-8} against {@code de_DE.UTF-8} with {@code LC_ALL})
 * and the time zone, and in the JDK each builds on: the module says {@code java = 21}, and each
 * side's JDK root ({@code JK_JDKS_DIR}, the only place it looks: {@code JK_JDK_PROBES=jk}, its own
 * {@code $HOME}, no {@code JAVA_HOME}) holds one JDK, Temurin 25.0.5 on one and an OpenJDK 27.0.1
 * build on the other (the running JDK under a {@code release} file of each name). Compile and test
 * keys name the level, not the JDK, so they match; each record names the JDK that made it.
 * Both share the Maven local repository the tier points at, so nothing is fetched.
 *
 * <p>Then the 27 side takes the 25 side's cache: under {@code jdk-match = level} its build is
 * served, with a note naming the JDK the results were made on; under the default {@code major} it
 * builds again on its own and says once why, naming both ways out. A third host on Temurin
 * 25.0.7, another patch of the first host's major, handed the first host's cache, replays its suite
 * under the default match with no test JVM started: what the suite was found to read is kept under
 * the module's portable tag with checkout-relative names, so another checkout finds it.
 *
 * <p>The comparison is over each home's task pointers ({@code cache/actions/tasks/<step>@<tag>},
 * the tag being the project id and the output's workspace-relative path, both portable). A key that
 * differs fails the test naming the step and each input whose value differs: from the two action
 * records' {@code INPUT} lines, and for a suite from the stamp tokens each home keeps beside its
 * discovered inputs. Same-JVM tests cannot give this: the locale, time zone, user and {@code $HOME}
 * an engine sees are its process's.
 */
@Tag("release")
class CrossHostKeyEqualityTest {

    private static final String MANIFEST = """
            group   = "com.example"
            name    = "keyeq"
            version = "1.0.0"
            java    = 21

            [application]
            main = "com.example.Main"
            """;

    private static final String MAIN = """
            package com.example;

            public final class Main {
                public static String greeting() {
                    return Main.class.getResource("/greeting.txt") != null ? "with" : "without";
                }

                public static void main(String[] args) {
                    System.out.println(greeting());
                }
            }
            """;

    private static final String TEST = """
            package com.example;

            import static org.junit.jupiter.api.Assertions.assertEquals;

            import java.util.Locale;
            import java.util.TimeZone;
            import org.junit.jupiter.api.Test;

            class MainTest {
                @Test
                void greets() {
                    assertEquals("with", Main.greeting());
                    assertEquals("1,234.50", String.format("%,.2f", 1234.5));
                    assertEquals("en", Locale.getDefault().getLanguage());
                    assertEquals("UTC", TimeZone.getDefault().getID());
                }
            }
            """;

    /** One side of the comparison: a host as a client process and its engine see it. */
    private record Side(String name, Path root, Path home, Path checkout, Map<String, String> env) {}

    @Test
    @Timeout(value = 30, unit = TimeUnit.MINUTES) // three cold engines in fresh homes, the 27 side twice more
    void two_hosts_compute_every_action_key_alike(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "the sides are POSIX shells' environments");
        Side a = side(tmp, "a", "work/app", "keyeqa", "en_US.UTF-8", "America/New_York");
        Side b = side(tmp, "b", "elsewhere/much/deeper/checkout-two", "keyeqb", "de_DE.UTF-8", "Asia/Tokyo");
        jdkInventory(a, "temurin-25.0.5", "Eclipse Adoptium", "25.0.5", "25.0.5+11-LTS");
        jdkInventory(b, "openjdk-27.0.1", "Oracle Corporation", "27.0.1", "27.0.1+9");
        Side c = side(tmp, "c", "third/clone", "keyeqc", "en_US.UTF-8", "Europe/Berlin");
        jdkInventory(c, "temurin-25.0.7", "Eclipse Adoptium", "25.0.7", "25.0.7+6-LTS");
        b.env().put("LC_ALL", "de_DE.UTF-8");
        b.env().put("LANGUAGE", "de");
        try {
            writeProject(a.checkout());
            assertThat(jk(a, "build")).as("site a builds").isZero();
            copySources(a.checkout(), b.checkout());
            assertThat(jk(b, "build")).as("site b builds").isZero();

            Map<String, String> keysA = tasks(a.home());
            Map<String, String> keysB = tasks(b.home());
            // Each home's engine also builds a warm-up project of its own under its own path; the
            // steps of the project under test are the ones both homes hold. A tag that leaked the
            // host would leave a step kind out of this intersection.
            keysA.keySet().retainAll(keysB.keySet());
            assertThat(keysA.keySet())
                    .as("the project's steps both sites keyed, by portable task name")
                    .anyMatch(t -> t.startsWith("compile-main@"))
                    .anyMatch(t -> t.startsWith("compile-test@"))
                    .anyMatch(t -> t.startsWith("package-jar@"))
                    .anyMatch(t -> t.startsWith("run-tests@"));

            System.out.println("cross-host key equality: comparing " + keysA.size() + " steps: " + keysA.keySet());
            List<String> leaks = new ArrayList<>();
            for (String task : keysA.keySet()) {
                String ka = keysA.get(task);
                String kb = keysB.get(task);
                if (kb == null || ka.equals(kb)) continue;
                leaks.add(task + " keys apart: " + differingInputs(task, a, ka, b, kb));
            }
            assertThat(leaks)
                    .as("action keys that differ between two hosts building one commit, with the inputs that"
                            + " differ (site a | site b); each is a leak of the host into the key")
                    .isEmpty();

            // The suite runs on the JDK the module resolved, from the side's own root; javac may run
            // in the engine's own JVM, whose JDK is not the module's choice, so the suite is the probe.
            String suite = keysA.keySet().stream()
                    .filter(t -> t.startsWith("run-tests@") && !t.contains("#"))
                    .findFirst()
                    .orElseThrow();
            assertThat(toolchain(a.home(), keysA.getOrDefault(suite, "")))
                    .as("site a's record names the JDK it built on, from its own JDK root only")
                    .contains("/25.0.5+11-LTS/");
            assertThat(toolchain(b.home(), keysB.getOrDefault(suite, "")))
                    .as("site b's record names the JDK it built on, from its own JDK root only")
                    .contains("/27.0.1+9/");

            // The 27 side takes the 25 side's cache, as a shared cache would hand it over.
            jk(b, "engine", "stop");
            replaceCache(a.home(), b.home());
            deleteTree(b.checkout().resolve("target")); // a fresh clone: nothing of its own built yet
            b.env().put("JK_CACHE_JDK_MATCH", "level");
            assertThat(jk(b, "build")).as("site b builds from site a's records").isZero();
            assertThat(b.root().resolve("jk-build.log"))
                    .as("a hit made on another JDK says which")
                    .content()
                    .contains("made on")
                    .contains("25.0.5");
            jk(b, "engine", "stop");
            b.env().remove("JK_CACHE_JDK_MATCH");
            Files.move(b.root().resolve("jk-build.log"), b.root().resolve("jk-build-level.log"));
            deleteTree(b.checkout().resolve("target"));
            assertThat(jk(b, "explain", "-v"))
                    .as("site b forecasts under jdk-match = major")
                    .isZero();
            assertThat(b.root().resolve("jk-explain--v.log"))
                    .as("under major, records made on JDK 25 do not serve 27, and jk says how to change that")
                    .content()
                    .contains("made on JDK 25")
                    .contains("jk jdk install temurin-25")
                    .contains("jdk-match = \"level\"");

            // A third host on another patch of site a's major, handed site a's cache: under the
            // default match it replays a's suite, no test JVM started, and says where it was made.
            replaceCache(a.home(), c.home());
            copySources(a.checkout(), c.checkout());
            assertThat(jk(c, "build")).as("site c builds from site a's records").isZero();
            assertThat(c.root().resolve("jk-build.log"))
                    .as("the suite replays from the shared cache: no test JVM starts")
                    .content()
                    .doesNotContain("test JVM flags")
                    .contains("made on eclipse-adoptium 25.0.5+11-LTS");
        } finally {
            jk(a, "engine", "stop");
            jk(b, "engine", "stop");
            jk(c, "engine", "stop");
        }
    }

    /** {@code TOOLCHAIN} of the record {@code key} names in {@code home}'s action cache. */
    private static String toolchain(Path home, String key) throws IOException {
        Path record = home.resolve("cache/actions/keys").resolve(key);
        for (String line : Files.readAllLines(record, StandardCharsets.UTF_8)) {
            if (line.startsWith("TOOLCHAIN ")) return line.substring("TOOLCHAIN ".length());
        }
        return "";
    }

    /** The id jk keeps a module's discovered suite reads under: its real path's digest. */
    private static String moduleId(Path dir) throws IOException {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256")
                    .digest(dir.toRealPath().toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTree(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) return;
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path f : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(f);
        }
    }

    /** {@code to}'s action cache and CAS become a copy of {@code from}'s. */
    private static void replaceCache(Path from, Path to) throws IOException {
        Path src = from.resolve("cache");
        Path dst = to.resolve("cache");
        deleteTree(dst);
        try (Stream<Path> files = Files.walk(src)) {
            for (Path f : files.toList()) {
                Path t = dst.resolve(src.relativize(f).toString());
                if (Files.isDirectory(f)) Files.createDirectories(t);
                else if (Files.isRegularFile(f)) Files.copy(f, t);
            }
        }
    }

    private static Side side(Path tmp, String name, String checkout, String user, String lang, String tz)
            throws IOException {
        Path root = Files.createDirectories(tmp.resolve(name));
        Path home = Files.createDirectories(root.resolve("jk-home"));
        Path fakeHome = Files.createDirectories(root.resolve("user-home"));
        Path temp = Files.createDirectories(root.resolve("tmp"));
        materializeEngine(home);
        Map<String, String> env = new LinkedHashMap<>();
        env.put("PATH", "/usr/local/bin:/usr/bin:/bin");
        env.put("HOME", fakeHome.toString());
        env.put("USER", user);
        env.put("LOGNAME", user);
        env.put("LANG", lang);
        env.put("TZ", tz);
        env.put("TMPDIR", temp.toString());
        env.put("JK_HOME", home.toString());
        // Maven's settings.xml off: the developer's mirrors and proxies are not the code under test.
        env.put("JK_M2_SETTINGS", root.resolve("no-settings.xml").toString());
        // The tier's pooled Maven local repository: JUnit is there, so neither site fetches.
        String m2 = System.getenv("JK_M2_LOCAL");
        if (m2 != null) env.put("JK_M2_LOCAL", m2);
        env.put("JK_NO_ANSI", "1");
        return new Side(name, root, home, Files.createDirectories(root.resolve(checkout)), env);
    }

    /**
     * A JDK root of the side's own ({@code JK_JDKS_DIR}) holding one JDK, {@code dir}: the running
     * JDK, linked file by file, under a {@code release} file naming {@code implementor} and the
     * version. Discovery looks nowhere else ({@code JK_JDK_PROBES=jk}).
     */
    private static void jdkInventory(Side side, String dir, String implementor, String version, String runtime)
            throws IOException {
        Path real = JavaHomes.runningJavaHome().toRealPath();
        Path root = Files.createDirectories(side.root().resolve("jdks"));
        Path fake = Files.createDirectories(root.resolve(dir));
        try (Stream<Path> children = Files.list(real)) {
            for (Path child : children.toList()) {
                String name = child.getFileName().toString();
                // The real JDK's own marker (read-only when jk installed it) is not linked:
                // the fake gets its own below.
                if (name.equals("release") || name.equals(JkOwnership.MARKER)) continue;
                if (name.equals("bin")) linkFiles(child, Files.createDirectories(fake.resolve(name)));
                else Symlinks.create(fake.resolve(name), child);
            }
        }
        StringBuilder release = new StringBuilder();
        for (String line : Files.readAllLines(real.resolve("release"), StandardCharsets.UTF_8)) {
            if (line.startsWith("IMPLEMENTOR=")) line = "IMPLEMENTOR=\"" + implementor + "\"";
            else if (line.startsWith("IMPLEMENTOR_VERSION=")) continue;
            else if (line.startsWith("JAVA_VERSION=")) line = "JAVA_VERSION=\"" + version + "\"";
            else if (line.startsWith("JAVA_RUNTIME_VERSION=")) line = "JAVA_RUNTIME_VERSION=\"" + runtime + "\"";
            release.append(line).append('\n');
        }
        Files.writeString(fake.resolve("release"), release.toString());
        // Installed by jk, as the root's probe asks: without the marker it lists nothing, and a build
        // that finds no JDK of its own takes the first javac on PATH (macOS's /usr/bin shim).
        Files.writeString(fake.resolve(JkOwnership.MARKER), "");
        side.env().put("JK_JDKS_DIR", root.toString());
        side.env().put("JK_JDK_PROBES", "jk");
    }

    /**
     * The launchers as files of the fake home's own (hard links, else copies): a launcher reached
     * through a symlink resolves to the real JDK's home, and the build would name that JDK.
     */
    private static void linkFiles(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.list(from)) {
            for (Path f : files.toList()) {
                Path t = to.resolve(f.getFileName().toString());
                try {
                    Files.createLink(t, f.toRealPath());
                } catch (IOException | UnsupportedOperationException crossDevice) {
                    Files.copy(f, t, StandardCopyOption.COPY_ATTRIBUTES);
                }
            }
        }
    }

    /** The engine under test, materialized under {@code home} as the tier does under its own. */
    private static void materializeEngine(Path home) {
        String property = "jk.env.JK_HOME";
        String was = System.getProperty(property);
        System.setProperty(property, home.toString());
        try {
            EngineTestSupport.ensureEngineMaterialized();
        } finally {
            if (was == null) System.clearProperty(property);
            else System.setProperty(property, was);
        }
    }

    /**
     * {@code jk <args>} in {@code side}'s checkout as a process of its own: this JVM's classpath and
     * JDK, its {@code jk.*.jar} locations (where the workers under test are), and nothing of its
     * environment but what the side says.
     */
    private static int jk(Side side, String... args) throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(JavaHomes.runningJavaHome().resolve("bin").resolve("java").toString());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        for (String key : new TreeSet<>(System.getProperties().stringPropertyNames())) {
            if (key.startsWith("jk.") && key.endsWith(".jar")) cmd.add("-D" + key + "=" + System.getProperty(key));
        }
        cmd.add("cc.jumpkick.cli.Jk");
        cmd.addAll(List.of(args));
        cmd.addAll(List.of("-C", side.checkout().toString(), "-x", "-u"));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(side.env());
        Path log = side.root().resolve("jk-" + String.join("-", args).replace('/', '_') + ".log");
        pb.redirectOutput(log.toFile());
        Process p = pb.start();
        if (!p.waitFor(10, TimeUnit.MINUTES)) {
            p.destroyForcibly();
            // The side's files go with the test's temp dir: what they say rides the failure.
            throw new AssertionError("jk " + String.join(" ", args) + " at site " + side.name()
                    + " did not finish in 10 minutes\n--- " + log + "\n" + tail(log) + engineLogs(side));
        }
        int exit = p.exitValue();
        if (exit != 0 && !args[0].equals("engine")) {
            System.err.println("jk " + String.join(" ", args) + " at site " + side.name() + " exited " + exit + ":\n"
                    + Files.readString(log, StandardCharsets.UTF_8));
        }
        return exit;
    }

    /** The last 40 lines of {@code file}, or why there are none. */
    private static String tail(Path file) {
        try {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
        } catch (IOException e) {
            return "(unreadable: " + e.getMessage() + ")";
        }
    }

    /** Each engine log of the side's home, its tail after its path. */
    private static String engineLogs(Side side) {
        Path dir = side.home().resolve("state/engine");
        StringBuilder out = new StringBuilder();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(f -> f.getFileName().toString().endsWith(".log"))
                    .sorted()
                    .toList()) {
                out.append("\n--- ").append(f).append('\n').append(tail(f));
            }
        } catch (IOException e) {
            out.append("\n(no engine logs at ")
                    .append(dir)
                    .append(": ")
                    .append(e.getMessage())
                    .append(')');
        }
        return out.toString();
    }

    private static void writeProject(Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), MANIFEST);
        write(dir.resolve("src/main/java/com/example/Main.java"), MAIN);
        write(dir.resolve("src/main/resources/greeting.txt"), "grüß dich\n");
        write(dir.resolve("src/test/java/com/example/MainTest.java"), TEST);
    }

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    /** The sources and the lock site a wrote, without its {@code target/}: another clone of the commit. */
    private static void copySources(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                Path rel = from.relativize(f);
                if (rel.startsWith("target")) continue;
                Files.createDirectories(to.resolve(rel).getParent());
                Files.copy(f, to.resolve(rel));
            }
        }
        assertThat(to.resolve("jk-lock.toml"))
                .as("the lock carries the project id both sites key by")
                .isRegularFile();
    }

    /** Each task pointer of {@code home}'s action cache, by name, to the key it names. */
    private static Map<String, String> tasks(Path home) throws IOException {
        Map<String, String> out = new TreeMap<>();
        Path dir = home.resolve("cache/actions/tasks");
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(Files::isRegularFile).toList()) {
                out.put(f.getFileName().toString(), Files.readString(f).strip());
            }
        }
        return out;
    }

    /** The inputs whose values differ between the two sites' records of {@code task}, in words. */
    private static String differingInputs(String task, Side a, String keyA, Side b, String keyB) throws IOException {
        Map<String, String> inA = inputs(a.home(), keyA);
        Map<String, String> inB = inputs(b.home(), keyB);
        if (task.startsWith("run-tests@")) {
            inA.putAll(stampTokens(a.home()));
            inB.putAll(stampTokens(b.home()));
        }
        List<String> moved = new ArrayList<>();
        for (String name : new TreeSet<>(union(inA.keySet(), inB.keySet()))) {
            String va = inA.get(name);
            String vb = inB.get(name);
            if (va == null ? vb != null : !va.equals(vb)) moved.add(name + ": " + va + " | " + vb);
        }
        return moved.isEmpty()
                ? "no recorded input differs (a key ingredient no record names)"
                : String.join("; ", moved);
    }

    private static TreeSet<String> union(Set<String> x, Set<String> y) {
        TreeSet<String> all = new TreeSet<>(x);
        all.addAll(y);
        return all;
    }

    /** A record's {@code INPUT <value> <name>} lines; a packaged step's {@code inputs} token list split. */
    private static Map<String, String> inputs(Path home, String key) throws IOException {
        Map<String, String> out = new TreeMap<>();
        Path record = home.resolve("cache/actions/keys").resolve(key);
        if (!Files.isRegularFile(record)) return out;
        for (String line : Files.readAllLines(record, StandardCharsets.UTF_8)) {
            if (!line.startsWith("INPUT ")) continue;
            String rest = line.substring("INPUT ".length());
            int sp = rest.lastIndexOf(' ');
            if (sp < 0) continue;
            String value = rest.substring(0, sp);
            String name = rest.substring(sp + 1);
            if (name.equals("inputs")) {
                for (String token : value.split(";")) {
                    int colon = token.indexOf(':');
                    out.put(colon > 0 ? token.substring(0, colon) : token, token);
                }
            } else {
                out.put(name, value);
            }
        }
        return out;
    }

    /**
     * The named stamp tokens the project's suite kept on its last green run, which the home's cache
     * holds beside what the suite was found to read ({@code cache/projects/<module>/suite-reads/
     * stamp-tokens-<tier>}); the warm-up project's are told apart by not naming this test source.
     */
    private static Map<String, String> stampTokens(Path home) throws IOException {
        Map<String, String> out = new TreeMap<>();
        Path projects = home.resolve("cache/projects");
        if (!Files.isDirectory(projects)) return out;
        try (Stream<Path> files = Files.walk(projects)) {
            for (Path f : files.filter(p -> p.getFileName().toString().startsWith("stamp-tokens-"))
                    .toList()) {
                List<String> lines = Files.readAllLines(f, StandardCharsets.UTF_8);
                if (lines.stream().noneMatch(l -> l.contains("MainTest.java"))) continue;
                for (String line : lines) {
                    int tab = line.indexOf('\t');
                    if (tab > 0) out.put("stamp " + line.substring(0, tab), line.substring(tab + 1));
                }
            }
        }
        return out;
    }
}
