// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static com.microsoft.playwright.assertions.PlaywrightAssertions.assertThat;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.Jk;
import cc.jumpkick.config.JkHttpConfig;
import cc.jumpkick.engine.http.CacheSnapshot;
import cc.jumpkick.engine.http.EngineHttpJobs;
import cc.jumpkick.engine.http.HttpEngineServer;
import cc.jumpkick.engine.http.HttpEvents;
import cc.jumpkick.engine.http.StatusSnapshot;
import cc.jumpkick.engine.jobs.JobSpec;
import cc.jumpkick.engine.journal.BuildJournal;
import cc.jumpkick.testing.RepoRoot;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The dashboard's new-project wizard in a real browser: Playwright's Chromium against the engine's
 * HTTP server in this JVM, serving the checkout's dashboard and the real form, preview and create.
 * A Spring Boot and a CLI project are created through the stepper, and the command the review step
 * shows, run through {@code jk new} into another directory, must scaffold the same tree, agent files
 * included; the project page the create opens shows what was wired for agents.
 *
 * <p>{@code release}: a real browser is worth running once per release, not on every push, and
 * Playwright fetches it while the dashboard loads Vue from a CDN ({@code jk test -p release -m
 * clients/cli --class …}). Each step's screenshot lands under {@code
 * clients/cli/target/new-project-wizard/}.
 */
@Tag("release")
class NewProjectWizardE2eTest {

    private static final String SPRING_BOOT = "java/spring-boot/webmvc-security-actuator-jpa-h2";
    private static final String CLI = "java/none/cli";

    @TempDir
    static Path state;

    private static HttpEngineServer server;
    private static String baseUrl;
    private static String token;
    private static Playwright playwright;
    private static Browser browser;
    private static final List<String> builds = new CopyOnWriteArrayList<>();

    private BrowserContext context;
    private Page page;

    @TempDir
    Path work;

