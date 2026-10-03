// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.NodeProvisioning;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.run.TaskNames;
import cc.jumpkick.run.TaskStatus;
import cc.jumpkick.testing.LoopbackHttp;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/**
 * The first build of a node module on a machine with no Node.js installs the locked release, and
 * {@code ensure-node} publishes it; the second build downloads nothing.
 */
@DisabledOnOs(OS.WINDOWS)
class EnsureNodeFirstBuildTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().withoutChecksums();

    private static final String VERSION = "24.99.0";

    @Test
    void the_first_build_installs_the_locked_node_and_publishes_its_home(@TempDir Path tmp) throws Exception {
        NodePlatform host = NodePlatform.host();
        String top = "node-v" + VERSION + "-" + host.key();
        byte[] archive = tarGz(top, "#!/bin/sh\necho v" + VERSION + "\n");
        String path = "/v" + VERSION + "/" + host.archiveName(VERSION);
        http.served().put(path, archive);

        Path web = Files.createDirectories(tmp.resolve("web"));
        Files.writeString(web.resolve("jk.toml"), "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = 24\n");
        Files.writeString(web.resolve("package.json"), "{}");
        LockfileWriter.write(
                Lockfile.empty("1.0.0")
                        .withNode(new NodePin(VERSION, null, null, Map.of(host.key(), Hashing.sha256Hex(archive)))),
                web.resolve("jk-lock.toml"));

        Path tools = tmp.resolve("tools");
        Supplier<NodeProvisioning> production = PlannerNodeSetup.provisioning;
        PlannerNodeSetup.provisioning = () -> new NodeProvisioning(
                new ToolRegistry(tools),
                new Http(),
                new NodeDiscovery(name -> null, tmp.resolve("home"), List.of()),
                http.base(),
                new PackageManagerResolver(new Http(), http.base()),
                host);
        try {
            Session session = Session.defaults().withCacheDir(tmp.resolve("cache"));
            SessionContext.runWhere(session, () -> {
                BuildPlan first = plan(web, tmp, session);
                BuildPlanResult result = first.run();
                assertThat(result.errors()).isEmpty();
                assertThat(ran(result)).as("ensure-node runs once").isEqualTo(1);
                NodeHome home = first.get(BuildPlanner.NODE_HOME).orElseThrow();
                assertThat(home.version()).isEqualTo(VERSION);
                assertThat(home.home()).isEqualTo(tools.resolve("node").resolve(VERSION));
                assertThat(home.node()).isExecutable();

                BuildPlanResult again = plan(web, tmp, session).run();
                assertThat(again.errors()).isEmpty();
                assertThat(http.requestsFor(path)).as("downloaded once").isEqualTo(1);
            });
        } finally {
            PlannerNodeSetup.provisioning = production;
        }
    }

    private static BuildPlan plan(Path web, Path tmp, Session session) {
        return BuildPlanner.fullPlan(new BuildPlanner.Inputs(
                web,
                tmp.resolve("cache"),
                web.resolve("jk.toml"),
                web.resolve("jk-lock.toml"),
                web,
                1,
                1,
                null,
                null,
                true,
                false,
                false,
                false,
                Set.of(),
                session));
    }

    private static long ran(BuildPlanResult result) {
        return result.steps().stream()
                .filter(s -> s.name().equals(TaskNames.ENSURE_NODE) && s.status() == TaskStatus.SUCCESS)
                .count();
    }

    /** A Node archive holding only {@code <top>/bin/node}. */
    private static byte[] tarGz(String top, String node) throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        entry(tar, top + "/", new byte[0], true);
        entry(tar, top + "/bin/", new byte[0], true);
        entry(tar, top + "/bin/node", node.getBytes(StandardCharsets.UTF_8), false);
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
