// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.toolchain;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.IsolatedStore;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.host.Os;
import cc.jumpkick.jsonl.Jsonl;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.testing.FakeNodeDist;
import cc.jumpkick.testing.FakePrograms;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk node} (and {@code jk nvm}) against a stub Node.js distribution: every verb, its
 * {@code --output json} row, and the locked Node.js first on {@code PATH} for {@code exec} and
 * {@code run}.
 */
@Tag("integration")
@IsolatedStore
class NodeCommandE2eTest {

    private static final String MIRROR = "jk.env.JK_NODE_DIST_MIRROR";
    private static final String VERSION = "24.99.0";
    private static final String OLDER = "22.99.0";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().withoutChecksums();

    private @Nullable String priorMirror;

    @BeforeEach
    void dist() throws IOException {
        priorMirror = System.getProperty(MIRROR);
        StringBuilder sums = new StringBuilder();
        for (String v : List.of(VERSION, OLDER)) {
            NodePlatform host = NodePlatform.host();
            byte[] archive = archive(host.key(), v);
            http.served().put("/v" + v + "/" + host.archiveName(v), archive);
            StringBuilder perVersion = new StringBuilder();
            for (NodePlatform p : NodePlatform.LOCKED) {
                String sha = p.key().equals(host.key()) ? Hashing.sha256Hex(archive) : "0".repeat(64);
                perVersion.append(sha).append("  ").append(p.archiveName(v)).append('\n');
            }
            if (NodePlatform.LOCKED.stream().noneMatch(p -> p.key().equals(host.key()))) {
                perVersion
                        .append(Hashing.sha256Hex(archive))
                        .append("  ")
                        .append(host.archiveName(v))
                        .append('\n');
            }
            http.serve("/v" + v + "/SHASUMS256.txt", perVersion.toString());
            sums.append(perVersion);
        }
        http.serve(
                "/index.json",
                "[{\"version\":\"v" + VERSION + "\",\"npm\":\"11.0.0\",\"lts\":\"Krypton\",\"security\":false,"
                        + "\"files\":[\"linux-x64\",\"linux-arm64\",\"osx-arm64-tar\",\"osx-x64-tar\"]},"
                        + "{\"version\":\"v" + OLDER + "\",\"npm\":\"10.0.0\",\"lts\":\"Jod\",\"security\":true,"
                        + "\"files\":[\"linux-x64\",\"linux-arm64\",\"osx-arm64-tar\",\"osx-x64-tar\"]}]");
        System.setProperty(MIRROR, http.baseUrl());
    }

    @AfterEach
    void restore() {
        if (priorMirror == null) System.clearProperty(MIRROR);
        else System.setProperty(MIRROR, priorMirror);
    }

    @Test
    void install_list_verify_and_uninstall(@TempDir Path dir) {
        String installed = Capture.stdout(() -> run("node", "install", "24", "-C", dir.toString(), "-O", "json"));
        assertThat(Jsonl.str(row(installed, "node-provisioned"), "version")).isEqualTo(VERSION);

        String listed = Capture.stdout(() -> run("node", "list", "-C", dir.toString(), "-O", "json"));
        String jk = row(listed, "node-list");
        assertThat(Jsonl.str(jk, "version")).isEqualTo(VERSION);
        assertThat(Jsonl.str(jk, "source")).isEqualTo("jk");

        String verified = Capture.stdout(() -> run("node", "verify", "-O", "json"));
        assertThat(Jsonl.bool(row(verified, "node-verify"), "ok", false)).isTrue();

        assertThat(run("node", "uninstall", VERSION)).isZero();
        String after = Capture.stdout(() -> run("node", "list", "-O", "json"));
        assertThat(after).doesNotContain("\"source\":\"jk\"");
    }

    @Test
    void an_install_shows_the_download_as_a_jdk_install_does(@TempDir Path dir) {
        var shown = Capture.both(() -> run("node", "install", OLDER, "-C", dir.toString(), "--no-ansi"));
        assertThat(shown.out() + shown.err())
                .as("the engine streams the download and the client draws the JDK's row")
                .contains("Downloading Node.js " + OLDER);
        assertThat(run("node", "uninstall", OLDER)).isZero();
    }

    @Test
    void list_remote_filters_by_lts_and_major(@TempDir Path dir) {
        String all = Capture.stdout(() -> run("node", "list-remote", "-O", "json"));
        assertThat(all).contains(VERSION).contains(OLDER);
        String major = Capture.stdout(() -> run("nvm", "ls-remote", "--major", "22", "-O", "json"));
        assertThat(major).contains(OLDER).doesNotContain(VERSION);
    }