    @BeforeAll
    static void serve() throws IOException {
        // No web root on disk: the dashboard is served from the jk-web jar this build made, as the
        // engine serves it (a disk web root is sandboxed and runs no script).
        Path web = state.resolve("no-web-root");
        Path tokenFile = state.resolve("http-token");
        Path log = Files.writeString(state.resolve("engine.log"), "jk engine: listening\n");
        EngineHttpJobs jobs = new EngineHttpJobs() {
            @Override
            public long trigger(JobSpec spec) {
                builds.add(spec.dir());
                return builds.size();
            }

            @Override
            public boolean cancel(long requestId) {
                return false;
            }

            @Override
            public int cancelDir(String dir) {
                return 0;
            }
        };
        // One snapshot: its start time is the engine generation the dashboard latches.
        StatusSnapshot status = new StatusSnapshot(
                "0.0.0-e2e", 1L, System.currentTimeMillis(), 0, 0, 1L << 20, 2L << 20, 256L << 20, -1L, 8, 16L << 30);
        server = new HttpEngineServer(
                new JkHttpConfig("127.0.0.1", 0, 16, 16, web.toString(), JkHttpConfig.Mcp.DEFAULTS),
                web,
                tokenFile,
                log,
                "0.0.0-e2e",
                () -> status,
                new HttpEvents(),
                jobs,
                new BuildJournal(state.resolve("journal")),
                List::of,
                () -> new CacheSnapshot(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                null);
        server.start();
        baseUrl = requireNonNull(server.url());
        token = Files.readString(tokenFile).strip();
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @AfterAll
    static void stop() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
        if (server != null) server.close();
    }

    @BeforeEach
    void page() {
        context = browser.newContext(new Browser.NewContextOptions().setViewportSize(1360, 900));
        page = context.newPage();
    }

    @AfterEach
    void closePage() {
        context.close();
    }

    @Test
    void a_spring_boot_project_made_in_the_wizard_is_the_one_its_command_makes() throws Exception {
        Path made = create("spring-boot", "webmvc", SPRING_BOOT, "shop", "simple");
        assertThat(made.resolve("jk.toml")).content().contains("[spring-boot]", "name = \"shop\"");
    }

    @Test
    void a_cli_project_made_in_the_wizard_is_the_one_its_command_makes() throws Exception {
        Path made = create("cli", "none/cli", CLI, "tool", "traditional");
        assertThat(made.resolve("jk.toml")).content().contains("main = \"org.example.e2e.Main\"");
    }

    /**
     * Walks the wizard: opens it with the directory pre-filled by link, picks {@code template}
     * through the search, names the project, picks {@code layout}, steps to review and creates it.
     * Then runs the review step's command into another directory and compares the two trees.
     */
    private Path create(String shot, String search, String template, String name, String layout) throws Exception {
        Path apps = Files.createDirectories(work.resolve("apps"));
        page.navigate(baseUrl + "#new?dir=" + enc(apps.toString()) + "&t=" + token);
        assertThat(page.locator(".wizard-stepper")).isVisible();

        page.locator("#wz-template").click();
        page.locator("#wz-template").fill(search);
        page.locator(".combo-list li:has(.wz-id:text-is(\"" + template + "\"))").click();
        assertThat(page.locator(".feed-head .retention")).hasText(template);
        page.locator("#wz-name").fill(name);
        page.locator("#wz-group").fill("org.example.e2e");
        page.locator("[data-field=layout] button", new Page.LocatorOptions().setHasText(layout))
                .click();
        assertThat(page.locator(".wz-tree")).containsText("jk.toml");
        assertThat(page.locator(".wz-manifest")).containsText("name = \"" + name + "\"");
        screenshot(shot + "-1-project");

        page.getByText("Next", new Page.GetByTextOptions().setExact(true)).click();
        assertThat(page.locator("[data-step=template]")).isVisible();
        screenshot(shot + "-2-template");
        page.getByText("Next", new Page.GetByTextOptions().setExact(true)).click();
        assertThat(page.locator("[data-step=review]")).isVisible();
        String command =
                requireNonNull(page.locator("pre.wz-cmd").textContent()).strip();
        assertThat(command).startsWith("jk new " + name + " -t " + template).contains("--dir ");
        screenshot(shot + "-3-review");

        page.getByText("Create project").click();
        Path made = apps.resolve(name);
        page.waitForURL(url -> url.contains("#project/"));
        assertThat(made.resolve("jk.toml")).exists();
        assertThat(builds).contains(made.toString());
        assertThat(made.resolve("AGENTS.md")).exists();
        assertThat(page.locator(".agent-notes")).containsText("trust this folder");
        screenshot(shot + "-4-project");

        Path again = Files.createDirectories(work.resolve("again"));
        assertThat(runCommand(command, again)).as(command).isZero();
        Map<String, String> wizard = tree(made);
        Map<String, String> cli = tree(again.resolve(name));
        assertThat(cli).as("`%s` scaffolds the tree the wizard did", command).isEqualTo(wizard);
        return made;
    }

    /**
     * Runs {@code command} as {@code jk new} would, its {@code --dir} pointed at {@code dir}. Its shell
     * is this JVM's environment, the one the wizard's engine reads its agents setting from, so both
     * wire the same agent files.
     */
    private static int runCommand(String command, Path dir) throws Exception {
        List<String> argv = new ArrayList<>(words(command));
        assertThat(argv.removeFirst()).isEqualTo("jk");
        int at = argv.indexOf("--dir");
        argv.set(at + 1, dir.toString());
        return Jk.execute(argv.toArray(String[]::new));
    }

    /** POSIX words: whitespace splits, single quotes keep, {@code '\''} is a quote. */
    private static List<String> words(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : line.toCharArray()) {
            if (c == '\'') {
                quoted = !quoted;
                any = true;
            } else if (c == '\\' && !quoted) {
                // only ever the escaped quote in '\''
            } else if (Character.isWhitespace(c) && !quoted) {
                if (any) out.add(word.toString());
                word.setLength(0);
                any = false;
            } else {
                word.append(c);
                any = true;
            }
        }
        if (any) out.add(word.toString());
        return out;
    }

    /** Every file of {@code project} but its build output, by path, with its text. */
    private static Map<String, String> tree(Path project) throws IOException {
        Map<String, String> out = new TreeMap<>();
        try (Stream<Path> walk = Files.walk(project)) {
            for (Path f : walk.filter(Files::isRegularFile).toList()) {
                String rel = project.relativize(f).toString().replace('\\', '/');
                if (rel.startsWith("target/")) continue;
                out.put(rel, Files.readString(f, StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private void screenshot(String name) throws IOException {
        Path dir = Files.createDirectories(
                RepoRoot.find(NewProjectWizardE2eTest.class).resolve("clients/cli/target/new-project-wizard"));
        page.screenshot(
                new Page.ScreenshotOptions().setPath(dir.resolve(name + ".png")).setFullPage(true));
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
