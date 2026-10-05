// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.compat;

import static org.assertj.core.api.Assertions.assertThat;

import cc.jumpkick.http.Http;
import cc.jumpkick.node.NodeCatalog;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.NodeResolution;
import cc.jumpkick.node.NodeResolver;
import cc.jumpkick.node.NodeSources;
import cc.jumpkick.node.NodeSpec;
import cc.jumpkick.node.PackageManager;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.node.PackageManagerSpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The newest Node 24 and pnpm from nodejs.org and the npm registry, installed and run. */
@Tag("network")
class NodeRealInstallTest {

    @Test
    void the_newest_24_installs_verified_and_runs(@TempDir Path tmp) throws Exception {
        NodeCatalog catalog =
                new NodeCatalog(new Http(), URI.create(NodeSources.NODEJS_DIST), tmp, Duration.ofHours(1));
        NodePlatform host = NodePlatform.host();
        NodeResolution resolution = new NodeResolver(catalog).resolve(NodeSpec.parse("24"), host);
        NodeProvisioning p = new NodeProvisioning(
                new ToolRegistry(tmp.resolve("tools")),
                new Http(),
                new NodeDiscovery(name -> null, tmp, List.of()),
                catalog.distBase(),
                new PackageManagerResolver(new Http(), URI.create(NodeSources.NPM_REGISTRY)),
                host);

        NodeHome node = p.ensure(resolution, new NodeProvisioning.Policy(true), ToolProgress.NONE);
        assertThat(run(List.of(node.node().toString(), "--version"))).isEqualTo("v" + resolution.version());

        NodeHome pnpm = p.withManager(node, new PackageManagerSpec(PackageManager.PNPM, "latest"), ToolProgress.NONE);
        assertThat(run(concat(pnpm.managerCommand(), "--version"))).matches("\\d+\\.\\d+\\.\\d+");
    }

    private static List<String> concat(List<String> argv, String arg) {
        var out = new ArrayList<>(argv);
        out.add(arg);
        return out;
    }

    private static String run(List<String> argv) throws Exception {
        Process proc = new ProcessBuilder(argv).redirectErrorStream(true).start();
        assertThat(proc.waitFor(60, TimeUnit.SECONDS)).isTrue();
        return new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
    }
}
