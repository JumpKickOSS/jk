// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.compat.NodeProvisioning;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.node.DiscoveredNode;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.NodeSources;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;

/**
 * An {@code npx} step with {@code allow-unlocked = true} fetches a package the lockfile does not
 * hold, from the configured registry with its token: the version it resolves is part of the step's
 * key, so the step stays cached until a newer version is published and then runs again.
 */
@Tag("integration")
@Isolated
class NodeUnlockedNpxTest {

    private static final String TOKEN = "npx-registry-token";

    @TempDir
    Path tmp;

    private final List<String> overlays = new ArrayList<>();
    private @Nullable NpmRegistryStub registry;
    private @Nullable Supplier<NodeProvisioning> production;

    @AfterEach
    void tearDown() {
        if (registry != null) registry.close();
        if (production != null) PlannerNodeSetup.provisioning = production;
        for (String name : overlays) System.clearProperty("jk.env." + name);
    }

    @Test
    void an_unlocked_npx_runs_again_only_when_a_newer_version_is_published() throws Exception {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        DiscoveredNode host = found.get(0);
        NpmRegistryStub stub = NpmRegistryStub.start(TOKEN, null);
        registry = stub;
        stub.publish("greet", "1.0.0", greet(), Map.of("greet", "index.js"));
        overlay("JK_HOME", Files.createDirectories(tmp.resolve("jk-home")).toString());
        overlay(NodeSources.REGISTRY_ENV, stub.base());
        overlay("JK_REPO_127_0_0_1_" + stub.port() + "_TOKEN", TOKEN);
        production = PlannerNodeSetup.provisioning;
        URI base = URI.create(stub.base());
        PlannerNodeSetup.provisioning = () -> new NodeProvisioning(
                new ToolRegistry(tmp.resolve("tools")),
                new Http(),
                new NodeDiscovery(),
                base,
                new PackageManagerResolver(new Http(), base),
                NodePlatform.host());

        Path web = Files.createDirectories(tmp.resolve("web"));
        Files.writeString(web.resolve("jk.toml"), """
                name = "web"
                group = "g"
                version = "1.0"

                [node]
                version = %d

                [[node.steps]]
                name = "hello"
                npx = "greet"
                allow-unlocked = true
                inputs = ["package.json"]
                outputs = ["out.txt"]
                """.formatted(major(host.version())));
        Files.writeString(web.resolve("package.json"), """
                {"name":"web","version":"1.0.0","scripts":{"build":"node build.js"}}
                """);
        Files.writeString(web.resolve("package-lock.json"), """
                {"name":"web","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"web","version":"1.0.0"}}}
                """);
        Files.writeString(
                web.resolve("build.js"),
                "require('fs').mkdirSync(require('path').join(__dirname, 'dist'), {recursive: true});\n");
        LockfileWriter.write(
                Lockfile.empty(JkVersion.VERSION).withNode(new NodePin(host.version(), null, null, Map.of())),
                web.resolve("jk-lock.toml"));

        build(web);
        assertThat(web.resolve("out.txt")).hasContent("1.0.0");
        assertThat(runs(web)).isEqualTo(1);
        assertThat(stub.requests())
                .as("the package came from the registry with its token")
                .anyMatch(q -> q.path().startsWith("/greet/-/") && ("Bearer " + TOKEN).equals(q.authorization()));

        build(web);
        assertThat(runs(web)).as("the same version is a cached step").isEqualTo(1);

        stub.publish("greet", "1.0.1", greet(), Map.of("greet", "index.js"));
        build(web);
        assertThat(web.resolve("out.txt")).hasContent("1.0.1");
        assertThat(runs(web)).as("a newer version runs the step again").isEqualTo(2);
    }

    /** A bin that appends to {@code runs.log} and writes its own version to {@code out.txt}. */
    private static Map<String, String> greet() {
        return Map.of("index.js", """
                #!/usr/bin/env node
                const fs = require('fs');
                fs.appendFileSync('runs.log', 'x\\n');
                fs.writeFileSync('out.txt', require('./package.json').version);
                """);
    }

    private static long runs(Path web) throws Exception {
        Path log = web.resolve("runs.log");
        return Files.isRegularFile(log) ? Files.readAllLines(log).size() : 0;
    }

    private void build(Path web) throws Exception {
        Session session = Session.defaults().withCacheDir(tmp.resolve("cache")).withVariant("", Map.of());
        BuildPlan plan = BuildPlanner.fullPlan(new BuildPlanner.Inputs(
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
        BuildPlanResult[] out = new BuildPlanResult[1];
        SessionContext.runWhere(session, () -> out[0] = plan.run());
        assertThat(out[0].errors()).isEmpty();
    }

    private void overlay(String name, String value) {
        System.setProperty("jk.env." + name, value);
        overlays.add(name);
    }

    private static int major(String version) {
        return Integer.parseInt(version.substring(0, version.indexOf('.')));
    }
}
