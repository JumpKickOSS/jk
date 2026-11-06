// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.engine.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.engine.plugin.PluginJar;
import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.Option;
import cc.jumpkick.form.Values;
import cc.jumpkick.giter8.Giter8TemplateIndex;
import cc.jumpkick.giter8.TemplateSpec;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.plugin.manifest.PluginDescriptor;
import cc.jumpkick.plugin.manifest.PluginTableRegistry;
import cc.jumpkick.scaffold.testing.AnswerVectors;
import cc.jumpkick.testing.RepoRoot;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

/**
 * The golden corpus of the new-project catalogue: every template under the repository's
 * {@code templates/}, and every template a first-party plugin jar bundles (the trees under
 * {@code plugins/<plugin>/src/main/resources/templates/}, reached by their catalogue id through the
 * jar this build produced, as {@code jk new -t <id>} reaches them), is scaffolded through the engine
 * ({@link NewProjectOps#create}) once per answer vector, and the file tree and {@code jk.toml} of
 * each are compared with the template's committed snapshot under
 * {@code src/test/resources/new-project-corpus/}. The vectors come from the
 * template's form ({@link AnswerVectors}: the defaults, then a pairwise set in which every option of
 * the layout and of every {@code [[ask]]} appears), so an option added to a template changes its
 * vectors, and the corpus fails until the snapshot holds the new one.
 *
 * <p>A snapshot holds nothing that differs between machines or moves on its own: the name, group
 * and default JDK are fixed (the JDK through {@link NewProjectJava}'s seam, never the machine's),
 * no file's contents but {@code jk.toml}'s are kept, and every version string in it (a library pin,
 * the plugin SDK's, the project's own) is written {@code …}, so neither {@code jk update} nor a jk
 * release rewrites a snapshot. A template's {@code maven()} property (the newest Quarkus platform)
 * is answered with a fixed version through {@link NewProjectOps}' seam, never a repository.
 *
 * <p>A deliberate change re-captures the snapshots: {@code NEW_PROJECT_CORPUS_CAPTURE=<dir> jk test
 * -m server/engine --class cc.jumpkick.engine.runtime.NewProjectCorpusTest} writes each template's
 * snapshot into {@code <dir>} (the checkout's {@code server/engine/src/test/resources/new-project-corpus}
 * to update them in place) and compares nothing; without the variable nothing is written. That
 * every vector also builds, tests and guards clean is the slow tier's {@code TemplateCatalogE2eTest},
 * which scaffolds the same vectors.
 *
 * <p>Fast tier: a scaffold renders a template from disk or a plugin jar with no network and no
 * engine, a few milliseconds each.
 */
class NewProjectCorpusTest {

    private static final String CAPTURE_ENV = "NEW_PROJECT_CORPUS_CAPTURE";
    private static final String SNAPSHOTS = "server/engine/src/test/resources/new-project-corpus";
    private static final String NAME = "demo";
    private static final String GROUP = "com.acme";
    private static final String JDK_MAJOR = "25";
    private static final String HEADER = "# The new-project corpus of ";
    /** Where a first-party plugin keeps the templates its jar bundles, under {@code plugins/<plugin>/}. */
    private static final String PLUGIN_TEMPLATES = "src/main/resources/templates";
    /** What every {@code maven()} property settles to here; a snapshot writes it {@code …} like any version. */
    private static final String MAVEN_VERSION = "1.0.0";

    /** A quoted version, bare or the last segment of a coordinate: {@code "33.8.0-jre"}, {@code "g:a:1.2.3"}. */
    private static final Pattern VERSION =
            Pattern.compile("\"((?:[A-Za-z0-9_.-]+:){0,2})\\d+(?:\\.\\d+)+(?:[-+.][A-Za-z0-9_.+-]*)?\"");

    /** A first-party plugin template: the plugin's directory under {@code plugins/}, and the template's id. */
    private record PluginTemplate(String plugin, String id) {}

    /** A registry entry this class replaced, put back after it. */
    private record Displaced(
            PluginDescriptor installed,
            @Nullable PluginDescriptor previous,
            @Nullable Path archive) {}

    @TempDir
    static Path scratch;

    private static final List<Displaced> DISPLACED = new ArrayList<>();

