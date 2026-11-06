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
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.scaffold.testing.AnswerVectors;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.util.JkDirs;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
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
 * scaffolded with {@code jk new -t <path>} once per answer vector of the new-project corpus, and the
 * project it writes passes {@code jk build}, {@code jk test} and {@code jk guard} with no edits — the
 * contract the catalog's README promises. The vectors are the corpus's ({@link AnswerVectors}, the
 * ones {@code NewProjectCorpusTest} snapshots): the defaults, then a pairwise set in which every
 * layout the template declares and every option it asks appears, so a binding only a non-default
 * answer reaches is built too, and an option added to a template is built as soon as it is asked.
 * A plugin template builds against the SDK this checkout publishes, and its jar is checked for a
 * plugin's shape. A template that asks for a native binary is also linked with {@code jk native},
 * once, where a GraalVM is available ({@code GRAALVM_HOME} with {@code native-image}); elsewhere
 * that test is skipped by assumption. A template that stops compiling, fails NullAway or its own
 * rule pack, or ships a red sample test fails here under its own id and vector, with the command's
 * console.
 *
 * <p>Slow tier: each scaffold resolves its framework's closure (Spring Boot, Ktor, JLine, Error
 * Prone) and builds and tests a whole project, minutes in all — framework end to end that breaks
 * when a template's pins move, the same cost and cadence as the other framework e2e suites, and
 * too long for the integration push gate. The snapshot comparison of the same vectors is the fast
 * tier's.
 */
@Tag("slow")
class TemplateCatalogE2eTest {

    /** The SDK pin a plugin template writes: this jk's own version. */
    private static final String SDK_PIN = "cc.jumpkick:jk-plugin-sdk:" + JkVersion.VERSION + "\"";

    /** The ask a template offers a GraalVM native executable with. */
    private static final String NATIVE = "native";

    @TempDir
    Path scratch;

    private @Nullable String sdkRepo;

    @TestFactory
    Stream<DynamicTest> every_template_scaffolds_builds_tests_and_guards_clean_for_each_answer_vector()
            throws IOException {
        Path root = RepoRoot.find(TemplateCatalogE2eTest.class).resolve("templates");
        List<Path> templates;
        try (Stream<Path> walk =
                Files.find(root, 3, (p, a) -> a.isDirectory() && p.toString().endsWith(".g8"))) {
            templates = walk.sorted().toList();
        }
        // Fake-green floor: an empty or near-empty walk is a moved tree, not a clean catalog.
        assertThat(templates).as("templates under %s", root).hasSizeGreaterThanOrEqualTo(10);

        List<DynamicTest> tests = new ArrayList<>();
        for (Path template : templates) {
            readEveryFile(template);
            String id = root.relativize(template).toString().replace('\\', '/').replaceAll("\\.g8$", "");
            Form form = NewProjectForm.of(NewProjectOps.templateForm(template.toString(), null, template));
            int n = 0;
            boolean linked = false;
            for (AnswerVectors.Vector v : AnswerVectors.of(NewProjectForm.templateFields(form))) {
                String dir = id.replace('/', '-') + "-" + n++;
                List<String> flags = flags(v.answers());
                String name = id + " (" + v.label() + ")";
                tests.add(DynamicTest.dynamicTest(name, () -> scaffoldAndCheck(template, name, dir, flags)));
                if (!linked && Boolean.TRUE.equals(v.answers().get(NATIVE))) {
                    linked = true;
                    tests.add(DynamicTest.dynamicTest(
                            name + " links with jk native", () -> scaffoldAndLink(template, name, dir, flags)));
                }
            }
        }
        return tests.stream();
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

    /** Scaffolds {@code template} with {@code flags} into {@code scratch/<dir>}; the project's path. */
    private Path scaffold(Path template, String name, String dir, List<String> flags) {
        Path project = scratch.resolve(dir);
        List<String> args = new ArrayList<>(List.of("new", "-t", template.toString(), "-N"));
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
    private void scaffoldAndLink(Path template, String name, String dir, List<String> flags) {
        String graal = System.getenv("GRAALVM_HOME");
        assumeTrue(
                graal != null && Files.isExecutable(Path.of(graal, "bin", "native-image")),
                "jk native needs GRAALVM_HOME naming a GraalVM with native-image");
        Path project = scaffold(template, name, dir + "-native", flags);
        jk(name, "-C", project.toString(), "native");
        Path exe = project.resolve("target/demo");
        assertThat(Files.isExecutable(exe))
                .as("%s: jk native writes an executable at target/demo", name)
                .isTrue();
    }

    private void scaffoldAndCheck(Path template, String name, String dir, List<String> flags) throws IOException {
        Path project = scaffold(template, name, dir, flags);
        boolean pinsSdk = Files.readString(project.resolve("jk.toml")).contains(SDK_PIN);
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
