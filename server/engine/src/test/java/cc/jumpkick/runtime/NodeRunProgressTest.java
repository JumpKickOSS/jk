// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.compat.NodeProvisioning;
import cc.jumpkick.compat.ToolProgress;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.config.JkBuildParser;
import cc.jumpkick.host.Hashing;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.testing.FakeNodeDist;
import cc.jumpkick.testing.LoopbackHttp;
import cc.jumpkick.wire.protocol.ExecPlan;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

/** A run plan that has to install its locked Node.js first reports the download as a JDK download does. */
class NodeRunProgressTest {

    @RegisterExtension
    final LoopbackHttp http = new LoopbackHttp().withoutChecksums();

    private static final String VERSION = "24.98.0";

    @Test
    void planning_a_run_reports_the_node_install_on_the_bound_progress(@TempDir Path tmp) throws Exception {
        NodePlatform host = NodePlatform.host();
        byte[] archive = FakeNodeDist.archive(VERSION, host.key());
        http.served().put("/v" + VERSION + "/" + host.archiveName(VERSION), archive);

        Path web = Files.createDirectories(tmp.resolve("web"));
        Files.writeString(web.resolve("jk.toml"), "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = 24\n");
        Files.writeString(web.resolve("package.json"), "{\"scripts\":{\"start\":\"node server.js\"}}");
        LockfileWriter.write(
                Lockfile.empty("1.0.0")
                        .withNode(new NodePin(VERSION, null, null, Map.of(host.key(), Hashing.sha256Hex(archive)))),
                web.resolve("jk-lock.toml"));

        List<String> events = new CopyOnWriteArrayList<>();
        ToolProgress recorder = new ToolProgress() {
            @Override
            public void downloading(String name, long readBytes, long totalBytes) {
                events.add("download " + name + " " + readBytes + "/" + totalBytes);
            }

            @Override
            public void installing(String name) {
                events.add("install " + name);
            }
        };

        Supplier<NodeProvisioning> production = PlannerNodeSetup.provisioning;
        PlannerNodeSetup.provisioning = () -> new NodeProvisioning(
                new ToolRegistry(tmp.resolve("tools")),
                new Http(),
                new NodeDiscovery(name -> null, tmp.resolve("home"), List.of()),
                http.base(),
                new PackageManagerResolver(new Http(), http.base()),
                host);
        try {
            ExecPlan plan = ScopedValue.where(NodeRun.PROGRESS, recorder)
                    .call(() -> NodeRun.plan(web, JkBuildParser.parse(web.resolve("jk.toml")), "/usr/bin:/bin"));
            assertThat(plan.error()).isNullOrEmpty();
        } finally {
            PlannerNodeSetup.provisioning = production;
        }

        String total = "/" + archive.length;
        assertThat(events)
                .as("the download carries its total from the first event, then the install")
                .anyMatch(e -> e.startsWith("download ") && e.endsWith(total))
                .anyMatch(e -> e.startsWith("install "));
        assertThat(events.getFirst()).startsWith("download ").endsWith(total);
    }
}
