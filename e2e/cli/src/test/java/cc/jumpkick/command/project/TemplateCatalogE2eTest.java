// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.PublishedSdk;
import cc.jumpkick.engine.runtime.NewProjectForm;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.Values;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.scaffold.testing.AnswerVectors;
import cc.jumpkick.testing.MemoryKills;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * Every template under the repository's {@code templates/} (the tree the public catalog mirrors) is
 * scaffolded with {@code jk new -t <path>}, and every template a first-party plugin jar bundles (the
 * trees under {@code plugins/<plugin>/src/main/resources/templates/}) with {@code jk new -t <id>}
 * through the jar this build produced, once per answer vector of the new-project corpus, and the
 * project it writes passes {@code jk format -c} with no edits. The vectors are the corpus's
 * ({@link AnswerVectors#of}, the ones {@code NewProjectCorpusTest} snapshots): the defaults, then a
 * pairwise set. Those in which every layout and every option a template asks appears at least once
 * ({@link AnswerVectors#eachValue}) also pass {@code jk build}, {@code jk test} and {@code jk
 * guard} — the contract the catalog's README promises — so a binding only a non-default answer
 * reaches is built too, and an option added to a template is built as soon as it is asked.
 * A plugin template builds against the SDK this checkout publishes, and its jar is checked for a
 * plugin's shape. A release's scaffold pins that SDK already; a {@code -SNAPSHOT}'s pins the newest
 * published release at or below it, which predates whatever the checkout changed in the SDK, so the
 * scaffold's pin is moved to the checkout's SDK before it builds, as {@code
 * ThirdPartyPluginExampleTest} does with the sample. A
 * template that asks for a native binary is also linked with {@code jk native} (quick-build mode),
 * once, where a GraalVM is available ({@code GRAALVM_HOME} with {@code native-image}); elsewhere
 * that test is skipped by assumption. An Android template is scaffolded and format-checked
 * everywhere, built only where an Android SDK with accepted licenses is installed: the test never
 * accepts them for the machine ({@code jk android licenses --yes} is the user's call), so elsewhere it is skipped by
 * assumption. A template that stops compiling, fails NullAway or its own rule pack, or ships a red
 * sample test fails here under its own id and vector, with the command's console.
 *
 * <p>Slow tier: each scaffold resolves its framework's closure (Spring Boot, Quarkus, Micronaut,
 * Grails, Ktor, JLine, Error Prone) and builds and tests a whole project, minutes in all — framework
 * end to end that breaks when a template's pins move, the same cost and cadence as the other
 * framework e2e suites, and too long for the integration push gate. The snapshot comparison of the
 * same vectors is the fast tier's.
 *
 * <p>The cases are dealt to {@link #SHARDS} subclasses, {@code TemplateCatalogShard<n>E2eTest}, so
 * the suite's pool runs them in parallel JVMs, each with its own engine and scratch and all on one
 * warm store. The class takes as long as its slowest shard, so the dealing balances estimated cost
 * ({@link #dealt}). Three because each shard's engine compiles and tests on cores of its own: on a
 * ten-core host eight shards made every case slower and the class longer, and on a seven-core host
 * six left their engines' builds waiting for room until one gave up and the memory limit killed two
 * shards. Nothing here writes the shared home: a plugin
 * scaffold names the checkout's SDK repository in its own {@code jk.toml}. All shards: {@code jk
 * test -s slow -m e2e/cli --class 'TemplateCatalog*E2eTest'}.
 */
@Tag("slow")
abstract class TemplateCatalogE2eTest {

    /** How many shard classes deal the cases; each is {@code TemplateCatalogShard<n>E2eTest}. */
    static final int SHARDS = 3;

    /**
     * The version this checkout's SDK is published at for a plugin scaffold: the running version
     * without a {@code -SNAPSHOT}, so the scaffold's no-snapshot rule accepts its pin.
     */
    private static final String SDK_VERSION = JkVersion.VERSION.replaceFirst("-SNAPSHOT$", "");

    /** The SDK release a plugin scaffold's {@code jk.toml} pins. */
    private static final Pattern SDK_PIN = Pattern.compile("cc\\.jumpkick:jk-plugin-sdk:([0-9][^\"]*)\"");

    /** The ask a template offers a GraalVM native executable with. */
    private static final String NATIVE = "native";

    /** Where a first-party plugin keeps the templates its jar bundles, under {@code plugins/<plugin>/}. */
    private static final String PLUGIN_TEMPLATES = "src/main/resources/templates";

    /**
     * Templates whose scaffold does not build clean today, by id, each with the defect: such a
     * template is still scaffolded, then skipped by assumption with the defect as the reason, never
     * silently. Delete an entry when its defect is fixed, and the template's vectors build again.
     */
    private static final Map<String, String> KNOWN_DEFECTS = Map.of();

    /** About what a vector that is only scaffolded and format-checked costs, in seconds. */
    private static final int FORMAT_COST = 2;

    /** About what scaffolding, building, testing and guarding a typical template costs, in seconds. */
    private static final int BUILD_COST = 15;

    /** About what linking a scaffold with {@code jk native} costs, in seconds. */
    private static final int LINK_COST = 30;

    /**
     * Templates whose build costs well above {@link #BUILD_COST}, by id, with about what one costs in
     * seconds on a loaded host. Only the dealing reads it: a missing or stale entry unbalances the
     * shards, it never drops or fails a case.
     */
    private static final Map<String, Integer> BUILD_COSTS =
            Map.of("java/quarkus/hello", 80, "kotlin/quarkus/hello", 80, "java/plugin", 40);

    /**
     * Rows a template's lock holds though its manifest declares none of them, by id, each with the
     * {@code added-by} line that says why: conditional dependencies jk follows the way the
     * framework's own resolver does. {@code jk why} on the row must name the same line.
     */
    private static final Map<String, Map<String, String>> CONDITIONAL_ROWS = Map.of(
            "kotlin/quarkus/hello",
            Map.of(
                    "io.quarkus:quarkus-rest-kotlin", "io.quarkus:quarkus-rest when io.quarkus:quarkus-kotlin",
                    "io.quarkus:quarkus-vertx-kotlin", "io.quarkus:quarkus-kotlin when io.quarkus:quarkus-vertx"));

    @TempDir
    Path scratch;

    private final int shard;

    private @Nullable String sdkRepo;

    /** The suite's cases at {@code shard}, {@code shard + SHARDS}, … in catalog order. */
    TemplateCatalogE2eTest(int shard) {
        this.shard = shard;
    }

    @TestFactory
    Stream<DynamicTest> every_template_scaffolds_builds_tests_and_guards_clean_for_each_answer_vector()
            throws IOException, ClassNotFoundException {
        Path repo = RepoRoot.find(TemplateCatalogE2eTest.class);
        Path root = repo.resolve("templates");
        List<Path> templates = templates(root);
        // Fake-green floor: an empty or near-empty walk is a moved tree, not a clean catalog.
        assertThat(templates).as("templates under %s", root).hasSizeGreaterThanOrEqualTo(10);
        // A templates/ tree is named by its path; a plugin's by its id, which reaches it in the jar.
        Map<String, Template> all = new LinkedHashMap<>();
        for (Path template : templates) {
            all.put(id(root, template), new Template(template, template.toString()));
        }
        int bundled = 0;
        try (Stream<Path> plugins = Files.list(repo.resolve("plugins"))) {
            for (Path plugin : plugins.sorted().toList()) {
                Path pluginRoot = plugin.resolve(PLUGIN_TEMPLATES);
                if (!Files.isDirectory(pluginRoot)) continue;
                for (Path template : templates(pluginRoot)) {
                    String id = id(pluginRoot, template);
                    all.put(id, new Template(template, id));
                    bundled++;
                }
            }
        }
        assertThat(bundled).as("templates under plugins/*/%s", PLUGIN_TEMPLATES).isGreaterThanOrEqualTo(10);

        // Fake-green floor: a missing shard class would drop its cases without a failure.
        for (int n = 0; n < SHARDS; n++) {
            String shardClass = TemplateCatalogE2eTest.class.getPackageName() + ".TemplateCatalogShard" + n + "E2eTest";
            assertThat(Class.forName(shardClass)).as(shardClass).isAssignableTo(TemplateCatalogE2eTest.class);
        }

        List<Case> cases = new ArrayList<>();
        for (var e : all.entrySet()) {
            String id = e.getKey();
            Path template = e.getValue().tree();
            String ref = e.getValue().ref();
            readEveryFile(template);
            // The form is read off the tree; a plugin's is the same tree its jar bundles.
            Form form = NewProjectForm.of(NewProjectOps.templateForm(template.toString(), null, template));
            List<Field> fields = NewProjectForm.templateFields(form);
            List<AnswerVectors.Vector> toBuild = AnswerVectors.eachValue(fields);
            int n = 0;
            boolean linked = false;
            for (AnswerVectors.Vector v : AnswerVectors.of(fields)) {
                String dir = id.replace('/', '-') + "-" + n++;
                List<String> flags = flags(v.answers());
                String name = id + " (" + v.label() + ")";
                if (!toBuild.contains(v)) {
                    cases.add(new Case(
                            FORMAT_COST,
                            false,
                            DynamicTest.dynamicTest(
                                    name + " formats clean", () -> scaffoldAndFormatCheck(ref, name, dir, flags))));
                    continue;
                }
                cases.add(new Case(
                        BUILD_COSTS.getOrDefault(id, BUILD_COST),
                        false,
                        DynamicTest.dynamicTest(name, () -> scaffoldAndCheck(id, ref, name, dir, flags))));
                if (!linked && Boolean.TRUE.equals(v.answers().get(NATIVE))) {
                    linked = true;
                    cases.add(new Case(
                            LINK_COST,
                            true,
                            DynamicTest.dynamicTest(
                                    name + " links with jk native", () -> scaffoldAndLink(ref, name, dir, flags))));
                }
            }
        }
        return dealt(cases, SHARDS).get(shard).stream();
    }

    /**
     * One case, about what it costs in seconds (what the dealing balances), and whether it links a
     * native image.
     */
    private record Case(int cost, boolean links, DynamicTest test) {}

    /**
     * {@code cases} dealt to {@code shards} lists. Every native link goes to the first, one after
     * another: {@code native-image} takes gigabytes of heap and every core, and three at once ran
     * the host out of memory. Then the rest, the costliest first, each to the list whose cost
     * is lowest so far, ties to the earlier case and the lower shard, so every shard deals the same
     * way. Each list keeps the order it was dealt in, so a shard's long cases start while the others
     * are still busy.
     */
    private static List<List<DynamicTest>> dealt(List<Case> cases, int shards) {
        List<List<DynamicTest>> dealt = new ArrayList<>();
        int[] load = new int[shards];
        for (int n = 0; n < shards; n++) dealt.add(new ArrayList<>());
        List<Case> costliestFirst = new ArrayList<>();
        for (Case c : cases) {
            if (c.links()) {
                dealt.getFirst().add(c.test());
                load[0] += c.cost();
            } else {
                costliestFirst.add(c);
            }
        }
        costliestFirst.sort(Comparator.comparingInt(Case::cost).reversed());
        for (Case c : costliestFirst) {
            int least = 0;
            for (int n = 1; n < shards; n++) {
                if (load[n] < load[least]) least = n;
            }
            dealt.get(least).add(c.test());
            load[least] += c.cost();
        }
        return dealt;
    }

    /** A template's tree in this checkout, and the ref {@code jk new -t} names it by. */
    private record Template(Path tree, String ref) {}

    /** The template directories under {@code root}: {@code <lang>/<framework>/<name>.g8}, sorted. */
    private static List<Path> templates(Path root) throws IOException {
        try (Stream<Path> walk =
                Files.find(root, 3, (p, a) -> a.isDirectory() && p.toString().endsWith(".g8"))) {
            return walk.sorted().toList();
        }
    }

    /** {@code template}'s catalogue id, {@code <lang>/<framework>/<name>}. */
    private static String id(Path root, Path template) {
        return root.relativize(template).toString().replace('\\', '/').replaceAll("\\.g8$", "");
    }

    /**
     * Reads every file of {@code template} in this JVM. The scaffolds read the template in the
     * engine's process, where the suite's input recording does not look; read here, each file is a
     * discovered input that keys the suite, so a template edit re-runs it instead of replaying the
     * last green outcome.
     */
    private static void readEveryFile(Path template) throws IOException {
        try (Stream<Path> files = Files.walk(template)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.readAllBytes(file);
            }
        }
    }

    /**
     * The {@code jk new} flags that give a vector's answers: {@code -L} for the layout, {@code -p
     * key=value} for each of the template's asks. The defaults vector gives none, so the template's
     * own defaults are what is built.
     */
    static List<String> flags(Map<String, Object> answers) {
        List<String> flags = new ArrayList<>();
        for (var e : answers.entrySet()) {
            if (e.getKey().equals("layout")) {
                flags.addAll(List.of("-L", String.valueOf(e.getValue())));
            } else {
                flags.addAll(List.of("-p", e.getKey() + "=" + Values.text(e.getValue())));
            }
        }
        return flags;
    }

    /** Scaffolds template {@code ref} with {@code flags} into {@code scratch/<dir>}; the project's path. */
    private Path scaffold(String ref, String name, String dir, List<String> flags) {
        Path project = scratch.resolve(dir);
        List<String> args = new ArrayList<>(List.of("new", "-t", ref, "-N"));
        args.addAll(flags);
        args.addAll(List.of("--name", "demo", project.toString()));
        jk(name, args.toArray(String[]::new));
        assertThat(project.resolve("jk.toml"))
                .as("%s writes a jk.toml at the project root", name)
                .exists();
        return project;
    }

    /**
     * A native vector's project linked by {@code jk native} with the GraalVM {@code GRAALVM_HOME}
     * names, into an executable at {@code target/demo}.
     */
    private void scaffoldAndLink(String ref, String name, String dir, List<String> flags) throws IOException {
        String graal = System.getenv("GRAALVM_HOME");
        assumeTrue(
                graal != null && Files.isExecutable(Path.of(graal, "bin", "native-image")),
                "jk native needs GRAALVM_HOME naming a GraalVM with native-image");
        Path project = scaffold(ref, name, dir + "-native", flags);
        // The same vector's own case builds and tests it; this one asks only whether it links, so
        // its tests are skipped and native-image runs in quick-build mode (-Ob): the same analysis
        // and link at a lower optimization level, in about half the time. Its heap is capped below
        // the half of the machine jk native gives it, which beside the other shards' engines ran a
        // 10 GiB host out of memory; these small apps peak under 1 GiB.
        Path jkToml = project.resolve("jk.toml");
        String manifest = Files.readString(jkToml);
        assertThat(manifest).as("%s declares [native]", name).contains("\n[native]\n");
        Files.writeString(jkToml, manifest.replace("\n[native]\n", "\n[native]\nargs = [\"-Ob\", \"-J-Xmx3g\"]\n"));
        jk(name, "-C", project.toString(), "native", "--skip-tests");
        Path exe = project.resolve("target/demo");
        assertThat(Files.isExecutable(exe))
                .as("%s: jk native writes an executable at target/demo", name)
                .isTrue();
    }

    /** A vector no built one needs: scaffolded and format-checked, as {@link #scaffoldAndCheck} starts. */
    private void scaffoldAndFormatCheck(String ref, String name, String dir, List<String> flags) {
        Path project = scaffold(ref, name, dir, flags);
        jk(name, "-C", project.toString(), "format", "-c");
    }

    private void scaffoldAndCheck(String id, String ref, String name, String dir, List<String> flags)
            throws IOException {
        Path project = scaffold(ref, name, dir, flags);
        // As generated, every source is what the template's own formatter writes. jk guard outside
        // CI formats an unformatted file in place and passes, so only a check sees it, as CI=1 jk
        // guard does on the user's first push. Before the Android assumption: no SDK is needed.
        jk(name, "-C", project.toString(), "format", "-c");
        String defect = KNOWN_DEFECTS.get(id);
        assumeTrue(defect == null, () -> name + " is scaffolded but not built: " + defect);
        String manifest = Files.readString(project.resolve("jk.toml"));
        if (manifest.contains("\n[android]")) {
            assumeTrue(
                    androidSdkWithLicenses(),
                    () -> name + " builds only with an Android SDK whose licenses are accepted (jk android"
                            + " licenses --yes), which this test does not do to the machine");
        }
        if (pinCheckoutSdk(project, manifest)) {
            Path jkToml = project.resolve("jk.toml");
            Files.writeString(jkToml, Files.readString(jkToml) + """

                    [repositories.jumpkick]
                    url    = "%s"
                    groups = ["cc.jumpkick", "cc.jumpkick.*"]
                    """.formatted(sdkRepo()));
        }
        for (String verb : List.of("build", "test", "guard")) {
            jk(name, "-C", project.toString(), verb);
        }
        if (Files.isRegularFile(project.resolve("src/main/resources/jk-plugin.toml"))) {
            assertPluginJar(project, name);
        }
        assertConditionalRows(id, name, project, manifest);
    }

    /** Each of the template's {@link #CONDITIONAL_ROWS} is locked with its reason, and {@code jk why} says it. */
    private static void assertConditionalRows(String id, String name, Path project, String manifest)
            throws IOException {
        Map<String, String> rows = CONDITIONAL_ROWS.getOrDefault(id, Map.of());
        if (rows.isEmpty()) return;
        String lock = Files.readString(project.resolve("jk-lock.toml"));
        String body = lock.substring(lock.indexOf("[[artifact]]"));
        for (var row : rows.entrySet()) {
            String artifact = row.getKey().substring(row.getKey().indexOf(':') + 1);
            assertThat(manifest).as("%s declares %s", name, artifact).doesNotContain("\"" + artifact + "\"");
            int at = body.indexOf("name     = \"" + row.getKey() + ":jar:\"");
            assertThat(at).as("%s locks %s", name, row.getKey()).isNotNegative();
            int next = body.indexOf("[[artifact]]", at);
            assertThat(body.substring(at, next < 0 ? body.length() : next))
                    .as("%s's row for %s says why", name, row.getKey())
                    .contains("added-by = [\n  \"" + row.getValue() + "\",\n]");
            String[] all = {"--no-ansi", "--no-progress", "-C", project.toString(), "why", row.getKey()};
            int[] exit = {0};
            Capture.Streams console = Capture.both(() -> exit[0] = Jk.execute(all));
            assertThat(exit[0])
                    .as("%s: jk why %s%n%s", name, row.getKey(), console.err())
                    .isZero();
            assertThat(console.out())
                    .as("%s: jk why %s", name, row.getKey())
                    .contains("conditional dependency of " + row.getValue());
        }
    }

    /**
     * Whether the Android SDK a build would use has licenses accepted: the store's managed root, else
     * the first of {@code ANDROID_HOME}, {@code ANDROID_SDK_ROOT} and Android Studio's default that
     * exists, as the Android plugin discovers it. Reads only; nothing is linked or created.
     */
    private static boolean androidSdkWithLicenses() {
        String home = System.getProperty("user.home", "");
        List<Path> candidates = new ArrayList<>();
        candidates.add(JkDirs.store().resolve("android-sdk"));
        for (String var : List.of("ANDROID_HOME", "ANDROID_SDK_ROOT")) {
            String value = System.getenv(var);
            if (value != null && !value.isBlank()) candidates.add(Path.of(value));
        }
        candidates.add(Path.of(home, "Android", "Sdk"));
        candidates.add(Path.of(home, "Library", "Android", "sdk"));
        for (Path sdk : candidates) {
            if (!Files.isDirectory(sdk)) continue;
            try (Stream<Path> licenses =
                    Files.isDirectory(sdk.resolve("licenses")) ? Files.list(sdk.resolve("licenses")) : Stream.empty()) {
                return licenses.findAny().isPresent();
            } catch (IOException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Moves a plugin scaffold's SDK pin, in {@code jk.toml} and in its manifest's {@code sdk} and
     * {@code jk-compat}, to {@link #SDK_VERSION}; whether the scaffold pins the SDK at all.
     */
    private static boolean pinCheckoutSdk(Path project, String manifest) throws IOException {
        Matcher pinned = SDK_PIN.matcher(manifest);
        if (!pinned.find()) return false;
        String pin = pinned.group(1);
        if (pin.equals(SDK_VERSION)) return true;
        Files.writeString(
                project.resolve("jk.toml"),
                manifest.replace("jk-plugin-sdk:" + pin + "\"", "jk-plugin-sdk:" + SDK_VERSION + "\""));
        Path pluginToml = project.resolve("src/main/resources/jk-plugin.toml");
        if (Files.isRegularFile(pluginToml)) {
            Files.writeString(
                    pluginToml,
                    Files.readString(pluginToml)
                            .replace("\"" + pin + "\"", "\"" + SDK_VERSION + "\"")
                            .replace("\">=" + pin + "\"", "\">=" + SDK_VERSION + "\""));
        }
        return true;
    }

    /**
     * The checkout's SDK, published once per shard at {@link #SDK_VERSION}, which jumpkick.build
     * serves only once that release ships, if ever with these bytes: this repository stands in for
     * jumpkick, from the scaffold's own {@code jk.toml}, while a plugin scaffold builds.
     */
    private String sdkRepo() throws IOException {
        if (sdkRepo == null)
            sdkRepo = PublishedSdk.publish(scratch.resolve("sdk"), SDK_VERSION).toString();
        return sdkRepo;
    }

    /** A plugin scaffold's jar is a plugin: the manifest at its root, the service file, the SDK's worker host. */
    private static void assertPluginJar(Path project, String name) throws IOException {
        Path jar;
        // Every build but jk release and jk publish names the snapshot of the declared version.
        try (Stream<Path> jars = Files.list(project.resolve("target"))) {
            jar = jars.filter(p -> p.getFileName().toString().matches("demo-[0-9.]+-SNAPSHOT\\.jar"))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(name + " builds no jar under target/"));
        }
        try (JarFile jf = new JarFile(jar.toFile())) {
            assertThat(jf.getEntry("jk-plugin.toml"))
                    .as("%s: jk-plugin.toml at the jar root", name)
                    .isNotNull();
            assertThat(jf.getEntry("META-INF/services/cc.jumpkick.plugin.Plugin"))
                    .as("%s: the service file", name)
                    .isNotNull();
            Manifest manifest = jf.getManifest();
            assertThat(manifest == null ? null : manifest.getMainAttributes().getValue("Main-Class"))
                    .as("%s: a plugin jar's entry is the SDK's worker host", name)
                    .isEqualTo("cc.jumpkick.plugin.process.PluginMain");
        }
    }

    /**
     * Runs one in-process jk command; a non-zero exit fails naming the template, vector and command,
     * or aborts when the kernel killed a process for memory while it ran: a link beside the other
     * shards' builds can take a small box past its limit, and that failure is the host's.
     */
    private static void jk(String name, String... args) {
        String[] all = Stream.concat(Stream.of("--no-ansi", "--no-progress"), Stream.of(args))
                .toArray(String[]::new);
        int[] exit = {0};
        MemoryKills kills = MemoryKills.mark();
        Capture.Streams console = Capture.both(() -> exit[0] = Jk.execute(all));
        if (exit[0] != 0) kills.abortIfAny("template " + name + ": jk " + String.join(" ", args));
        assertThat(exit[0])
                .as(
                        "template %s: jk %s exited %d%n%s%s",
                        name, String.join(" ", args), exit[0], console.out(), console.err())
                .isZero();
    }
}
