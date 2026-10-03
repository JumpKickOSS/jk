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
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code node-install} against a private registry that wants a token: the token comes from the
 * credential chain, reaches npm through a user config jk writes for the run, and is gone after it.
 */
@Tag("integration")
@DisabledOnOs(OS.WINDOWS)
class NodeRegistryAuthTest {

    private static final String TOKEN = "s3cret-npm-token";

    @TempDir
    Path tmp;

    private HttpServer registry;
    private final List<String> authorizations = new ArrayList<>();
    private Supplier<NodeProvisioning> production;
    private final List<String> overlays = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        List<DiscoveredNode> found = new NodeDiscovery().discover();
        assumeTrue(!found.isEmpty(), "a Node.js on this host");
        byte[] tarball = tarball();
        registry = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        registry.createContext("/", exchange -> {
            String auth = exchange.getRequestHeaders().getFirst("Authorization");
            synchronized (authorizations) {
                authorizations.add(String.valueOf(auth));
            }
            boolean ok = ("Bearer " + TOKEN).equals(auth)
                    && exchange.getRequestURI().getPath().equals("/tiny/-/tiny-1.0.0.tgz");
            byte[] body = ok ? tarball : new byte[0];
            exchange.sendResponseHeaders(ok ? 200 : 401, ok ? body.length : -1);
            if (ok) exchange.getResponseBody().write(body);
            exchange.close();
        });
        registry.start();
        String base = "http://127.0.0.1:" + registry.getAddress().getPort() + "/";

        DiscoveredNode host = found.get(0);
        Path web = Files.createDirectories(tmp.resolve("web"));
        Files.writeString(
                web.resolve("jk.toml"),
                "name = \"web\"\ngroup = \"g\"\nversion = \"1.0\"\nnode = " + major(host.version()) + "\n");
        Files.writeString(web.resolve("package.json"), """
                {"name":"web","version":"1.0.0","dependencies":{"tiny":"1.0.0"},
                 "scripts":{"build":"node build.js"}}
                """);
        Files.writeString(web.resolve("package-lock.json"), """
                {"name":"web","version":"1.0.0","lockfileVersion":3,"requires":true,
                 "packages":{"":{"name":"web","version":"1.0.0","dependencies":{"tiny":"1.0.0"}},
                  "node_modules/tiny":{"version":"1.0.0","resolved":"%stiny/-/tiny-1.0.0.tgz","integrity":"%s"}}}
                """.formatted(base, integrity(tarball)));
        Files.writeString(web.resolve("build.js"), """
                require('tiny');
                require('fs').mkdirSync(require('path').join(__dirname, 'dist'), {recursive: true});
                """);
        LockfileWriter.write(
                Lockfile.empty(JkVersion.VERSION).withNode(new NodePin(host.version(), null, null, Map.of())),
                web.resolve("jk-lock.toml"));

        overlay(NodeSources.REGISTRY_ENV, base);
        overlay("JK_REPO_127_0_0_1_" + registry.getAddress().getPort() + "_TOKEN", TOKEN);
        production = PlannerNodeSetup.provisioning;
        URI nowhere = URI.create(base);
        PlannerNodeSetup.provisioning = () -> new NodeProvisioning(
                new ToolRegistry(tmp.resolve("tools")),
                new Http(),
                new NodeDiscovery(),
                nowhere,
                new PackageManagerResolver(new Http(), nowhere),
                NodePlatform.host());
    }

    @AfterEach
    void tearDown() {
        if (registry != null) registry.stop(0);
        if (production != null) PlannerNodeSetup.provisioning = production;
        for (String name : overlays) System.clearProperty("jk.env." + name);
    }

    @Test
    void a_private_registry_s_token_installs_the_package_and_leaves_no_copy_behind() throws Exception {
        Path web = tmp.resolve("web");
        Session session = Session.defaults().withCacheDir(tmp.resolve("cache")).withVariant("", Map.of());

        BuildPlanResult r = run(plan(web, session), session);

        assertThat(r.errors())
                .as("%s; registry saw %s", r.errors(), authorizations)
                .isEmpty();
        assertThat(web.resolve("node_modules/tiny/index.js")).exists();
        assertThat(authorizations).contains("Bearer " + TOKEN);
        Path work = web.resolve("target/node");
        if (Files.isDirectory(work)) {
            try (Stream<Path> files = Files.list(work)) {
                assertThat(files.map(p -> p.getFileName().toString()))
                        .noneMatch(n -> n.startsWith(NodeNetwork.USERCONFIG_PREFIX));
            }
        }
        try (Stream<Path> files = Files.walk(web.resolve("target"))) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                assertThat(Files.readString(file, StandardCharsets.ISO_8859_1))
                        .as("%s never holds the token", file)
                        .doesNotContain(TOKEN);
            }
        }
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

    private static String integrity(byte[] bytes) throws Exception {
        return "sha512-"
                + Base64.getEncoder()
                        .encodeToString(MessageDigest.getInstance("SHA-512").digest(bytes));
    }

    /** {@code tiny-1.0.0.tgz}: a gzipped ustar archive of {@code package/package.json} and {@code package/index.js}. */
    private static byte[] tarball() throws IOException {
        ByteArrayOutputStream tar = new ByteArrayOutputStream();
        entry(tar, "package/package.json", "{\"name\":\"tiny\",\"version\":\"1.0.0\",\"main\":\"index.js\"}");
        entry(tar, "package/index.js", "module.exports = 1;\n");
        tar.write(new byte[1024]);
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(tar.toByteArray());
        }
        return gz.toByteArray();
    }

    private static void entry(ByteArrayOutputStream tar, String name, String content) throws IOException {
        byte[] body = content.getBytes(StandardCharsets.UTF_8);
        byte[] header = new byte[512];
        put(header, 0, name);
        put(header, 100, "0000644");
        put(header, 108, "0000000");
        put(header, 116, "0000000");
        put(header, 124, String.format("%011o", body.length));
        put(header, 136, String.format("%011o", 0));
        for (int i = 148; i < 156; i++) header[i] = ' ';
        header[156] = '0';
        put(header, 257, "ustar");
        put(header, 263, "00");
        long sum = 0;
        for (byte b : header) sum += b & 0xff;
        put(header, 148, String.format("%06o", sum));
        header[154] = 0;
        header[155] = ' ';
        tar.write(header);
        tar.write(body);
        int pad = (512 - body.length % 512) % 512;
        tar.write(new byte[pad]);
    }

    private static void put(byte[] header, int at, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(bytes, 0, header, at, bytes.length);
    }
}