    @Test
    void nvm_is_the_same_command_as_node(@TempDir Path dir) {
        assertThat(Capture.stdout(() -> run("nvm", "list-remote", "--lts")))
                .isEqualTo(Capture.stdout(() -> run("node", "list-remote", "--lts")));
        assertThat(Capture.stdout(() -> run("--help", "nvm")))
                .contains("list-remote")
                .contains("pin");
    }

    @Test
    void which_exec_and_run_use_the_locked_node(@TempDir Path project) throws Exception {
        lockedProject(project);
        assertThat(run("node", "install", VERSION)).isZero();

        String which = Capture.stdout(() -> run("node", "which", "-C", project.toString()))
                .trim();
        assertThat(Path.of(which))
                .endsWithRaw(Path.of("node", VERSION).resolve(Os.isWindows() ? "node.exe" : "bin/node"));

        // The locked node is first on PATH even when the shell has another.
        Path out = project.resolve("which-node.txt");
        List<String> lookup = Os.isWindows()
                ? List.of("cmd", "/c", "where node > which-node.txt")
                : List.of("sh", "-c", "command -v node > which-node.txt");
        List<String> exec = new ArrayList<>(List.of("node", "exec", "-C", project.toString(), "--"));
        exec.addAll(lookup);
        assertThat(run(exec.toArray(String[]::new))).isZero();
        assertThat(Files.readAllLines(out).getFirst().trim()).isEqualTo(which);

        // `run` hands the script to the package manager under the locked node.
        assertThat(run("node", "run", "-C", project.toString(), "build")).isZero();
        assertThat(Files.readString(project.resolve("node-args.txt")).replace("\r\n", "\n"))
                .contains("npm-cli.js")
                .contains("run\nbuild");
    }

    @Test
    void pin_writes_what_nvmrc_names_and_the_node_version_file(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\n");
        Files.writeString(project.resolve(".nvmrc"), "22\n");

        String pinned =
                Capture.stdout(() -> run("node", "pin", "--file", "--no-lock", "-C", project.toString(), "-O", "json"));

        assertThat(Files.readString(project.resolve("jk.toml"))).contains("node = 22");
        assertThat(Files.readString(project.resolve(".node-version")).trim()).isEqualTo("22");
        assertThat(Jsonl.str(row(pinned, "node-pinned"), "from")).isEqualTo(".nvmrc");
    }

    @Test
    void pin_relocks_and_update_reports_the_newest_of_the_major(@TempDir Path project) throws Exception {
        Files.writeString(project.resolve("jk.toml"), "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\n");
        assertThat(run("node", "pin", "24", "-C", project.toString())).isZero();
        assertThat(Files.readString(project.resolve("jk-lock.toml")))
                .contains("[node]")
                .contains(VERSION);

        String update = Capture.stdout(() -> run("node", "update", "-C", project.toString(), "-O", "json"));
        String row = row(update, "node-update");
        assertThat(Jsonl.str(row, "current")).isEqualTo(VERSION);
        assertThat(Jsonl.bool(row, "behind", true)).isFalse();
    }

    @Test
    void install_through_jk_install_is_the_same_install(@TempDir Path m2) {
        assertThat(run("install", "node:" + OLDER, "--m2-dir", m2.toString())).isZero();
        String listed = Capture.stdout(() -> run("node", "list", "-O", "json"));
        assertThat(listed).contains(OLDER);
    }

    /** A project whose lock pins {@link #VERSION}, with a {@code package.json} for {@code run}. */
    private static void lockedProject(Path project) throws IOException {
        Files.writeString(project.resolve("jk.toml"), "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = 24\n");
        Files.writeString(project.resolve("package.json"), "{\"scripts\":{\"build\":\"echo built\"}}");
        LockfileWriter.write(
                Lockfile.empty("1.0.0").withNode(new NodePin(VERSION, null, null, Map.of())),
                project.resolve("jk-lock.toml"));
    }

    /** The first line of {@code out} of {@code type}. */
    private static String row(String out, String type) {
        for (String line : out.split("\n")) {
            if (line.contains("\"type\":\"" + type + "\"")) return line;
        }
        throw new AssertionError("no " + type + " row in:\n" + out);
    }

    /**
     * A Node archive whose {@code node} answers {@code --version} with {@code version} and otherwise
     * writes its arguments, one per line, to {@code node-args.txt} in its working directory.
     */
    private static byte[] archive(String platformKey, String version) {
        String sh = "if [ \"$1\" = \"--version\" ]; then echo v" + version + "; exit 0; fi\n"
                + "printf '%s\\n' \"$@\" > node-args.txt";
        String cmd = "if \"%~1\"==\"--version\" (echo v" + version + "& exit /b 0)\n"
                + "(for %%a in (%*) do @echo %%~a) > node-args.txt";
        return FakeNodeDist.archive(
                version, platformKey, new FakePrograms.Script(sh, cmd), FakePrograms.Script.printing(FakeNodeDist.NPM));
    }
}
