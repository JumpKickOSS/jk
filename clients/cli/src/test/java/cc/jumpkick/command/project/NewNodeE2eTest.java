// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.command.project;

import static cc.jumpkick.cli.testing.JkRun.run;
import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.cli.engine.IsolatedStore;
import cc.jumpkick.cli.testing.Capture;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.model.command.Exit;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code jk new --lang node}, {@code --frontend} and {@code jk init} against a stub Node.js
 * distribution whose {@code npx} stands in for every generator: it records its argv and writes the
 * {@code package.json} and lockfile a real one would. Its major is one no host has installed, so
 * discovery never picks a real {@code npx}.
 */
@Tag("integration")
@IsolatedStore
@DisabledOnOs(OS.WINDOWS)
class NewNodeE2eTest {

    private static final String MIRROR = "jk.env.JK_NODE_DIST_MIRROR";
    private static final String VERSION = "99.9.0";

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().withoutChecksums();

    private @Nullable String priorMirror;

    @BeforeEach
    void dist() throws IOException {
        priorMirror = System.getProperty(MIRROR);
        NodePlatform host = NodePlatform.host();
        byte[] archive = archive("node-v" + VERSION + "-" + host.key());
        http.served().put("/v" + VERSION + "/" + host.archiveName(VERSION), archive);
        StringBuilder sums = new StringBuilder();
        for (NodePlatform p : NodePlatform.LOCKED) {
            String sha = p.key().equals(host.key()) ? Hashing.sha256Hex(archive) : "0".repeat(64);
            sums.append(sha).append("  ").append(p.archiveName(VERSION)).append('\n');
        }
        if (NodePlatform.LOCKED.stream().noneMatch(p -> p.key().equals(host.key()))) {
            sums.append(Hashing.sha256Hex(archive))
                    .append("  ")
                    .append(host.archiveName(VERSION))
                    .append('\n');
        }
        http.serve("/v" + VERSION + "/SHASUMS256.txt", sums.toString());
        http.serve(
                "/index.json",
                "[{\"version\":\"v" + VERSION + "\",\"npm\":\"11.0.0\",\"lts\":\"Krypton\",\"security\":false,"
                        + "\"files\":[\"linux-x64\",\"linux-arm64\",\"osx-arm64-tar\",\"osx-x64-tar\"]}]");
        System.setProperty(MIRROR, http.baseUrl());
    }

    @AfterEach
    void restore() {
        if (priorMirror == null) System.clearProperty(MIRROR);
        else System.setProperty(MIRROR, priorMirror);
    }

    @Test
    void new_lang_node_runs_the_generator_under_the_newest_lts_and_pins_it(@TempDir Path dir) throws IOException {
        int exit = run(
                "new",
                "--lang",
                "node",
                "-t",
                "vite-react",
                "--group",
                "com.acme",
                dir.resolve("web").toString());

        assertThat(exit).isZero();
        Path web = dir.resolve("web");
        assertThat(Files.readString(dir.resolve("npx-args.txt")))
                .isEqualTo("--yes\ncreate-vite@latest\nweb\n--template\nreact-ts\n--no-interactive\n");
        assertThat(Files.readString(web.resolve("jk.toml")))
                .isEqualTo("name = \"web\"\ngroup = \"com.acme\"\nversion = \"0.1.0\"\n\nnode = 99\n");
        assertThat(Files.readString(web.resolve(".gitignore")))
                .contains("node_modules/")
                .contains("target/");
        assertThat(web.resolve("package-lock.json")).exists();
    }

    @Test
    void a_new_node_module_in_a_workspace_joins_it_and_inherits_its_identity(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("jk.toml"), """
                group = "com.acme"
                name = "shop"
                version = "1.0.0"

                [workspace]
                modules = []
                """);

        int exit = run(
                "new", "--lang", "node", "-t", "next", dir.resolve("storefront").toString());

