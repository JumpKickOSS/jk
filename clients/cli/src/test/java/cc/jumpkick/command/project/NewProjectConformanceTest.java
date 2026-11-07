// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.tui.form.Scripted.DOWN;
import static cc.jumpkick.cli.tui.form.Scripted.ENTER;
import static cc.jumpkick.cli.tui.form.Scripted.LEFT;
import static cc.jumpkick.cli.tui.form.Scripted.RIGHT;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.EngineRequests;
import cc.jumpkick.cli.tui.form.Scripted;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.McpHandler;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.runtime.NewProjectForm;
import cc.jumpkick.engine.runtime.NewProjectJava;
import cc.jumpkick.engine.runtime.NewProjectOps;
import cc.jumpkick.engine.runtime.NewProjectPost;
import cc.jumpkick.form.DerivedDefaults;
import cc.jumpkick.form.Field;
import cc.jumpkick.form.Form;
import cc.jumpkick.form.FormCommand;
import cc.jumpkick.form.Option;
import cc.jumpkick.form.Values;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.jdk.InstalledJdk;
import cc.jumpkick.jdk.JdkInventory;
import cc.jumpkick.jsonl.MiniJson;
import cc.jumpkick.scaffold.NewProjectStep;
import cc.jumpkick.testing.RepoRoot;
import cc.jumpkick.wire.protocol.NewProjectFormAck;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * One answer set, every surface: the same scripted answers go through the CLI renderer (keystrokes
 * into a memory TTY, the form walked as {@code jk new} walks it, then the request {@code jk new}
 * posts), through MCP {@code new} (a fake client that declared form elicitation, passing as
 * arguments what the engine will not ask and answering each elicitation from the script), and
 * through the web wizard's view model ({@code wizard-model.js} under Node: a link carrying the
 * script opened in the wizard, and the body it posts settled as {@code POST /api/projects} settles
 * it). Each surface's posted answers, read back as one map of form key to text, must equal the
 * script and so each other, and the projects they scaffold must be identical, file for file and
 * byte for byte.
 *
 * <p>The template asks one field of every kind a template may ask, all but the conditional one
 * without a default, so MCP elicits them rather than taking defaults, plus a field shown only when
 * an earlier answer allows. Each surface is a {@link Surface}: it turns the script into its own
 * inputs and reports what it posted and where the project went; the assertions compare every
 * surface in {@link #surfaces} with the first.
 *
 * <p>Fast tier: the engine's form, MCP handler and scaffolder run in this JVM, from a template on
 * disk, with no network and no engine; the view model runs under the Node.js this module pins.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class NewProjectConformanceTest {

    /**
     * The scripted answers, one per field of the form: jk's step ({@code template} and {@code dir}
     * are filled in per run), then the template's.
     */
    private static Map<String, Object> script(Path template, Path parent) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("lang", "java");
        s.put("template", template.toString());
        s.put("name", "widget");
        s.put("group", "org.example.widgets");
        s.put("dir", parent.toString());
        s.put("layout", "simple");
        s.put("motto", "hello");
        s.put("native", true);
        s.put("database", "postgres");
        s.put("cache", "on");
        s.put("libs", List.of("jackson", "slf4j"));
        return s;
    }

    /** What a surface posted, as form key to text, and the project it scaffolded. */
    record Outcome(Map<String, String> posted, Path project) {}

    /** One way of answering the new-project form. */
    interface Surface {
        String name();

        Outcome run(Path template, Map<String, Object> script, Path parent) throws Exception;
    }

    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();

    @TempDir
    Path temp;

    private Path template;

    @BeforeEach
    void setUp() throws IOException {
        template = asking(temp.resolve("templates/java/none/asking.g8"));
        JdkInventory inventory = new JdkInventory(temp.resolve("jdks"), temp.resolve("jk-jdks.toml"));
        inventory.setDefault(new InstalledJdk("temurin-25.0.1", temp.resolve("jdks/temurin-25.0.1")));
        NewProjectJava.inventory(() -> inventory);
    }

    @AfterEach
    void tearDown() {
        NewProjectJava.inventory(null);
        threads.shutdownNow();
    }

    /** Every surface, the first the reference the others are compared with. */
    private List<Surface> surfaces() {
        return List.of(new Cli(), new Mcp(), new Web());
    }

    @Test
    void one_answer_set_posts_the_same_answers_and_scaffolds_the_same_tree_on_every_surface() throws Exception {
        Path parent = Files.createDirectories(temp.resolve("apps"));
        Map<String, Object> script = script(template, parent);
        Map<String, String> expected = new LinkedHashMap<>();
        script.forEach((k, v) -> expected.put(k, Values.text(v)));

        Map<String, String> referencePosted = null;
        Map<String, String> referenceTree = null;
        String reference = null;
        for (Surface surface : surfaces()) {
            Outcome outcome = surface.run(template, script, parent);
            assertThat(outcome.posted())
                    .as("%s posts the scripted answers", surface.name())
                    .containsExactlyInAnyOrderEntriesOf(expected);
            Map<String, String> tree = tree(outcome.project());
            assertThat(tree.get("jk.toml"))
                    .as("%s scaffolds the answers", surface.name())
                    .contains("motto = \"hello\"", "db = \"postgres\"", "cache = \"on\"", "libs = \"jackson,slf4j\"")
                    .contains("group = \"org.example.widgets\"", "java = 25", "[native]");
            assertThat(tree).as("%s renders the simple layout", surface.name()).containsKey("src/Main.java");
            if (reference == null) {
                reference = surface.name();
                referencePosted = outcome.posted();
                referenceTree = tree;
            } else {
                assertThat(outcome.posted())
                        .as("%s posts what %s posts", surface.name(), reference)
                        .isEqualTo(referencePosted);
                assertThat(tree)
                        .as("%s scaffolds the tree %s scaffolds", surface.name(), reference)
                        .isEqualTo(referenceTree);
            }
            // The next surface scaffolds into the same directory, so its dir answer is the same too.
            PathUtil.deleteRecursivelyOrThrow(outcome.project());
        }
    }

    /**
     * {@code jk new} on a terminal: the form walked with the widgets, keystrokes made from the
     * script, then the request the command posts once the form settled.
     */
    private final class Cli implements Surface {

        private static final Map<String, FormCommand.Flag> FLAGS = Map.of(
                "lang", FormCommand.Flag.named("--lang"),
                "template", FormCommand.Flag.named("-t"),
                "name", FormCommand.Flag.positional(),
                "group", FormCommand.Flag.named("--group"),
                "dir", FormCommand.Flag.named("--dir"),
                "layout", FormCommand.Flag.named("-L"));

        @Override
        public String name() {
            return "the CLI";
        }

        @Override
        public Outcome run(Path template, Map<String, Object> script, Path parent) throws Exception {
            NewForm.Forms forms = (ref, lang) -> {
                NewProjectOps.TemplateForm t = NewProjectOps.templateForm(requireNonNull(ref), lang, parent);
                return new NewProjectFormAck(null, NewProjectForm.of(t).toJson(), t.id(), Map.of());
            };
            // The surface derives group and directory itself; here they derive the scripted ones.
            DerivedDefaults derived = from -> switch (from) {
                case NewProjectStep.GIT_EMAIL -> script.get("group");
                case NewProjectStep.PARENT_DIR -> script.get("dir");
                default -> null;
            };
            NewForm.Given given = new NewForm.Given(null, template.toString(), null, null, null, null, Map.of());
            Form form = Form.fromJson(forms.fetch(template.toString(), null).form());
            String keys = keys(form, script, derived);
            Scripted.Run<NewForm.Answered> walked = Scripted.drive(Scripted.Mode.ANSI, keys, "", asker -> {
                try {
                    return NewForm.walk(asker, forms, given, derived, parent, "jk new", FLAGS);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
            NewForm.Answered a = walked.answer()
                    .orElseThrow(() -> new AssertionError("the walk backed out:\n" + String.join("\n", walked.last())));
            List<String> pairs = new ArrayList<>();
            a.params().forEach((k, v) -> pairs.add(k + "=" + v));
            Path base = Path.of(requireNonNull(a.text("dir")));
            EngineRequests.NewProjectRequest posted = NewTemplate.request(
                    new NewTemplate.Args(
                            a.template(),
                            pairs,
                            a.name(),
                            a.text("group"),
                            a.text("lang"),
                            a.text("layout"),
                            null,
                            base,
                            parent,
                            null,
                            false,
                            null),
                    requireNonNull(NewTemplate.params(pairs)));
            // As the engine's new verb takes the request off the wire.
            NewProjectOps.Created created = NewProjectOps.createWithIdentity(new NewProjectOps.Request(
                    posted.name(),
                    posted.parentDir(),
                    posted.group(),
                    posted.lang(),
                    posted.layout(),
                    posted.template(),
                    posted.standalone(),
                    posted.templateParams(),
                    posted.relaxParent(),
                    posted.targetDir(),
                    posted.cwd()));
            Map<String, String> answers = new LinkedHashMap<>();
            putIfSet(answers, "lang", posted.lang());
            putIfSet(answers, "template", posted.template());
            putIfSet(answers, "name", posted.name());
            putIfSet(answers, "group", posted.group());
            putIfSet(answers, "dir", posted.parentDir());
            putIfSet(answers, "layout", posted.layout());
            for (Field f : form.fields()) {
                if (!NewProjectStep.KEYS.contains(f.key()))
                    putIfSet(answers, f.key(), posted.templateParams().get(f.key()));
            }
            return new Outcome(answers, created.path());
        }

        /**
         * The keystrokes that answer {@code form} with {@code script}, field by field as the walk
         * asks them: the template is already named, an advanced or hidden field is not asked, a
         * text or path whose default is the answer takes it with Enter, a choice moves from its
         * default to the answer, a bool is y or n, and a multi toggles each option that differs from
         * its default.
         */
        private static String keys(Form form, Map<String, Object> script, DerivedDefaults derived) {
            StringBuilder keys = new StringBuilder();
            Map<String, Object> values = new LinkedHashMap<>();
            for (Field f : form.fields()) {
                Object answer = requireNonNull(script.get(f.key()), f.key());
                boolean asked = !f.key().equals("template") && !f.advanced() && Form.visible(f, values);
                values.put(f.key(), answer);
                if (!asked) continue;
                Object fallback = Form.defaultOf(f, derived);
                switch (f.kind()) {
                    case TEXT, PATH, SEARCH ->
                        keys.append(answer.equals(fallback) ? "" : answer).append(ENTER);
                    case BOOL -> keys.append(Boolean.TRUE.equals(answer) ? "y" : "n");
                    case CHOICE -> {
                        List<String> ids = f.options().stream().map(Option::id).toList();
                        int from = fallback == null ? 0 : ids.indexOf(String.valueOf(fallback));
                        int to = ids.indexOf(String.valueOf(answer));
                        keys.append((to >= from ? RIGHT : LEFT).repeat(Math.abs(to - from)))
                                .append(ENTER);
                    }
                    case MULTI -> {
                        List<?> picked = (List<?>) answer;
                        List<?> preset = fallback instanceof List<?> l ? l : List.of();
                        int focus = 0;
                        for (int i = 0; i < f.options().size(); i++) {
                            String id = f.options().get(i).id();
                            if (picked.contains(id) == preset.contains(id)) continue;
                            keys.append(DOWN.repeat(i - focus)).append(' ');
                            focus = i;
                        }
                        keys.append(ENTER);
                    }
                }
            }
            return keys.toString();
        }
    }

    /**
     * MCP {@code new} from a client that declared form elicitation: the call carries the template,
     * the parent directory and every answer the engine would not ask (a field with a default); the
     * client answers each elicitation with the script's answers to the fields it asks.
     */
    private final class Mcp implements Surface {

        @Override
        public String name() {
            return "MCP elicitation";
        }

        @Override
        public Outcome run(Path template, Map<String, Object> script, Path parent) throws Exception {
            McpHandler mcp = handler();
            String session = requireNonNull(mcp.handle(
                            "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":"
                                    + "\"2025-11-25\",\"capabilities\":{\"elicitation\":{\"form\":{}}},"
                                    + "\"clientInfo\":{\"name\":\"fake\",\"version\":\"1\"}}}",
                            null)
                    .openedSessionId());
            Form form = NewProjectForm.of(NewProjectOps.templateForm(template.toString(), null, parent));
            Map<String, String> posted = new LinkedHashMap<>();
            Map<String, Object> args = new LinkedHashMap<>();
            Map<String, Object> params = new LinkedHashMap<>();
            args.put("template", script.get("template"));
            args.put("parentDir", script.get("dir"));
            posted.put("template", String.valueOf(script.get("template")));
            posted.put("dir", String.valueOf(script.get("dir")));
            for (Field f : form.fields()) {
                if (f.key().equals("template") || f.key().equals("dir") || f.defaultValue() == null) continue;
                Object answer = requireNonNull(script.get(f.key()), f.key());
                (NewProjectStep.KEYS.contains(f.key()) ? args : params).put(f.key(), answer);
                posted.put(f.key(), Values.text(answer));
            }
            args.put("params", params);
            String call = "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{\"name\":\"new\","
                    + "\"arguments\":" + MiniJson.write(args) + "}}";

            BlockingQueue<Map<String, Object>> sent = new LinkedBlockingQueue<>();
            CompletableFuture<McpHandler.Reply> reply =
                    CompletableFuture.supplyAsync(() -> mcp.handle(call, session, null, null, sent::add), threads);
            while (!reply.isDone()) {
                Map<String, Object> request = sent.poll(100, TimeUnit.MILLISECONDS);
                if (request == null) continue;
                assertThat(request.get("method")).isEqualTo("elicitation/create");
                Map<String, Object> content = new LinkedHashMap<>();
                for (String key : properties(request).keySet()) {
                    Object answer = requireNonNull(script.get(key), key);
                    content.put(key, answer);
                    posted.put(key, Values.text(answer));
                }
                mcp.handle(
                        "{\"jsonrpc\":\"2.0\",\"id\":" + MiniJson.write(String.valueOf(request.get("id")))
                                + ",\"result\":{\"action\":\"accept\",\"content\":" + MiniJson.write(content) + "}}",
                        session);
            }
            String body = reply.get(30, TimeUnit.SECONDS).body();
            assertThat(body).as(body).contains("\"created\"");
            return new Outcome(posted, parent.resolve(String.valueOf(script.get("name"))));
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> properties(Map<String, Object> request) {
            Map<String, Object> params = (Map<String, Object>) requireNonNull(request.get("params"));
            Map<String, Object> schema = (Map<String, Object>) requireNonNull(params.get("requestedSchema"));
            return (Map<String, Object>) requireNonNull(schema.get("properties"));
        }

        private static McpHandler handler() {
            return new McpHandler(
                    () -> new StatusSnapshot(
                            "0.0.0",
                            1L,
                            System.currentTimeMillis() - 5_000,
                            0,
                            0,
                            1L << 20,
                            2L << 20,
                            256L << 20,
                            -1L,
                            8,
                            16L << 30),
                    new EngineHttpJobs() {
                        @Override
                        public long trigger(JobSpec spec) {
                            return 1L;
                        }

                        @Override
                        public boolean cancel(long requestId) {
                            return false;
                        }

                        @Override
                        public int cancelDir(String dir) {
                            return 0;
                        }
                    },
                    dir -> Map.of(),
                    List::of,
                    "0.0.0");
        }
    }

    /**
     * The dashboard's wizard: the form served as {@code GET /api/forms/new-project} serves it, the
     * view model under Node opening a link that carries the script and building the body it posts,
     * and that body settled and scaffolded as {@code POST /api/projects} does.
     */
    private final class Web implements Surface {

        /** Opens the link in the view model and prints the body the wizard posts. */
        private static final String DRIVER = """
                import { readFileSync } from 'node:fs';
                import { linkFromHash, openingAnswers, postBody, wizardHash } from './wizard-model.js';
                const { served, script } = JSON.parse(readFileSync(0, 'utf8'));
                const link = linkFromHash(wizardHash(script));
                const answers = openingAnswers(served, link.answers);
                process.stdout.write(JSON.stringify(postBody(served.form, answers, served.derived)));
                """;

        @Override
        public String name() {
            return "the web wizard";
        }

        @Override
        public Outcome run(Path template, Map<String, Object> script, Path parent) throws Exception {
            Map<String, String> derived = Map.of(
                    NewProjectStep.GIT_EMAIL, String.valueOf(script.get("group")),
                    NewProjectStep.PARENT_DIR, String.valueOf(script.get("dir")));
            NewProjectOps.TemplateForm t = NewProjectPost.templateForm(template.toString(), null, derived);
            Map<String, Object> served = new LinkedHashMap<>();
            served.put("form", MiniJson.parse(NewProjectForm.of(t).toJson()));
            served.put("template", t.id());
            served.put("derived", derived);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("served", served);
            input.put("script", script);
            Object body = MiniJson.parse(node(MiniJson.write(input)));

            Map<String, String> posted = new LinkedHashMap<>();
            Map<String, Object> answers = new LinkedHashMap<>();
            if (MiniJson.get(body, "answers") instanceof Map<?, ?> raw) {
                raw.forEach((k, v) -> {
                    answers.put(String.valueOf(k), requireNonNull(v));
                    posted.put(String.valueOf(k), Values.text(requireNonNull(v)));
                });
            }
            NewProjectPost.Outcome outcome = NewProjectPost.settle(answers, MiniJson.str(body, "rev"), derived);
            if (!(outcome instanceof NewProjectPost.Outcome.Ready ready)) {
                throw new AssertionError("the wizard's post did not settle: " + outcome);
            }
            NewProjectOps.Created created = NewProjectOps.createWithIdentity(ready.request());
            return new Outcome(posted, created.path());
        }

        /** Runs {@link #DRIVER} beside a copy of the view model, {@code input} on its stdin. */
        private String node(String input) throws IOException, InterruptedException {
            Path stage = Files.createDirectories(temp.resolve("wizard"));
            Path model = RepoRoot.find(NewProjectConformanceTest.class)
                    .resolve("clients/web/src/main/resources/web/wizard-model.js");
            Files.copy(model, stage.resolve("wizard-model.js"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(stage.resolve("package.json"), "{\"type\":\"module\"}\n");
            Files.writeString(stage.resolve("drive.mjs"), DRIVER);
            Path in = Files.writeString(stage.resolve("input.json"), input);
            String home = System.getenv("NODE_HOME");
            String node = home == null || home.isBlank()
                    ? "node"
                    : Path.of(home, "bin", "node").toString();
            Process p = new ProcessBuilder(node, "drive.mjs")
                    .directory(stage.toFile())
                    .redirectInput(in.toFile())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(p.waitFor(30, TimeUnit.SECONDS)).as("node finishes").isTrue();
            assertThat(p.exitValue()).as("node drive.mjs: %s", out).isZero();
            return out;
        }
    }

    /** Every file of {@code project}, by its path under it, with its text. */
    private static Map<String, String> tree(Path project) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(project)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                out.put(
                        project.relativize(f).toString().replace('\\', '/'),
                        Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void putIfSet(Map<String, String> out, String key, @Nullable String value) {
        if (value != null) out.put(key, value);
    }

    /**
     * A template asking one field of each kind with no default (so MCP elicits each), and one shown
     * only when the database is postgres; it renders both layouts.
     */
    private static Path asking(Path g8) throws IOException {
        Files.createDirectories(g8.resolve("src/main/g8"));
        Files.writeString(g8.resolve(".jk-template.toml"), """
                language = "java"
                framework = "none"
                name = "asking"
                layouts = ["traditional", "simple"]

                [[ask]]
                key = "motto"
                label = "Motto"
                kind = "text"
                pattern = "[a-z]+"

                [[ask]]
                key = "native"
                label = "Native binary"
                kind = "bool"

                [[ask]]
                key = "database"
                label = "Database"
                kind = "choice"
                options = ["none", "h2", "postgres"]

                [[ask]]
                key = "cache"
                label = "Cache"
                kind = "choice"
                options = ["off", "on"]
                when = "database == 'postgres'"

                [[ask]]
                key = "libs"
                label = "Libraries"
                kind = "multi"
                options = ["guava", "jackson", "slf4j"]
                """);
        Files.writeString(g8.resolve("default.properties"), "name=demo\njava=21\njk_layout=traditional\n");
        Files.writeString(g8.resolve("src/main/g8/jk.toml"), """
                group = "$organization$"
                name = "$name$"
                java = $java$
                motto = "$motto$"
                db = "$database$"
                $if(cache)$
                cache = "$cache$"
                $endif$
                libs = "$libs$"
                $if(native.truthy)$
                [native]
                $endif$
                """);
        Path main = g8.resolve(
                "src/main/g8/src/$if(simple.truthy)$.$else$main$endif$/$if(simple.truthy)$.$else$java$endif$");
        Files.createDirectories(main);
        Files.writeString(main.resolve("Main.java"), "class Main {}\n");
        return g8;
    }
}
