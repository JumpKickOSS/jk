// SPDX-License-Identifier: Apache-2.0
package cc.jumpkick.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import cc.jumpkick.compat.NodeProvisioning;
import cc.jumpkick.compat.ToolRegistry;
import cc.jumpkick.config.Session;
import cc.jumpkick.config.SessionContext;
import cc.jumpkick.host.PathUtil;
import cc.jumpkick.http.Http;
import cc.jumpkick.lock.Lockfile;
import cc.jumpkick.lock.LockfileWriter;
import cc.jumpkick.lock.NodePin;
import cc.jumpkick.model.JkVersion;
import cc.jumpkick.node.DiscoveredNode;
import cc.jumpkick.node.NodeDiscovery;
import cc.jumpkick.node.NodeHome;
import cc.jumpkick.node.NodePlatform;
import cc.jumpkick.node.NodeSources;
import cc.jumpkick.node.PackageManager;
import cc.jumpkick.node.PackageManagerResolver;
import cc.jumpkick.run.BuildPlan;
import cc.jumpkick.run.BuildPlanResult;
import cc.jumpkick.testing.TestCaches;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Every package manager installs a frozen lockfile for real against private registries: the
 * manager itself is provisioned through the registry stub, the packages want a token, and a scoped
 * package comes from its own registry with its own token. Nothing reaches the public registry
 * except the stub's one-time mirror of the managers' own tarballs.
 */
@Tag("integration")
@Isolated
@DisabledOnOs(OS.WINDOWS)
class NodeManagerInstallsTest {

    private static final String TOKEN = "main-registry-token";
    private static final String SCOPE_TOKEN = "acme-registry-token";
    private static final Map<String, String> MANAGERS =
            Map.of("pnpm", "pnpm@12.9.1", "yarn", "yarn@4.18.1", "bun", "bun@1.4.2");

    @TempDir
    Path tmp;

    private final List<String> overlays = new ArrayList<>();
    private @Nullable NpmRegistryStub main;
    private @Nullable NpmRegistryStub scope;
    private @Nullable Supplier<NodeProvisioning> production;

    @AfterEach
    void tearDown() {
        if (main != null) main.close();
        if (scope != null) scope.close();
        if (production != null) PlannerNodeSetup.provisioning = production;
        for (String name : overlays) System.clearProperty("jk.env." + name);
    }

    @ParameterizedTest
    @ValueSource(strings = {"npm", "pnpm", "yarn", "bun"})
    void a_frozen_install_reaches_each_registry_with_its_own_token(String manager) throws Exception {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        DiscoveredNode host = found.get(0);
        main = NpmRegistryStub.start(TOKEN, TestCaches.dir("npm-upstream"));
        scope = NpmRegistryStub.start(SCOPE_TOKEN, null);
        main.publish("tiny", "1.0.0", Map.of("index.js", "module.exports = 'tiny';\n"), Map.of());
        scope.publish("@acme/thing", "1.0.0", Map.of("index.js", "module.exports = 'thing';\n"), Map.of());
        // Berry takes the registry and its token from jk; its scopes are a separate concern.
        boolean scoped = !manager.equals("yarn");

        Path home = Files.createDirectories(tmp.resolve("jk-home"));
        Files.writeString(
                home.resolve("config.toml"), scoped ? "[node.scopes]\n\"@acme\" = \"" + scope.base() + "\"\n" : "");
        overlay("JK_HOME", home.toString());
        overlay(NodeSources.REGISTRY_ENV, main.base());
        overlay("JK_REPO_127_0_0_1_" + main.port() + "_TOKEN", TOKEN);
        overlay("JK_REPO_127_0_0_1_" + scope.port() + "_TOKEN", SCOPE_TOKEN);
        production = PlannerNodeSetup.provisioning;
        URI registry = URI.create(main.base());
        PlannerNodeSetup.provisioning = () -> new NodeProvisioning(
                new ToolRegistry(tmp.resolve("tools")),
                new Http(),
                new NodeDiscovery(),
                registry,
                new PackageManagerResolver(new Http(), registry),
                NodePlatform.host());

        @Nullable String spec = MANAGERS.get(manager);
        Path web = project(host, manager, spec, scoped);
        NodePin pin = new NodePin(host.version(), null, spec, Map.of());
        LockfileWriter.write(Lockfile.empty(JkVersion.VERSION).withNode(pin), web.resolve("jk-lock.toml"));

        lock(web, PlannerNodeSetup.ensure(PlannerNodeSetup.provisioning.get(), pin, b -> {}), manager);
        main.clearRequests();
        scope.clearRequests();

        Session session = Session.defaults().withCacheDir(tmp.resolve("cache")).withVariant("", Map.of());
        BuildPlanResult r = run(plan(web, session), session);

        assertThat(r.errors())
                .as("%s; main saw %s; scope saw %s", r.errors(), main.requests(), scope.requests())
                .isEmpty();
        assertThat(web.resolve("node_modules/tiny/index.js")).exists();
        assertThat(main.requests())
                .as("the main registry is asked with its token")
                .anyMatch(q -> q.path().startsWith("/tiny") && ("Bearer " + TOKEN).equals(q.authorization()))
                .noneMatch(q -> SCOPE_TOKEN.equals(bearer(q)));
        if (scoped) {
            assertThat(web.resolve("node_modules/@acme/thing/index.js")).exists();
            assertThat(scope.requests())
                    .as("the scope's registry is asked with its own token")
                    .anyMatch(q ->
                            q.path().startsWith("/@acme/thing") && ("Bearer " + SCOPE_TOKEN).equals(q.authorization()))
                    .noneMatch(q -> TOKEN.equals(bearer(q)));
            assertThat(main.requests()).noneMatch(q -> q.path().startsWith("/@acme"));
        }
        try (Stream<Path> files = Files.walk(web.resolve("target"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(file, StandardCharsets.ISO_8859_1))
                        .as("%s never holds a token", file)
                        .doesNotContain(TOKEN)
                        .doesNotContain(SCOPE_TOKEN);
            }
        }
        try (Stream<Path> files = Files.walk(web.resolve("target"))) {
            assertThat(files.map(p -> p.getFileName().toString()))
                    .as("the run's registry config is gone")
                    .noneMatch(n ->
                            n.startsWith(NodeNetwork.USERCONFIG_PREFIX) || n.startsWith(NodeNetwork.BUN_CONFIG_PREFIX));
        }
    }