        assertThat(exit).isZero();
        assertThat(Files.readString(dir.resolve("storefront/jk.toml")))
                .isEqualTo("name = \"storefront\"\n\nnode = 99\n");
        assertThat(Files.readString(dir.resolve("jk.toml"))).contains("\"storefront\"");
    }

    @Test
    void unknown_framework_and_offline_are_refused(@TempDir Path dir) {
        String err = Capture.stderr(() -> assertThat(run(
                        "new",
                        "--lang",
                        "node",
                        "-t",
                        "gatsby",
                        dir.resolve("x").toString()))
                .isEqualTo(Exit.USAGE));
        assertThat(err).contains("unknown framework `gatsby`").contains("vite-react");
        String offline = Capture.stderr(() -> assertThat(run(
                        "new",
                        "--offline",
                        "--lang",
                        "node",
                        "-t",
                        "angular",
                        dir.resolve("y").toString()))
                .isEqualTo(Exit.CONFIG));
        assertThat(offline).contains("run without --offline");
        assertThat(dir.resolve("x")).doesNotExist();
    }

    @Test
    void frontend_swaps_a_template_web_module_and_keeps_its_manifest(@TempDir Path app) throws IOException {
        Path web = app.resolve("web");
        Files.createDirectories(web.resolve("src"));
        Files.writeString(web.resolve("jk.toml"), "name = \"web\"\nnode = 24\n");
        Files.writeString(web.resolve("package.json"), "{\"name\":\"vite\"}");
        Files.writeString(web.resolve("src/main.tsx"), "vite");

        assertThat(NewNode.replaceFrontend(web, "angular", List.of(), false)).isZero();

        assertThat(Files.readString(web.resolve("jk.toml"))).isEqualTo("name = \"web\"\nnode = 24\n");
        assertThat(web.resolve("src/main.tsx")).doesNotExist();
        assertThat(Files.readString(app.resolve("npx-args.txt"))).contains("@angular/cli@latest\nnew\nweb\n");
    }

    @Test
    void init_makes_a_package_json_directory_a_node_module_from_its_nvmrc(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("package.json"), "{\"name\":\"ui\"}");
        Files.writeString(dir.resolve(".nvmrc"), "22\n");

        String out = Capture.stdout(() -> assertThat(run("-C", dir.toString(), "init", "--group", "com.acme"))
                .isZero());

        assertThat(out).contains("node = 22").contains(".nvmrc");
        assertThat(Files.readString(dir.resolve("jk.toml")))
                .contains("node = 22")
                .contains("group = \"com.acme\"");
    }

    @Test
    void init_adds_subdirectories_holding_a_package_json_as_members(@TempDir Path root) throws IOException {
        Files.writeString(root.resolve("jk.toml"), "group = \"com.acme\"\nname = \"shop\"\nversion = \"1.0.0\"\n");
        Files.createDirectories(root.resolve("apps/admin/node_modules/dep"));
        Files.writeString(root.resolve("apps/admin/package.json"), "{}");
        Files.writeString(root.resolve("apps/admin/node_modules/dep/package.json"), "{}");
        Files.createDirectories(root.resolve("storefront"));
        Files.writeString(root.resolve("storefront/package.json"), "{\"engines\":{\"node\":\">=20\"}}");

        List<Path> members = InitNode.members(root);
        assertThat(members).containsExactly(root.resolve("apps/admin"), root.resolve("storefront"));
        InitNode.addMembers(root, members);

        assertThat(Files.readString(root.resolve("jk.toml")))
                .contains("[workspace]")
                .contains("\"apps/admin\"")
                .contains("\"storefront\"");
        assertThat(Files.readString(root.resolve("storefront/jk.toml"))).contains("node = 20");
    }

    /** A Node.js tarball whose {@code npx} records its argv and writes what a generator would. */
    private static byte[] archive(String top) throws IOException {
        String node = "#!/bin/sh\nif [ \"$1\" = \"--version\" ]; then echo v" + VERSION + "; exit 0; fi\n";
        String npx = """
                #!/bin/sh
                printf '%s\\n' "$@" > npx-args.txt
                for a in "$@"; do
                  case "$a" in -*|*@latest|new|create) ;; *) dir="$a"; break ;; esac
                done
                mkdir -p "$dir"
                printf '{"name":"%s"}' "$dir" > "$dir/package.json"
                printf '{}' > "$dir/package-lock.json"
                """;
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        entry(tar, top + "/", new byte[0], true);
        entry(tar, top + "/bin/", new byte[0], true);
        entry(tar, top + "/bin/node", node.getBytes(StandardCharsets.UTF_8), false);
        entry(tar, top + "/bin/npx", npx.getBytes(StandardCharsets.UTF_8), false);
        tar.write(new byte[1024]);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    private static void entry(ByteArrayOutputStream tar, String name, byte[] data, boolean dir) throws IOException {
        byte[] h = new byte[512];
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(n, 0, h, 0, n.length);
        octal(h, 100, 8, 0755);
        octal(h, 108, 8, 0);
        octal(h, 116, 8, 0);
        octal(h, 124, 12, data.length);
        octal(h, 136, 12, 0);
        h[156] = (byte) (dir ? '5' : '0');
        System.arraycopy("ustar ".getBytes(StandardCharsets.US_ASCII), 0, h, 257, 6);
        for (int i = 148; i < 156; i++) h[i] = ' ';
        int sum = 0;
        for (byte b : h) sum += b & 0xff;
        octal(h, 148, 7, sum);
        tar.write(h);
        tar.write(data);
        int pad = (512 - data.length % 512) % 512;
        tar.write(new byte[pad]);
    }

    private static void octal(byte[] h, int at, int len, long value) {
        String s = Long.toOctalString(value);
        String padded = "0".repeat(Math.max(0, len - 1 - s.length())) + s;
        byte[] b = padded.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(b, 0, h, at, Math.min(b.length, len - 1));
        h[at + len - 1] = 0;
    }
}
