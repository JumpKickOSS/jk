// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.cli.testing.PublishedSdk;
import cc.jumpkick.engine.runtime.NewProjectForm;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.Values;
import cc.jumpkick.host.JkDirs;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.scaffold.testing.AnswerVectors;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
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
 * project it writes passes {@code jk build}, {@code jk test} and {@code jk guard} with no edits — the
 * contract the catalog's README promises. The vectors are the corpus's ({@link AnswerVectors}, the
 * ones {@code NewProjectCorpusTest} snapshots): the defaults, then a pairwise set in which every
 * layout the template declares and every option it asks appears, so a binding only a non-default
 * answer reaches is built too, and an option added to a template is built as soon as it is asked.
 * A plugin template builds against the SDK this checkout publishes, and its jar is checked for a
 * plugin's shape. A template that asks for a native binary is also linked with {@code jk native},
 * once, where a GraalVM is available ({@code GRAALVM_HOME} with {@code native-image}); elsewhere
 * that test is skipped by assumption. An Android template is scaffolded everywhere but built only
 * where an Android SDK with accepted licenses is installed: the test never accepts them for the
 * machine ({@code jk android licenses --yes} is the user's call), so elsewhere it is skipped by
 * assumption. A template that stops compiling, fails NullAway or its own rule pack, or ships a red
 * sample test fails here under its own id and vector, with the command's console.
 *
 * <p>Slow tier: each scaffold resolves its framework's closure (Spring Boot, Quarkus, Micronaut,
 * Grails, Ktor, JLine, Error Prone) and builds and tests a whole project, minutes in all — framework
 * end to end that breaks when a template's pins move, the same cost and cadence as the other
 * framework e2e suites, and too long for the integration push gate. The snapshot comparison of the
 * same vectors is the fast tier's.
 */
@Tag("slow")
class TemplateCatalogE2eTest {

    /** The SDK pin a plugin template writes: this jk's own version. */
    private static final String SDK_PIN = "cc.jumpkick:jk-plugin-sdk:" + JkVersion.VERSION + "\"";

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

    private @Nullable String sdkRepo;

    @TestFactory
    Stream<DynamicTest> every_template_scaffolds_builds_tests_and_guards_clean_for_each_answer_vector()
            throws IOException {
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

        List<DynamicTest> tests = new ArrayList<>();
        for (var e : all.entrySet()) {
            String id = e.getKey();
            Path template = e.getValue().tree();
            String ref = e.getValue().ref();
            readEveryFile(template);
            // The form is read off the tree; a plugin's is the same tree its jar bundles.
            Form form = NewProjectForm.of(NewProjectOps.templateForm(template.toString(), null, template));
            int n = 0;
            boolean linked = false;
            for (AnswerVectors.Vector v : AnswerVectors.of(NewProjectForm.templateFields(form))) {
                String dir = id.replace('/', '-') + "-" + n++;
                List<String> flags = flags(v.answers());
                String name = id + " (" + v.label() + ")";
                tests.add(DynamicTest.dynamicTest(name, () -> scaffoldAndCheck(id, ref, name, dir, flags)));
                if (!linked && Boolean.TRUE.equals(v.answers().get(NATIVE))) {
                    linked = true;
                    tests.add(DynamicTest.dynamicTest(
                            name + " links with jk native", () -> scaffoldAndLink(ref, name, dir, flags)));
                }
            }
        }
        return tests.stream();
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
    private void scaffoldAndLink(String ref, String name, String dir, List<String> flags) {
        String graal = System.getenv("GRAALVM_HOME");
        assumeTrue(
                graal != null && Files.isExecutable(Path.of(graal, "bin", "native-image")),
                "jk native needs GRAALVM_HOME naming a GraalVM with native-image");
        Path project = scaffold(ref, name, dir + "-native", flags);
        jk(name, "-C", project.toString(), "native");
        Path exe = project.resolve("target/demo");
        assertThat(Files.isExecutable(exe))
                .as("%s: jk native writes an executable at target/demo", name)
                .isTrue();
    }

    private void scaffoldAndCheck(String id, String ref, String name, String dir, List<String> flags)
            throws IOException {
        Path project = scaffold(ref, name, dir, flags);
        String defect = KNOWN_DEFECTS.get(id);
        assumeTrue(defect == null, () -> name + " is scaffolded but not built: " + defect);
        String manifest = Files.readString(project.resolve("jk.toml"));
        if (manifest.contains("\n[android]")) {
            assumeTrue(
                    androidSdkWithLicenses(),
                    () -> name + " builds only with an Android SDK whose licenses are accepted (jk android"
                            + " licenses --yes), which this test does not do to the machine");
        }
        boolean pinsSdk = manifest.contains(SDK_PIN);
        Path userConfig = JkDirs.userConfigFile();
        String priorConfig = Files.isRegularFile(userConfig) ? Files.readString(userConfig) : null;
        if (pinsSdk) {
            Files.writeString(userConfig, (priorConfig == null ? "" : priorConfig) + """

                    [repositories.jumpkick]
                    url    = "%s"
                    groups = ["cc.jumpkick", "cc.jumpkick.*"]
                    """.formatted(sdkRepo()));
        }
        try {
            for (String verb : List.of("build", "test", "guard")) {
                jk(name, "-C", project.toString(), verb);
            }
        } finally {
            if (pinsSdk) {
                if (priorConfig != null) Files.writeString(userConfig, priorConfig);
                else Files.delete(userConfig);
            }
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
     * The checkout's SDK, published once for the suite: a plugin template pins the SDK at this jk's
     * version, which jumpkick.build serves for a release and nothing serves for a checkout, so this
     * repository stands in for jumpkick, from the user's config, while that scaffold builds.
     */
    private String sdkRepo() throws IOException {
        if (sdkRepo == null)
            sdkRepo = PublishedSdk.publish(scratch.resolve("sdk"), JkVersion.VERSION)
                    .toString();
        return sdkRepo;
    }

    /** A plugin scaffold's jar is a plugin: the manifest at its root, the service file, the SDK's worker host. */
    private static void assertPluginJar(Path project, String name) throws IOException {
        Path jar;
        try (Stream<Path> jars = Files.list(project.resolve("target"))) {
            jar = jars.filter(p -> p.getFileName().toString().matches("demo-[0-9.]+\\.jar"))
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

    /** Runs one in-process jk command; a non-zero exit fails naming the template, vector and command. */
    private static void jk(String name, String... args) {
        String[] all = Stream.concat(Stream.of("--no-ansi", "--no-progress"), Stream.of(args))
                .toArray(String[]::new);
        int[] exit = {0};
        Capture.Streams console = Capture.both(() -> exit[0] = Jk.execute(all));
        assertThat(exit[0])
                .as(
                        "template %s: jk %s exited %d%n%s%s",
                        name, String.join(" ", args), exit[0], console.out(), console.err())
                .isZero();
    }
}
