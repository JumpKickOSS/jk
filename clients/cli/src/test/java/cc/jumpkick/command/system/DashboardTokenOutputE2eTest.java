// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.cache.EngineInstall;
import cc.jumpkick.cli.Jk;
import cc.jumpkick.cli.engine.EngineSpawn;
import cc.jumpkick.host.Os;
import cc.jumpkick.host.Processes;
import cc.jumpkick.jsonl.Jsonl;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * No command that prints a dashboard URL prints the engine's token where its output is captured:
 * machine output ({@code -O jsonl}), agent output ({@code --agent}) and piped output (JK-JDT0Y).
 * Every run here is piped, as an agent's tool call and a CI step are. The token is the engine's,
 * read from its owner-only file; the one explicit ask, {@code jk web -O jsonl --with-token}, still
 * hands it over.
 *
 * <p>A forked client against a private {@code JK_HOME} seeded with the sandbox's engine, as {@code
 * RestampedEngineNoopE2eTest} runs: the dashboard serves from a real engine process.
 */
@Tag("integration")
class DashboardTokenOutputE2eTest {

    @Test
    void no_machine_agent_or_piped_output_carries_the_engine_token(@TempDir Path tmp) throws Exception {
        Optional<EngineInstall.Materialized> sandboxEngine =
                EngineInstall.current().currentInstall();
        assumeTrue(sandboxEngine.isPresent(), "the sandbox JK_HOME has no engine jar to seed the private home with");
        Path home = tmp.resolve("home");
        copyTree(sandboxEngine.get().root(), home.resolve("lib").resolve(EngineInstall.BIN_NAME));
        Files.deleteIfExists(home.resolve("lib").resolve(EngineInstall.BIN_NAME).resolve(EngineInstall.SHELF_NAME));
        Path cwd = Files.createDirectories(tmp.resolve("cwd"));
        try {
            Map<String, Run> runs = new LinkedHashMap<>();
            // The first run starts the engine, which mints its token.
            runs.put("jk web -O jsonl", jk(home, cwd, Map.of(), "web", "-O", "jsonl"));
            List<String> tokens = tokenFiles(home);
            assertThat(tokens)
                    .as("the engine's token file; jk web -O jsonl said:\n"
                            + runs.get("jk web -O jsonl").output())
                    .isNotEmpty()
                    .allMatch(t -> !t.isBlank());
            runs.put("jk web --no-open (piped)", jk(home, cwd, Map.of(), "web", "--no-open"));
            runs.put("jk web --no-open --agent", jk(home, cwd, Map.of(), "web", "--no-open", "--agent"));
            runs.put("jk web --no-open under CI", jk(home, cwd, Map.of("CI", "true"), "web", "--no-open"));
            runs.put("jk web --no-open under an agent", jk(home, cwd, Map.of("CLAUDECODE", "1"), "web", "--no-open"));
            runs.put("jk engine status (piped)", jk(home, cwd, Map.of(), "engine", "status"));
            runs.put("jk engine status -O jsonl", jk(home, cwd, Map.of(), "engine", "status", "-O", "jsonl"));
            runs.put("jk engine status --agent", jk(home, cwd, Map.of(), "engine", "status", "--agent"));
            if (!Os.isWindows()) {
                // Off Windows there is nothing to fix and no decision is raised; on Windows a piped
                // run would wait for another surface to answer.
                runs.put("jk doctor --fix (piped)", jk(home, cwd, Map.of(), "doctor", "--fix"));
            }
            runs.forEach((what, run) -> {
                assertThat(run.exit()).as(what + ":\n" + run.output()).isZero();
                for (String token : tokens) assertThat(run.output()).as(what).doesNotContain(token);
            });
            assertThat(runs.get("jk web --no-open (piped)").output())
                    .as("the URL itself is still printed")
                    .contains("http://");
            assertThat(runs.get("jk engine status (piped)").output()).contains("http://");

            // The one explicit ask, for an editor that connects to the engine, still hands it over.
            Run asked = jk(home, cwd, Map.of(), "web", "-O", "jsonl", "--with-token");
            assertThat(asked.exit()).as(asked.output()).isZero();
            String line = asked.output()
                    .lines()
                    .filter(l -> l.startsWith("{"))
                    .reduce((a, b) -> b)
                    .orElseThrow(() -> new AssertionError("no connect line:\n" + asked.output()));
            assertThat(tokens).contains(Jsonl.str(line, "token"));
        } finally {
            jk(home, cwd, Map.of(), "engine", "stop", "--now");
        }
    }

    /** The trimmed contents of every engine token file under {@code home}. */
    private static List<String> tokenFiles(Path home) throws IOException {
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(home)) {
            for (Path f : files.filter(p -> p.getFileName().toString().endsWith(".http-token"))
                    .toList()) {
                String token = Files.readString(f).strip();
                if (!out.contains(token)) out.add(token);
            }
        }
        return out;
    }

    private record Run(int exit, String output) {}

    /** Fork the CLI on this JVM's classpath against {@code home}, its output piped; bounded. */
    private static Run jk(Path home, Path cwd, Map<String, String> env, String... args)
            throws IOException, InterruptedException {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        cmd.addAll(EngineSpawn.forwardedJvmArgs());
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(Jk.class.getName());
        cmd.add("--no-progress");
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).directory(cwd.toFile()).redirectErrorStream(true);
        pb.environment().put("JK_HOME", home.toString());
        // The engine's state, its token file among it, in this home and no other suite's.
        pb.environment().put("JK_STATE_DIR", home.resolve("state").toString());
        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.environment().remove("JK_ENGINE_EXE");
        // The dashboard on, on a port of its own; no front-door duty, so nothing of this home
        // pairs with, or writes the token of, the person's engines.
        pb.environment().put("JK_HTTP_ENABLED", "true");
        pb.environment().put("JK_HTTP_PORT", "0");
        pb.environment().put("JK_FRONT_DOOR", "false");
        // The agent and CI marks of the shell running this suite would hide what a plain pipe prints.
        pb.environment().remove("CI");
        pb.environment().remove("JK_AGENT");
        for (String name : List.of(
                "GROK_AGENT",
                "CLAUDECODE",
                "AI_AGENT",
                "CURSOR_AGENT",
                "CODEX_THREAD_ID",
                "CODEX_SANDBOX",
                "GEMINI_CLI")) {
            pb.environment().remove(name);
        }
        pb.environment().putAll(env);
        Processes.Captured run = Processes.capture(pb, Duration.ofMinutes(4));
        if (run.timedOut()) {
            throw new IOException(
                    "jk " + String.join(" ", args) + " did not finish within 4 minutes:\n" + run.output());
        }
        return new Run(run.exit(), run.output());
    }

    private static void copyTree(Path from, Path to) throws IOException {
        try (Stream<Path> files = Files.walk(from)) {
            for (Path src : files.toList()) {
                Path dst = to.resolve(from.relativize(src).toString());
                if (Files.isDirectory(src)) Files.createDirectories(dst);
                else Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }
}
