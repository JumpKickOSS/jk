// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import cc.jumpkick.cli.engine.EngineTestSupport;
import cc.jumpkick.host.Os;
import cc.jumpkick.jdk.JavaHomes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * and the time zone. They share the JDK (one build per major is installed on most hosts; two
 * patches of an unpinned major would differ by design, see {@code JdkToolchain}) and the Maven
 * local repository the tier points at, so nothing is fetched.
 *
 * <p>The comparison is over each home's task pointers ({@code cache/actions/tasks/<step>@<tag>},
 * the tag being the project id and the output's workspace-relative path, both portable). A key that
 * differs fails the test naming the step and each input whose value differs: from the two action
 * records' {@code INPUT} lines, and for a suite from the stamp tokens each home keeps beside its
 * discovered inputs. Same-JVM tests cannot give this: the locale, time zone, user and {@code $HOME}
 * an engine sees are its process's.
 */
@Tag("integration")
class CrossHostKeyEqualityTest {

    private static final String MANIFEST = """
            group   = "com.example"
            name    = "keyeq"
            version = "1.0.0"

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
            import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

            class MainTest {
                @Test
                void greets() {
                    assertEquals("with", Main.greeting());
                    assertEquals("1,234.50", String.format("%,.2f", 1234.5));
                    assertEquals("en", Locale.getDefault().getLanguage());
                }
            }
            """;

    /** One side of the comparison: a host as a client process and its engine see it. */
    private record Side(String name, Path root, Path home, Path checkout, Map<String, String> env) {}

    @Test
    @Timeout(value = 12, unit = TimeUnit.MINUTES) // two cold engines in fresh homes, each building and testing
    void two_hosts_compute_every_action_key_alike(@TempDir Path tmp) throws Exception {
        assumeFalse(Os.isWindows(), "the sides are POSIX shells' environments");
        Side a = side(tmp, "a", "work/app", "keyeqa", "en_US.UTF-8", "America/New_York");
        Side b = side(tmp, "b", "elsewhere/much/deeper/checkout-two", "keyeqb", "de_DE.UTF-8", "Asia/Tokyo");
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
        } finally {
            jk(a, "engine", "stop");
            jk(b, "engine", "stop");
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
            throw new AssertionError("jk " + String.join(" ", args) + " at site " + side.name()
                    + " did not finish in 10 minutes; log: " + log);
        }
        int exit = p.exitValue();
        if (exit != 0 && !args[0].equals("engine")) {
            System.err.println("jk " + String.join(" ", args) + " at site " + side.name() + " exited " + exit + ":\n"
                    + Files.readString(log, StandardCharsets.UTF_8));
        }
        return exit;
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