    private Path project(DiscoveredNode host, String manager, @Nullable String spec, boolean scoped)
            throws IOException {
        Path web = Files.createDirectories(tmp.resolve("web"));
        Files.writeString(
                web.resolve("jk.toml"),
                "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = " + major(host.version()) + "\n");
        String deps = scoped ? "\"tiny\":\"1.0.0\",\"@acme/thing\":\"1.0.0\"" : "\"tiny\":\"1.0.0\"";
        String require = scoped ? "require('tiny'); require('@acme/thing');" : "require('tiny');";
        Files.writeString(
                web.resolve("package.json"),
                "{\"name\":\"web\",\"version\":\"1.0.0\""
                        + (manager.equals("npm") ? "" : ",\"packageManager\":\"" + spec + "\"")
                        + ",\"dependencies\":{" + deps + "},\"scripts\":{\"build\":\"node build.js\"}}\n");
        Files.writeString(
                web.resolve("build.js"),
                require + "\nrequire('fs').mkdirSync(require('path').join(__dirname, 'dist'), {recursive: true});\n");
        if (manager.equals("yarn")) {
            Files.writeString(web.resolve(".yarnrc.yml"), "nodeLinker: node-modules\n");
        }
        return web;
    }

    /**
     * Write the manager's lockfile by running it unfrozen against the stubs, with the network
     * settings jk hands it, then remove everything the install left so jk's frozen install has to
     * fetch through the registries.
     */
    private void lock(Path web, NodeHome node, String manager) throws Exception {
        PackageManager pm = PackageManager.valueOf(manager.toUpperCase(Locale.ROOT));
        Map<String, String> env = new HashMap<>(System.getenv());
        env.remove("CI");
        Path work = tmp.resolve("lock-work");
        Map<String, String> network = NodeNetwork.env(work, pm);
        env.putAll(network);
        env.put("PATH", node.path(System.getenv("PATH")));
        env.put("npm_config_cache", tmp.resolve("lock-cache/npm").toString());
        env.put("pnpm_config_store_dir", tmp.resolve("lock-cache/pnpm").toString());
        env.put("BUN_INSTALL_CACHE_DIR", tmp.resolve("lock-cache/bun").toString());
        env.put("YARN_GLOBAL_FOLDER", tmp.resolve("lock-cache/yarn").toString());
        env.put("YARN_ENABLE_IMMUTABLE_INSTALLS", "false");
        env.put("YARN_ENABLE_TELEMETRY", "0");
        List<String> argv = new ArrayList<>(node.managerCommand());
        argv.addAll(
                switch (pm) {
                    case NPM -> List.of("install", "--package-lock-only");
                    case PNPM -> List.of("install", "--lockfile-only");
                    case YARN -> List.of("install", "--mode=update-lockfile");
                    case BUN -> List.of("install", "--lockfile-only");
                });
        ProcessBuilder pb = new ProcessBuilder(argv).directory(web.toFile()).redirectErrorStream(true);
        pb.environment().clear();
        pb.environment().putAll(env);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(p.waitFor(5, TimeUnit.MINUTES)).isTrue();
        NodeNetwork.discard(network);
        assertThat(p.exitValue()).as("%s lockfile run:\n%s", manager, out).isZero();
        for (String left : List.of("node_modules", ".yarn/cache", ".pnp.cjs")) {
            PathUtil.deleteRecursively(web.resolve(left));
        }
    }

    private static String bearer(NpmRegistryStub.Request q) {
        String auth = q.authorization();
        return auth != null && auth.startsWith("Bearer ") ? auth.substring("Bearer ".length()) : "";
    }

    private void overlay(String name, String value) {
        System.setProperty("jk.env." + name, value);
        overlays.add(name);
    }

    private BuildPlan plan(Path web, Session session) {
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

    private static BuildPlanResult run(BuildPlan plan, Session session) throws Exception {
        BuildPlanResult[] out = new BuildPlanResult[1];
        SessionContext.runWhere(session, () -> out[0] = plan.run());
        return out[0];
    }

    private static int major(String version) {
        return Integer.parseInt(version.substring(0, version.indexOf('.')));
    }
}