    @BeforeAll
    static void fixedDefaultJdk() throws IOException {
        JdkInventory inventory = new JdkInventory(scratch.resolve("jdks"), scratch.resolve("jk-jdks.toml"));
        inventory.setDefault(new InstalledJdk(
                "temurin-" + JDK_MAJOR + ".0.1", scratch.resolve("jdks/temurin-" + JDK_MAJOR + ".0.1")));
        NewProjectJava.inventory(() -> inventory);
        NewProjectOps.versionLookup((group, artifact, stable) -> MAVEN_VERSION);
    }

    @AfterAll
    static void machineDefaults() {
        NewProjectJava.inventory(null);
        NewProjectOps.versionLookup(null);
        // The registry is process-wide: every later test in this JVM sees what it held before.
        for (Displaced d : DISPLACED.reversed()) {
            PluginTableRegistry.unregister(d.installed());
            if (d.previous() != null) PluginTableRegistry.restoreBuiltIn(d.previous(), d.archive());
        }
        DISPLACED.clear();
        Giter8TemplateIndex.invalidate();
    }

    @TestFactory
    Stream<DynamicTest> every_catalogue_template_scaffolds_each_answer_vector_as_its_snapshot() throws IOException {
        Path repo = RepoRoot.find(NewProjectCorpusTest.class);
        Path root = repo.resolve("templates");
        List<Path> templates = templates(root);
        // Fake-green floor: an empty or near-empty walk is a moved tree, not a clean catalogue.
        assertThat(templates).as("templates under %s", root).hasSizeGreaterThanOrEqualTo(10);
        List<PluginTemplate> bundled = pluginTemplates(repo.resolve("plugins"));
        // The same floor for the plugins' templates: ten when the corpus first took them in.
        assertThat(bundled).as("templates under plugins/*/%s", PLUGIN_TEMPLATES).hasSizeGreaterThanOrEqualTo(10);
        installPlugins(bundled);
        String capture = System.getenv(CAPTURE_ENV);
        Path out = capture == null || capture.isBlank() ? null : Path.of(capture);
        Path snapshots = repo.resolve(SNAPSHOTS);

        List<DynamicTest> tests = new ArrayList<>();
        Set<String> expected = new TreeSet<>();
        Map<String, Scaffoldable> all = new LinkedHashMap<>();
        for (Path template : templates) {
            String id = root.relativize(template).toString().replace('\\', '/').replaceAll("\\.g8$", "");
            all.put(id, new Scaffoldable(template.toString(), template));
        }
        Path pluginCwd = Files.createDirectories(scratch.resolve("plugin-cwd"));
        for (PluginTemplate t : bundled) {
            assertThat(all)
                    .as("%s is both a templates/ tree and plugin %s's", t.id(), t.plugin())
                    .doesNotContainKey(t.id());
            all.put(t.id(), new Scaffoldable(t.id(), pluginCwd));
        }
        for (var e : all.entrySet()) {
            String id = e.getKey();
            Scaffoldable template = e.getValue();
            String file = id.replace('/', '-') + ".txt";
            expected.add(file);
            tests.add(DynamicTest.dynamicTest(id, () -> {
                String actual = snapshot(template, id);
                if (out != null) {
                    Files.createDirectories(out);
                    Files.writeString(out.resolve(file), actual, StandardCharsets.UTF_8);
                    return;
                }
                Path golden = snapshots.resolve(file);
                assertThat(golden)
                        .as("%s has no snapshot; capture it with %s=<dir> (see this class's javadoc)", id, CAPTURE_ENV)
                        .isRegularFile();
                assertThat(actual)
                        .as(
                                "%s scaffolds other than its snapshot %s; a deliberate change re-captures it with"
                                        + " %s=<dir> (see this class's javadoc)",
                                id, golden, CAPTURE_ENV)
                        .isEqualTo(Files.readString(golden, StandardCharsets.UTF_8));
            }));
        }
        for (PluginTemplate t : bundled) {
            tests.add(DynamicTest.dynamicTest(t.id() + " resolves to plugin " + t.plugin() + "'s jar", () -> {
                Optional<TemplateSpec> spec =
                        Giter8TemplateIndex.resolve(t.id(), null, Giter8TemplateIndex.searchRoots(pluginCwd));
                assertThat(spec).as("%s in the catalogue", t.id()).isPresent();
                assertThat(spec.get().source()).as("%s's source", t.id()).isEqualTo(TemplateSpec.SOURCE_PLUGIN);
                assertThat(spec.get().pluginId()).as("%s's plugin", t.id()).isEqualTo(t.plugin());
            }));
        }
        tests.add(DynamicTest.dynamicTest("no snapshot outlives its template", () -> {
            if (out != null) return;
            Set<String> committed = new TreeSet<>();
            try (Stream<Path> files = Files.list(snapshots)) {
                files.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".txt"))
                        .forEach(committed::add);
            }
            assertThat(committed).containsExactlyElementsOf(expected);
        }));
        return tests.stream();
    }

    /**
     * A template as a create names it: {@code ref} (a {@code templates/} tree's absolute path, a
     * plugin template's catalogue id) resolved against {@code cwd}.
     */
    private record Scaffoldable(String ref, Path cwd) {}

    /**
     * The templates every first-party plugin bundles: {@code <plugin>/src/main/resources/templates/
     * <lang>/<framework>/<name>.g8} under {@code plugins}, sorted by id.
     */
    private static List<PluginTemplate> pluginTemplates(Path plugins) throws IOException {
        List<PluginTemplate> out = new ArrayList<>();
        try (Stream<Path> dirs = Files.list(plugins)) {
            for (Path plugin : dirs.filter(Files::isDirectory).sorted().toList()) {
                Path root = plugin.resolve(PLUGIN_TEMPLATES);
                if (!Files.isDirectory(root)) continue;
                for (Path template : templates(root)) {
                    String id = root.relativize(template)
                            .toString()
                            .replace('\\', '/')
                            .replaceAll("\\.g8$", "");
                    out.add(new PluginTemplate(plugin.getFileName().toString(), id));
                }
            }
        }
        out.sort((a, b) -> a.id().compareTo(b.id()));
        return out;
    }

    /**
     * Installs the jar this build produced of every plugin in {@code bundled}, as the engine installs
     * a first-party plugin, so its templates reach the catalogue. The jar is the {@code -D} property
     * {@code test-plugin-jars} in this module's {@code jk.toml} hands the test JVM; a missing one is
     * broken wiring, not a skip.
     */
    private static void installPlugins(List<PluginTemplate> bundled) {
        Set<String> plugins = new TreeSet<>();
        for (PluginTemplate t : bundled) plugins.add(t.plugin());
        for (String plugin : plugins) {
            PluginJar worker = PluginJar.byArtifactId("jk-" + plugin)
                    .orElseThrow(() -> new AssertionError("plugins/" + plugin + " is no first-party worker"));
            String jar = System.getProperty(worker.jarProperty());
            assertThat(jar)
                    .as(
                            "-D%s: plugins/%s bundles templates, so test-plugin-jars in server/engine/jk.toml"
                                    + " names it",
                            worker.jarProperty(), plugin)
                    .isNotBlank();
            PluginDescriptor previous = PluginTableRegistry.byIdOrTable(plugin);
            Path archive = previous == null ? null : PluginTableRegistry.archive(previous.id());
            PluginTableRegistry.installFromJar(Path.of(jar));
            PluginDescriptor installed = PluginTableRegistry.byIdOrTable(plugin);
            if (installed == null) throw new AssertionError("plugin " + plugin + " is not installed from " + jar);
            DISPLACED.add(new Displaced(installed, previous, archive));
        }
        Giter8TemplateIndex.invalidate();
    }

    /** The template directories under {@code root}: {@code <lang>/<framework>/<name>.g8}, sorted. */
    static List<Path> templates(Path root) throws IOException {
        try (Stream<Path> walk =
                Files.find(root, 3, (p, a) -> a.isDirectory() && p.toString().endsWith(".g8"))) {
            return walk.sorted().toList();
        }
    }

    /** The form every surface renders for {@code template}: jk's step, then the template's. */
    private static Form form(Scaffoldable template) throws IOException {
        return NewProjectForm.of(NewProjectOps.templateForm(template.ref(), null, template.cwd()));
    }

    /**
     * {@code template}'s snapshot: a header, then per vector its label, every answer it settles to,
     * the scaffold's files and its normalized {@code jk.toml}. Every option of every field must be
     * settled by some vector, or the corpus does not cover the template.
     */
    private static String snapshot(Scaffoldable template, String id) throws IOException {
        Form form = form(template);
        List<Field> fields = NewProjectForm.templateFields(form);
        List<AnswerVectors.Vector> vectors = AnswerVectors.of(fields);
        Map<String, Set<String>> reached = new LinkedHashMap<>();
        StringBuilder out = new StringBuilder(HEADER)
                .append(id)
                .append(": NewProjectCorpusTest writes it; change the template, then re-capture.\n");
        int n = 0;
        for (AnswerVectors.Vector v : vectors) {
            Map<String, Object> settled = settled(form, fields, v.answers());
            settled.forEach((k, value) -> reached.computeIfAbsent(k, x -> new HashSet<>())
                    .addAll(
                            value instanceof List<?> list
                                    ? list.stream().map(String::valueOf).toList()
                                    : List.of(String.valueOf(value))));
            Path project = scaffold(template, v.answers(), scratch.resolve(id.replace('/', '-') + "-" + n++));
            out.append("\n=== ").append(v.label()).append('\n');
            settled.forEach((k, value) -> out.append(k)
                    .append(" = ")
                    .append(AnswerVectors.text(value))
                    .append('\n'));
            out.append("--- files\n");
            for (String f : files(project)) out.append(f).append('\n');
            out.append("--- jk.toml\n").append(normalize(Files.readString(project.resolve("jk.toml"))));
        }
        for (Field f : fields) {
            Set<String> seen = reached.getOrDefault(f.key(), Set.of());
            for (String option : options(f)) {
                assertThat(seen)
                        .as("%s: no vector settles %s to %s", id, f.key(), option)
                        .contains(option);
            }
        }
        return out.toString();
    }

    /** What a field's answers can be, as text: a choice's or multi's options, a bool's two values. */
    private static List<String> options(Field f) {
        return switch (f.kind()) {
            case CHOICE, MULTI -> f.options().stream().map(Option::id).toList();
            case BOOL -> List.of("false", "true");
            default -> List.of();
        };
    }

    /** The template fields' values once {@code answers} settle the form, defaults filling the rest. */
    private static Map<String, Object> settled(Form form, List<Field> fields, Map<String, Object> answers) {
        Map<String, Object> given = new LinkedHashMap<>(answers);
        given.put("name", NAME);
        Map<String, Object> values = form.check(given, DerivedDefaults.NONE).values();
        Map<String, Object> out = new LinkedHashMap<>();
        for (Field f : fields) {
            Object value = values.get(f.key());
            if (value != null) out.put(f.key(), value);
        }
        return out;
    }

    /**
     * Scaffolds {@code template} with {@code answers} as MCP {@code new} hands them to the engine:
     * the layout as the request's, every other answer as a template property. The defaults vector
     * sends neither, so the engine's own defaults are what it snapshots.
     */
    private static Path scaffold(Scaffoldable template, Map<String, Object> answers, Path parent) throws IOException {
        Files.createDirectories(parent);
        Map<String, String> params = new LinkedHashMap<>();
        String layout = null;
        for (var e : answers.entrySet()) {
            if (e.getKey().equals("layout")) layout = (String) e.getValue();
            else params.put(e.getKey(), Values.text(e.getValue()));
        }
        return NewProjectOps.create(new NewProjectOps.Request(
                        NAME,
                        parent.toString(),
                        GROUP,
                        null,
                        layout,
                        template.ref(),
                        true,
                        params,
                        false,
                        null,
                        template.cwd().toString()))
                .path();
    }

    private static List<String> files(Path project) throws IOException {
        try (Stream<Path> walk = Files.walk(project)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> project.relativize(p).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }

    /** {@code toml} with every quoted version written {@code …}; a coordinate keeps its group and artifact. */
    static String normalize(String toml) {
        Matcher m = VERSION.matcher(toml);
        StringBuilder out = new StringBuilder();
        while (m.find()) m.appendReplacement(out, Matcher.quoteReplacement("\"" + m.group(1) + "…\""));
        m.appendTail(out);
        String text = out.toString();
        return text.endsWith("\n") ? text : text + "\n";
    }
}
