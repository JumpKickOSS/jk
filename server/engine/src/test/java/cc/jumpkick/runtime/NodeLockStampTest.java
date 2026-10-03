// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileReader;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkBuild;
import cc.jumpkick.node.NodeCatalog;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.NodeResolver;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.testing.LoopbackHttp;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** {@code jk lock}'s {@code [node]}: resolved from a recorded catalog, kept, moved and refused. */
class NodeLockStampTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().withoutChecksums();

    @TempDir
    Path tmp;

    private static final NodePlatform LINUX = new NodePlatform("linux", "x64", false);

    private static final String INDEX = """
            [{"version":"v26.1.0","date":"2026-09-16","files":["linux-x64"],"npm":"11.8.0","lts":false,"security":false},
            {"version":"v24.21.0","date":"2026-09-09","files":["linux-x64"],"npm":"11.6.0","lts":"Krypton","security":false},
            {"version":"v24.20.0","date":"2026-08-01","files":["linux-x64"],"npm":"11.5.0","lts":"Krypton","security":false},
            {"version":"v22.20.0","date":"2026-07-01","files":["linux-x64"],"npm":"10.9.0","lts":"Jod","security":false}]
            """;

    private void serveCatalog() {
        http.serve("/index.json", INDEX);
        for (String v : new String[] {"26.1.0", "24.21.0", "24.20.0", "22.20.0"}) {
            StringBuilder sums = new StringBuilder();
            for (NodePlatform p : NodePlatform.LOCKED) {
                sums.append(sha(v, p.key()))
                        .append("  ")
                        .append(p.archiveName(v))
                        .append('\n');
            }
            http.serve("/v" + v + "/SHASUMS256.txt", sums.toString());
        }
        http.serve("/pnpm", """
                {"dist-tags":{"latest":"10.18.1"},"versions":{"10.18.1":{"dist":{"tarball":"%s","integrity":"sha512-AA=="}}}}
                """.formatted(http.base() + "/pnpm/-/pnpm-10.18.1.tgz"));
    }

    /** A stand-in digest that names its version and platform, so a test can tell them apart. */
    private static String sha(String version, String platform) {
        String seed = (version + platform).replaceAll("[^0-9]", "");
        return (seed + "0".repeat(64)).substring(0, 64);
    }

    private NodeResolver resolver() {
        return new NodeResolver(new NodeCatalog(new Http(), http.base(), tmp.resolve("store"), Duration.ofHours(1)));
    }

    private PackageManagerResolver managers() {
        return new PackageManagerResolver(new Http(), http.base());
    }

    private Path module(String name, String nodeLine, String packageJson) throws Exception {
        Path dir = Files.createDirectories(tmp.resolve(name));
        Files.writeString(
                dir.resolve("jk.toml"),
                "name = \"" + name + "\"\ngroup = \"g\"\nversion = \"1.0\"\n" + nodeLine + "\n");
        Files.writeString(dir.resolve("package.json"), packageJson);
        return dir;
    }

    private static NodePin pin(Lockfile lock) {
        return Objects.requireNonNull(lock.node(), "[node]");
    }

    private Lockfile stamp(Path dir, @Nullable Lockfile previous) throws Exception {
        return NodeLockStamp.apply(
                Lockfile.empty("1.0.0"),
                previous,
                NodeLockStamp.declared(Map.of(dir, JkBuildParser.parse(dir.resolve("jk.toml")))),
                resolver(),
                managers(),
                LINUX);
    }

    @Test
    void a_major_locks_the_newest_release_of_it_with_every_platform_digest() throws Exception {
        serveCatalog();
        Path web = module("web", "node = 24", "{\"scripts\":{\"build\":\"vite build\"}}");

        Lockfile lock = stamp(web, null);

        NodePin pin = pin(lock);
        assertThat(pin.version()).isEqualTo("24.21.0");
        assertThat(pin.npm()).isEqualTo("11.6.0");
        assertThat(pin.packageManager()).as("npm needs no manager pin").isNull();
        assertThat(pin.sha256()).hasSize(6).containsEntry("win-arm64", sha("24.21.0", "win-arm64"));
        // What jk lock writes reads back the same.
        assertThat(LockfileReader.parse(LockfileWriter.render(lock)).node()).isEqualTo(pin);
    }

    @Test
    void a_relock_keeps_a_suggestion_and_an_update_moves_it_within_its_major() throws Exception {
        serveCatalog();
        Path web = module("web", "node = 24", "{}");
        Lockfile older = Lockfile.empty("1.0.0")
                .withNode(new NodePin("24.20.0", "11.5.0", null, Map.of("linux-x64", sha("24.20.0", "linux-x64"))));

        assertThat(pin(stamp(web, older)).version()).as("jk lock keeps it").isEqualTo("24.20.0");
        assertThat(pin(stamp(web, null)).version()).as("jk update moves it").isEqualTo("24.21.0");
    }

    @Test
    void a_required_release_is_locked_exactly_whatever_the_previous_lock_held() throws Exception {
        serveCatalog();
        Path web = module("web", "node = \"=24.20.0\"", "{}");
        Lockfile newer = Lockfile.empty("1.0.0")
                .withNode(new NodePin("24.21.0", "11.6.0", null, Map.of("linux-x64", sha("24.21.0", "linux-x64"))));

        assertThat(pin(stamp(web, newer)).version()).isEqualTo("24.20.0");
        assertThat(pin(stamp(web, null)).version()).isEqualTo("24.20.0");
    }

    @Test
    void the_package_manager_is_its_named_version_else_the_newest_kept_on_relock() throws Exception {
        serveCatalog();
        Path named = module("named", "node = 24", "{\"packageManager\":\"pnpm@9.15.0\"}");
        assertThat(pin(stamp(named, null)).packageManager()).isEqualTo("pnpm@9.15.0");

        Path bare = module("bare", "node = 24", "{}");
        Files.writeString(bare.resolve("pnpm-lock.yaml"), "lockfileVersion: '9.0'\n");
        Lockfile fresh = stamp(bare, null);
        assertThat(pin(fresh).packageManager()).as("the registry's newest").isEqualTo("pnpm@10.18.1");

        Lockfile kept =
                stamp(bare, Lockfile.empty("1.0.0").withNode(new NodePin("24.21.0", null, "pnpm@10.0.0", Map.of())));
        assertThat(pin(kept).packageManager())
                .as("a relock keeps what it chose")
                .isEqualTo("pnpm@10.0.0");
    }

    @Test
    void no_node_declared_clears_the_table() throws Exception {
        Path app = Files.createDirectories(tmp.resolve("app"));
        Files.writeString(app.resolve("jk.toml"), "name = \"app\"\ngroup = \"g\"\nversion = \"1.0\"\n");
        Lockfile pinned = Lockfile.empty("1.0.0").withNode(new NodePin("24.21.0", null, null, Map.of()));

        Lockfile lock = NodeLockStamp.apply(
                pinned,
                pinned,
                NodeLockStamp.declared(Map.of(app, JkBuildParser.parse(app.resolve("jk.toml")))),
                resolver(),
                managers(),
                LINUX);

        assertThat(lock.node()).isNull();
    }

    @Test
    void two_node_versions_or_two_managers_in_one_workspace_are_refused() throws Exception {
        Path a = module("a", "node = 24", "{\"packageManager\":\"pnpm@10.18.1\"}");
        Path b = module("b", "node = 22", "{}");
        Map<Path, JkBuild> versions = new LinkedHashMap<>();
        versions.put(a, JkBuildParser.parse(a.resolve("jk.toml")));
        versions.put(b, JkBuildParser.parse(b.resolve("jk.toml")));
        assertThatThrownBy(() -> NodeLockStamp.declared(versions))
                .hasMessageContaining("one Node.js version per workspace");

        Path c = module("c", "node = 24", "{\"packageManager\":\"bun@1.3.0\"}");
        Map<Path, JkBuild> managers = new LinkedHashMap<>();
        managers.put(a, JkBuildParser.parse(a.resolve("jk.toml")));
        managers.put(c, JkBuildParser.parse(c.resolve("jk.toml")));
        assertThatThrownBy(() -> NodeLockStamp.declared(managers))
                .hasMessageContaining("one package manager per workspace");
    }
}
